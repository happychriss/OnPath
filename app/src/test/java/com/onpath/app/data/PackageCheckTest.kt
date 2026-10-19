package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageCheckTest {
    private val json = """{"format":1,"region":"south-tyrol","bbox":[10.7,46.3,11.6,46.85],"osm_date":"2026-10-19T01:56:37Z",
        "files":{"base.pmtiles":100,"terrain.pmtiles":200,"pois.geojson":30}}"""

    @Test fun allFilesFromThePackage() {
        val p = DataFiles.PackageCheck.parse(json) { mapOf("base.pmtiles" to 100L, "terrain.pmtiles" to 200L, "pois.geojson" to 30L)[it] ?: -1 }
        assertTrue(p.ok)
        assertEquals("south-tyrol", p.region)
        assertEquals("10.70–11.60 E · 46.30–46.85 N", p.area)
        assertEquals("2026-10-19T01:56:37Z", p.osmDate)
    }

    @Test fun oldTerrainIsNamed() {
        val p = DataFiles.PackageCheck.parse(json) { mapOf("base.pmtiles" to 100L, "terrain.pmtiles" to 150L)[it] ?: -1 }
        assertFalse(p.ok)
        assertEquals(listOf("terrain.pmtiles"), p.mismatched) // pois.geojson missing = fine (optional)
    }

    @Test fun importerKeepsPackageJson() {
        assertEquals("package.json", DataImporter.targetPath("onpath-data/package.json"))
        assertEquals(null, DataImporter.targetPath("onpath-data/manifest.json"))
    }
}
