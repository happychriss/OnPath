package com.onpath.app.search

import com.onpath.app.debug.Problems
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Reads peaks, passes and place names (towns, villages, hamlets) from the base map (base.pmtiles,
 * OpenMapTiles schema, layers "mountain_peak" and "place") – pois.geojson has neither.
 * Scans the highest zoom level once (≈1–2 s on a phone for a region); the result is cached by
 * [OfflineIndex]. Minimal PMTiles v3 + Mapbox Vector Tile decoding, points only, no dependencies.
 */
object BaseMapPlaces {

    /** One extracted named point: display name (German first), other names, kind, class, elevation. */
    data class Item(val name: String, val alt: List<String>, val kind: String, val sub: String?, val lat: Double, val lon: Double, val ele: Int?)

    private val PLACE_CLASSES = setOf("city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter",
        "isolated_dwelling", "locality")

    fun read(file: File): List<Item> = readGrouped(file) { _, _, _ -> Unit }[Unit].orEmpty()

    /**
     * Like [read], but per group: [group] maps a tile (z, x, y of the highest zoom) to its group (e.g. the map area
     * that owns it) or null (tile not read at all – only the wanted areas' tiles are decoded). Deduplicated per group.
     */
    fun <K : Any> readGrouped(file: File, group: (Int, Int, Int) -> K?): Map<K, List<Item>> {
        val out = HashMap<K, ArrayList<Item>>()
        forEachMaxZoomTile(file, group) { tile, z, x, y, k ->
            try { decodeTile(tile, z, x, y, out.getOrPut(k) { ArrayList() }) } catch (ex: Exception) {
                Problems.warn("search", "Base map tile not decodable; its places are missing from search", ex)
            }
        }
        return out.mapValues { dedupe(it.value) }
    }

    /** One trail-name piece from overlays.pmtiles (layer "trailnames", t = "n"): name, a point on it, whole length. */
    data class TrailName(val name: String, val lat: Double, val lon: Double, val len: Double)

    /**
     * Trail names from a format-2 overlays.pmtiles (map package without trailnames.geojson): every t = "n" line piece
     * of the highest zoom, placed at its middle vertex (pieces whose middle lies in the tile buffer belong to the
     * neighbour tile). The caller merges pieces of one name. Never throws for a broken tile (logged, skipped).
     */
    fun readTrailNames(file: File): List<TrailName> = readTrailNamesGrouped(file) { _, _, _ -> Unit }[Unit].orEmpty()

    /** [readTrailNames] per group of tiles (see [readGrouped]). */
    fun <K : Any> readTrailNamesGrouped(file: File, group: (Int, Int, Int) -> K?): Map<K, List<TrailName>> {
        val out = HashMap<K, ArrayList<TrailName>>()
        forEachMaxZoomTile(file, group) { tile, z, x, y, k ->
            try { decodeTrailNames(tile, z, x, y, out.getOrPut(k) { ArrayList() }) } catch (ex: Exception) {
                Problems.warn("search", "Overlay tile not decodable; its trail names are missing from search", ex)
            }
        }
        return out
    }

