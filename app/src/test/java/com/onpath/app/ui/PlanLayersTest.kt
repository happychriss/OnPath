package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Navigate shows only the route line, no numbered plan points; Plan shows them. */
class PlanLayersTest {
    // canEditMap = Plan mode and no track shown (AppViewModel.canEditMap)
    @Test fun planShowsLineAndMarkers() = assertEquals(true to true, planLayers(canEditMap = true, planFollowed = false))
    @Test fun planDuringTourShowsMarkersOverFollowedLine() =
        assertEquals(false to true, planLayers(canEditMap = true, planFollowed = true))
    @Test fun navigateFollowingPlanShowsNoMarkers() =
        assertEquals(false to false, planLayers(canEditMap = false, planFollowed = true))
    @Test fun navigateWithoutPlanShowsNothing() =
        assertEquals(false to false, planLayers(canEditMap = false, planFollowed = false))

    // fix-tour-edit-line.md cause 2: after an edit during a tour the plan line is hidden (the tour follows position →
    // ahead) and only its pale copy shows it – a service re-route replaced that copy, so every line between the plan
    // points vanished.
    private fun g(i: Int) = com.onpath.app.routing.GeoPoint(51.0 + i * 0.001, 7.0)
    private val plan = (0..9).map(::g)
    private val ahead = (5..9).map(::g)
    private val reroute = listOf(g(5), com.onpath.app.routing.GeoPoint(51.0055, 7.001), g(9))

    @Test fun aReRouteAfterAnEditKeepsThePalePlan() =
        assertEquals(listOf(ahead, plan), paleLines(plan = true, planFollowed = true, navOriginal = ahead, planLine = plan,
            followLine = reroute))

    @Test fun thePalePlanDoesNotWaitForPassedPoints() =
        assertEquals(listOf(plan), paleLines(plan = true, planFollowed = true, navOriginal = null, planLine = plan, followLine = ahead))

    @Test fun theWholePlanFollowed_noPaleCopy() {
        assertEquals(emptyList<Any>(), paleLines(plan = true, planFollowed = true, navOriginal = null, planLine = plan, followLine = plan))
        // re-routed off the whole plan: the plan is the grey original once, not twice
        assertEquals(listOf(plan), paleLines(plan = true, planFollowed = true, navOriginal = plan, planLine = plan, followLine = reroute))
        // Navigate: only the re-route's original
        assertEquals(listOf(ahead), paleLines(plan = false, planFollowed = true, navOriginal = ahead, planLine = plan, followLine = reroute))
    }

    /** Rule: in Plan the plan is never invisible – its line, the followed line, or its pale copy. */
    @Test fun inPlanThePlanIsNeverInvisible() {
        val lines = listOf(null, plan, ahead, reroute)
        for (nav in lines) for (follow in lines) for (followed in listOf(true, false)) {
            val (showLine, _) = planLayers(canEditMap = true, planFollowed = followed)
            val pale = paleLines(plan = true, planFollowed = followed, navOriginal = nav, planLine = plan, followLine = follow)
            assertEquals("nav=$nav follow=$follow followed=$followed", true, showLine || follow == plan || plan in pale)
        }
    }
}
