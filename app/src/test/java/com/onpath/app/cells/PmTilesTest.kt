package com.onpath.app.cells

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class PmTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun tileIdsFollowTheSpec() {
        assertEquals(0L, PmTiles.zxyToTileId(0, 0, 0))
        // spec test values (z1 Hilbert order)
        assertEquals(1L, PmTiles.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmTiles.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmTiles.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmTiles.zxyToTileId(1, 1, 0))
        assertEquals(5L, PmTiles.zxyToTileId(2, 0, 0))
        val r = Random(7)
        repeat(2000) {
            val z = r.nextInt(0, 18); val n = 1 shl z
            val x = r.nextInt(n); val y = r.nextInt(n)
            assertEquals(Triple(z, x, y), PmTiles.tileIdToZxy(PmTiles.zxyToTileId(z, x, y)))
        }
    }

    @Test fun directoryRoundTrip() {
        val e = listOf(PmTiles.Entry(5, 0, 10, 1), PmTiles.Entry(6, 10, 7, 3), PmTiles.Entry(100, 0, 10, 1), PmTiles.Entry(101, 0, 0, 0))
        assertEquals(e, PmTiles.decodeDirectory(PmTiles.encodeDirectory(e)))
    }

    @Test fun writeAndReadBackSmall() {
        val f = File(tmp.root, "a.pmtiles")
        val tiles = mapOf(Triple(10, 543, 361) to byteArrayOf(1, 2, 3), Triple(11, 1086, 722) to byteArrayOf(4, 5))
        PmTiles.write(tiles, f, metadataJson = """{"name":"x"}""")
        PmTiles.Reader(f).use { r ->
            assertEquals(2, r.entries().size)
            assertArrayEquals(byteArrayOf(1, 2, 3), r.tile(10, 543, 361))
            assertArrayEquals(byteArrayOf(4, 5), r.tile(11, 1086, 722))
            assertNull(r.tile(11, 1087, 722))
            assertEquals("""{"name":"x"}""", r.metadata())
        }
        assertEquals(2L, PmTiles.verify(f))
    }

    /** Enough random tiles that the root directory does not fit 16 KiB: leaf directories on read and write. */
    @Test fun leafDirectoriesRoundTrip() {
        val r = Random(1)
        val tiles = HashMap<Triple<Int, Int, Int>, ByteArray>()
        for (x in 8000 until 8180) for (y in 5000 until 5180) tiles[Triple(14, x, y)] = ByteArray(r.nextInt(1, 300)) { r.nextInt().toByte() }
        val src = File(tmp.root, "big.pmtiles")
        PmTiles.write(tiles, src)
        PmTiles.Reader(src).use { assertTrue("expected leaf directories", it.header.leafLength > 0) }
        val out = File(tmp.root, "merged.pmtiles")
        val res = PmTiles.merge(listOf(PmTiles.Input(src) { _, _, _ -> true }), out)!!
        assertEquals(tiles.size.toLong(), res.tiles)
        assertEquals(tiles.size.toLong(), PmTiles.verify(out))
        PmTiles.Reader(out).use { rd ->
            assertTrue(rd.header.leafLength > 0)
            assertTrue(rd.header.rootLength <= PmTiles.ROOT_LIMIT)
            val byId = rd.entries().associateBy { it.tileId }
            for ((k, v) in tiles.entries.take(500)) assertArrayEquals(v, rd.tileBytes(byId[PmTiles.zxyToTileId(k.first, k.second, k.third)]!!))
        }
    }

    @Test fun mergeKeepsFiltersOverridesAndDedupes() {
        val shared = byteArrayOf(9, 9, 9)
        val a = File(tmp.root, "a.pmtiles"); val b = File(tmp.root, "b.pmtiles")
        PmTiles.write(mapOf(Triple(10, 1, 1) to byteArrayOf(1), Triple(10, 2, 2) to byteArrayOf(2),
            Triple(12, 0, 0) to shared, Triple(12, 0, 1) to shared, Triple(12, 1, 1) to shared), a)
        PmTiles.write(mapOf(Triple(10, 2, 2) to byteArrayOf(22), Triple(10, 3, 3) to byteArrayOf(3)), b)
        val out = File(tmp.root, "m.pmtiles")
        val res = PmTiles.merge(listOf(PmTiles.Input(a) { z, x, _ -> !(z == 10 && x == 1) }, PmTiles.Input(b) { _, _, _ -> true }), out)!!
        assertEquals(5L, res.tiles) // 10/2/2 (b wins), 10/3/3, three z12
        PmTiles.Reader(out).use { r ->
            assertNull(r.tile(10, 1, 1))
            assertArrayEquals(byteArrayOf(22), r.tile(10, 2, 2))
            assertArrayEquals(byteArrayOf(3), r.tile(10, 3, 3))
            assertArrayEquals(shared, r.tile(12, 0, 1))
            assertEquals(3L, r.header.tileContents) // shared stored once
            assertEquals(10, r.header.minZoom); assertEquals(12, r.header.maxZoom)
        }
        assertNull(PmTiles.merge(listOf(PmTiles.Input(a) { _, _, _ -> false }), File(tmp.root, "none.pmtiles")))
    }

    @Test fun mergeRefusesMixedTileTypes() {
        val a = File(tmp.root, "a.pmtiles"); val b = File(tmp.root, "b.pmtiles")
        PmTiles.write(mapOf(Triple(10, 1, 1) to byteArrayOf(1)), a, tileType = 2, tileCompression = PmTiles.COMPRESSION_NONE)
        PmTiles.write(mapOf(Triple(10, 1, 2) to byteArrayOf(1)), b, tileType = 4, tileCompression = PmTiles.COMPRESSION_NONE)
        try {
            PmTiles.merge(listOf(PmTiles.Input(a) { _, _, _ -> true }, PmTiles.Input(b) { _, _, _ -> true }), File(tmp.root, "x.pmtiles"))
            fail("expected IncompatibleException")
        } catch (e: PmTiles.IncompatibleException) { assertNotNull(e.message) }
    }

    @Test fun truncatedFileIsRejected() {
        val a = File(tmp.root, "a.pmtiles")
        PmTiles.write(mapOf(Triple(10, 1, 1) to ByteArray(1000) { 1 }), a)
        val t = File(tmp.root, "t.pmtiles")
        t.writeBytes(a.readBytes().copyOf(a.length().toInt() - 100))
        try { PmTiles.verify(t); fail("expected an error") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("truncated")) }
    }

    /** The search's own PMTiles reader (BaseMapPlaces) reads what the writer produced. */
    @Test fun searchReaderUnderstandsWrittenFiles() {
        val f = File(tmp.root, "dir.pmtiles")
        val e = PmTiles.decodeDirectory(PmTiles.encodeDirectory(listOf(PmTiles.Entry(1, 0, 4, 2))))
        assertEquals(e, com.onpath.app.search.BaseMapPlaces.decodeDirectory(PmTiles.encodeDirectory(e)).map { PmTiles.Entry(it.tileId, it.offset, it.length, it.runLength) })
        PmTiles.write(mapOf(Triple(14, 8710, 5781) to PmTiles.gzip(ByteArray(0))), f)
        assertEquals(emptyList<Any>(), com.onpath.app.search.BaseMapPlaces.read(f))
    }
}
