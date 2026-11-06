package com.onpath.app.map

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route draws itself (owner 2026-11-06, polish 2): when, and which part per frame. */
class RouteRevealTest {
    // two legs of ~1.1 km each, straight north
    private val a = GeoPoint(51.0, 7.0); private val b = GeoPoint(51.01, 7.0); private val c = GeoPoint(51.02, 7.0)
    private val legs = listOf(listOf(a, GeoPoint(51.005, 7.0), b), listOf(b, c))

    @Test fun aNewRouteInPlanDrawsItself() =
        assertTrue(RouteReveal.shouldAnimate(firstDraw = false, sameRoute = false, planMode = true, pointCount = 120, animationsOn = true))

    @Test fun notTheFirstDrawingAfterTheMapLoaded() =
        assertFalse(RouteReveal.shouldAnimate(firstDraw = true, sameRoute = false, planMode = true, pointCount = 120, animationsOn = true))

    @Test fun notTheSameRouteShownAgain() =
        assertFalse(RouteReveal.shouldAnimate(firstDraw = false, sameRoute = true, planMode = true, pointCount = 120, animationsOn = true))

    @Test fun notARerouteWhileNavigating() =
        assertFalse(RouteReveal.shouldAnimate(firstDraw = false, sameRoute = false, planMode = false, pointCount = 120, animationsOn = true))

    @Test fun notAVeryLongLine() =
        assertFalse(RouteReveal.shouldAnimate(false, false, true, RouteReveal.MAX_POINTS + 1, true))

    @Test fun notWithAnimationsOffOnThePhone() =
        assertFalse(RouteReveal.shouldAnimate(false, false, true, 120, animationsOn = false))

    @Test fun nothingAtTheStartAllAtTheEnd() {
        val line = RouteReveal.Line(legs)
        assertTrue(line.cut(0.0).isEmpty())
        assertEquals(legs, line.cut(1.0))
    }

    @Test fun halfwayEndsAtTheJoin() {
        val line = RouteReveal.Line(legs)
        val half = line.cut(0.5)
        assertEquals(legs[0], half[0])
        assertEquals(b.lat, half.last().last().lat, 1e-6)
    }

    @Test fun aQuarterCutsInsideTheFirstLegByDistance() {
        val line = RouteReveal.Line(legs)
        val q = line.cut(0.25)
        assertEquals(1, q.size)
        val shown = Geo.length(q[0])
        assertEquals(line.total * 0.25, shown, 1.0)
    }

    @Test fun threeQuartersKeepTheFirstLegWhole() {
        val line = RouteReveal.Line(legs)
        val q = line.cut(0.75)
        assertEquals(2, q.size)
        assertEquals(legs[0], q[0])
        assertEquals(line.total * 0.75, Geo.length(q[0]) + Geo.length(q[1]), 1.0)
    }

    @Test fun easeRunsFromNothingToAllAndSlowsDown() {
        assertEquals(0f, RouteReveal.ease(0f), 0f)
        assertEquals(1f, RouteReveal.ease(1f), 0f)
        assertTrue(RouteReveal.ease(0.5f) > 0.5f)
    }
}
