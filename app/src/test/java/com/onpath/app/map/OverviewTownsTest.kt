package com.onpath.app.map

import com.onpath.app.cells.PmTiles
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Towns of the world overview name loading map areas when the app knows no better name ("near Ravensburg"). */
class OverviewTownsTest {

    // ---- a tiny vector tile writer (only what the reader needs) ----
    private fun ByteArrayOutputStream.varint(v: Long) { var x = v; while (x >= 0x80 || x < 0) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; write(x.toInt()) }
    private fun ByteArrayOutputStream.key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun ByteArrayOutputStream.bytes(field: Int, b: ByteArray) { key(field, 2); varint(b.size.toLong()); write(b) }
    private fun msg(f: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(f).toByteArray()
    private fun zz(v: Int) = ((v shl 1) xor (v shr 31)).toLong()

    data class P(val cls: String, val name: String, val nameDe: String?, val rank: Int, val x: Int, val y: Int)

    private fun tile(places: List<P>, extra: Boolean = true): ByteArray {
        val keys = listOf("class", "name", "name:de", "rank")
        val values = ArrayList<Any>()
        fun vi(v: Any): Int { val i = values.indexOf(v); return if (i >= 0) i else { values += v; values.size - 1 } }
        val feats = places.map { p ->
            val tags = mutableListOf(0, vi(p.cls), 1, vi(p.name), 3, vi(p.rank.toLong()))
            p.nameDe?.let { tags += listOf(2, vi(it)) }
            msg {
                bytes(2, msg { tags.forEach { varint(it.toLong()) } })
                key(3, 0); varint(1)
                bytes(4, msg { varint(9); varint(zz(p.x)); varint(zz(p.y)) })
            }
        }
        val layer = msg {
            key(15, 0); varint(2)
            bytes(1, "place".toByteArray())
            feats.forEach { bytes(2, it) }
            keys.forEach { bytes(3, it.toByteArray()) }
            values.forEach { v -> bytes(4, msg { if (v is String) bytes(1, v.toByteArray()) else { key(4, 0); varint(v as Long) } }) }
            key(5, 0); varint(4096)
        }
        val other = msg { key(15, 0); varint(2); bytes(1, "water".toByteArray()); key(5, 0); varint(4096) }
        return PmTiles.gzip(msg { if (extra) bytes(3, other); bytes(3, layer) })
    }

    /** Tile pixel (extent 4096) of [lat]/[lon] in tile [x]/[y] at zoom [z]. */
    private fun px(z: Int, x: Int, y: Int, lat: Double, lon: Double): Pair<Int, Int> {
        val n = 1 shl z
        val fx = (lon + 180) / 360 * n
        val fy = (1 - ln(tan(Math.toRadians(lat)) + 1 / kotlin.math.cos(Math.toRadians(lat))) / PI) / 2 * n
        return Math.round((fx - x) * 4096).toInt() to Math.round((fy - y) * 4096).toInt()
    }

    private fun archive(): ByteArray {
        // z5 tile 16/11 holds the Lake Constance area; z4 (lower zoom) must be ignored
        val (rx, ry) = px(5, 16, 11, 47.78, 9.61)
        val (cx, cy) = px(5, 16, 11, 46.85, 9.53)
        val t = tile(listOf(
            P("city", "Ravensburg", "Ravensburg", 9, rx, ry),
            P("city", "Chur", null, 9, cx, cy),
            P("country", "Schweiz", "Schweiz", 1, 2000, 3000),
            P("city", "Buffer", "Buffer", 9, -20, 100), // a neighbour's town in the buffer: not counted here
        ))
        val low = tile(listOf(P("city", "LowZoom", "LowZoom", 1, 100, 100)), extra = false)
        val f = File.createTempFile("overview", ".pmtiles")
        try {
            PmTiles.write(mapOf(Triple(5, 16, 11) to t, Triple(4, 8, 5) to low), f)
            return f.readBytes()
        } finally { f.delete() }
    }

    @Test fun readsTownsOfTheLastZoomWithGermanNames() {
        val towns = OverviewTowns.parse(archive())
        assertEquals(setOf("Ravensburg", "Chur"), towns.map { it.name }.toSet())
        val r = towns.first { it.name == "Ravensburg" }
        assertEquals(47.78, r.lat, 0.01); assertEquals(9.61, r.lon, 0.01); assertEquals(9, r.rank)
    }

    @Test fun nearestTownWithinReach() {
        val towns = OverviewTowns.parse(archive())
        assertEquals("Ravensburg", OverviewTowns.nearest(towns, GeoPoint(47.92, 9.75))?.name) // Bad Waldsee
        assertEquals("Chur", OverviewTowns.nearest(towns, GeoPoint(46.80, 9.60))?.name)
        assertNull(OverviewTowns.nearest(towns, GeoPoint(40.0, 0.0))) // nothing near: no name
        assertNull(OverviewTowns.nearest(emptyList(), GeoPoint(47.0, 9.0)))
    }

    /** The real overview of the last debug build, when there is one on this machine (map data is not in git). */
    @Test fun realOverviewNamesSouthTyrol() {
        val f = File("build/intermediates/assets/debug/mergeDebugAssets/overview/base.pmtiles")
        if (!f.isFile) return
        val towns = OverviewTowns.parse(f.readBytes())
        assertTrue("${towns.size}", towns.size > 1000)
        assertEquals("Bozen", OverviewTowns.nearest(towns, GeoPoint(46.55, 11.30))?.name)
    }
}
