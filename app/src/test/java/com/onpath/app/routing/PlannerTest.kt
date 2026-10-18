package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Snaps every point 0.0005° south (≈ 55 m) and reports that as matched; [failOn] requests fail. */
    private class SnapEngine(val failOn: (List<GeoPoint>) -> Boolean = { false }) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            if (failOn(waypoints)) throw RoutingException("No route.")
            val s = waypoints.map { GeoPoint(it.lat - 0.0005, it.lon) }
            return RouteResult(s, 1000.0, 0.0, 0.0, 60.0, matched = s)
        }
    }
    private fun south(p: GeoPoint) = GeoPoint(p.lat - 0.0005, p.lon)

    @Test fun snappedForSingleRequest() {
        val r = Planner(SnapEngine()).plan(pts, Activity.HIKE, 3)
        assertEquals(pts.map(::south), r.snapped)
    }

    @Test fun snappedForChunks() {
        val r = Planner(SnapEngine()).plan(pts, Activity.HIKE, 3, pinned = setOf(2))
        assertEquals(pts.map(::south), r.snapped) // index 2 comes from the first chunk (pinned: the VM keeps it)
        // shared boundary: the first known value wins
        assertEquals(listOf(GeoPoint(1.0, 1.0), GeoPoint(9.0, 9.0), GeoPoint(3.0, 3.0)),
            Planner.mergeSnapped(3, listOf(0, 1, 2), listOf(listOf(GeoPoint(1.0, 1.0), null), listOf(GeoPoint(9.0, 9.0), GeoPoint(3.0, 3.0)))))
    }

    @Test fun snappedForLegByLegFallback() {
        // whole request fails, leg 1→2 fails: points 0..1 and 2..4 still get snapped from their routed legs
        val e = SnapEngine { it.size > 2 || it == listOf(pts[1], pts[2]) }
        val r = Planner(e).plan(pts, Activity.HIKE, 3)
        assertEquals(pts.map(::south), r.snapped)
        val straight = r.legs[1]
        assertTrue(straight.straight)
        assertEquals(listOf(south(pts[1]), south(pts[2])), straight.points) // meets the routed legs
    }

    @Test fun straightLineEngineSnapsNothing() {
        val r = Planner(StraightLineEngine()).plan(pts, Activity.HIKE, 3)
        assertTrue(r.snapped.all { it == null })
    }
}
