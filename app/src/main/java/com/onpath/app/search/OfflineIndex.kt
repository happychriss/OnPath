package com.onpath.app.search

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.poi.Poi
import com.onpath.app.routing.GeoPoint
import java.io.File
import java.io.InputStreamReader
import kotlin.math.ln

/** What the user asked for and where they are looking. */
data class SearchQuery(
    val text: String,
    val category: SearchKinds.Category? = null,
    /** Reference for distances: the position if known, else the map centre. */
    val refLat: Double,
    val refLon: Double,
    /** Current planned route line (for "near your route"), or null. */
    val route: List<GeoPoint>? = null,
    val limit: Int = 60,
)

/**
 * One ranked result. [tier]: 3 = whole name equals the query, 2 = name/words start with it, 1 = other match.
 * [score]: text match, kind boost, minus distance (higher = better); see [SearchRanking.ORDER].
 */
data class SearchHit(val place: Place, val score: Double, val tier: Int, val distanceM: Double, val nearRoute: Boolean)

/**
 * In-memory offline search index over one map folder: POIs (pois.geojson), peaks/passes and place names
 * (base.pmtiles, cached), trail names (trailnames.geojson, t = "n"; without it: layer "trailnames" of
 * overlays.pmtiles). Missing files just mean fewer results. The app uses [DiskIndex] (same answers, kept on disk);
 * this one is the reference it is tested against, and the readers / helpers here are shared.
 */
class OfflineIndex(val entries: List<Entry>, override val signature: String = "") : PlaceIndex {

    class Entry(val place: Place, val words: Array<String>, val fullNames: Array<String>) {
        constructor(place: Place) : this(
            place,
            (listOfNotNull(place.name) + place.alt).flatMap { SearchText.tokens(it) }.distinct().toTypedArray(),
            (listOfNotNull(place.name) + place.alt).map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct().toTypedArray(),
        )
    }

    override val size: Int get() = entries.size

    override fun search(q: SearchQuery, addresses: List<AddressHit>): List<SearchHit> = SearchRanking.rank(entries, q, addresses)

    override fun place(id: String): Place? = entries.firstOrNull { it.place.id == id }?.place

