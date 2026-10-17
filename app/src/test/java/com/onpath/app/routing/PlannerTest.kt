package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlannerTest {

    /** Records every request; returns the waypoints as geometry with fixed numbers per request. */
    private class FakeEngine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            return RouteResult(waypoints, 1000.0 * (waypoints.size - 1), 10.0, 5.0, 60.0,
                wayTypes = listOf(Share("path", 600.0), Share("road", 400.0)), surfaces = listOf(Share("gravel", 1000.0)))
        }
    }

    private val pts = (0..4).map { GeoPoint(46.6 + it * 0.01, 11.1) }

    @Test fun chunkBounds() {
        assertEquals(listOf(0, 4), Planner.chunkBounds(5, emptySet()))
        assertEquals(listOf(0, 2, 4), Planner.chunkBounds(5, setOf(2)))
        assertEquals(listOf(0, 1, 3, 4), Planner.chunkBounds(5, setOf(3, 1, 0, 4, 9))) // ends and invalid ignored
    }

    @Test fun noPinnedIsOneRequest() {
        val e = FakeEngine()
        val r = Planner(e).plan(pts, Activity.HIKE, 3)
        assertEquals(listOf(pts), e.calls)
        assertEquals(4000.0, r.distanceM, 0.0)
    }

    @Test fun pinnedSplitsRequestsAndSums() {
        val e = FakeEngine()
        val r = Planner(e).plan(pts, Activity.HIKE, 3, pinned = setOf(2))
        assertEquals(listOf(pts.subList(0, 3), pts.subList(2, 5)), e.calls)
        assertEquals(4000.0, r.distanceM, 0.0)
        assertEquals(20.0, r.ascentM, 0.0)
        assertEquals(10.0, r.descentM, 0.0)
        assertEquals(120.0, r.durationS, 0.0)
        assertEquals(listOf(Share("path", 1200.0), Share("road", 800.0)), r.wayTypes)
        assertEquals(listOf(Share("gravel", 2000.0)), r.surfaces)
        assertEquals(pts, r.points) // the pinned point appears once, exactly
        assertNull(r.error)
    }
}
