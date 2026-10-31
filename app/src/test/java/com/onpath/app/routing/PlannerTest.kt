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

    /**
     * A point ON a footway, reachable only there and back: BRouter's via correction cuts that detour and the plan would
     * say "no way" for the rest. Engine: with a via point it draws the line past it (≈ 370 m away) but reports it
     * matched on its way.
     */
    private class SpurCutEngine : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            if (waypoints.size == 2) return RouteResult(waypoints, 1000.0, 0.0, 0.0, 60.0, matched = waypoints)
            val line = listOf(waypoints.first(), GeoPoint(waypoints[1].lat, waypoints[1].lon - 0.0053), waypoints.last())
            return RouteResult(line, 1500.0, 0.0, 0.0, 90.0, matched = waypoints)
        }
    }

    @Test fun pointOnAWayCutAsADetourIsStillVisited() {
        val a = GeoPoint(51.1742, 6.9235); val b = GeoPoint(51.1544, 6.9422); val c = GeoPoint(51.1458, 6.9265)
        val e = SpurCutEngine()
        val r = Planner(e).plan(listOf(a, b, c), Activity.BIKE_TREKKING, 3)
        assertEquals(listOf(listOf(a, b, c), listOf(a, b), listOf(b, c)), e.calls)
        assertTrue("the route goes through point 2", r.points.contains(b))
        assertEquals(b, r.snapped[1]) // no gap: the plan sheet shows no "no way" line
        assertNull(r.error)
    }

    @Test fun pointBesideTheWayIsStillSmoothed() {
        // tapped 100 m beside the road: matched far from the tap → the cut spike stays cut (one request)
        val a = GeoPoint(51.1742, 6.9235); val b = GeoPoint(51.1544, 6.9422); val c = GeoPoint(51.1458, 6.9265)
        val e = object : RoutingEngine {
            var n = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                n++
                val m = GeoPoint(b.lat, b.lon - 0.0015) // ≈ 105 m west, on the line
                return RouteResult(listOf(a, m, c), 1500.0, 0.0, 0.0, 90.0, matched = listOf(a, m, c))
            }
        }
        Planner(e).plan(listOf(a, b, c), Activity.BIKE_TREKKING, 3)
        assertEquals(1, e.n)
    }

    /**
     * Field bug 2026-10-30 (TU Dortmund, Bike): stop 3 on a path south of some steps, BRouter matched it 4.5 m from the
     * tap, but in the whole route its via correction cut the there-and-back and the line passed the matched spot 47 m
     * away – under the old fixed 50 m, so nothing happened and the phone moved the stop 47 m. Engine: an east-west line;
     * with stop 3 inside a request the line goes straight past it (the cut), as a request end it ends at its spot.
     */
    private class StepsCutEngine(val stop: GeoPoint, val spot: GeoPoint, val passBy: GeoPoint) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val matched = waypoints.map { if (it == stop) spot else it }
            val line = matched.mapIndexed { i, p -> if (p == spot && i in 1 until matched.lastIndex) passBy else p }
            return RouteResult(line, 1000.0, 0.0, 0.0, 60.0, matched = matched)
        }
    }
    private val east = (0..4).map { GeoPoint(51.4870, 7.4100 + it * 0.003) }
    /** [meters] north of [p] (0.00001° lat ≈ 1.11 m). */
    private fun north(p: GeoPoint, meters: Double) = GeoPoint(p.lat + meters / 111_195.0, p.lon)

    @Test fun stopOnAPathMatched4mAndCut47mFromItsSpotIsVisited() {
        val spot = north(east[2], -47.0)          // matched spot: 47 m south of the line
        val stop = north(spot, -4.5)              // tapped 4.5 m beside its path
        val wps = listOf(east[0], east[1], stop, east[3], east[4])
        val e = StepsCutEngine(stop, spot, east[2])
        assertTrue("the cut line misses the spot by 47 m", WaypointSnap.distToLine(spot, e.route(wps, Activity.BIKE_TOURING, 2).points) in 46.0..48.0)
        e.calls.clear()
        val r = Planner(e).plan(wps, Activity.BIKE_TOURING, 2)
        assertEquals("routed again with stop 3 as a request end", listOf(wps, wps.subList(0, 3), wps.subList(2, 5)), e.calls)
        assertTrue("the line goes to stop 3's spot", WaypointSnap.distToLine(spot, r.points) < 1.0)
        assertEquals(spot, r.snapped[2])
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun snappingNoiseAndTapsBesideAWayAreNoSpur() {
        // spot 12 m off the line (a short spike trimmed at a junction): under 15 m → smoothed, one request
        run {
            val spot = north(east[2], -12.0); val stop = north(spot, -4.5)
            val e = StepsCutEngine(stop, spot, east[2])
            Planner(e).plan(listOf(east[0], east[1], stop, east[3], east[4]), Activity.BIKE_TOURING, 2)
            assertEquals(1, e.calls.size)
        }
        // tapped 20 m beside a path whose spot the line misses by 47 m: under 3 × 20 m → the user's tap was vague, smoothed
        run {
            val spot = north(east[2], -47.0); val stop = north(spot, -20.0)
            val e = StepsCutEngine(stop, spot, east[2])
            Planner(e).plan(listOf(east[0], east[1], stop, east[3], east[4]), Activity.BIKE_TOURING, 2)
            assertEquals(1, e.calls.size)
        }
    }

    /**
     * Every request with interior points cuts its first one (worst case): each pass pins one more stop. After
     * [Planner.MAX_SPUR_PASSES] re-plans the stop still cut is reported as skipped (the plan sheet shows it), not hidden.
     */
    @Test fun aStopStillCutAfterTheLastPassIsReportedSkipped() {
        val wps = (0..6).map { GeoPoint(51.4870, 7.4100 + it * 0.003) }.mapIndexed { i, p -> if (i in 1..5) north(p, -30.0) else p }
        val e = object : RoutingEngine {
            var n = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                n++
                val line = waypoints.mapIndexed { i, p -> if (i == 1 && waypoints.size > 2) north(p, 30.0) else p }
                return RouteResult(line, 1000.0, 0.0, 0.0, 60.0, matched = waypoints)
            }
        }
        val r = Planner(e).plan(wps, Activity.BIKE_TOURING, 2)
        assertEquals(setOf(1 + Planner.MAX_SPUR_PASSES), r.skipped)
        assertTrue((1..Planner.MAX_SPUR_PASSES).all { WaypointSnap.distToLine(wps[it], r.points) < 1.0 })
    }
}
