package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PoiTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cell = CellId(543, 361) // Meran

    private fun poi(lat: Double, lon: Double, props: Map<String, Any?>) = JSONObject().put("type", "Feature")
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lon, lat))))
        .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })

    private fun file(vararg features: JSONObject): File = tmp.newFile().apply {
        writeText(JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features.toList())).toString())
    }

    private fun build(f: File, keep: (Double, Double) -> Boolean = { _, _ -> true }): Pair<File, PoiTiles.Result?> {
        val out = File(tmp.root, "out/pois.pmtiles")
        return out to PoiTiles.build(listOf(PoiTiles.Input(f, keep)), out, File(tmp.root, "work"))
    }

    @Test fun kindsGoIntoTheZoomsTheStyleDrawsThem() {
        val c = cell.center
        val (out, r) = build(file(
            poi(c.lat, c.lon, mapOf("id" to "n1", "kind" to "train", "name" to "Meran")),
            poi(c.lat + 0.001, c.lon, mapOf("id" to "n2", "kind" to "restaurant", "name" to "Zum Löwen", "pr" to 120)),
            poi(c.lat - 0.001, c.lon, mapOf("id" to "n3", "kind" to "toilets")),
        ))
        assertEquals(3, r!!.features)
        val byZoom = MvtTestDecoder.featuresByZoom(out)
        assertEquals(setOf(10, 11, 12, 13, 14), byZoom.keys)
        assertEquals(listOf("n1"), byZoom.getValue(10).map { it.props["id"] })
        assertEquals(listOf("n1"), byZoom.getValue(11).map { it.props["id"] })
        assertEquals(setOf("n1", "n2"), byZoom.getValue(12).map { it.props["id"] }.toSet())
        assertEquals(setOf("n1", "n2", "n3"), byZoom.getValue(14).map { it.props["id"] }.toSet())
        // every tile belongs to the POIs' owner cell
        PmTiles.Reader(out).use { rd ->
            rd.entries().forEach { e -> val (z, x, y) = PmTiles.tileIdToZxy(e.tileId); assertEquals(cell, CellId.ofTile(z, x, y)) }
            assertEquals(10, rd.header.minZoom); assertEquals(14, rd.header.maxZoom)
            assertEquals(PmTiles.TYPE_MVT, rd.header.tileType); assertEquals(PmTiles.COMPRESSION_GZIP, rd.header.tileCompression)
            assertTrue(rd.metadata().contains("\"id\":\"pois\""))
        }
        assertFalse(File(tmp.root, "work").exists())
    }

    @Test fun propertiesAndPositionSurviveTheTiles() {
        val lat = 46.67123; val lon = 11.15987
        val (out, _) = build(file(poi(lat, lon, mapOf("id" to "w7", "kind" to "hut", "name" to "Hütte \"Alm\"", "ele" to 1834,
            "opening_hours" to "Mo-Fr 08:00-18:00; Sa 08:00-12:00", "merged" to 1, "rating" to 4.5, "open" to true,
            "nothing" to null))))
        val f = MvtTestDecoder.featuresByZoom(out).getValue(14).single()
        assertEquals(mapOf("id" to "w7", "kind" to "hut", "name" to "Hütte \"Alm\"", "ele" to 1834L,
            "opening_hours" to "Mo-Fr 08:00-18:00; Sa 08:00-12:00", "merged" to 1L, "rating" to 4.5, "open" to true), f.props)
        // z14 tile ≈ 1.6 km wide here, 8192 units → well under a metre
        assertEquals(lat, f.lat, 1e-5); assertEquals(lon, f.lon, 1e-5)
    }

    @Test fun pointsOnTheCellEdgeStayInTheirOwnersTiles() {
        val b = cell.bbox // west, south, east, north
        // west edge belongs to this cell, north edge too (half-open rule); the east edge is the neighbour's
        val (out, _) = build(file(
            poi(46.6, b[0], mapOf("id" to "west", "kind" to "train")),
            poi(b[3], 11.2, mapOf("id" to "north", "kind" to "train")),
        ))
        PmTiles.Reader(out).use { rd ->
            rd.entries().forEach { e -> val (z, x, y) = PmTiles.tileIdToZxy(e.tileId); assertEquals(cell, CellId.ofTile(z, x, y)) }
        }
        assertEquals(setOf("west", "north"), MvtTestDecoder.featuresByZoom(out).getValue(14).map { it.props["id"] }.toSet())
    }

    @Test fun nothingKeptWritesNothing() {
        val c = cell.center
        val (out, r) = build(file(poi(c.lat, c.lon, mapOf("kind" to "cafe"))), keep = { _, _ -> false })
        assertNull(r)
        assertFalse(out.exists())
        val (out2, r2) = build(file())
        assertNull(r2); assertFalse(out2.exists())
    }

    @Test fun manyCellsAreJoinedIntoOneArchive() {
        val other = CellId(544, 361)
        val (out, r) = build(file(
            poi(cell.center.lat, cell.center.lon, mapOf("id" to "a", "kind" to "cafe")),
            poi(other.center.lat, other.center.lon, mapOf("id" to "b", "kind" to "cafe")),
            JSONObject().put("type", "Feature").put("properties", JSONObject().put("kind", "cafe")), // no geometry: skipped
        ))
        assertEquals(2, r!!.features); assertEquals(1, r.skipped)
        assertEquals(6, PmTiles.verify(out)) // z12, z13, z14 for each
        PmTiles.Reader(out).use { rd ->
            assertEquals(setOf(cell, other), rd.entries().map { e -> PmTiles.tileIdToZxy(e.tileId).let { (z, x, y) -> CellId.ofTile(z, x, y) } }.toSet())
        }
    }

    @Test fun zoomTable() {
        assertEquals(10, PoiTiles.minZoom("train"))
        assertEquals(12, PoiTiles.minZoom("restaurant"))
        assertEquals(14, PoiTiles.minZoom("bus"))
        assertEquals(14, PoiTiles.minZoom("something_new"))
        assertEquals(14, PoiTiles.minZoom(null))
    }
}

