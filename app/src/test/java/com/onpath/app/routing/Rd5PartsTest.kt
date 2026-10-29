package com.onpath.app.routing

import com.onpath.app.routing.Rd5Parts.Part
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/** 1° parts of BRouter rd5 files (docs/design/map-data-size.md §6): part choice, index / tail, atomic writing. */
class Rd5PartsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun partNamesAndIndex() {
        val meran = Rd5Parts.partOf(46.67, 11.16)
        assertEquals(Part(11, 46), meran)
        assertEquals("E10_N45.rd5", meran.tile)
        assertEquals(6, meran.index) // (11-10)*5 + (46-45)
        assertEquals("E10_N45.rd5:E11_N46", meran.key)
        assertEquals("11°E 46°N", meran.label)
        assertEquals(meran, Part.parse(meran.key))
        assertEquals(meran, Part.of("E10_N45.rd5", 6))
        val hilden = Rd5Parts.partOf(51.17, 6.93)
        assertEquals("E5_N50.rd5", hilden.tile); assertEquals(6, hilden.index)
        // west / south: floorMod keeps 0..24 as BRouter does (lon + 180 based)
        val london = Rd5Parts.partOf(51.5, -0.1)
        assertEquals(Part(-1, 51), london); assertEquals("W5_N50.rd5", london.tile); assertEquals(4 * 5 + 1, london.index)
        assertEquals(london, Part.parse(london.key))
        val rio = Rd5Parts.partOf(-22.9, -43.2)
        assertEquals("W45_S25.rd5", rio.tile); assertEquals(Part(-44, -23), rio); assertEquals(1 * 5 + 2, rio.index)
        assertNull(Part.parse("E10_N45.rd5")); assertNull(Part.parse("E10_N45.rd5:E16_N46")) // not inside the tile
        for (i in 0 until 25) assertEquals(i, Part.of("W45_S25.rd5", i).index)
    }

    @Test fun boxCorridorAndPoint() {
        assertEquals(setOf(Part(11, 46)), Rd5Parts.forBox(46.56, 10.90 + 0.2, 46.80, 11.25))
        assertEquals(setOf(Part(10, 46), Part(11, 46)), Rd5Parts.forBox(46.56, 10.90, 46.80, 11.25))
        assertEquals(setOf(Part(11, 46)), Rd5Parts.forBox(46.0, 11.0, 47.0, 12.0)) // edges exactly on degree lines
        assertEquals(setOf(Part(11, 46)), Rd5Parts.forPoint(GeoPoint(46.67, 11.16))) // Meran: 2 km stays inside
        assertEquals(setOf(Part(10, 46), Part(11, 46)), Rd5Parts.forPoint(GeoPoint(46.67, 11.01)))
        // Bozen -> St. Pankraz: whole line in 11°E 46°N, but 10 km around St. Pankraz reaches west of 11°E
        val line = listOf(GeoPoint(46.497, 11.358), GeoPoint(46.585, 11.087))
        assertEquals(setOf(Part(10, 46), Part(11, 46)), Rd5Parts.forCorridor(line))
        assertEquals(setOf(Part(11, 46)), Rd5Parts.forCorridor(line, marginM = 1_000.0))
        // a long straight leg samples the parts it crosses (Bozen -> Munich)
        val long = Rd5Parts.forCorridor(listOf(GeoPoint(46.5, 11.35), GeoPoint(48.14, 11.58)), 1_000.0)
        assertTrue(long.containsAll(listOf(Part(11, 46), Part(11, 47), Part(11, 48))))
        assertTrue(long.all { it.lon == 11 })
        assertTrue(Rd5Parts.forCorridor(listOf(GeoPoint(Double.NaN, 1.0))).isEmpty())
    }

    @Test fun crcIsBRoutersCrc() {
        val b = Random(1).nextBytes(500)
        assertEquals(btools.util.Crc32.crc(b, 0, 200), Rd5Parts.crc(b, 0, 200))
        assertEquals(btools.util.Crc32.crc(b, 17, 333), Rd5Parts.crc(b, 17, 333))
    }

    @Test fun writesPartialFileThatBRouterOpens() {
        val (remote, idx, tail) = fakeRemoteFile(tmp.newFolder("r"), sizes = sizes())
        val dest = File(tmp.newFolder("seg"), "E10_N45.rd5")
        val keep = setOf(1, 6, 24)
        Rd5Parts.write(dest, idx, tail, keep.associateWith { Rd5Parts.fromFile(remote, idx, it) })
        val local = Rd5Parts.readIndex(dest)!!
        assertEquals(keep, local.withData)
        for (i in keep) {
            assertArrayEquals(slice(remote, idx.start(i), idx.size(i)), slice(dest, local.start(i), local.size(i)))
            assertEquals(idx.high(i), local.high(i)) // version bits kept
        }
        assertEquals(local.dataEnd + tail.size, dest.length())
        // BRouter's own reader: index checksum ok, new format (1/32° grid) recognised, creation time kept
        val pf = btools.mapaccess.PhysicalFile(dest, btools.codec.DataBuffers(), -1, -1)
        assertEquals(32, pf.divisor)
        assertEquals(Rd5Parts.creationTime(tail), pf.creationTime)
        pf.close()
        assertFalse(File(dest.parentFile, ".E10_N45.rd5.tmp").exists())
        assertEquals(true, Rd5Parts.hasData(dest.parentFile, 46.6, 11.2))
        assertEquals(false, Rd5Parts.hasData(dest.parentFile, 46.6, 10.9 + 1.5))
        assertNull(Rd5Parts.hasData(dest.parentFile, 52.0, 13.0))
    }

    @Test fun failedWriteLeavesTheOldFile() {
        val (remote, idx, tail) = fakeRemoteFile(tmp.newFolder("r"), sizes = sizes())
        val dest = File(tmp.newFolder("seg"), "E10_N45.rd5")
        Rd5Parts.write(dest, idx, tail, mapOf(6 to Rd5Parts.fromFile(remote, idx, 6)))
        val before = dest.readBytes()
        val short = File(tmp.root, "short.part").apply { writeBytes(ByteArray(3)) }
        try {
            Rd5Parts.write(dest, idx, tail, mapOf(6 to Rd5Parts.fromFile(remote, idx, 6), 7 to Rd5Parts.fromPartFile(short)))
            throw AssertionError("a part of the wrong size must fail")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertArrayEquals(before, dest.readBytes())
        assertFalse(File(dest.parentFile, ".E10_N45.rd5.tmp").exists())
    }

    @Test fun tailOfAnotherVersionIsRefused() {
        val (_, idx, tail) = fakeRemoteFile(tmp.newFolder("r"), sizes = sizes())
        val other = Rd5Parts.Index(Rd5Parts.buildIndex(idx, setOf(1)))
        try {
            Rd5Parts.buildTail(other, tail, other.raw); throw AssertionError("mixed versions must fail")
        } catch (e: java.io.IOException) {
            // expected
        }
    }

    /** With the real brouter.de file (ONPATH_RD5; skipped when it is missing): only part 6 kept, BRouter's integrity check passes. */
    @Test fun realFileIntegrity() {
        val src = File(System.getenv("ONPATH_RD5") ?: "/home/development/Projects/onpath-build/out/segments/E10_N45.rd5")
        assumeTrue(src.isFile)
        val idx = Rd5Parts.readIndex(src)!!
        val tail = Rd5Parts.readTail(src, idx)
        assertEquals(113, tail.size)
        val dest = File(tmp.newFolder("seg"), "E10_N45.rd5")
        Rd5Parts.write(dest, idx, tail, mapOf(6 to Rd5Parts.fromFile(src, idx, 6)))
        assertNull(btools.mapaccess.PhysicalFile.checkFileIntegrity(dest))
    }

    companion object {
        fun sizes(): IntArray = IntArray(25) { i -> if (i == 4 || i == 9) 0 else 300 + i * 37 }

        /** A file laid out like a brouter.de rd5 (random part bytes, real index + tail rules). */
        fun fakeRemoteFile(dir: File, sizes: IntArray, created: Long = 1_760_000_000_000L, seed: Int = 7): Triple<File, Rd5Parts.Index, ByteArray> {
            val rnd = Random(seed)
            val index = ByteBuffer.allocate(200)
            var pos = 200L
            for (i in 0 until 25) { pos += sizes[i]; index.putLong((if (i == 0) 11L shl 48 else 0L) or pos) }
            val idxBytes = index.array()
            val tail = ByteBuffer.allocate(113)
            tail.putLong(created).putInt(Rd5Parts.crc(idxBytes) xor 2)
            for (i in 0 until 25) tail.putInt(rnd.nextInt())
            tail.put(1)
            val f = File(dir, "E10_N45.rd5")
            f.outputStream().use { out ->
                out.write(idxBytes)
                for (i in 0 until 25) out.write(rnd.nextBytes(sizes[i]))
                out.write(tail.array())
            }
            return Triple(f, Rd5Parts.Index(idxBytes), tail.array())
        }

        fun slice(f: File, start: Long, len: Long): ByteArray = f.readBytes().copyOfRange(start.toInt(), (start + len).toInt())
    }
}
