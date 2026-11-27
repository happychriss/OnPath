package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loading notification: which states still load, and the action's one number. */
class MapLoadServiceTest {
    @Test fun whatCountsAsLoading() {
        assertTrue(MapLoadService.loading(CellState.Requested))
        assertTrue(MapLoadService.loading(CellState.Building("contours", 0.5, 60)))
        assertTrue(MapLoadService.loading(CellState.Downloading(1, 10)))
        assertFalse("waiting for Wi-Fi is the user's choice, not loading", MapLoadService.loading(CellState.WaitingForWifi(10)))
        assertFalse(MapLoadService.loading(CellState.Ready("v1")))
        assertFalse(MapLoadService.loading(CellState.Offline("no connection")))
    }

    /** The notification says the sign's number (one action, [com.onpath.app.ui.BackgroundAction]) – "loading 34 %". */
    @Test fun theNotificationSaysTheActionsNumber() {
        assertEquals("loading 34 %", MapLoadService.text(34))
        assertEquals("Preparing", MapLoadService.text(null))
    }
}
