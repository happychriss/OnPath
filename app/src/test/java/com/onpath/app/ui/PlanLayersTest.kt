package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan shows the plan's line and numbered points – also during a tour, blue as without one; Navigate shows only the
 * followed line. Owner 2026-11-01: no grey route while planning ("das Planen mit der grauen Route klappt überhaupt nicht
 * – wir lassen die blaue Route"; the blue line had vanished when the plan was hidden behind a pale copy).
 */
class PlanLayersTest {
    // canEditMap = Plan mode and no track shown (AppViewModel.canEditMap)
    @Test fun planShowsLineAndMarkers() = assertEquals(true to true, planLayers(canEditMap = true))
    @Test fun navigateShowsNoPlanLineAndNoMarkers() = assertEquals(false to false, planLayers(canEditMap = false))

    /** Owner 2026-11-01: in Navigate the plan's points show while the tour follows the plan (passed ones with a ✓). */
    @Test fun navigateFollowingThePlanShowsItsPoints() =
        assertEquals(false to true, planLayers(canEditMap = false, navigateWithPlan = true))

    private fun g(i: Int) = com.onpath.app.routing.GeoPoint(51.0 + i * 0.001, 7.0)
    private val ahead = (5..9).map(::g)

    @Test fun planHasNoGreyLines() = assertEquals(emptyList<Any>(), paleLines(plan = true, navOriginal = ahead))

    @Test fun navigateKeepsTheReRoutesGreyOriginal() = assertEquals(listOf(ahead), paleLines(plan = false, navOriginal = ahead))

    @Test fun theFollowedRouteIsNeverGrey() {
        assertEquals(listOf("TRACK", "FOLLOW"), com.onpath.app.map.MapController.TrackLook.entries.map { it.name })
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val look = vm.substringAfter("fun trackLook()").substringBefore("\n\n")
        assertEquals(true, look.contains("TrackLook.FOLLOW") && look.contains("shown != null"))
    }
}
