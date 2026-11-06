package com.onpath.app.ui

import com.onpath.app.ui.PlanCamera.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /** The four moments are wired: app start (StartCamera), back in the app, Navigate → Plan and the end of a tour. */
    @Test fun theMomentsAreWired() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("StartCamera.Step.SHOW_PLAN -> { vm.showPlanWhole(PlanCamera.Moment.APP_START)"))
        assertTrue(vm.contains("showPlanWhole(PlanCamera.Moment.BACK_IN_APP)"))
        assertTrue(vm.contains("PlanCamera.Moment.TOUR_END else PlanCamera.Moment.FROM_NAVIGATE"))
        // the fit is the one of "Show whole route" (north up, FitPaddingRule via UiEvent.Fit)
        assertTrue(vm.contains("UiEvent.Fit(WholeRoute.bounds(listOfNotNull(route?.points), pts, null), northUp = true)"))
    }
}