    /**
     * Calls [onTile] with every tile of the file's highest zoom (decompressed MVT bytes, z, x, y, group) whose [group]
     * is not null; other tiles are not even read. MVT files only.
     */
    private fun <K : Any> forEachMaxZoomTile(file: File, group: (Int, Int, Int) -> K?, onTile: (ByteArray, Int, Int, Int, K) -> Unit) {
        if (!file.isFile) return
        RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(127)
            raf.readFully(h)
            if (String(h, 0, 7, Charsets.US_ASCII) != "PMTiles" || h[7].toInt() != 3) return
            fun u64(o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (h[o + i].toLong() and 0xff); return v }
            val rootOff = u64(8); val rootLen = u64(16)
            val leafOff = u64(40); val dataOff = u64(56)
            val internalComp = h[97].toInt(); val tileComp = h[98].toInt(); val tileType = h[99].toInt()
            val maxZoom = h[101].toInt()
            if (tileType != 1) return // not MVT
            val z = maxZoom
            val zStart = zoomStart(z); val zEnd = zoomStart(z + 1)
            fun readBytes(off: Long, len: Long): ByteArray { val b = ByteArray(len.toInt()); raf.seek(off); raf.readFully(b); return b }
            fun walk(off: Long, len: Long, depth: Int) {
                if (depth > 4) return
                val dir = decodeDirectory(decompress(readBytes(off, len), internalComp))
                for (e in dir) {
                    if (e.runLength == 0L) { walk(leafOff + e.offset, e.length, depth + 1); continue }
                    if (e.tileId + e.runLength <= zStart || e.tileId >= zEnd) continue
                    val wanted = ArrayList<Pair<Triple<Int, Int, Int>, K>>()
                    for (id in maxOf(e.tileId, zStart) until minOf(e.tileId + e.runLength, zEnd)) {
                        val t = tileIdToZxy(id)
                        group(t.first, t.second, t.third)?.let { wanted += t to it }
                    }
                    if (wanted.isEmpty()) continue
                    val tile = try { decompress(readBytes(dataOff + e.offset, e.length), tileComp) } catch (ex: Exception) {
                        Problems.warn("search", "Map tile (${file.name}) unreadable; its names are missing from search", ex); continue
                    }
                    for ((t, k) in wanted) onTile(tile, t.first, t.second, t.third, k)
                }
            }
            walk(rootOff, rootLen, 0)
        }
    }

    /** Trail-name lines (layer "trailnames", t = "n") of one tile → [out]. */
    fun decodeTrailNames(b: ByteArray, z: Int, x: Int, y: Int, out: MutableList<TrailName>) {
        val r = Proto(b, 0, b.size)
        while (r.more()) {
            val (field, wire) = r.key()
            if (field != 3 || wire != 2) { r.skip(wire); continue }
            val (start, end) = r.slice()
            var name: String? = null
            val keys = ArrayList<String>(); val values = ArrayList<Any?>()
            val feats = ArrayList<Long>()
            var extent = 4096
            val lr = Proto(b, start, end)
            while (lr.more()) {
                val (f, w) = lr.key()
                when {
                    f == 1 && w == 2 -> name = lr.string()
                    f == 2 && w == 2 -> { val (s, e) = lr.slice(); feats += (s.toLong() shl 32) or e.toLong() }
                    f == 3 && w == 2 -> keys += lr.string()
                    f == 4 && w == 2 -> { val (s, e) = lr.slice(); values += decodeValue(b, s, e) }
                    f == 5 && w == 0 -> extent = lr.varint().toInt()
                    else -> lr.skip(w)
                }
            }
            if (name != "trailnames") continue
            for (fe in feats) {
                val fr = Proto(b, (fe ushr 32).toInt(), (fe and 0xffffffffL).toInt())
                var type = 0; var tags: IntArray? = null; var geom: IntArray? = null
                while (fr.more()) {
                    val (f, w) = fr.key()
                    when {
                        f == 2 && w == 2 -> tags = fr.packed()
                        f == 3 && w == 0 -> type = fr.varint().toInt()
                        f == 4 && w == 2 -> geom = fr.packed()
                        else -> fr.skip(w)
                    }
                }
                if (type != 2 || geom == null) continue
                val props = HashMap<String, Any?>()
                tags?.let { t -> var i = 0; while (i + 1 < t.size) { keys.getOrNull(t[i])?.let { k -> props[k] = values.getOrNull(t[i + 1]) }; i += 2 } }
                if (props["t"] != "n") continue
                val label = (props["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val pts = lineVertices(geom)
                if (pts.isEmpty()) continue
                val (px, py) = pts[pts.size / 2]
                if (px < 0 || py < 0 || px >= extent || py >= extent) continue // middle in the buffer: neighbour's piece
                val len = (props["len"] as? Number)?.toDouble() ?: 0.0
                out += TrailName(label, latOf(y + py.toDouble() / extent, z), lonOf(x + px.toDouble() / extent, z), len)
            }
        }
    }

    /** Vertices (tile coordinates) of the first part of an MVT line geometry. */
    fun lineVertices(geom: IntArray): List<Pair<Int, Int>> {
        val pts = ArrayList<Pair<Int, Int>>()
        var i = 0; var cx = 0; var cy = 0
        while (i < geom.size) {
            val cmd = geom[i] and 7; val count = geom[i] ushr 3; i++
            if (cmd == 1 && pts.isNotEmpty()) break // second part: one is enough for a search point
            if (cmd != 1 && cmd != 2) break
            repeat(count) {
                if (i + 1 >= geom.size) return pts // truncated geometry
                cx += zigzag(geom[i]); cy += zigzag(geom[i + 1]); i += 2
                pts += cx to cy
            }
        }
        return pts
    }

    /** Same name within 300 m (tile buffers, bilingual duplicates): keep one. */
    fun dedupe(items: List<Item>): List<Item> {
        val byName = HashMap<String, MutableList<Item>>()
        val out = ArrayList<Item>()
        for (it in items) {
            val list = byName.getOrPut(SearchText.normalize(it.name) + "|" + it.kind) { ArrayList() }
            if (list.none { o -> com.onpath.app.data.Geo.distance(o.lat, o.lon, it.lat, it.lon) < 300.0 }) { list += it; out += it }
        }
        return out
    }

    // ---------------- PMTiles ----------------

    data class Entry(val tileId: Long, val offset: Long, val length: Long, val runLength: Long)

    private fun decompress(b: ByteArray, comp: Int): ByteArray = when (comp) {
        2 -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        0, 1 -> b // unknown / none
        else -> throw IllegalStateException("compression $comp not supported")
    }

    fun decodeDirectory(b: ByteArray): List<Entry> {
        val r = Proto(b, 0, b.size)
        val n = r.varint().toInt()
        val ids = LongArray(n); val runs = LongArray(n); val lens = LongArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = r.varint()
        for (i in 0 until n) lens[i] = r.varint()
        for (i in 0 until n) {
            val v = r.varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    /** First tile id of zoom [z]: sum of 4^k for k < z. */
    fun zoomStart(z: Int): Long { var acc = 0L; for (k in 0 until z) acc += 1L shl (2 * k); return acc }

    /** PMTiles tile id (Hilbert order per zoom) → z, x, y. */
    fun tileIdToZxy(id: Long): Triple<Int, Int, Int> {
        var acc = 0L
        for (z in 0..30) {
            val num = 1L shl (2 * z)
            if (acc + num > id) {
                var t = id - acc
                val n = 1L shl z
                var x = 0L; var y = 0L
                var s = 1L
                while (s < n) {
                    val rx = 1L and (t / 2)
                    val ry = 1L and (t xor rx)
                    if (ry == 0L) {
                        if (rx == 1L) { x = s - 1 - x; y = s - 1 - y }
                        val tmp = x; x = y; y = tmp
                    }
                    x += s * rx; y += s * ry
                    t /= 4; s *= 2
                }
                return Triple(z, x.toInt(), y.toInt())
            }
            acc += num
        }
        throw IllegalArgumentException("tile id too large")
    }

    // ---------------- Mapbox Vector Tile (points of two layers) ----------------

    fun decodeTile(b: ByteArray, z: Int, x: Int, y: Int, out: MutableList<Item>) {
        val r = Proto(b, 0, b.size)
        while (r.more()) {
            val (field, wire) = r.key()
            if (field == 3 && wire == 2) { val (s, e) = r.slice(); decodeLayer(b, s, e, z, x, y, out) } else r.skip(wire)
        }
    }

    private fun decodeLayer(b: ByteArray, start: Int, end: Int, z: Int, x: Int, y: Int, out: MutableList<Item>) {
        // First pass: name, keys, values, extent; remember feature slices.
        var name: String? = null
        val keys = ArrayList<String>(); val values = ArrayList<Any?>()
        val feats = ArrayList<Long>()
        var extent = 4096
        val r = Proto(b, start, end)
        while (r.more()) {
            val (field, wire) = r.key()
            when {
                field == 1 && wire == 2 -> {
                    name = r.string()
                    if (name != "place" && name != "mountain_peak") return
                }
                field == 2 && wire == 2 -> { val (s, e) = r.slice(); feats += (s.toLong() shl 32) or e.toLong() }
                field == 3 && wire == 2 -> keys += r.string()
                field == 4 && wire == 2 -> { val (s, e) = r.slice(); values += decodeValue(b, s, e) }
                field == 5 && wire == 0 -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        val isPeak = name == "mountain_peak"
        if (name == null) return
        for (f in feats) {
            val s = (f ushr 32).toInt(); val e = (f and 0xffffffffL).toInt()
            val fr = Proto(b, s, e)
            var type = 0
            var tags: IntArray? = null
            var geom: IntArray? = null
            while (fr.more()) {
                val (field, wire) = fr.key()
                when {
                    field == 2 && wire == 2 -> tags = fr.packed()
                    field == 3 && wire == 0 -> type = fr.varint().toInt()
                    field == 4 && wire == 2 -> geom = fr.packed()
                    else -> fr.skip(wire)
                }
            }
            if (type != 1 || geom == null || geom.size < 3 || (geom[0] and 7) != 1) continue
            val px = zigzag(geom[1]); val py = zigzag(geom[2])
            if (px < 0 || py < 0 || px >= extent || py >= extent) continue // tile buffer: belongs to a neighbour
            val props = HashMap<String, Any?>()
            tags?.let { t -> var i = 0; while (i + 1 < t.size) { keys.getOrNull(t[i])?.let { k -> props[k] = values.getOrNull(t[i + 1]) }; i += 2 } }
            val item = toItem(props, isPeak, lonOf(x + px.toDouble() / extent, z), latOf(y + py.toDouble() / extent, z)) ?: continue
            out += item
        }
    }

    private fun toItem(p: Map<String, Any?>, isPeak: Boolean, lon: Double, lat: Double): Item? {
        fun s(k: String) = (p[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val cls = s("class") ?: return null
        val (kind, sub) = if (isPeak) when (cls) {
            "peak", "volcano" -> "peak" to null
            "saddle" -> "saddle" to null
            else -> return null
        } else if (cls in PLACE_CLASSES) "place" to cls else return null
        val full = s("name") ?: return null
        val display = s("name:de") ?: s("name_de")?.takeIf { it != full } ?: full
        val alt = listOfNotNull(full, s("name:it"), s("name_int"), s("name_en")).distinct().filter { it != display }
        val ele = (p["ele"] as? Number)?.toInt()
        return Item(display, alt, kind, sub, lat, lon, ele)
    }

    private fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    private fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    private fun decodeValue(b: ByteArray, s: Int, e: Int): Any? {
        val r = Proto(b, s, e)
        var v: Any? = null
        while (r.more()) {
            val (field, wire) = r.key()
            v = when (field) {
                1 -> r.string()
                2 -> java.lang.Float.intBitsToFloat(r.fixed32())
                3 -> java.lang.Double.longBitsToDouble(r.fixed64())
                4, 5 -> r.varint()
                6 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                7 -> r.varint() != 0L
                else -> { r.skip(wire); v }
            }
        }
        return v
    }

    /** Tiny protobuf reader over a byte range. */
    private class Proto(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val c = b[pos++].toInt() and 0xff
                v = v or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return v
                shift += 7
                if (shift > 63) throw IllegalStateException("bad varint")
            }
        }
        fun key(): Pair<Int, Int> { val k = varint(); return (k ushr 3).toInt() to (k and 7).toInt() }
        fun slice(): Pair<Int, Int> { val len = varint().toInt(); val s = pos; pos += len; if (pos > end) throw IllegalStateException("truncated"); return s to pos }
        fun string(): String { val (s, e) = slice(); return String(b, s, e - s, Charsets.UTF_8) }
        fun fixed32(): Int { var v = 0; for (i in 3 downTo 0) v = (v shl 8) or (b[pos + i].toInt() and 0xff); pos += 4; return v }
        fun fixed64(): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[pos + i].toLong() and 0xff); pos += 8; return v }
        fun packed(): IntArray {
            val (s, e) = slice()
            val sub = Proto(b, s, e)
            val out = ArrayList<Int>()
            while (sub.more()) out += sub.varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("wire type $wire")
            }
        }
    }
}
