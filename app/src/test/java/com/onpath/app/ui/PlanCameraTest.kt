package com.onpath.app.ui

import com.onpath.app.ui.PlanCamera.Show
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner 2026-11-06: in Plan with a plan the plan is in front, the position only without a plan. */
class PlanCameraTest {
    @Test fun aPlanShowsWholeOnePointCentredNoPlanNothing() {
        assertEquals(Show.WHOLE, PlanCamera.show(5, touchedSince = false, trackShown = false))
        assertEquals(Show.ONE_POINT, PlanCamera.show(1, touchedSince = false, trackShown = false))
        assertEquals(Show.NOTHING, PlanCamera.show(0, touchedSince = false, trackShown = false))
    }

    @Test fun neverAgainstTheUser() {
        assertEquals("a finger on the map meanwhile keeps the map", Show.NOTHING, PlanCamera.show(5, touchedSince = true, trackShown = false))
        assertEquals("a shown track keeps its own view", Show.NOTHING, PlanCamera.show(5, touchedSince = false, trackShown = true))
    }

}
