package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTest {
    // Straight line eastwards, one vertex every ~76 m.
    private val route = (0..100).map { GeoPoint(46.67, 11.10 + it * 0.001) }
    private val start = route[0]
    private val stop1 = route[30]
    private val stop2 = route[60]
    private val dest = route[100]
    private val plan = listOf(start, stop1, stop2, dest)

    private fun at(lon: Double, latOffset: Double = 0.0) = GeoPoint(46.67 + latOffset, lon)

    @Test fun dropsStopsBehindTheUser() =
        assertEquals(listOf(stop2, dest), RouteProgress.remainingStops(plan, route, at(11.145), emptySet()))

    @Test fun startIsDroppedOnceOnTheRoute() =
        assertEquals(listOf(stop1, stop2, dest), RouteProgress.remainingStops(plan, route, at(11.1002), emptySet()))

    @Test fun noFixKeepsStops() =
        assertEquals(plan, RouteProgress.remainingStops(plan, route, null, setOf(1)))

    @Test fun visitedStopDropsEverythingBeforeIt() {
        // Back near the start (off the line), but stop 2 was reached earlier: stop 1 was skipped.
        val r = RouteProgress.remainingStops(plan, route, at(11.10, latOffset = 0.01), setOf(2))
        assertEquals(listOf(dest), r)
    }

    @Test fun destinationIsAlwaysKept() =
        assertEquals(listOf(dest), RouteProgress.remainingStops(plan, route, at(11.21), setOf(0, 1, 2, 3)))

    @Test fun withoutRouteOnlyVisitedCounts() {
        assertEquals(listOf(stop2, dest), RouteProgress.remainingStops(plan, null, at(11.145), setOf(0, 1)))
        assertEquals(plan, RouteProgress.remainingStops(plan, null, at(11.145), emptySet()))
    }

    @Test fun roundTripAtTheStartKeepsTheWayOut() {
        val out = route.take(51)
        val loop = out + out.reversed().drop(1)
        val trip = listOf(start, route[50], start)
        assertEquals(listOf(route[50], start), RouteProgress.remainingStops(trip, loop, at(11.1001), setOf(0, 2)))
    }

    @Test fun visitedStopsWithinRadius() {
        val track = listOf(at(11.1), at(11.13, latOffset = 0.0003), at(11.15)) // ~33 m north of stop 1
        assertEquals(setOf(0, 1), RouteProgress.visitedStops(plan, track))
        assertEquals(setOf(0), RouteProgress.visitedStops(plan, track, radiusM = 20.0))
    }

    @Test fun arrival() {
        assertTrue(RouteProgress.arrived(at(11.2003), dest, remainingAlongM = 20.0))   // ~23 m away
        assertTrue(RouteProgress.arrived(at(11.2003), dest, remainingAlongM = null))   // free line end
        assertFalse(RouteProgress.arrived(at(11.2008), dest, remainingAlongM = 10.0))  // ~61 m away
        assertFalse(RouteProgress.arrived(null, dest, 0.0))
        assertFalse(RouteProgress.arrived(at(11.2), null, 0.0))
        // Round trip just after the start: close to the destination, but the whole line is left.
        assertFalse(RouteProgress.arrived(at(11.1001), start, remainingAlongM = 7_500.0))
    }
}
