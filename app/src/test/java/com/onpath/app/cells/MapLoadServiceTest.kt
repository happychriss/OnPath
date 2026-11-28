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

    /**
     * The notification says the sign's facts (one action, [com.onpath.app.ui.BackgroundAction]): the map areas counted –
     * „3 of 7 map areas“, never a percent (VISION 2026-11-27); nothing counted yet: "Preparing".
     */
    @Test fun theNotificationSaysTheAreasCounted() {
        assertEquals("3 of 7 map areas", MapLoadService.text(3 to 7))
        assertEquals("Preparing", MapLoadService.text(0 to 0))
        assertEquals("Preparing", MapLoadService.text(null))
    }

    /** The action counts map areas only (routing data is no area); an area that left it is done. */
    @Test fun theActionCountsAreasOnly() {
        val a = com.onpath.app.domain.ActionPercent()
        a.update(listOf(com.onpath.app.domain.ActionPercent.Item("cell:1", 10, 0.2), com.onpath.app.domain.ActionPercent.Item("cell:2", 10, 0.5),
            com.onpath.app.domain.ActionPercent.Item("routing", 10, 0.1)))
        assertEquals(0 to 2, a.areas())
        a.update(listOf(com.onpath.app.domain.ActionPercent.Item("cell:2", 10, 0.9)))
        assertEquals("cell:1 left: done", 1 to 2, a.areas())
    }
}