/** Reads the POI archives back (Mapbox Vector Tile 2.1, only what PoiTiles writes: points, the value types). */
object MvtTestDecoder {
    data class Feature(val z: Int, val x: Int, val y: Int, val lat: Double, val lon: Double, val props: Map<String, Any>)

    fun featuresByZoom(file: File): Map<Int, List<Feature>> = PmTiles.Reader(file).use { r ->
        r.entries().flatMap { e ->
            val (z, x, y) = PmTiles.tileIdToZxy(e.tileId)
            decode(PmTiles.decompress(r.tileBytes(e), r.header.tileCompression), z, x, y)
        }.groupBy { it.z }
    }

    fun decode(tile: ByteArray, z: Int, x: Int, y: Int): List<Feature> {
        val out = ArrayList<Feature>()
        for ((f, layer) in fields(tile)) if (f == 3) {
            val lf = fields(layer as ByteArray)
            val keys = lf.filter { it.first == 3 }.map { String(it.second as ByteArray) }
            val values = lf.filter { it.first == 4 }.map { v ->
                val (vf, vv) = fields(v.second as ByteArray).single()
                when (vf) {
                    1 -> String(vv as ByteArray)
                    3 -> java.lang.Double.longBitsToDouble(vv as Long)
                    6 -> (vv as Long).let { (it ushr 1) xor -(it and 1) }
                    7 -> (vv as Long) != 0L
                    else -> error("value type $vf")
                }
            }
            val extent = (lf.firstOrNull { it.first == 5 }?.second as? Long ?: 4096L).toInt()
            assertEquals("pois", String(lf.first { it.first == 1 }.second as ByteArray))
            for ((ff, fv) in lf) if (ff == 2) {
                val ffs = fields(fv as ByteArray)
                val tags = ffs.firstOrNull { it.first == 2 }?.let { varints(it.second as ByteArray) }.orEmpty()
                assertEquals(1L, ffs.first { it.first == 3 }.second)
                val g = varints(ffs.first { it.first == 4 }.second as ByteArray)
                assertEquals(9L, g[0])
                fun unzig(n: Long) = (n ushr 1) xor -(n and 1)
                val px = unzig(g[1]); val py = unzig(g[2])
                val n = (1 shl z).toDouble()
                val mx = (x + px.toDouble() / extent) / n
                val my = (y + py.toDouble() / extent) / n
                val lon = mx * 360 - 180
                val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * my))))
                val props = (tags.indices step 2).associate { i -> keys[tags[i].toInt()] to values[tags[i + 1].toInt()] }
                out += Feature(z, x, y, lat, lon, props)
            }
        }
        return out
    }

    /** (field, value): varint → Long, fixed64 → Long, length-delimited → ByteArray. */
    private fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) return r; s += 7 } }
        while (i < b.size) {
            val key = varint(); val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += field to varint()
                1 -> { var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; out += field to v }
                2 -> { val len = varint().toInt(); out += field to b.copyOfRange(i, i + len); i += len }
                else -> error("wire type ${key and 7}")
            }
        }
        return out
    }

    private fun varints(b: ByteArray): List<Long> {
        val out = ArrayList<Long>(); var i = 0
        while (i < b.size) { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c < 0x80) break; s += 7 }; out += r }
        return out
    }
}
