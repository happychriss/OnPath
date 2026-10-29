package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Trail names read from a format-2 overlays tile (layer "trailnames"), hand-encoded MVT. */
class OverlayTrailNamesTest {
    private class W {
        val o = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x >= 0x80) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, b: ByteArray) { key(field, 2); varint(b.size.toLong()); o.write(b) }
        fun str(field: Int, s: String) = bytes(field, s.toByteArray())
        fun packed(field: Int, v: IntArray) { val w = W(); v.forEach { w.varint(it.toLong() and 0xffffffffL) }; bytes(field, w.o.toByteArray()) }
        fun uint(field: Int, v: Long) { key(field, 0); varint(v) }
    }

    private fun zz(n: Int) = (n shl 1) xor (n shr 31)

    private fun value(build: W.() -> Unit) = W().apply(build).o.toByteArray()

    /** One layer, features = (tags, geometry). keys: t, name, len; values: "n", "r", name, 1200. */
    private fun tile(layer: String, feats: List<Pair<IntArray, IntArray>>): ByteArray {
        val l = W()
        l.str(1, layer)
        for ((tags, geom) in feats) {
            val f = W(); f.packed(2, tags); f.uint(3, 2); f.packed(4, geom)
            l.bytes(2, f.o.toByteArray())
        }
        listOf("t", "name", "len").forEach { l.str(3, it) }
        l.bytes(4, value { str(1, "n") })
        l.bytes(4, value { str(1, "r") })
        l.bytes(4, value { str(1, "Algunder Waalweg") })
        l.bytes(4, value { uint(5, 1200) })
        l.uint(5, 4096); l.uint(15, 2)
        return W().apply { bytes(3, l.o.toByteArray()) }.o.toByteArray()
    }

    private fun line(vararg pts: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        var cx = 0; var cy = 0
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) out += (1 or (1 shl 3)) else if (i == 1) out += (2 or ((pts.size - 1) shl 3))
            out += zz(x - cx); out += zz(y - cy); cx = x; cy = y
        }
        return out.toIntArray()
    }

    @Test fun readsNamedLinesAtTheirMiddle() {
        val name = intArrayOf(0, 0, 1, 2, 2, 3)          // t=n, name, len
        val ref = intArrayOf(0, 1, 1, 2)                  // t=r: not a name
        val t = tile("trailnames", listOf(name to line(100 to 100, 2048 to 2048, 4000 to 4000), ref to line(10 to 10, 20 to 20)))
        val out = ArrayList<BaseMapPlaces.TrailName>()
        BaseMapPlaces.decodeTrailNames(t, 14, 8699, 5784, out)
        assertEquals(1, out.size)
        assertEquals("Algunder Waalweg", out[0].name)
        assertEquals(1200.0, out[0].len, 0.0)
        // middle vertex = tile centre of z14 8699/5784 (Algund)
        assertEquals(11.1511, out[0].lon, 0.001)
        assertEquals(46.6721, out[0].lat, 0.001)
    }

    @Test fun pieceInTheTileBufferBelongsToTheNeighbour() {
        val name = intArrayOf(0, 0, 1, 2, 2, 3)
        val t = tile("trailnames", listOf(name to line(-60 to 10, -40 to 10, -20 to 10)))
        val out = ArrayList<BaseMapPlaces.TrailName>()
        BaseMapPlaces.decodeTrailNames(t, 14, 8699, 5784, out)
        assertTrue(out.isEmpty())
    }

    @Test fun otherLayersIgnored() {
        val t = tile("paths", listOf(intArrayOf(0, 0, 1, 2) to line(1 to 1, 2 to 2)))
        val out = ArrayList<BaseMapPlaces.TrailName>()
        BaseMapPlaces.decodeTrailNames(t, 14, 8699, 5784, out)
        assertTrue(out.isEmpty())
    }

    @Test fun readsRealOverlaysWhenAvailable() {
        // Optional: a format-2 package folder (not in git), e.g. ONPATH_F2_DATA=…/onpath-build/out-f2
        val dir = System.getenv("ONPATH_F2_DATA")?.let { java.io.File(it) } ?: return
        val f = java.io.File(dir, "overlays.pmtiles")
        if (!f.isFile) return
        val names = OfflineIndex.readTrailsFromTiles(f)
        println("trail names from ${f.path}: ${names.size}")
        assertTrue(names.size > 500)
        assertTrue(names.any { it.name == "Algunder Waalweg" })
    }

    @Test fun sameNameWithin2kmMerges() {
        val p = OfflineIndex.mergeTrailParts(listOf(
            OfflineIndex.Companion.TrailPart("Algunder Waalweg", 46.68, 11.13, 500.0),
            OfflineIndex.Companion.TrailPart("Algunder Waalweg", 46.681, 11.131, 900.0),
            OfflineIndex.Companion.TrailPart("Algunder Waalweg", 46.75, 11.13, 100.0)))
        assertEquals(2, p.size)
    }
}
