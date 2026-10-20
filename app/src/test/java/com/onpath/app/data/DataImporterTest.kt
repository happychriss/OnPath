package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataImporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun targetPaths() {
        assertEquals("base.pmtiles", DataImporter.targetPath("onpath-data/base.pmtiles"))
        assertEquals("segments/E10_N45.rd5", DataImporter.targetPath("onpath-data/segments/E10_N45.rd5"))
        assertEquals("segments/E10_N45.rd5", DataImporter.targetPath("E10_N45.rd5"))
        assertEquals("pois.geojson", DataImporter.targetPath("x/y/pois.geojson"))
        assertEquals("addresses.sqlite", DataImporter.targetPath("onpath-data/addresses.sqlite"))
        assertEquals("addresses/10-543-361.sqlite", DataImporter.targetPath("onpath-data/addresses/10-543-361.sqlite"))
        assertEquals(null, DataImporter.targetPath("onpath-data/other.sqlite"))
        assertEquals("styles/onpath-high-contrast.json", DataImporter.targetPath("onpath-high-contrast.json"))
        assertEquals("styles/mine.json", DataImporter.targetPath("onpath-data/styles/mine.json"))
        assertNull(DataImporter.targetPath("../evil.pmtiles"))
        assertNull(DataImporter.targetPath("onpath-data/readme.txt"))
        assertNull(DataImporter.targetPath("random.json"))
        assertNull(DataImporter.targetPath("__MACOSX/._base.pmtiles"))
    }

    @Test fun importsZipIntoDataDir() {
        val dir = tmp.newFolder("onpath")
        val r = DataImporter.import(ByteArrayInputStream(zip(
            "onpath-data/base.pmtiles" to "tiles",
            "onpath-data/segments/E10_N45.rd5" to "rd5",
            "onpath-data/notes.txt" to "skip me",
        )), "onpath-data-southtyrol.zip", dir)
        assertEquals(listOf("base.pmtiles", "segments/E10_N45.rd5"), r.imported)
        assertEquals(listOf("notes.txt"), r.skipped)
        assertEquals("tiles", File(dir, "base.pmtiles").readText())
        assertEquals("rd5", File(dir, "segments/E10_N45.rd5").readText())
        assertFalse(dir.walk().any { it.name.endsWith(".importing") })
    }

    @Test fun replacesExistingFile() {
        val dir = tmp.newFolder("onpath")
        File(dir, "pois.geojson").writeText("old")
        DataImporter.import(ByteArrayInputStream("new".toByteArray()), "pois.geojson", dir)
        assertEquals("new", File(dir, "pois.geojson").readText())
    }

    @Test fun rejectsUnknownSingleFile() {
        val dir = tmp.newFolder("onpath")
        try {
            DataImporter.import(ByteArrayInputStream("x".toByteArray()), "holiday.jpg", dir); fail()
        } catch (e: DataImporter.ImportException) {
            assertTrue(e.message!!.contains("not OnPath map data"))
        }
    }

    @Test fun zipWithoutMapDataFails() {
        val dir = tmp.newFolder("onpath")
        try {
            DataImporter.import(ByteArrayInputStream(zip("a.txt" to "x")), "a.zip", dir); fail()
        } catch (e: DataImporter.ImportException) { /* expected */ }
    }

    @Test fun brokenStreamKeepsOldFile() {
        val dir = tmp.newFolder("onpath")
        File(dir, "base.pmtiles").writeText("good")
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 10) 'x'.code else throw java.io.IOException("cut")
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (n++ < 1) { b[off] = 1; 1 } else throw java.io.IOException("cut")
        }
        try { DataImporter.import(failing, "base.pmtiles", dir); fail() } catch (e: java.io.IOException) { /* expected */ }
        assertEquals("good", File(dir, "base.pmtiles").readText())
        assertFalse(dir.walk().any { it.name.endsWith(".importing") })
    }

    @Test fun routingTileNames() {
        assertEquals("E10_N45.rd5", SegmentDownloader.tileName(46.5, 11.35))
        assertEquals("E5_N45.rd5", SegmentDownloader.tileName(47.5, 9.9))
        assertEquals("W5_N50.rd5", SegmentDownloader.tileName(51.5, -0.1))
        assertEquals("E10_N50.rd5", SegmentDownloader.tileName(52.5, 13.4))
    }
}