    override fun placesIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, kinds: Set<String>): List<Place> =
        entries.map { it.place }.filter {
            it.source != Place.Source.TRAIL && it.name != null && it.kind in kinds && it.lat in minLat..maxLat && it.lon in minLon..maxLon
        }

    companion object {
        const val POIS = "pois.geojson"
        const val TRAILS = "trailnames.geojson"
        const val BASE = "base.pmtiles"
        const val OVERLAYS = com.onpath.app.data.DataFiles.OVERLAYS
        private const val CACHE = "search-places-v1.tsv"

        /** Changes when any of the source files changes (size + modification time). */
        fun signature(dataDir: File): String = listOf(POIS, TRAILS, BASE, OVERLAYS).joinToString(";") { n ->
            val f = File(dataDir, n); if (f.isFile) "$n:${f.length()}:${f.lastModified()}" else "$n:-"
        }

        /** Builds the index; never throws (a broken file is skipped and logged). Call off the main thread. */
        fun load(dataDir: File, cacheDir: File, sig: String = signature(dataDir)): OfflineIndex {
            val places = ArrayList<Place>()
            safe("pois") { places += readPois(File(dataDir, POIS)) }
            safe("trails") {
                val geojson = File(dataDir, TRAILS)
                places += if (geojson.isFile) readTrails(geojson) else readTrailsFromTiles(File(dataDir, OVERLAYS))
            }
            safe("base") { places += baseMapPlaces(File(dataDir, BASE), cacheDir).map { it.toPlace() } }
            return OfflineIndex(places.map { Entry(it) }, sig)
        }

        private inline fun safe(what: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { Problems.warn("search", "Search index: $what skipped (file unreadable)", e) }
        }

        /** A base-map peak / place as a search [Place]. */
        fun placeOf(i: BaseMapPlaces.Item) = i.toPlace()

        private fun BaseMapPlaces.Item.toPlace() = Place(
            id = "m:$kind:${"%.5f".format(java.util.Locale.ROOT, lat)},${"%.5f".format(java.util.Locale.ROOT, lon)}",
            name = name, kind = kind, lat = lat, lon = lon, ele = ele, sub = sub, source = Place.Source.MAP, alt = alt,
        )

        /** Peaks + places from the base map, cached in [cacheDir] (keyed by size + mtime of base.pmtiles). */
        fun baseMapPlaces(base: File, cacheDir: File): List<BaseMapPlaces.Item> {
            if (!base.isFile) return emptyList()
            val key = "${base.length()}:${base.lastModified()}"
            val cache = File(cacheDir, CACHE)
            if (cache.isFile) try {
                val lines = cache.readLines()
                if (lines.firstOrNull() == key) return lines.drop(1).mapNotNull { parseCacheLine(it) }
            } catch (e: Exception) { Problems.warn("search", "Search place cache unreadable; reading the base map again", e) }
            val items = BaseMapPlaces.read(base)
            try {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$CACHE.tmp")
                tmp.bufferedWriter().use { w ->
                    w.write(key); w.write("\n")
                    for (it in items) { w.write(cacheLine(it)); w.write("\n") }
                }
                tmp.renameTo(cache)
            } catch (e: Exception) { Problems.warn("search", "Search place cache not written; rebuilt on next start", e) }
            return items
        }

        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('|', '/')

        fun cacheLine(i: BaseMapPlaces.Item) = listOf(i.kind, i.sub ?: "", i.lat.toString(), i.lon.toString(),
            i.ele?.toString() ?: "", clean(i.name), i.alt.joinToString("|") { clean(it) }).joinToString("\t")

        fun parseCacheLine(l: String): BaseMapPlaces.Item? {
            val p = l.split('\t')
            if (p.size < 7) return null
            return BaseMapPlaces.Item(p[5], p[6].split('|').filter { it.isNotEmpty() }, p[0], p[1].ifEmpty { null },
                p[2].toDoubleOrNull() ?: return null, p[3].toDoubleOrNull() ?: return null, p[4].toIntOrNull())
        }

        // ---------------- GeoJSON (streaming, tolerant) ----------------

        fun readPois(f: File): List<Place> = readPoisGrouped(f) { _, _ -> Unit }[Unit].orEmpty()

        /**
         * POIs per group: [group] maps a point to its group (the map area owning it) or null (left out; its properties
         * are skipped unread when the geometry comes first, as in our files – a scan for a few areas stays cheap).
         */
        fun <K : Any> readPoisGrouped(f: File, group: (Double, Double) -> K?): Map<K, List<Place>> {
            if (!f.isFile) return emptyMap()
            val out = HashMap<K, ArrayList<Place>>()
            readFeatures(f, keepPoint = { lat, lon -> group(lat, lon) != null }) { type, coords, props ->
                if (type != "Point") return@readFeatures
                val c = coords as? List<*> ?: return@readFeatures
                val lon = (c.getOrNull(0) as? Double) ?: return@readFeatures
                val lat = (c.getOrNull(1) as? Double) ?: return@readFeatures
                val k = group(lat, lon) ?: return@readFeatures
                Poi.fromProperties(props, lat, lon)?.let { out.getOrPut(k) { ArrayList() } += Place.fromPoi(it) }
            }
            return out
        }

        /** Trail names from a format-2 overlays.pmtiles (layer "trailnames"), merged like [readTrails]. */
        fun readTrailsFromTiles(f: File): List<Place> {
            if (!f.isFile) return emptyList()
            return mergeTrailParts(BaseMapPlaces.readTrailNames(f).map { TrailPart(it.name, it.lat, it.lon, it.len) })
        }

        data class TrailPart(val name: String, val lat: Double, val lon: Double, val len: Double)

        /** Trail names (t = "n"): one entry per name and area (parts within 2 km merge), placed mid-trail. */
        fun readTrails(f: File): List<Place> = mergeTrailParts(readTrailPartsGrouped(f) { _, _ -> Unit }[Unit].orEmpty())

        /** Unmerged trail pieces of trailnames.geojson per group of their middle point (null = left out). */
        fun <K : Any> readTrailPartsGrouped(f: File, group: (Double, Double) -> K?): Map<K, List<TrailPart>> {
            if (!f.isFile) return emptyMap()
            val out = HashMap<K, ArrayList<TrailPart>>()
            readFeatures(f) { type, coords, props ->
                if (type != "LineString" || props["t"] != "n") return@readFeatures
                val name = (props["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@readFeatures
                val pts = (coords as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.let { (it.getOrNull(1) as? Double) to (it.getOrNull(0) as? Double) } }
                    ?.filter { it.first != null && it.second != null } ?: return@readFeatures
                if (pts.isEmpty()) return@readFeatures
                val mid = pts[pts.size / 2]
                val k = group(mid.first!!, mid.second!!) ?: return@readFeatures
                out.getOrPut(k) { ArrayList() } += TrailPart(name, mid.first!!, mid.second!!, (props["len"] as? Double) ?: 0.0)
            }
            return out
        }

        /** Longest piece first; a piece within 2 km of a kept piece of the same name is dropped. Pure, unit-tested. */
        fun mergeTrailParts(parts: List<TrailPart>): List<Place> = mergeTrailIndices(parts).mapIndexed { k, i ->
            val p = parts[i]
            Place("t:$k", p.name, "trail", p.lat, p.lon, source = Place.Source.TRAIL)
        }

        /** [mergeTrailParts] as the indices (into [parts]) of the kept pieces, in result order. */
        fun mergeTrailIndices(parts: List<TrailPart>): List<Int> {
            val kept = HashMap<String, MutableList<TrailPart>>()
            val out = ArrayList<Int>()
            for (i in parts.indices.sortedByDescending { parts[it].len }) {
                val p = parts[i]
                val list = kept.getOrPut(SearchText.normalize(p.name)) { ArrayList() }
                if (list.any { Geo.distance(it.lat, it.lon, p.lat, p.lon) < 2000.0 }) continue
                list += p
                out += i
            }
            return out
        }

        /**
         * Streams the features of a GeoJSON file. [keepPoint]: a Point whose position it rejects is skipped – its
         * properties are not parsed when the geometry came first.
         */
        private fun readFeatures(f: File, keepPoint: ((Double, Double) -> Boolean)? = null,
                                 onFeature: (String?, Any?, Map<String, Any?>) -> Unit) {
            JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "features") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var type: String? = null; var coords: Any? = null; var props: Map<String, Any?> = emptyMap()
                        var skip = false
                        fun rejected(): Boolean {
                            if (keepPoint == null || type != "Point") return false
                            val c = coords as? List<*> ?: return false
                            val lon = c.getOrNull(0) as? Double ?: return false
                            val lat = c.getOrNull(1) as? Double ?: return false
                            return !keepPoint(lat, lon)
                        }
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "geometry" -> {
                                    if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                                    r.beginObject()
                                    while (r.hasNext()) when (r.nextName()) {
                                        "type" -> type = r.nextString()
                                        "coordinates" -> coords = readValue(r)
                                        else -> r.skipValue()
                                    }
                                    r.endObject()
                                }
                                "properties" -> {
                                    if (!skip && rejected()) skip = true
                                    if (skip) { r.skipValue(); continue }
                                    @Suppress("UNCHECKED_CAST")
                                    props = (readValue(r) as? Map<String, Any?>) ?: emptyMap()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (!skip) onFeature(type, coords, props)
                    }
                    r.endArray()
                }
            }
        }

        private fun readValue(r: JsonReader): Any? = when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> { val l = ArrayList<Any?>(); r.beginArray(); while (r.hasNext()) l += readValue(r); r.endArray(); l }
            JsonToken.BEGIN_OBJECT -> { val m = HashMap<String, Any?>(); r.beginObject(); while (r.hasNext()) m[r.nextName()] = readValue(r); r.endObject(); m }
            JsonToken.NUMBER -> r.nextDouble()
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> { r.nextNull(); null }
            else -> r.nextString()
        }
    }
}

