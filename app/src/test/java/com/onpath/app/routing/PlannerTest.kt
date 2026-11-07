package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Planner routes leg by leg (PLANNER_VERSION 4, owner 2026-11-05): one request of two points per leg. */
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

    @Test fun everyLegIsOneRequestOfTwoPoints() {
        val e = FakeEngine()
        val r = Planner(e).plan(pts, Activity.HIKE, 3)
        assertEquals(pts.zipWithNext { a, b -> listOf(a, b) }, e.calls)
        assertEquals(4, r.legs.size)
        assertEquals(4000.0, r.distanceM, 0.0)
    }

    @Test fun pinnedPointsChangeNoRequestAndTheNumbersAddUp() {
        val e = FakeEngine()
        val r = Planner(e).plan(pts, Activity.HIKE, 3, pinned = setOf(2))
        assertEquals(pts.zipWithNext { a, b -> listOf(a, b) }, e.calls)
        assertEquals(4000.0, r.distanceM, 0.0)
        assertEquals(40.0, r.ascentM, 0.0)
        assertEquals(20.0, r.descentM, 0.0)
        assertEquals(240.0, r.durationS, 0.0)
        assertEquals(listOf(Share("path", 2400.0), Share("road", 1600.0)), r.wayTypes)
        assertEquals(listOf(Share("gravel", 4000.0)), r.surfaces)
        assertEquals(pts, r.points) // every point appears once, exactly
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

    @Test fun snappedToWhereTheRouterMatched() {
        val r = Planner(SnapEngine()).plan(pts, Activity.HIKE, 3)
        assertEquals(pts.map(::south), r.snapped)
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun aFailedLegIsAStraightLineMeetingItsNeighbours() {
        // leg 2 → 3 fails: points 0..1 and 2..4 still get snapped from their routed legs
        val e = SnapEngine { it == listOf(pts[1], pts[2]) }
        val r = Planner(e).plan(pts, Activity.HIKE, 3)
        assertEquals(pts.map(::south), r.snapped)
        val straight = r.legs[1]
        assertTrue(straight.straight)
        assertEquals(listOf(south(pts[1]), south(pts[2])), straight.points) // meets the routed legs
        assertTrue(r.legs.filterIndexed { i, _ -> i != 1 }.none { it.straight })
        assertEquals(Planner.legError(2, 3, RoutingException("No route.")), r.error)
    }

    @Test fun straightLineEngineSnapsNothing() {
        val r = Planner(StraightLineEngine()).plan(pts, Activity.HIKE, 3)
        assertTrue(r.snapped.all { it == null })
    }

    /**
     * A point ON a footway, reachable only there and back: a whole BRouter request cut that detour (its via correction).
     * Leg by leg every point is a request end, so the line goes to it.
     */
    @Test fun pointOnAWayIsAlwaysVisited() {
        val a = GeoPoint(51.1742, 6.9235); val b = GeoPoint(51.1544, 6.9422); val c = GeoPoint(51.1458, 6.9265)
        val e = object : RoutingEngine {
            val calls = mutableListOf<List<GeoPoint>>()
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                calls += waypoints
                return RouteResult(waypoints, 1000.0, 0.0, 0.0, 60.0, matched = waypoints)
            }
        }
        val r = Planner(e).plan(listOf(a, b, c), Activity.BIKE_TREKKING, 3)
        assertEquals(listOf(listOf(a, b), listOf(b, c)), e.calls)
        assertTrue("the route goes through point 2", r.points.contains(b))
        assertEquals(b, r.snapped[1]) // no gap: the plan sheet shows no "no way" line
        assertNull(r.error)
    }

    /**
     * Field bug 2026-10-30 (TU Dortmund, Bike): stop 3 on a path south of some steps, BRouter matched it 4.5 m from the
     * tap; inside a whole request its via correction cut the there-and-back and the line passed 47 m from the spot. Leg by
     * leg the stop is a request end: its legs end at its spot, the line passes it (core feature C3).
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
        assertTrue("a whole request misses the spot by 47 m", WaypointSnap.distToLine(spot, e.route(wps, Activity.BIKE_TOURING, 2).points) in 46.0..48.0)
        e.calls.clear()
        val r = Planner(e).plan(wps, Activity.BIKE_TOURING, 2)
        assertEquals("one request per leg, stop 3 a request end", wps.zipWithNext { a, b -> listOf(a, b) }, e.calls)
        assertTrue("the line goes to stop 3's spot", WaypointSnap.distToLine(spot, r.points) < 1.0)
        assertEquals(spot, r.snapped[2])
        assertTrue(r.skipped.isEmpty())
    }

    /** A stop the router cannot reach even as a request end (its legs end 30 m short of its spot) stays skipped – shown. */
    @Test fun aStopStillCutAfterTheLastPassIsReportedSkipped() {
        val wps = (0..6).map { GeoPoint(51.4870, 7.4100 + it * 0.003) }.mapIndexed { i, p -> if (i in 1..5) north(p, -30.0) else p }
        val e = object : RoutingEngine {
            var n = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                n++
                val line = waypoints.map { if (it == wps[3]) north(it, 30.0) else it }
                return RouteResult(line, 1000.0, 0.0, 0.0, 60.0, matched = waypoints)
            }
        }
        val r = Planner(e).plan(wps, Activity.BIKE_TOURING, 2)
        assertEquals(setOf(3), r.skipped)
        assertEquals(6, e.n) // one request per leg – nothing routed twice
        assertTrue((0..6).filter { it != 3 }.all { WaypointSnap.distToLine(wps[it], r.points) < 1.0 })
    }

    @Test fun climbMirrorsBRoutersTenMetreFilter() {
        fun line(vararg e: Double) = e.mapIndexed { i, v -> GeoPoint(51.48, 7.4 + i * 0.001, v) }
        assertEquals(0.0 to 0.0, Planner.climb(line(100.0, 108.0, 100.0, 109.0)))
        assertEquals(10.0 to 0.0, Planner.climb(line(100.0, 120.0)))
        assertEquals(10.0 to 10.0, Planner.climb(line(100.0, 120.0, 100.0)))
    }
}
