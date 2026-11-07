package com.onpath.web

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanReach
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.PlanGap
import com.onpath.app.ui.PlanGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stop the route does not reach says what does (owner 2026-11-07, "Meran → Mutkopf" at T1; donald's app PlanReach):
 * the planner's gaps JSON carries the app's reach line instead of the missing metres, and the way that reaches the
 * point (the page draws it amber on "Show", like a steep section). Without a reach: the old line, no way.
 */
class WebPlanReachTest {
    private val start = GeoPoint(46.670, 11.160)
    private val end = GeoPoint(46.680, 11.170) // where the route stopped
    private val goal = GeoPoint(46.684, 11.175) // ② – not reached at T1
    private val gap = PlanGap(1, goal, end, 552.0)

    private fun result(): PlanResult {
        val line = listOf(start, end)
        val route = PlannedRoute(listOf(PlannedLeg(line, false)), 1500.0, 300.0, 0.0, 1800.0, null)
        return PlanResult(listOf(start, goal), route, Activity.HIKE, 1, emptySet(), emptySet(), listOf(start, goal),
            false, emptySet(), listOf(gap), emptyList())
    }

    private fun text(json: String): String = Regex(""""text":"([^"]*)"""").find(json)!!.groupValues[1]

    @Test fun reachedAtAHigherLevelSaysSoAndCarriesTheWay() {
        val way = listOf(end, GeoPoint(46.682, 11.172))
        val reach = PlanReach(1, PlanReach.Fix.Level(2), way)
        val json = WebPlanRules.gapsJson(result(), emptyMap(), reach)
        assertEquals(PlanGaps.reachText(listOf(gap), reach), text(json))
        assertTrue(json, text(json).contains("T2"))
        // the way that reaches it, ending at the point itself
        assertTrue(json, json.contains("\"way\":[[46.680000,11.170000],[46.682000,11.172000],[46.684000,11.175000]]"))
    }

    @Test fun withoutAReachTheMissingMetresAsBefore() {
        val json = WebPlanRules.gapsJson(result(), emptyMap(), null)
        assertEquals(PlanGaps.text(listOf(gap), Activity.HIKE) { null }, text(json))
        assertTrue(json, json.contains("\"way\":null"))
    }

    @Test fun aReachForAnotherPointIsIgnored() {
        val json = WebPlanRules.gapsJson(result(), emptyMap(), PlanReach(0, PlanReach.Fix.Level(3), listOf(start, end)))
        assertTrue(json, json.contains("\"way\":null"))
    }
}