/** Matching and ranking (pure, unit-tested). */
object SearchRanking {
    /** A result within this distance of the planned line is "near your route". */
    const val NEAR_ROUTE_M = 1000.0
    /** Score points for "near your route" – as much as ~6 km less distance; never lifts a result over a better tier. */
    const val NEAR_ROUTE_BONUS = 20.0
    /** An online result ranks this much below an offline one that matches as well (the offline copy has more details). */
    const val ONLINE_PENALTY = 5.0
    /** Online results whose town is named in the query ("via Roma 3 Bolzano"), as in [OnlineRanking]. */
    const val QUERY_TOWN_BONUS = 30.0

    /**
     * The one order of the mixed result list (owner: one list, best first – no blocks per source):
     * how well the text matches (tier), then the score (text, kind, distance to the position / map centre,
     * near the route).
     */
    val ORDER: Comparator<SearchHit> = compareByDescending<SearchHit> { it.tier }
        .thenByDescending { it.score + if (it.nearRoute) NEAR_ROUTE_BONUS else 0.0 }

    /** Text score 0..~1.35 and tier, or null when the entry does not match every query word. */
    fun textMatch(qTokens: List<String>, qFull: String, e: OfflineIndex.Entry,
                  qKinds: List<Set<String>> = qTokens.map { SearchText.kindsFor(it) }): Pair<Double, Int>? {
        if (qTokens.isEmpty()) return 0.0 to 1
        var sum = 0.0
        var allPrefix = true
        for ((qi, q) in qTokens.withIndex()) {
            var best = 0.0
            for (w in e.words) {
                val m = SearchText.matchWord(q, w).score
                if (m > best) { best = m; if (m == 1.0) break }
            }
            if (best < SearchText.Match.PREFIX.score && e.place.kind in qKinds[qi]) best = maxOf(best, 0.7)
            if (best == 0.0) return null
            if (best < SearchText.Match.PREFIX.score) allPrefix = false
            sum += best
        }
        var score = sum / qTokens.size
        var tier = if (allPrefix) 2 else 1
        when {
            e.fullNames.any { it == qFull } -> { score += 0.35; tier = 3 }
            e.fullNames.any { it.startsWith(qFull) } -> { score += 0.15; tier = maxOf(tier, 2) }
        }
        return score to tier
    }

