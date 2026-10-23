package com.onpath.app.map

import android.content.Context
import com.onpath.app.cells.PmTiles
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Larger towns of the world overview map in the APK ([Overview], `overview/base.pmtiles`: Natural Earth populated
 * places in the OpenMapTiles `place` layer, class `city`, German name first) – to name a map area that loads in the
 * background ("near Ravensburg") when the app knows no better name. Read once, off the main thread; pure apart from
 * [load], unit-tested.
 */
object OverviewTowns {
    data class Town(val name: String, val lat: Double, val lon: Double, val rank: Int)

    /** A town farther than this from an area's centre does not name it. */
    const val MAX_M = 60_000.0

    /** The towns of the bundled overview; empty when the build has none. Blocking (≈ 3 MB read) – call off the main thread. */
    fun load(context: Context): List<Town> {
        if ("base.pmtiles" !in Overview.bundled(context)) return emptyList()
        return context.assets.open("${Overview.ASSET_DIR}/base.pmtiles").use { parse(it.readBytes()) }
    }

    /** The nearest town to [p] within [maxM], or null. */
    fun nearest(towns: List<Town>, p: GeoPoint, maxM: Double = MAX_M): Town? {
        var best: Town? = null; var bestD = maxM
        for (t in towns) {
            val d = Geo.distance(p.lat, p.lon, t.lat, t.lon)
            if (d <= bestD) { best = t; bestD = d }
        }
        return best
    }

    /** Every place (not country) of the last zoom level of the PMTiles archive [b]. */
    fun parse(b: ByteArray): List<Town> {
        val h = PmTiles.Header.parse(b.copyOf(PmTiles.HEADER_SIZE))
        fun slice(off: Long, len: Long): ByteArray {
            if (off < 0 || len < 0 || off + len > b.size) throw IOException("overview map truncated")
            return b.copyOfRange(off.toInt(), (off + len).toInt())
        }
        val entries = ArrayList<PmTiles.Entry>()
        fun walk(off: Long, len: Long, depth: Int) {
            if (depth > 4) throw IOException("overview map: directories nested too deep")
            for (e in PmTiles.decodeDirectory(PmTiles.decompress(slice(off, len), h.internalCompression))) {
                if (e.runLength == 0L) walk(h.leafOffset + e.offset, e.length, depth + 1) else entries += e
            }
        }
        walk(h.rootOffset, h.rootLength, 0)
        val z = h.maxZoom
        val first = PmTiles.zoomStart(z); val last = PmTiles.zoomStart(z + 1)
        val out = LinkedHashMap<String, Town>()
        for (e in entries) {
            if (e.tileId + e.runLength <= first || e.tileId >= last) continue
            val places = Mvt.points(PmTiles.decompress(slice(h.dataOffset + e.offset, e.length), h.tileCompression), "place")
                .filter { it.props["class"] != "country" }
            if (places.isEmpty()) continue
            for (i in 0 until e.runLength) {
                val (tz, x, y) = PmTiles.tileIdToZxy(e.tileId + i)
                if (tz != z) continue
                for (pt in places) {
                    if (pt.x < 0 || pt.y < 0 || pt.x >= pt.extent || pt.y >= pt.extent) continue // buffer copy of a neighbour's
                    val name = (pt.props["name:de"] ?: pt.props["name_de"] ?: pt.props["name"])?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val n = (1 shl z).toDouble()
                    val lon = (x + pt.x.toDouble() / pt.extent) / n * 360 - 180
                    val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + pt.y.toDouble() / pt.extent) / n))))
                    val rank = (pt.props["rank"] as? Number)?.toInt() ?: 99
                    out.putIfAbsent("$name@${"%.2f".format(java.util.Locale.ROOT, lat)},${"%.2f".format(java.util.Locale.ROOT, lon)}", Town(name, lat, lon, rank))
                }
            }
        }
        return out.values.toList()
    }

    /** Minimal Mapbox Vector Tile reader: point features of one layer with their properties. */
    internal object Mvt {
        data class Point(val x: Int, val y: Int, val extent: Int, val props: Map<String, Any>)

        private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
            fun more() = pos < end
            fun varint(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    if (pos >= end) throw IOException("vector tile truncated")
                    val c = b[pos++].toInt() and 0xff
                    v = v or ((c and 0x7f).toLong() shl shift)
                    if (c < 0x80) return v
                    shift += 7
                    if (shift > 63) throw IOException("bad varint")
                }
            }
            fun sub(): Pb { val n = varint().toInt(); if (n < 0 || pos + n > end) throw IOException("vector tile truncated"); return Pb(b, pos, pos + n).also { pos += n } }
            fun string(): String = sub().let { String(b, it.pos, it.end - it.pos, Charsets.UTF_8) }
            fun fixed(n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += n; return v }
            fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> pos += 8; 2 -> sub(); 5 -> pos += 4; else -> throw IOException("wire type $wire") } }
            fun packed(): IntArray { val s = sub(); val l = ArrayList<Int>(); while (s.more()) l += s.varint().toInt(); return l.toIntArray() }
        }

        private fun zz(v: Int) = (v ushr 1) xor -(v and 1)

        fun points(tile: ByteArray, layer: String): List<Point> {
            val t = Pb(tile, 0, tile.size)
            while (t.more()) {
                val key = t.varint().toInt()
                if (key ushr 3 == 3 && key and 7 == 2) { val l = readLayer(t.sub(), layer); if (l != null) return l } else t.skip(key and 7)
            }
            return emptyList()
        }

        private fun readLayer(l: Pb, want: String): List<Point>? {
            var name = ""; var extent = 4096
            val keys = ArrayList<String>(); val values = ArrayList<Any>(); val feats = ArrayList<Pb>()
            while (l.more()) {
                val key = l.varint().toInt()
                when (key ushr 3) {
                    1 -> name = l.string()
                    2 -> feats += l.sub()
                    3 -> keys += l.string()
                    4 -> values += readValue(l.sub())
                    5 -> extent = l.varint().toInt()
                    else -> l.skip(key and 7)
                }
            }
            if (name != want) return null
            val out = ArrayList<Point>()
            for (f in feats) {
                var tags = IntArray(0); var type = 0; var geom = IntArray(0)
                while (f.more()) {
                    val key = f.varint().toInt()
                    when (key ushr 3) {
                        2 -> tags = f.packed()
                        3 -> type = f.varint().toInt()
                        4 -> geom = f.packed()
                        else -> f.skip(key and 7)
                    }
                }
                if (type != 1 || geom.size < 3 || geom[0] and 7 != 1) continue
                val props = HashMap<String, Any>()
                var i = 0
                while (i + 1 < tags.size) { keys.getOrNull(tags[i])?.let { k -> values.getOrNull(tags[i + 1])?.let { props[k] = it } }; i += 2 }
                out += Point(zz(geom[1]), zz(geom[2]), extent, props)
            }
            return out
        }

        private fun readValue(v: Pb): Any {
            var r: Any = ""
            while (v.more()) {
                val key = v.varint().toInt()
                r = when (key ushr 3) {
                    1 -> v.string()
                    2 -> java.lang.Float.intBitsToFloat(v.fixed(4).toInt()).toDouble()
                    3 -> java.lang.Double.longBitsToDouble(v.fixed(8))
                    4, 5 -> v.varint()
                    6 -> v.varint().let { (it ushr 1) xor -(it and 1) }
                    7 -> v.varint() != 0L
                    else -> { v.skip(key and 7); r }
                }
            }
            return r
        }
    }
}
