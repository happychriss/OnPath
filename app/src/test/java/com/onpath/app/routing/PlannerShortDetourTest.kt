package com.onpath.app.routing

import com.onpath.app.data.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Short-trip detour retry (owner 2026-10-31, field case Hilden: 0.73 km straight, BRouter rode 2.05 km): a leg under
 * 2 km straight routed more than twice as long is routed once more via the midpoint and taken only when ≥ 30 % shorter.
 * Real BRouter data: tools/routing/jvm-test/ShortDetourTest.kt.
 */
class PlannerShortDetourTest {

    /**
     * Routes every consecutive pair of a request straight, except the pairs in [detours] (both directions): those go
     * round a side point, [factor] × their straight distance. A pair with a midpoint of a detour pair (the retry) goes
     * round too when [midFactor] > 1. [fail] = requests that throw. Each request moves the clock [stepMs] on.
     */
    private class DetourEngine(
        val detours: Set<Pair<GeoPoint, GeoPoint>>,
        val factor: Double = 2.8,
        val midFactor: Double = 1.0,
        val stepMs: Long = 0L,
        val fail: (List<GeoPoint>) -> Boolean = { false },
    ) : RoutingEngine {
        var clock = 0L
        val calls = mutableListOf<List<GeoPoint>>()
        val limits = mutableListOf<Long?>()
        private val mids = detours.map { Planner.midpoint(it.first, it.second) }.toSet()

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult = go(waypoints, null)
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult =
            go(waypoints, limits.timeoutMs)

        private fun go(waypoints: List<GeoPoint>, limit: Long?): RouteResult {
            calls += waypoints; limits += limit; clock += stepMs
            if (fail(waypoints)) throw RoutingException("No route found.")
            val line = ArrayList<GeoPoint>()
            for (i in 1 until waypoints.size) {
                val p = waypoints[i - 1]; val q = waypoints[i]
                line += p
                val f = when {
                    (p to q) in detours || (q to p) in detours -> factor
                    p in mids || q in mids -> midFactor
                    else -> 1.0
                }
                if (f > 1.0) line += side(p, q, f)
            }
            line += waypoints.last()
            return RouteResult(line, Geo.length(line), 0.0, 0.0, 60.0, matched = waypoints)
        }

        /** The point beside the middle of p → q that makes p → it → q [f] times as long as p → q. */
        private fun side(p: GeoPoint, q: GeoPoint, f: Double): GeoPoint {
            val d = Geo.distance(p, q)
            val h = sqrt((f * d / 2) * (f * d / 2) - (d / 2) * (d / 2)) // metres off the line
            return GeoPoint((p.lat + q.lat) / 2 + h / 111_195.0, (p.lon + q.lon) / 2)
        }
    }

    private val a = GeoPoint(51.0, 7.0)
    private val b = GeoPoint(51.0, 7.01)          // ≈ 700 m east of a
    private val mid = Planner.midpoint(a, b)
    private val x = GeoPoint(51.0, 6.98)           // ≈ 1.4 km west of a
    private val straight = Geo.distance(a, b)
    private val act = Activity.BIKE_TOURING

    @Test fun aShortTripWithALongDetourIsRetriedViaTheMidpointAndTaken() {
        val e = DetourEngine(setOf(a to b))
        val p = Planner(e, now = { e.clock })
        val r = p.plan(listOf(a, b), act, 2)
        assertEquals(listOf(listOf(a, b), listOf(a, mid, b)), e.calls)
        assertEquals(straight, r.distanceM, 1.0)
        assertNull(r.error)
        assertFalse(r.legs.any { it.straight })
        // the via is internal to the leg: the plan keeps its two points
        assertEquals(listOf(a, b), r.snapped)
        assertTrue(r.skipped.isEmpty())
        assertEquals(2, p.tries.size)
    }

    @Test fun legsTwoKmOrLongerAreNotRetried() {
        val far = GeoPoint(51.0, 7.03) // ≈ 2.1 km
        val e = DetourEngine(setOf(a to far))
        val r = Planner(e).plan(listOf(a, far), act, 2)
        assertEquals(1, e.calls.size)
        assertEquals(2.8 * Geo.distance(a, far), r.distanceM, 1.0)
    }

