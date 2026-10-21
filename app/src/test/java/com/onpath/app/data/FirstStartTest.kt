package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fresh install in the cell world (phone test 2026-10-21): when the missing-data screen blocks, and the style format. */
class FirstStartTest {
    private val nothing = listOf(
        DataFiles.Check("base.pmtiles", false, "Base map"),
        DataFiles.Check("terrain.pmtiles", false, "Relief / elevation"),
        DataFiles.Check(DataFiles.OVERLAYS, false, "Trails and route networks"),
        DataFiles.Check("segments/*.rd5", false, "Routing data"),
    )
    private val onlyRouting = listOf(DataFiles.Check("segments/*.rd5", false, "Routing data"))

    @Test fun overviewAndDownloadsNeverBlock() {
        assertTrue(DataFiles.blocking(nothing, overview = true, downloads = true).isEmpty())
        assertTrue(DataFiles.blocking(onlyRouting, overview = true, downloads = true).isEmpty())
    }

    @Test fun withoutOverviewEverythingMissingBlocksAsBefore() =
        assertEquals(nothing, DataFiles.blocking(nothing, overview = false, downloads = true))

    @Test fun withoutDownloadsEverythingMissingBlocksAsBefore() {
        assertEquals(nothing, DataFiles.blocking(nothing, overview = true, downloads = false))
        assertEquals(onlyRouting, DataFiles.blocking(onlyRouting, overview = false, downloads = false))
    }

    @Test fun nothingMissingNeverBlocks() =
        assertTrue(DataFiles.blocking(emptyList(), overview = false, downloads = false).isEmpty())

    private fun files(vararg names: String): (String) -> Boolean = { it in names.toSet() }

    @Test fun emptyMapFolderUsesTheCellFormat() = assertEquals(2, DataFiles.styleFormatOf(files()))

    @Test fun styleFormatFollowsThePackage() {
        assertEquals(2, DataFiles.styleFormatOf(files("overlays.pmtiles")))
        assertEquals(2, DataFiles.styleFormatOf(files("base.pmtiles", "overlays.pmtiles", "routes.geojson")))
        assertEquals(1, DataFiles.styleFormatOf(files("base.pmtiles", "routes.geojson", "trails.geojson")))
        assertEquals(1, DataFiles.styleFormatOf(files("base.pmtiles")))
        assertEquals(1, DataFiles.styleFormatOf(files("routes.geojson")))
    }
}