    /** Distance penalty in score points: 0 next to you, ~18 at 5 km, ~30 at 20 km. */
    fun distancePenalty(m: Double) = 10.0 * ln(1.0 + m / 1000.0)

    /** An offline address hit as a [SearchHit] (same score scale as names: text, kind boost, distance). */
    fun addressHit(a: AddressHit, q: SearchQuery): SearchHit {
        val d = Geo.distance(q.refLat, q.refLon, a.place.lat, a.place.lon)
        return SearchHit(a.place, 100.0 * a.text + SearchKinds.boost(a.place.kind, a.place.sub) - distancePenalty(d), a.tier, d, false)
    }

    fun rank(entries: List<OfflineIndex.Entry>, q: SearchQuery, addresses: List<AddressHit> = emptyList()): List<SearchHit> {
        val qTokens = SearchText.tokens(q.text)
        if (qTokens.isEmpty() && q.category == null) return emptyList()
        val qFull = qTokens.joinToString(" ")
        val kinds = q.category?.kinds
        val qKinds = qTokens.map { SearchText.kindsFor(it) }
        val queryKinds = qKinds.flatten().toSet()
        val cand = ArrayList<SearchHit>()
        for (e in entries) {
            if (kinds != null && e.place.kind !in kinds) continue
            if (qTokens.isNotEmpty() && e.place.name == null && e.place.kind !in queryKinds) continue
            val (text, tier) = textMatch(qTokens, qFull, e, qKinds) ?: continue
            val d = Geo.distance(q.refLat, q.refLon, e.place.lat, e.place.lon)
            val score = 100.0 * text + SearchKinds.boost(e.place.kind, e.place.sub) - distancePenalty(d)
            cand += SearchHit(e.place, score, tier, d, false)
        }
        addresses.forEach { if (kinds == null || it.place.kind in kinds) cand += addressHit(it, q) }
        // Best few hundred by text, then the (more expensive) route check.
        val top = cand.sortedWith(compareByDescending<SearchHit> { it.tier }.thenByDescending { it.score }).take(400)
        val line = q.route?.takeIf { it.size >= 2 }?.let { simplify(it, 400) }
        val withRoute = if (line == null) top else {
            val b = Geo.bounds(line)!!
            val pad = 0.015
            top.map { h ->
                val p = h.place
                val inBox = p.lat in (b[0] - pad)..(b[2] + pad) && p.lon in (b[1] - pad * 1.5)..(b[3] + pad * 1.5)
                if (inBox && Geo.distanceToLine(p.lat, p.lon, line) <= NEAR_ROUTE_M) h.copy(nearRoute = true) else h
            }
        }
        return withRoute.sortedWith(ORDER).take(q.limit)
    }

