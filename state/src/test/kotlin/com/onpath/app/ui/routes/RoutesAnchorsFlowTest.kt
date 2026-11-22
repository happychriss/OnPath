package com.onpath.app.ui.routes

import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.SharedPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/** Another plan takes the map's place (docs/design/detour-stitch.md): the old one is kept whole, the new one brings its own anchors. */
class RoutesAnchorsFlowTest : RoutesFlowBase() {
    private val anchor = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
    private val own = mapOf(1 to Anchors(before = listOf(anchor)))

    private fun App.withStitchedHike() {
        planRoute.activity = Activity.HIKE; planRoute.sac = 2
        plan.arriving(listOf(a, b, c), AnchorSet.of(own, Activity.HIKE, 2))
        plan.edit { clear(); addAll(listOf(a, b, c)) }
    }

    @Test fun aRouteOfAnotherProfileLeavesTheOldPlanWholeUnderZuletzt() {
        val app = App()
        app.withStitchedHike()
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 3, a, c)); app.settle()
        val row = app.draftRows.single()
        assertEquals("its own activity, not the new route's", "HIKE", row.activity)
        assertEquals(2, row.sac)
        assertEquals(own, row.anchors)
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
    }

    @Test fun aPlanLinkLeavesTheOldPlanWholeAndBringsItsOwnAnchors() {
        val app = App()
        app.withStitchedHike()
        val other = GeoPoint(c.lat + 0.01, c.lon)
        val theirs = mapOf(1 to Anchors(after = listOf(GeoPoint(c.lat + 0.005, c.lon))))
        val link = PlanCode.decode(PlanCode.encode(SharedPlan(listOf(a, c, other), Activity.BIKE_TOURING, 2, anchors = theirs)))!!
        app.go.openSharedPlan(link); app.settle()
        val row = app.draftRows.single()
        assertEquals("HIKE", row.activity); assertEquals(own, row.anchors)
        assertEquals(theirs, app.plan.anchorsFor(Activity.BIKE_TOURING, 2))
    }

    @Test fun aRouteOpenedDuringATourOnAnotherProfileRoutesWithoutItsAnchors() {
        val app = App()
        app.planRoute.activity = Activity.BIKE_TOURING
        val t = planned("Alm", "HIKE", 2, a, b, c).copy(anchors = own)
        app.slice.openTour(t, duringTour = true); app.settle()
        assertEquals(emptyMap<Int, Anchors>(), app.plan.anchorsFor(app.planRoute.activity, app.planRoute.sac))
    }
}
