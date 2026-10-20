package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format detection and required overlay files (format 1 GeoJSON vs format 2 overlays.pmtiles). */
class MapFormatTest {
    private fun files(vararg names: String): (String) -> Boolean = { it in names.toSet() }

    @Test fun overlaysFileMeansFormat2() {
        assertEquals(2, DataFiles.formatOf(files("base.pmtiles", "overlays.pmtiles")))
        // leftover format-1 files from an older import do not matter
        assertEquals(2, DataFiles.formatOf(files("overlays.pmtiles", "routes.geojson", "trails.geojson")))
    }

    @Test fun withoutOverlaysFileFormat1() {
        assertEquals(1, DataFiles.formatOf(files("base.pmtiles", "routes.geojson", "trails.geojson")))
        assertEquals(1, DataFiles.formatOf(files()))
    }

    @Test fun format2NeedsOnlyOverlays() {
        val c = DataFiles.overlayChecks(files("overlays.pmtiles"))
        assertEquals(listOf("overlays.pmtiles"), c.map { it.name })
        assertTrue(c.all { it.present })
    }

    @Test fun format1NeedsRoutesAndTrails() {
        val ok = DataFiles.overlayChecks(files("routes.geojson", "trails.geojson"))
        assertEquals(listOf("routes.geojson", "trails.geojson"), ok.map { it.name })
        assertTrue(ok.all { it.present })
        val half = DataFiles.overlayChecks(files("routes.geojson"))
        assertEquals(listOf(true, false), half.map { it.present })
    }

    @Test fun emptyFolderAsksForOverlays() {
        val c = DataFiles.overlayChecks(files())
        assertEquals(listOf("overlays.pmtiles"), c.map { it.name })
        assertFalse(c.single().present)
    }
}