    /**
     * Online results (already deduplicated against the offline ones and in the provider's order) on the offline
     * score scale, so both can be mixed into one list: the same text match over name, other names, town and address
     * (Photon also matches words we cannot see: then tier 1), kind boost, distance, the provider's order as a small
     * tie-break, [QUERY_TOWN_BONUS] for a town named in the query, minus [ONLINE_PENALTY].
     */
    fun onlineHits(places: List<Place>, q: SearchQuery, queryTowns: Set<String> = emptySet(),
                   /** Where the online search was centred (a town named in the query), for the distance score. */
                   centreLat: Double = q.refLat, centreLon: Double = q.refLon): List<SearchHit> {
        val qTokens = SearchText.tokens(q.text)
        val qFull = qTokens.joinToString(" ")
        return places.mapIndexed { i, p ->
            val (text, tier) = textMatch(qTokens, qFull, onlineEntry(p)) ?: (0.5 to 1)
            val d = Geo.distance(q.refLat, q.refLon, p.lat, p.lon)
            val dc = if (centreLat == q.refLat && centreLon == q.refLon) d else Geo.distance(centreLat, centreLon, p.lat, p.lon)
            var score = 100.0 * text + SearchKinds.boost(p.kind, p.sub) - distancePenalty(dc) - 1.5 * i - ONLINE_PENALTY
            if (p.town != null && SearchText.normalize(p.town) in queryTowns) score += QUERY_TOWN_BONUS
            SearchHit(p, score, tier, d, false)
        }
    }

    /** An online place as an index entry: its words are those of the name, other names, town and address. */
    private fun onlineEntry(p: Place) = OfflineIndex.Entry(p, (listOfNotNull(p.name, p.town, p.address) + p.alt)
        .flatMap { SearchText.tokens(it) }.distinct().toTypedArray(),
        (listOfNotNull(p.name) + p.alt).map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct().toTypedArray())

    /**
     * Online places (found for an earlier text) that also match [text] by the words we see – name, town, address – so
     * they stay in the list while the user types on ("olive hild" → "olive hilde": the restaurant in Hilden stays).
     */
    fun stillMatching(places: List<Place>, text: String): List<Place> {
        val qTokens = SearchText.tokens(text)
        if (qTokens.isEmpty()) return emptyList()
        val qFull = qTokens.joinToString(" ")
        return places.filter { textMatch(qTokens, qFull, onlineEntry(it)) != null }
    }

    /**
     * The next list for the SAME text as the one on screen ([shown]): every row shown stays where it is (updated when
     * [next] has it again), rows of [next] not shown yet go below, in [next]'s order. Nothing moves or vanishes under
     * the finger (owner; field bug 0.13.2: the restaurant shown, then the list jumped). A new text is ranked afresh.
     */
    fun keepShown(shown: List<SearchHit>, next: List<SearchHit>): List<SearchHit> {
        val kept = shown.map { s -> next.firstOrNull { it.place.id == s.place.id } ?: s }
        val added = next.filter { n -> shown.none { it.place.id == n.place.id || SearchMerge.samePlace(it.place, n.place) } }
        return kept + added
    }

    /**
     * Offline and online hits as one list in [ORDER]. [keepShown]: the offline rows were already on screen when the
     * online ones arrived – they stay exactly where they are and the online rows go below them, ranked among
     * themselves (owner: rows never move under the finger). The next
     * keystroke re-ranks everything.
     */
    fun mix(offline: List<SearchHit>, online: List<SearchHit>, keepShown: Boolean = false): List<SearchHit> = when {
        online.isEmpty() -> offline
        keepShown && offline.isNotEmpty() -> offline + online.sortedWith(ORDER)
        else -> (offline + online).sortedWith(ORDER)
    }

    /** Every n-th point (plus the last), at most [max] points. */
    fun simplify(line: List<GeoPoint>, max: Int): List<GeoPoint> {
        if (line.size <= max) return line
        val step = (line.size + max - 1) / max
        return line.filterIndexed { i, _ -> i % step == 0 } + line.last()
    }
}
