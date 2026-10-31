package com.onpath.app.routing

import com.onpath.app.time.Factors
import com.onpath.app.time.TimeEstimate
import com.onpath.app.ui.PlanGaps
import com.onpath.app.ui.RouteWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one plan computation of app and web ([PlanResult]): which points snap onto the way and which keep their place
 * (pins, named stops, far points, skipped stops – Bug 01), the model time, gaps, warnings, rating and profile.
 */
class PlanResultTest {
    /** [meters] north of [p] (0.00001° lat ≈ 1.11 m). */
    private fun north(p: GeoPoint, meters: Double) = GeoPoint(p.lat + meters / 111_195.0, p.lon, p.ele)

    /** A west → east line, ~210 m between neighbours, with elevation. */
    private val base = (0..6).map { GeoPoint(51.4870, 7.4100 + it * 0.003, 100.0 + it * 8) }

    /**
     * Matches every waypoint with [matchOf] (here: the [base] point of the same longitude) and draws the line through
     * the matched spots – like BRouter catching a tap on the nearest way.
     */
    private class WayEngine(val matchOf: (GeoPoint) -> GeoPoint) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val m = waypoints.map(matchOf)
            return RouteResult(m, 1000.0, 50.0, 10.0, 999.0, matched = m)
        }
    }

    private fun onBase(p: GeoPoint) = base.minBy { kotlin.math.abs(it.lon - p.lon) }

    // start 20 m beside its way, a free via 20 m off, a pinned via, a named via, a via 300 m off (far), destination on the way
    private val wps = listOf(north(base[0], 20.0), north(base[1], 20.0), north(base[2], 20.0), north(base[3], 20.0),
        north(base[4], 300.0).copy(ele = null), base[5])
    private val pins = setOf(2)
    private val named = setOf(3)
    private val f = Factors()

    private fun plan(named: Set<Int> = this.named, act: Activity = Activity.HIKE) =
        PlanResult.plan(WayEngine(::onBase), wps, act, 2, pins, named, f)

    @Test fun freePointsSnapOntoTheWayPinsNamedAndFarStopsKeepTheirPlace() {
        val r = plan()
        assertEquals(wps, r.routed)
        assertEquals(wps.size, r.points.size)
        assertTrue(r.moved)
        // snapped: start and the free via move onto the way (elevation of the tap kept)
        assertEquals(base[0].lat, r.points[0].lat, 1e-9); assertEquals(wps[0].ele, r.points[0].ele)
        assertEquals(base[1].lat, r.points[1].lat, 1e-9)
        // pinned, named, far: unchanged
        assertSame(wps[2], r.points[2])
        assertSame(wps[3], r.points[3])
        assertSame(wps[4], r.points[4])
        assertEquals(setOf(4), r.far)
        // destination already on the way
        assertEquals(base[5], r.points[5])
    }

    @Test fun theFarStopIsTheGapOnTheSnappedPoints() {
        val r = plan()
        assertEquals(1, r.gaps.size)
        val g = r.gaps.single()
        assertEquals(4, g.index); assertFalse(g.skipped)
        assertEquals(300.0, g.distanceM, 2.0)
        assertEquals(PlanGaps.of(r.points, r.route.snapped, r.route.skipped), r.gaps)
    }

    @Test fun withoutTheNameTheStopSnapsAndWithNamedTakesItBack() {
        val free = plan(named = emptySet())
        assertEquals(base[3].lat, free.points[3].lat, 1e-9)
        val kept = free.withNamed(setOf(3))
        assertSame(wps[3], kept.points[3])
        assertEquals(plan().points, kept.points)
        assertSame(kept, kept.withNamed(setOf(3))) // same names: nothing to work out again
        assertSame(free.route, kept.route)
    }

    @Test fun nothingMovesWhenEveryPointIsOnTheWay() {
        val on = base.subList(0, 4)
        val r = PlanResult.plan(WayEngine(::onBase), on, Activity.HIKE, 2, emptySet(), emptySet(), f)
        assertFalse(r.moved)
        assertSame(on, r.points)
        assertTrue(r.gaps.isEmpty())
    }

    @Test fun modelTimeWarningsRatingAndProfileComeFromTheAppsOwnRules() {
        val r = plan(act = Activity.BIKE_TOURING)
        assertEquals(TimeEstimate.planSeconds(r.route.points, Activity.BIKE_TOURING, f), r.route.durationS, 0.0)
        assertTrue("not the router's time", r.route.durationS != 999.0 * 2)
        assertEquals(RouteWarnings.detect(r.route.points, Activity.BIKE_TOURING, 2), r.spots)
        assertEquals(RouteSummary.rating(Activity.BIKE_TOURING, r.route.distanceM, r.route.ascentM, r.route.points), r.rating)
        val p = r.profile!!
        assertEquals(r.route.points.count { it.ele != null }, p.ele.size)
        assertEquals(RouteSummary.steepness(p.dist, p.ele), r.steepness)
    }

    /**
     * Bug 01: a stop on a way that every request cuts (BRouter's via correction) is still skipped after the planner's
     * passes – it keeps its place and is a gap ("Route misses point …"), never silently moved onto the line.
     */
    @Test fun aSkippedStopKeepsItsPlaceAndIsAGap() {
        val line = (0..6).map { GeoPoint(51.4870, 7.4100 + it * 0.003) }
        val stops = line.mapIndexed { i, p -> if (i in 1..5) north(p, -30.0) else p }
        val cutFirst = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                val l = waypoints.mapIndexed { i, p -> if (i == 1 && waypoints.size > 2) north(p, 30.0) else p }
                return RouteResult(l, 1000.0, 0.0, 0.0, 60.0, matched = waypoints)
            }
        }
        val r = PlanResult.plan(cutFirst, stops, Activity.BIKE_TOURING, 2, emptySet(), emptySet(), f)
        val k = 1 + Planner.MAX_SPUR_PASSES
        assertEquals(setOf(k), r.route.skipped)
        assertTrue(k in r.far)
        assertSame(stops[k], r.points[k])
        val g = r.gaps.single { it.index == k }
        assertTrue(g.skipped)
        assertEquals(30.0, g.distanceM, 1.0)
    }

    @Test fun profileKeepsPointsWithElevationAtTheirDistanceAlongTheWholeLine() {
        val pts = listOf(GeoPoint(46.0, 11.0, 500.0), GeoPoint(46.001, 11.0), GeoPoint(46.002, 11.0, 520.0))
        val p = ProfileData.of(pts)!!
        assertEquals(listOf(0, 2), p.idx.toList())
        assertEquals(com.onpath.app.data.Geo.cumulative(pts).last(), p.total, 0.0)
        assertEquals(p.total, p.dist[1], 0.0)
        assertEquals(500.0, p.min, 0.0); assertEquals(520.0, p.max, 0.0)
        assertNull(ProfileData.of(listOf(GeoPoint(46.0, 11.0, 1.0), GeoPoint(46.001, 11.0))))
        assertNull(ProfileData.of(listOf(GeoPoint(46.0, 11.0, 1.0))))
    }
}
