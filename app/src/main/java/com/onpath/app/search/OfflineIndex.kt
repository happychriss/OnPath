package com.onpath.app.search

import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.onpath.app.data.Geo
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

/** One ranked result. [tier]: 3 = whole name equals the query, 2 = name/words start with it, 1 = other match. */
data class SearchHit(val place: Place, val score: Double, val tier: Int, val distanceM: Double, val nearRoute: Boolean)

/**
 * In-memory offline search index over the region package: POIs (pois.geojson), peaks/passes and
 * place names (base.pmtiles, cached), trail names (trailnames.geojson, t = "n"). Missing files just
 * mean fewer results. ~20 000 entries; a query scans them all in well under 100 ms.
 */
class OfflineIndex(val entries: List<Entry>, val signature: String = "") {

    class Entry(val place: Place, val words: Array<String>, val fullNames: Array<String>) {
        constructor(place: Place) : this(
            place,
            (listOfNotNull(place.name) + place.alt).flatMap { SearchText.tokens(it) }.distinct().toTypedArray(),
            (listOfNotNull(place.name) + place.alt).map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct().toTypedArray(),
        )
    }

    fun search(q: SearchQuery): List<SearchHit> = SearchRanking.rank(entries, q)

    companion object {
        const val POIS = "pois.geojson"
        const val TRAILS = "trailnames.geojson"
        const val BASE = "base.pmtiles"
        private const val CACHE = "search-places-v1.tsv"

        /** Changes when any of the source files changes (size + modification time). */
        fun signature(dataDir: File): String = listOf(POIS, TRAILS, BASE).joinToString(";") { n ->
            val f = File(dataDir, n); if (f.isFile) "$n:${f.length()}:${f.lastModified()}" else "$n:-"
        }

        /** Builds the index; never throws (a broken file is skipped and logged). Call off the main thread. */
        fun load(dataDir: File, cacheDir: File): OfflineIndex {
            val sig = signature(dataDir)
            val places = ArrayList<Place>()
            safe("pois") { places += readPois(File(dataDir, POIS)) }
            safe("trails") { places += readTrails(File(dataDir, TRAILS)) }
            safe("base") { places += baseMapPlaces(File(dataDir, BASE), cacheDir).map { it.toPlace() } }
            return OfflineIndex(places.map { Entry(it) }, sig)
        }

        /** android.util.Log, but never throws (JVM tests, layoutlib). */
        fun log(msg: String) { try { Log.w("OnPath", msg) } catch (_: Throwable) { } }

        private inline fun safe(what: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { log("search index: $what skipped: $e") }
        }

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
            } catch (_: Exception) { }
            val items = BaseMapPlaces.read(base)
            try {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$CACHE.tmp")
                tmp.bufferedWriter().use { w ->
                    w.write(key); w.write("\n")
                    for (it in items) { w.write(cacheLine(it)); w.write("\n") }
                }
                tmp.renameTo(cache)
            } catch (e: Exception) { log("search cache not written: $e") }
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

        fun readPois(f: File): List<Place> {
            if (!f.isFile) return emptyList()
            val out = ArrayList<Place>()
            readFeatures(f) { type, coords, props ->
                if (type != "Point") return@readFeatures
                val c = coords as? List<*> ?: return@readFeatures
                val lon = (c.getOrNull(0) as? Double) ?: return@readFeatures
                val lat = (c.getOrNull(1) as? Double) ?: return@readFeatures
                Poi.fromProperties(props, lat, lon)?.let { out += Place.fromPoi(it) }
            }
            return out
        }

        /** Trail names (t = "n"): one entry per name and area (parts within 2 km merge), placed mid-trail. */
        fun readTrails(f: File): List<Place> {
            if (!f.isFile) return emptyList()
            class Part(val name: String, val lat: Double, val lon: Double, val len: Double)
            val parts = ArrayList<Part>()
            readFeatures(f) { type, coords, props ->
                if (type != "LineString" || props["t"] != "n") return@readFeatures
                val name = (props["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@readFeatures
                val pts = (coords as? List<*>)?.mapNotNull { p -> (p as? List<*>)?.let { (it.getOrNull(1) as? Double) to (it.getOrNull(0) as? Double) } }
                    ?.filter { it.first != null && it.second != null } ?: return@readFeatures
                if (pts.isEmpty()) return@readFeatures
                val mid = pts[pts.size / 2]
                parts += Part(name, mid.first!!, mid.second!!, (props["len"] as? Double) ?: 0.0)
            }
            val kept = HashMap<String, MutableList<Part>>()
            val out = ArrayList<Place>()
            for (p in parts.sortedByDescending { it.len }) {
                val list = kept.getOrPut(SearchText.normalize(p.name)) { ArrayList() }
                if (list.any { Geo.distance(it.lat, it.lon, p.lat, p.lon) < 2000.0 }) continue
                list += p
                out += Place("t:${out.size}", p.name, "trail", p.lat, p.lon, source = Place.Source.TRAIL)
            }
            return out
        }

        private fun readFeatures(f: File, onFeature: (String?, Any?, Map<String, Any?>) -> Unit) {
            JsonReader(InputStreamReader(f.inputStream().buffered(1 shl 16), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "features") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var type: String? = null; var coords: Any? = null; var props: Map<String, Any?> = emptyMap()
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
                                    @Suppress("UNCHECKED_CAST")
                                    props = (readValue(r) as? Map<String, Any?>) ?: emptyMap()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        onFeature(type, coords, props)
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

    fun rank(entries: List<OfflineIndex.Entry>, q: SearchQuery): List<SearchHit> {
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
        return withRoute.sortedWith(
            compareByDescending<SearchHit> { it.nearRoute }.thenByDescending { it.tier }.thenByDescending { it.score }
        ).take(q.limit)
    }

    /** Every n-th point (plus the last), at most [max] points. */
    fun simplify(line: List<GeoPoint>, max: Int): List<GeoPoint> {
        if (line.size <= max) return line
        val step = (line.size + max - 1) / max
        return line.filterIndexed { i, _ -> i % step == 0 } + line.last()
    }
}
