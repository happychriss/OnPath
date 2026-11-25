package com.onpath.app.ui.plan

import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.routing.Activity
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.ExpandedPlan
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlanAnchors
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import com.onpath.app.time.Factors
import com.onpath.app.ui.plan.PlanRecalcState.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The idle settling's body without the app's threads ([PlanSettle]): the leg cap, the epoch, no anchors, the quiet run's cache miss. */
class PlanSettleTest {
    private val act = Activity.BIKE_TOURING
    private val sac = 2
    private val v0 = GeoPoint(51.0, 7.0)
    private val a = GeoPoint(51.2, 7.0)
    private val v1 = GeoPoint(51.4, 7.0)
    private val b = GeoPoint(51.6, 7.0)
    private val v2 = GeoPoint(51.8, 7.0)
    private val visible = listOf(v0, v1, v2)
    private val anchors = mapOf(1 to Anchors(listOf(a), listOf(b)))
    private val stitched: ExpandedPlan get() = PlanAnchors.expand(visible, anchors, emptySet())

    @get:Rule val problems = ProblemsRule()

    private class Good : RoutingEngine {
        var calls = 0
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls++
            val f = waypoints.first(); val t = waypoints.last()
            val mid = GeoPoint((f.lat + t.lat) / 2, (f.lon + t.lon) / 2 + 0.01)
            return RouteResult(listOf(f, mid, t), 1000.0, 5.0, 5.0, 60.0, matched = listOf(f, t))
        }
    }

    private class Failing : RoutingEngine {
        var calls = 0
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult { calls++; throw RoutingException("no route here") }
    }

    private fun cutLeg(x: GeoPoint, y: GeoPoint) = RouteResult(listOf(x, y), 900.0, 1.0, 1.0, 50.0, matched = listOf(x, y))

    /** The cache as a stitch leaves it: a cut leg under every cut key of the plan. */
    private fun cutCache(): LegCache {
        val plan = stitched
        val c = LegCache()
        for (i in 0 until plan.points.size - 1) c.put(plan.points[i], plan.points[i + 1], act, sac, cutLeg(plan.points[i], plan.points[i + 1]), cut = true)
        return c
    }

    private fun run(anchors: Map<Int, Anchors> = this.anchors) = Run(1, visible, emptySet(), act, sac, 1, false, Factors(), 0L, null, null, anchors)

    private fun taken(cache: LegCache, r: Run = run()) = PlanRecalcState.route(r, Good(), cache, RouteCancel(), { _, _ -> }).result

    @Test fun noAnchorsGivesNullAndClearsTheFailures() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run(); val res = taken(cache, r)
        val work = s.prepare(r, res, visible, cache)!!
        s.settle(work, Failing(), cache, RouteCancel()) { false }
        assertTrue("a failed leg is counted", s.failureCount() > 0)
        assertNull(s.prepare(run(emptyMap()), res, visible, cache))
        assertEquals(0, s.failureCount())
    }

    /** Settles with [engine] again and again until a round asks nothing (every leg capped); the number of rounds that asked. */
    private fun untilQuiet(s: PlanSettle, r: Run, res: com.onpath.app.routing.PlanResult, cache: LegCache, engine: Failing): Int {
        var rounds = 0
        while (rounds < 8) {
            val before = engine.calls
            s.settle(s.prepare(r, res, visible, cache)!!, engine, cache, RouteCancel()) { false }
            if (engine.calls == before) return rounds
            rounds++
        }
        return rounds
    }

    @Test fun aLegIsSkippedOnceItFailedTwice() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run(); val res = taken(cache, r)
        val failing = Failing()
        val rounds = untilQuiet(s, r, res, cache, failing)
        assertTrue("asked, then capped (rounds $rounds)", rounds in 2..7)
        val warns = problems.said("routing", com.onpath.app.debug.Problems.Level.WARN).filter { it.startsWith("A leg of the plan could not be settled twice") }
        assertTrue("one warning per capped leg", warns.isNotEmpty())
        val calls = failing.calls
        assertNull(s.settle(s.prepare(r, res, visible, cache)!!, failing, cache, RouteCancel()) { false })
        assertEquals("a capped leg is not asked again", calls, failing.calls)
    }

    @Test fun aNewRoutingDataEpochResetsTheCap() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run(); val res = taken(cache, r)
        val failing = Failing()
        untilQuiet(s, r, res, cache, failing)
        val capped = failing.calls
        assertTrue(s.failureCount() > 0)
        cache.newData() // new routing data: the epoch moves, the cached legs stay
        val work = s.prepare(r, res, visible, cache)
        assertNotNull(work)
        assertEquals("the failures were cleared by the epoch change", 0, s.failureCount())
        s.settle(work!!, failing, cache, RouteCancel()) { false }
        assertTrue("the legs are asked again", failing.calls > capped)
    }

    @Test fun aTourRunningGivesNoWork() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run()
        assertNull(s.prepare(r, taken(cache, r), visible, cache) { true })
        assertTrue(problems.traced("routing").any { it.startsWith("Settling skipped") })
    }

    @Test fun aLegMissingFromTheCacheInTheQuietRunGivesNull() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run(); val res = taken(cache, r)
        val work = s.prepare(r, res, visible, cache)!!
        // the settle routes (Good) and writes its legs; then the cache is wiped by the engine's last call so the quiet run misses
        val wiping = object : RoutingEngine {
            val inner = Good(); var n = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                val out = inner.route(waypoints, activity, sacLimit)
                if (++n >= 1) cache.clear()
                return out
            }
        }
        val out = s.settle(work, wiping, cache, RouteCancel()) { false }
        assertNull(out)
        assertTrue(problems.traced("routing").any { it.startsWith("Settling: a leg is missing from the cache") })
    }

    @Test fun aSettleThatRoutedShowsTheQuietLine() {
        val cache = cutCache(); val s = PlanSettle()
        val r = run(); val res = taken(cache, r)
        val out = s.settle(s.prepare(r, res, visible, cache)!!, Good(), cache, RouteCancel()) { false }
        assertNotNull(out)
        assertTrue(problems.traced("routing").any { it.startsWith("Settling: the plan is shown again") })
    }
}
