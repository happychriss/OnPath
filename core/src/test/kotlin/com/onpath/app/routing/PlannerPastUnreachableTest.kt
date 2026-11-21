package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.domain.PlanGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A stop the chosen level cannot reach no longer pulls the route on a long detour (owner 2026-11-21; field case Hike T1
 * Vellau → Mutkopf → Leiteralm: 30.3 km, 38 % of the line twice, ② 552 m off; at T2 10.8 km). [Planner.passUnreachable]:
 * the leg past the stop is routed directly and cut at the line point nearest the stop; only for an interior stop that
 * the route does not reach, that something else reaches ([PlanReach]) and whose way via it is ≥ 1.5 × the direct way and
 * ≥ 2 km longer. Real data: tools/routing/jvm-test (the measuring main T1MutkopfJvm).
 */
class PlannerPastUnreachableTest {

    /**
     * A router with stops it cannot reach below a T level ([needs]: stop → lowest T that reaches it; 99 = nothing does):
     * below it the stop is matched [snapM] north of itself, a leg to / from that spot goes round a point [detourM] north of
     * the leg's middle (the long way round the mountain). Straight lines are densified (a vertex every ~100 m).
     * [failDirect] = a request between two reached points fails (after the first request).
     */
    private class MountainEngine(val needs: Map<GeoPoint, Int>, val detourM: Double = 3_000.0, val snapM: Double = 600.0,
                                 val failDirect: Boolean = false) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        private fun north(p: GeoPoint, m: Double) = GeoPoint(p.lat + m / 111_195.0, p.lon)

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            val cut = waypoints.map { w -> needs[w]?.let { sacLimit < it } == true }
            val m = waypoints.mapIndexed { i, w -> if (cut[i]) north(w, snapM) else w }
            if (failDirect && waypoints.size == 2 && cut.none { it } && calls.size > 2) throw RoutingException("No route found.")
            val line = ArrayList<GeoPoint>()
            fun seg(p: GeoPoint, q: GeoPoint) {
                val n = maxOf(1, (Geo.distance(p, q) / 100.0).toInt())
                for (s in 0 until n) line += GeoPoint(p.lat + (q.lat - p.lat) * s / n, p.lon + (q.lon - p.lon) * s / n, 1000.0)
            }
            for (i in 1 until m.size) {
                if (cut[i - 1] || cut[i]) {
                    val mid = GeoPoint((m[i - 1].lat + m[i].lat) / 2, (m[i - 1].lon + m[i].lon) / 2)
                    val x = north(mid, detourM)
                    seg(m[i - 1], x); seg(x, m[i])
                } else seg(m[i - 1], m[i])
            }
            line += m.last()
            val d = Geo.length(line)
            return RouteResult(line, d, 0.0, 0.0, d / 4.0, matched = m)
        }
    }

    private val a = GeoPoint(46.70, 11.10)
    private val s = GeoPoint(46.71, 11.14)
    private val b = GeoPoint(46.70, 11.18)
    private val act = Activity.HIKE

    private fun plan(e: RoutingEngine, pts: List<GeoPoint>, sac: Int, pinned: Set<Int> = emptySet(), cache: LegCache = LegCache()) =
        Planner(e, legs = cache).plan(pts, act, sac, pinned)

    private fun gaps(r: PlannedRoute, pts: List<GeoPoint>) = PlanGaps.of(pts, r.snapped, r.skipped)

    @Test fun anUnreachableStopWithALongDetourIsPassedAndStaysAGap() {
        val e = MountainEngine(mapOf(s to 2))
        val pts = listOf(a, s, b)
        val r = plan(e, pts, 1)
        val direct = Geo.distance(a, b)
        assertEquals(2, r.legs.size)
        assertTrue("passed: ${r.distanceM}", r.distanceM < direct * 1.1)
        // the two legs meet at the cut point, on the direct line, nearest the stop
        assertEquals(r.legs[0].points.last(), r.legs[1].points.first())
        assertEquals(11.14, r.legs[0].points.last().lon, 0.002)
        val g = gaps(r, pts)
        assertEquals(listOf(1), g.map { it.index })
        assertEquals(r.legs[0].points.last(), g[0].routeEnd)
        assertTrue(g[0].distanceM > 1_000)
        assertFalse(r.legs.any { it.straight })
        assertNull(r.error)
    }

    @Test fun theCutIsKeptInTheLegCacheAndTheNextRunRoutesNothing() {
        val e = MountainEngine(mapOf(s to 2)); val cache = LegCache()
        val first = plan(e, listOf(a, s, b), 1, cache = cache)
        val n = e.calls.size
        val second = plan(e, listOf(a, s, b), 1, cache = cache)
        assertEquals(n, e.calls.size)
        assertEquals(first.legs.map { it.points }, second.legs.map { it.points })
        assertEquals(first.distanceM, second.distanceM, 0.001)
    }

    @Test fun aHigherLevelReachesTheStopSoNothingChanges() {
        val e = MountainEngine(mapOf(s to 2))
        val pts = listOf(a, s, b)
        val r = plan(e, pts, 2)
        assertTrue(gaps(r, pts).isEmpty())
        assertEquals(s, r.legs[0].points.last())
        assertEquals(2, e.calls.size) // two legs, no direct route asked
    }

    @Test fun aStopNothingReachesKeepsTodaysLine() {
        val e = MountainEngine(mapOf(s to 99))
        val pts = listOf(a, s, b)
        val r = plan(e, pts, 1)
        assertTrue("kept: ${r.distanceM}", r.distanceM > 2 * Geo.distance(a, b))
        assertEquals(1, gaps(r, pts).size)
    }

    @Test fun aShortSpurKeepsTodaysLine() {
        val e = MountainEngine(mapOf(s to 2), detourM = 0.0)
        val r = plan(e, listOf(a, s, b), 1)
        assertEquals(s.lat + 600.0 / 111_195.0, r.legs[0].points.last().lat, 1e-6)
        assertEquals(2, e.calls.size) // under 2 km more: no direct route asked
    }

    @Test fun theDestinationAndTheStartAreNeverCut() {
        val e = MountainEngine(mapOf(s to 2))
        val toDest = plan(e, listOf(a, s), 1)
        assertEquals(1, toDest.legs.size)
        assertTrue(toDest.distanceM > 2 * Geo.distance(a, s))
        val fromStart = plan(e, listOf(s, a), 1)
        assertTrue(fromStart.distanceM > 2 * Geo.distance(a, s))
        assertEquals(2, e.calls.size)
    }

    @Test fun twoUnreachableStopsInARowAreBothPassed() {
        val s2 = GeoPoint(46.708, 11.16)
        val e = MountainEngine(mapOf(s to 2, s2 to 2))
        val pts = listOf(a, s, s2, b)
        val r = plan(e, pts, 1)
        assertEquals(3, r.legs.size)
        assertTrue("passed: ${r.distanceM} ${r.legs.map { Geo.length(it.points) }} ${e.calls.map { it.size }}", r.distanceM < Geo.distance(a, b) * 1.3)
        for (i in 1 until 3) assertEquals(r.legs[i - 1].points.last(), r.legs[i].points.first())
        assertEquals(listOf(1, 2), gaps(r, pts).map { it.index })
    }

    @Test fun aRoundTripStaysClosedAndItsLastLegIsUntouched() {
        val e = MountainEngine(mapOf(s to 2))
        val pts = listOf(a, s, b, a)
        val r = plan(e, pts, 1)
        assertEquals(3, r.legs.size)
        assertEquals(0.0, Geo.distance(a, r.points.first()), 0.01); assertEquals(0.0, Geo.distance(a, r.points.last()), 0.01)
        assertTrue("passed: ${r.distanceM}", r.distanceM < 2.3 * Geo.distance(a, b))
        assertEquals(listOf(1), gaps(r, pts).map { it.index })
    }

    @Test fun aThereAndBackToTheStopIsNoRoutePastIt() {
        val e = MountainEngine(mapOf(s to 2))
        val r = plan(e, listOf(a, s, a), 1)
        assertTrue(r.distanceM > 2 * Geo.distance(a, s))
        assertEquals(2, e.calls.size)
    }

    @Test fun aPinnedStopIsPassedToo() {
        val e = MountainEngine(mapOf(s to 2))
        val r = plan(e, listOf(a, s, b), 1, pinned = setOf(1))
        assertTrue(r.distanceM < Geo.distance(a, b) * 1.1)
    }

    @Test fun aBikePlanPassesAStopOnlyOnFootReaches() {
        val e = MountainEngine(mapOf(s to 2))
        // the fake router ignores the T level for a bike: the stop is out of reach by bike, a hike at T2 reaches it
        val bike = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) =
                e.route(waypoints, activity, if (activity == Activity.HIKE) sacLimit else 1)
        }
        val r = Planner(bike, legs = LegCache()).plan(listOf(a, s, b), Activity.BIKE_TOURING, 1)
        assertTrue("passed: ${r.distanceM}", r.distanceM < Geo.distance(a, b) * 1.1)
    }

    @Test fun noDirectRouteKeepsTodaysLine() {
        val e = MountainEngine(mapOf(s to 2), failDirect = true)
        val r = plan(e, listOf(a, s, b), 1)
        assertNull(r.error)
        assertTrue(r.distanceM > 2 * Geo.distance(a, b))
        assertEquals(2, r.legs.size)
    }

    @Test fun cancelStopsThePass() {
        val c = RouteCancel()
        val inner = MountainEngine(mapOf(s to 2))
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                if (inner.calls.size == 2) c.cancel()
                return inner.route(waypoints, activity, sacLimit)
            }
        }
        try { Planner(e, c, legs = LegCache()).plan(listOf(a, s, b), act, 1); fail("cancelled") } catch (x: RoutingException) { assertNotNull(x) }
    }

    @Test fun theThresholds() {
        assertTrue(Planner.isPastDetour(30_300.0, 10_800.0))
        assertFalse(Planner.isPastDetour(10_000.0, 7_000.0))   // under 1.5 ×
        assertFalse(Planner.isPastDetour(3_900.0, 2_000.0))    // 1.95 ×, but under 2 km more
        assertTrue(Planner.isPastDetour(4_000.0, 2_000.0))
        assertFalse(Planner.isPastDetour(5.0, 0.0))
    }
}
