package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner screenshot 2026-10-22: with the points open the map controls went up over the ☰ button. */
class FooterPlacementTest {
    private fun place(sheetTop: Int, footerH: Int = 200) = FooterPlacement.place(sheetTop, footerH, limitPx = 300, fadePx = 100)

    @Test fun ridesOnTheSheetWhenThereIsRoom() = assertEquals(FooterPlacement.Spot(700, 1f), place(900))

    @Test fun neverAboveTheTopBar() {
        val s = place(450)  // would be at 250, above the limit
        assertEquals(300, s.y)
        assertEquals(0.5f, s.alpha, 1e-4f)
    }

    @Test fun goneWhenTheSheetCoversItsPlace() = assertEquals(0f, place(380).alpha, 0f)

    @Test fun exactlyAtTheLimitIsFine() = assertEquals(FooterPlacement.Spot(300, 1f), place(500))
}