    @Test fun aDetourUpToTwiceTheStraightDistanceIsNotRetried() {
        val e = DetourEngine(setOf(a to b), factor = 1.95)
        Planner(e).plan(listOf(a, b), act, 2)
        assertEquals(1, e.calls.size)
    }

    @Test fun aFewMetresRoundABlockAreNoDetour() {
        val c = GeoPoint(51.0, 7.0008) // ≈ 56 m: 2.5× is only 84 m more
        val e = DetourEngine(setOf(a to c), factor = 2.5)
        Planner(e).plan(listOf(a, c), act, 2)
        assertEquals(1, e.calls.size)
    }

    @Test fun theRetryIsTakenOnlyWhenAtLeast30PercentShorter() {
        // 2.8 × straight first; the retry 2.1 × = 25 % shorter → kept
        val kept = DetourEngine(setOf(a to b), midFactor = 2.1)
        val r1 = Planner(kept).plan(listOf(a, b), act, 2)
        assertEquals(2, kept.calls.size)
        assertEquals(2.8 * straight, r1.distanceM, 1.0)
        // the retry 1.9 × = 32 % shorter → taken
        val taken = DetourEngine(setOf(a to b), midFactor = 1.9)
        val r2 = Planner(taken).plan(listOf(a, b), act, 2)
        assertEquals(1.9 * straight, r2.distanceM, 2.0)
    }

    @Test fun aFailedRetryKeepsTheFirstRoute() {
        val e = DetourEngine(setOf(a to b)) { mid in it }
        val r = Planner(e).plan(listOf(a, b), act, 2)
        assertEquals(2, e.calls.size)
        assertEquals(2.8 * straight, r.distanceM, 1.0)
        assertNull(r.error)
        assertFalse(r.legs.any { it.straight })
    }

    @Test fun aLegInsideALongerPlanIsRetriedOnItsOwn() {
        // leg by leg (PLANNER_VERSION 4): every leg is its own request – the detour leg is retried, the others not
        val e = DetourEngine(setOf(a to b))
        val r = Planner(e).plan(listOf(x, a, b), act, 2)
        assertEquals(listOf(listOf(x, a), listOf(a, b), listOf(a, mid, b)), e.calls)
        assertEquals(Geo.distance(x, a) + straight, r.distanceM, 1.0)
        assertEquals(listOf(x, a, b), r.snapped) // three plan points, the via is not one of them
        assertTrue(r.skipped.isEmpty())
        assertEquals(2, r.legs.size)
        assertNull(r.error)
    }

    @Test fun theRetriedLegIsCachedAsTaken() {
        val e = DetourEngine(setOf(a to b))
        val cache = LegCache()
        Planner(e, legs = cache).plan(listOf(x, a, b), act, 2)
        e.calls.clear()
        val r = Planner(e, legs = cache).plan(listOf(x, a, b), act, 2)
        assertTrue("nothing routed again: ${e.calls}", e.calls.isEmpty())
        assertEquals(Geo.distance(x, a) + straight, r.distanceM, 1.0)
    }

    @Test fun theRetryCountsAgainstTheRequestsTimeLimit() {
        val limit = RouteTimeLimit.forRoute(listOf(a, b), act)
        // the first request used up the limit: no retry
        val slow = DetourEngine(setOf(a to b), stepMs = limit)
        val r = Planner(slow, now = { slow.clock }).plan(listOf(a, b), act, 2)
        assertEquals(1, slow.calls.size)
        assertEquals(2.8 * straight, r.distanceM, 1.0)
        // the first request took 5 s: the retry gets what is left
        val e = DetourEngine(setOf(a to b), stepMs = 5_000L)
        Planner(e, now = { e.clock }).plan(listOf(a, b), act, 2)
        assertEquals(2, e.calls.size)
        assertEquals(limit - 5_000L, e.limits[1])
    }

    @Test fun isShortDetour() {
        assertTrue(Planner.isShortDetour(730.0, 2054.0))
        assertFalse(Planner.isShortDetour(730.0, 1400.0))   // < 2×
        assertFalse(Planner.isShortDetour(2100.0, 6000.0))  // not a short trip
        assertFalse(Planner.isShortDetour(40.0, 120.0))     // only 80 m more
        assertFalse(Planner.isShortDetour(0.0, 500.0))      // the same point twice
    }
}
