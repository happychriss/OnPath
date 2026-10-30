package com.onpath.app.search

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.poi.Poi

/**
 * The offline search index on disk ([SearchAreas] writes it, one SQLite file for all map areas). Only what ranking
 * needs is held in memory, and only once a search ran: per entry its kind, position and the ids of its name words, plus
 * the vocabulary of distinct words (~10 MB for 170 000 entries instead of ~80 MB of [Place] objects). Every match is
 * scored exactly as by [SearchRanking.rank] (same words, same rules, same order); only the results shown are read
 * from the file ([Place] with all details). Never throws from [search] / [place] / [placesIn]: a broken file is
 * reported, gives no results and marks the index [broken].
 */
class DiskIndex internal constructor(
    private val db: () -> SearchDb,
    /** The plan's area keys in order; region areas leave out what an installed cell owns. */
    private val areas: List<String>,
    private val regionKeys: Set<String>,
    private val installed: Set<Int>,
    /** Row ids of the trail pieces kept by the merge ([OfflineIndex.mergeTrailIndices]), in entry order. */
    private val trailRids: IntArray,
    override val size: Int,
    override val signature: String,
) : PlaceIndex {

    @Volatile override var broken: Boolean = false
        private set

    @Volatile private var words: WordIndex? = null

    /** Entries held in memory for ranking now (0 = not read yet); for the health line. */
    val inMemory: Int get() = words?.n ?: 0

    private fun fail(what: String, e: Throwable) {
        broken = true
        Problems.warn("search", "Offline search index unreadable ($what); it is built again", e)
    }

    override fun warmUp() {
        try { wordIndex() } catch (e: Exception) { fail("reading", e) }
    }

    override fun search(q: SearchQuery, addresses: List<AddressHit>): List<SearchHit> = try {
        val w = wordIndex()
        val ranked = rank(w, q, addresses)
        val rows = rowsOf(ranked.mapNotNull { c -> c.i.takeIf { it >= 0 }?.let { w.rid[it] } })
        ranked.mapNotNull { c ->
            if (c.addr != null) return@mapNotNull c.addr.copy(nearRoute = c.nearRoute)
            val trail = c.i - w.trailStart
            val p = rows[w.rid[c.i]]?.let { r -> if (trail in 0 until w.trailCount) r.trail(trail) else r.place() }
                ?: return@mapNotNull null // its area was replaced meanwhile (the new index is on its way)
            SearchHit(p, c.score, c.tier, c.d, c.nearRoute)
        }
    } catch (e: Exception) {
        fail("search", e); addresses.takeIf { q.category == null }.orEmpty().map { SearchRanking.addressHit(it, q) }
            .sortedWith(SearchRanking.ORDER).take(q.limit)
    }

    override fun place(id: String): Place? = try {
        if (id.startsWith("t:")) {
            val k = id.removePrefix("t:").toIntOrNull()
            k?.takeIf { it in trailRids.indices }?.let { rowsOf(listOf(trailRids[it]))[trailRids[it]]?.trail(it) }
        } else {
            val found = ArrayList<Row>()
            db().query("SELECT $COLS FROM entry WHERE pid = ? AND part IN (0, 1) AND ${areaFilter()}", id) { found += Row.of(it) }
            found.filter { visible(it) }.sortedWith(UNION_ORDER()).firstOrNull()?.place()
        }
    } catch (e: Exception) { fail("place", e); null }

    override fun placesIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, kinds: Set<String>): List<Place> = try {
        if (kinds.isEmpty()) emptyList() else {
            val found = ArrayList<Row>()
            db().query("SELECT $COLS FROM entry WHERE lat >= ${num(minLat)} AND lat <= ${num(maxLat)} AND lon >= ${num(minLon)} " +
                "AND lon <= ${num(maxLon)} AND part IN (0, 1) AND name IS NOT NULL AND kind IN (${kinds.joinToString(",") { quote(it) }}) " +
                "AND ${areaFilter()}") { found += Row.of(it) }
            found.filter { visible(it) }.sortedWith(UNION_ORDER()).map { it.place() }
        }
    } catch (e: Exception) { fail("places", e); emptyList() }

    /** Every entry as a [Place], in index order (tests and checks – reads the whole file; never in the app). */
    fun allPlaces(): List<Place> {
        val w = wordIndex()
        val rows = rowsOf(w.rid.toList())
        return (0 until w.n).mapNotNull { i ->
            val t = i - w.trailStart
            rows[w.rid[i]]?.let { r -> if (t in 0 until w.trailCount) r.trail(t) else r.place() }
        }
    }

    /** Index order: POIs (areas in plan order), trails, base-map places. */
    @Suppress("FunctionName")
    private fun UNION_ORDER(): Comparator<Row> {
        val pos = areas.withIndex().associate { it.value to it.index }
        return compareBy<Row> { it.part }.thenBy { pos[it.area] ?: Int.MAX_VALUE }.thenBy { it.rid }
    }

    private fun visible(r: Row) = !(r.area in regionKeys && r.cell in installed)

    private fun areaFilter() = "area IN (${areas.joinToString(",") { quote(it) }})"

    private fun rowsOf(rids: List<Int>): Map<Int, Row> {
        if (rids.isEmpty()) return emptyMap()
        val out = HashMap<Int, Row>()
        for (chunk in rids.distinct().chunked(500)) {
            db().query("SELECT $COLS FROM entry WHERE rid IN (${chunk.joinToString(",")})") { r -> Row.of(r).let { out[it.rid] = it } }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------- words in memory

    /** What ranking needs, per entry in index order; built from the file once (then kept). */
    internal class WordIndex(
        val n: Int,
        val kindCode: ShortArray, val kinds: Array<String>, val subs: Array<String?>,
        val lat: DoubleArray, val lon: DoubleArray, val named: BooleanArray, val rid: IntArray,
        /** Distinct word ids of entry i: words[wordStart[i] until wordStart[i + 1]]. */
        val wordStart: IntArray, val words: IntArray,
        /** Full names of entry i: names nameStart[i] until nameStart[i + 1]; name k = tokens[tokenStart[k] until tokenStart[k + 1]]. */
        val nameStart: IntArray, val tokenStart: IntArray, val tokens: IntArray,
        val vocab: Array<String>,
        val trailStart: Int, val trailCount: Int,
    )

    /** Collects entries in index order into a [WordIndex]. */
    internal class WordIndexBuilder {
        private val vocab = HashMap<String, Int>()
        private val vocabList = ArrayList<String>()
        private val kindMap = HashMap<Pair<String, String?>, Int>()
        private val kinds = ArrayList<Pair<String, String?>>()
        private var kindCode = ShortArray(1024); private var lat = DoubleArray(1024); private var lon = DoubleArray(1024)
        private var named = BooleanArray(1024); private var rid = IntArray(1024)
        private var wordStart = IntArray(1025); private var words = IntArray(4096)
        private var nameStart = IntArray(1025); private var tokenStart = IntArray(2048); private var tokens = IntArray(4096)
        private var nw = 0; private var nNames = 0; private var nt = 0
        var n = 0; private set
        var trailStart = 0
        var trailCount = 0
        private val entryWords = ArrayList<Int>(16)

        private fun wordId(w: String): Int = vocab.getOrPut(w) { vocabList += w; vocabList.size - 1 }

        /** [full]: the entry's normalized full names joined by '|' (as stored), or null / empty. */
        fun add(rowId: Int, kind: String, sub: String?, la: Double, lo: Double, hasName: Boolean, full: String?) {
            if (n == lat.size) {
                val m = n * 2
                kindCode = kindCode.copyOf(m); lat = lat.copyOf(m); lon = lon.copyOf(m); named = named.copyOf(m); rid = rid.copyOf(m)
                wordStart = wordStart.copyOf(m + 1); nameStart = nameStart.copyOf(m + 1)
            }
            val k = Pair(kind, sub)
            val code = kindMap.getOrPut(k) { kinds += k; kinds.size - 1 }
            if (code > Short.MAX_VALUE) throw IllegalStateException("too many kinds")
            kindCode[n] = code.toShort(); lat[n] = la; lon[n] = lo; named[n] = hasName; rid[n] = rowId
            entryWords.clear()
            if (!full.isNullOrEmpty()) for (name in full.split('|')) {
                if (name.isEmpty()) continue
                if (nNames + 1 >= tokenStart.size) tokenStart = tokenStart.copyOf(tokenStart.size * 2)
                tokenStart[nNames] = nt
                for (t in name.split(' ')) {
                    if (t.isEmpty()) continue
                    val id = wordId(t)
                    if (nt == tokens.size) tokens = tokens.copyOf(nt * 2)
                    tokens[nt++] = id
                    if (id !in entryWords) entryWords += id
                }
                nNames++
                tokenStart[nNames] = nt
            }
            nameStart[n + 1] = nNames
            if (nw + entryWords.size > words.size) words = words.copyOf(maxOf(words.size * 2, nw + entryWords.size))
            for (id in entryWords) words[nw++] = id
            wordStart[n + 1] = nw
            n++
        }

        fun build() = WordIndex(n, kindCode.copyOf(n), kinds.map { it.first }.toTypedArray(), kinds.map { it.second }.toTypedArray(),
            lat.copyOf(n), lon.copyOf(n), named.copyOf(n), rid.copyOf(n), wordStart.copyOf(n + 1), words.copyOf(nw),
            nameStart.copyOf(n + 1), tokenStart.copyOf(nNames + 1), tokens.copyOf(nt), vocabList.toTypedArray(), trailStart, trailCount)
    }

    @Synchronized private fun wordIndex(): WordIndex {
        words?.let { return it }
        val t0 = System.currentTimeMillis()
        val b = WordIndexBuilder()
        val d = db()
        fun part(p: Int) {
            for (a in areas) {
                val region = a in regionKeys
                d.query("SELECT rid, kind, sub, lat, lon, name IS NOT NULL, full, cell FROM entry WHERE area = ? AND part = $p ORDER BY rid", a) { r ->
                    if (region && r.long(7).toInt() in installed) return@query
                    b.add(r.long(0).toInt(), r.str(1)!!, r.str(2), r.double(3), r.double(4), r.long(5) != 0L, r.str(6))
                }
            }
        }
        part(PART_POI)
        b.trailStart = b.n
        val trails = HashMap<Int, Row>()
        for (chunk in trailRids.toList().chunked(500)) {
            d.query("SELECT $COLS FROM entry WHERE rid IN (${chunk.joinToString(",")})") { r -> Row.of(r).let { trails[it.rid] = it } }
        }
        for (rid in trailRids) {
            val r = trails[rid] ?: throw java.io.IOException("trail row $rid missing")
            b.add(rid, "trail", null, r.lat, r.lon, true, r.full)
        }
        b.trailCount = b.n - b.trailStart
        part(PART_PLACE)
        val w = b.build()
        words = w
        Problems.trace("search", "Search words read: ${w.n} entries, ${w.vocab.size} words in ${System.currentTimeMillis() - t0} ms")
        return w
    }

    // ------------------------------------------------------------------------------------------- ranking

    /** A candidate: entry [i] (or an address, i = -1). */
    private class Cand(val i: Int, val score: Double, val tier: Int, val d: Double, val addr: SearchHit?) {
        var nearRoute = false
        val lat: Double get() = addr!!.place.lat
        val lon: Double get() = addr!!.place.lon
    }

    /** [SearchRanking.rank], step by step the same, on the word ids. */
    private fun rank(w: WordIndex, q: SearchQuery, addresses: List<AddressHit>): List<Cand> {
        val qTokens = SearchText.tokens(q.text)
        if (qTokens.isEmpty() && q.category == null) return emptyList()
        val nq = qTokens.size
        val kinds = q.category?.kinds
        val qKinds = qTokens.map { SearchText.kindsFor(it) }
        val queryKinds = qKinds.flatten().toSet()
        val nk = w.kinds.size
        val inCategory = BooleanArray(nk) { kinds == null || w.kinds[it] in kinds }
        val inQueryKinds = BooleanArray(nk) { w.kinds[it] in queryKinds }
        val kindNamed = Array(nq) { t -> BooleanArray(nk) { w.kinds[it] in qKinds[t] } }
        val boost = DoubleArray(nk) { SearchKinds.boost(w.kinds[it], w.subs[it]) }
        // how each query word matches each word of the vocabulary (Match ordinal; higher = better)
        val match = Array(nq) { t -> val qt = qTokens[t]; ByteArray(w.vocab.size) { v -> SearchText.matchWord(qt, w.vocab[v]).ordinal.toByte() } }
        val exactId = IntArray(nq) { t -> match[t].indexOfFirst { it.toInt() == EXACT } }
        val cand = ArrayList<Cand>()
        for (i in 0 until w.n) {
            val kc = w.kindCode[i].toInt()
            if (!inCategory[kc]) continue
            if (nq > 0 && !w.named[i] && !inQueryKinds[kc]) continue
            var text = 0.0
            var tier = 1
            if (nq > 0) {
                var sum = 0.0
                var allPrefix = true
                var ok = true
                for (t in 0 until nq) {
                    var bestOrd = 0
                    val m = match[t]
                    for (j in w.wordStart[i] until w.wordStart[i + 1]) { val o = m[w.words[j]].toInt(); if (o > bestOrd) bestOrd = o }
                    var best = SCORES[bestOrd]
                    if (best < PREFIX_SCORE && kindNamed[t][kc]) best = maxOf(best, 0.7)
                    if (best == 0.0) { ok = false; break }
                    if (best < PREFIX_SCORE) allPrefix = false
                    sum += best
                }
                if (!ok) continue
                var score = sum / nq
                tier = if (allPrefix) 2 else 1
                when (fullNameMatch(w, i, nq, match, exactId)) {
                    2 -> { score += 0.35; tier = 3 }
                    1 -> { score += 0.15; tier = maxOf(tier, 2) }
                }
                text = score
            }
            val d = Geo.distance(q.refLat, q.refLon, w.lat[i], w.lon[i])
            val score = 100.0 * text + boost[kc] - SearchRanking.distancePenalty(d)
            cand += Cand(i, score, tier, d, null)
        }
        if (kinds == null) addresses.forEach { a -> SearchRanking.addressHit(a, q).let { cand += Cand(-1, it.score, it.tier, it.distanceM, it) } }
        val top = cand.sortedWith(compareByDescending<Cand> { it.tier }.thenByDescending { it.score }).take(400)
        val line = q.route?.takeIf { it.size >= 2 }?.let { SearchRanking.simplify(it, 400) }
        if (line != null) {
            val b = Geo.bounds(line)!!
            val pad = 0.015
            for (c in top) {
                val la = if (c.i >= 0) w.lat[c.i] else c.lat
                val lo = if (c.i >= 0) w.lon[c.i] else c.lon
                val inBox = la in (b[0] - pad)..(b[2] + pad) && lo in (b[1] - pad * 1.5)..(b[3] + pad * 1.5)
                if (inBox && Geo.distanceToLine(la, lo, line) <= SearchRanking.NEAR_ROUTE_M) c.nearRoute = true
            }
        }
        return top.sortedWith(compareByDescending<Cand> { it.tier }
            .thenByDescending { it.score + if (it.nearRoute) SearchRanking.NEAR_ROUTE_BONUS else 0.0 }).take(q.limit)
    }

    /**
     * 2 = a full name equals the query, 1 = one starts with it, 0 = neither. A full name is its words joined by single
     * spaces ([SearchText.normalize]), so "equals" = the same words, "starts with" = the same words before the last
     * query word, which starts that name word.
     */
    private fun fullNameMatch(w: WordIndex, i: Int, nq: Int, match: Array<ByteArray>, exactId: IntArray): Int {
        var res = 0
        for (k in w.nameStart[i] until w.nameStart[i + 1]) {
            val s = w.tokenStart[k]; val len = w.tokenStart[k + 1] - s
            if (len < nq) continue
            var same = true
            for (t in 0 until nq - 1) if (w.tokens[s + t] != exactId[t]) { same = false; break }
            if (!same) continue
            val last = w.tokens[s + nq - 1]
            if (len == nq && last == exactId[nq - 1]) return 2
            if (match[nq - 1][last] >= PREFIX) res = 1
        }
        return res
    }

    // ------------------------------------------------------------------------------------------- rows

    /** One row of the entry table. */
    internal class Row(
        val rid: Int, val area: String, val part: Int, val pid: String?, val kind: String, val sub: String?, val name: String?,
        val alt: String?, val lat: Double, val lon: Double, val ele: Int?, val oh: String?, val phone: String?,
        val web: String?, val wiki: String?, val cuisine: String?, val cell: Int, val full: String?,
    ) {
        fun place(): Place = when (part) {
            PART_POI -> Place.fromPoi(Poi(pid ?: "", kind, name, lat, lon, ele, oh, phone, web, wiki, cuisine))
            else -> Place(id = pid ?: "", name = name, kind = kind, lat = lat, lon = lon, ele = ele, sub = sub,
                source = Place.Source.MAP, alt = splitAlt(alt))
        }

        /** The [k]-th merged trail (this row is its kept piece). */
        fun trail(k: Int) = Place("t:$k", name, "trail", lat, lon, source = Place.Source.TRAIL)

        companion object {
            fun of(r: SearchDb.Row) = Row(r.long(0).toInt(), r.str(1)!!, r.long(2).toInt(), r.str(3), r.str(4)!!, r.str(5), r.str(6),
                r.str(7), r.double(8), r.double(9), if (r.isNull(10)) null else r.long(10).toInt(), r.str(11), r.str(12),
                r.str(13), r.str(14), r.str(15), r.long(16).toInt(), r.str(17))
        }
    }

    companion object {
        const val PART_POI = 0
        const val PART_PLACE = 1
        const val PART_TRAIL = 2

        internal const val COLS = "rid, area, part, pid, kind, sub, name, alt, lat, lon, ele, oh, phone, web, wiki, cuisine, cell, full"

        /** Separator of other names in the alt column (never in a name). */
        const val ALT_SEP = '\u001f'

        private val SCORES = SearchText.Match.entries.map { it.score }.toDoubleArray()
        private val PREFIX = SearchText.Match.PREFIX.ordinal
        private val EXACT = SearchText.Match.EXACT.ordinal
        private val PREFIX_SCORE = SearchText.Match.PREFIX.score

        fun splitAlt(s: String?): List<String> = if (s.isNullOrEmpty()) emptyList() else s.split(ALT_SEP)

        /** Normalized full names of a name and its other names, as stored in the full column. */
        fun fullOf(name: String?, alt: List<String>): String =
            (listOfNotNull(name) + alt).map { SearchText.normalize(it) }.filter { it.isNotEmpty() }.distinct().joinToString("|")

        internal fun quote(s: String) = "'" + s.replace("'", "''") + "'"
        private fun num(d: Double) = if (d.isFinite()) d.toString() else "0"
    }
}
