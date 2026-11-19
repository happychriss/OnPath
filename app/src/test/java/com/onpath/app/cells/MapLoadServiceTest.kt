package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loading notification's numbers: which states still load, and one overall percent. */
class MapLoadServiceTest {
    @Test fun whatCountsAsLoading() {
        assertTrue(MapLoadService.loading(CellState.Requested))
        assertTrue(MapLoadService.loading(CellState.Building("contours", 0.5, 60)))
        assertTrue(MapLoadService.loading(CellState.Downloading(1, 10)))
        assertFalse("waiting for Wi-Fi is the user's choice, not loading", MapLoadService.loading(CellState.WaitingForWifi(10)))
        assertFalse(MapLoadService.loading(CellState.Ready("v1")))
        assertFalse(MapLoadService.loading(CellState.Offline("no connection")))
    }

    /** The number is the real bytes of what downloads now; no number while nothing downloads ([com.onpath.app.ui.PercentStallTest]). */
    @Test fun percentOnlyWhileItDownloads() {
        assertNull(MapLoadService.percent(listOf(CellState.Ready("v1"))))
        assertNull("prepared on the server: the bar runs", MapLoadService.percent(listOf(CellState.Requested)))
        assertEquals(50, MapLoadService.percent(listOf(CellState.Downloading(50, 100))))
        assertEquals(50, MapLoadService.percent(listOf(CellState.Requested, CellState.Downloading(50, 100), CellState.Ready("v1"))))
        assertEquals(100, MapLoadService.percent(listOf(CellState.Downloading(100, 100))))
        assertNull("joined into the map: no 99 % that stands", MapLoadService.percent(listOf(CellState.Installing)))
        assertTrue(MapLoadService.finishing(listOf(CellState.Installing, CellState.Ready("v1"))))
        assertFalse(MapLoadService.finishing(listOf(CellState.Installing, CellState.Queued(1, 60))))
    }
}
