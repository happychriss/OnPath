package com.onpath.app.routing

import com.onpath.app.debug.Problems
import com.onpath.app.time.Factors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Planner on an [ExpandedPlan] (docs/design/detour-stitch.md §8): visible points V0 V1 V2, the middle point with a
 * hidden anchor on each side (V0, A, V1, B, V2). Legs are requests of the expanded list; everything the user sees
 * (numbers, texts, progress, snapped, skipped) counts the visible points and legs.
 */
class PlannerAnchorsTest {
    private val act = Activity.BIKE_TOURING
    private val v0 = GeoPoint(51.0, 7.0)
    private val a = GeoPoint(51.2, 7.0)
    private val v1 = GeoPoint(51.4, 7.0)
    private val b = GeoPoint(51.6, 7.0)
    private val v2 = GeoPoint(51.8, 7.0)
    private val visible = listOf(v0, v1, v2)
    private val anchors = mapOf(1 to Anchors(listOf(a), listOf(b)))

    private fun expanded(pins: Set<Int> = emptySet()) = PlanAnchors.expand(visible, anchors, pins)

    /** Records every request; [fail] throws "no route", [slow] a timeout. The line is the request's points. */
    private class Engine(val fail: (List<GeoPoint>) -> Boolean = { false }, val slow: (List<GeoPoint>) -> Boolean = { false }) : RoutingEngine {
        val calls = mutableListOf<List<GeoPoint>>()
        var onCall: (List<GeoPoint>) -> Unit = {}
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            calls += waypoints
            onCall(waypoints)
            if (slow(waypoints)) throw RoutingException("took too long", timedOut = true)
            if (fail(waypoints)) throw RoutingException("No route found.")
            return RouteResult(waypoints, 1000.0, 5.0, 5.0, 60.0, matched = waypoints)
        }
    }

    private fun pair(x: GeoPoint, y: GeoPoint) = listOf(x, y)

    @Test fun anAnchoredPlanRequestsItsExpandedLegsInOrder() {
        val e = Engine()
        val r = Planner(e).plan(expanded(), act, 2)
        assertEquals(listOf(pair(v0, a), pair(a, v1), pair(v1, b), pair(b, v2)), e.calls)
        assertEquals(4, r.legs.size)
        assertNull(r.error)
        assertEquals(4000.0, r.distanceM, 0.0)
    }

    @Test fun aPlanWithoutAnchorsIsTheOldPlan() {
        val e1 = Engine(); val e2 = Engine()
        val old = Planner(e1).plan(visible, act, 2, setOf(1))
        val viaExpanded = Planner(e2).plan(PlanAnchors.expand(visible, emptyMap(), setOf(1)), act, 2)
        assertEquals(old, viaExpanded)
        assertEquals(e1.calls, e2.calls)
    }

    @Test fun snappedAndSkippedAreInVisiblePoints() {
        val e = Engine()
        val r = Planner(e).plan(expanded(), act, 2)
        assertEquals(3, r.snapped.size)
        assertEquals(v0, r.snapped[0]!!.copy(way = null))
        assertEquals(v1.lat, r.snapped[1]!!.lat, 1e-9)
        assertEquals(v2.lat, r.snapped[2]!!.lat, 1e-9)
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun aSkippedPointIsReportedByItsVisibleIndex() {
        // the router matched V1 25 m east of it, but the line ends 100 m west of it: the line misses the matched spot
        val matchedV1 = GeoPoint(v1.lat, v1.lon + 0.00036)
        val endV1 = GeoPoint(v1.lat, v1.lon - 0.0014)
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
                val line = waypoints.map { if (it == v1) endV1 else it }
                return RouteResult(line, 1000.0, 0.0, 0.0, 60.0, matched = waypoints.map { if (it == v1) matchedV1 else it })
            }
        }
        val r = Planner(e).plan(expanded(), act, 2)
        assertEquals(setOf(1), r.skipped)
        assertEquals(3, r.snapped.size)
    }

    @Test fun theStraightLineEngineDrawsStraightLegsBetweenVisiblePointsOnly() {
        val r = Planner(StraightLineEngine()).plan(expanded(), act, 2)
        assertEquals(listOf(pair(v0, v1), pair(v1, v2)), r.legs.map { it.points })
        assertTrue(r.legs.all { it.straight })
    }

    @Test fun aPinnedPointStaysPinnedInTheExpandedPlan() {
        val x = PlanAnchors.expand(visible, anchors, setOf(1))
        assertEquals(setOf(2), x.pins)        // V1 sits at expanded index 2
        assertEquals(setOf(1), x.visiblePins)
        assertEquals(setOf(1), x.withoutAnchorsOfLeg(0).visiblePins)
        assertEquals(setOf(1), x.withoutAnchorsOfLeg(0).pins) // V0, V1, B, V2
    }

    // ---- the leg cache: cut legs ----

    @Test fun aPlainPlanNeverReadsACutLegButAnAnchoredOneDoes() {
        val cache = LegCache()
        val cutLeg = RouteResult(listOf(a, v1), 7.0, 0.0, 0.0, 1.0, matched = listOf(a, v1))
        cache.put(a, v1, act, 2, cutLeg, cut = true)
        // a plain plan with the same two ends routes it
        val e1 = Engine()
        val plain = Planner(e1, legs = cache).plan(listOf(a, v1), act, 2)
        assertEquals(listOf(pair(a, v1)), e1.calls)
        assertEquals(1000.0, plain.distanceM, 0.0)
        // an anchored plan takes the cut leg
        val cache2 = LegCache()
        cache2.put(a, v1, act, 2, cutLeg, cut = true)
        val e2 = Engine()
        val r = Planner(e2, legs = cache2).plan(expanded(), act, 2)
        assertEquals(listOf(pair(v0, a), pair(v1, b), pair(b, v2)), e2.calls)
        assertEquals(3007.0, r.distanceM, 0.0)
    }

    @Test fun aLegBetweenTwoVisiblePointsDoesNotReadACutLeg() {
        val cache = LegCache()
        cache.put(v0, v1, act, 2, RouteResult(listOf(v0, v1), 7.0, 0.0, 0.0, 1.0), cut = true)
        val e = Engine()
        Planner(e, legs = cache).plan(visible, act, 2)
        assertEquals(listOf(pair(v0, v1), pair(v1, v2)), e.calls)
    }

    @Test fun routedLegsAreStoredUnderTheirPlainKey() {
        val cache = LegCache()
        Planner(Engine(), legs = cache).plan(expanded(), act, 2)
        assertNotNull(cache.get(v0, a, act, 2))
        assertNotNull(cache.get(a, v1, act, 2))
        assertNull("no cut leg was made", cache.lookupAnchored(a, v1, act, 2)?.takeIf { it.leg.distanceM == 7.0 })
        assertEquals(4, cache.size)
        // a second run routes nothing
        val e = Engine()
        Planner(e, legs = cache).plan(expanded(), act, 2)
        assertTrue(e.calls.isEmpty())
    }

    // ---- visible numbers ----

    @Test fun errorTextsNameTheVisibleLeg() {
        // V1 → B → V2 has no route, and neither has the full leg V1 → V2 after the fallback
        val e = Engine(fail = { it.last() == v2 })
        val r = Planner(e).plan(expanded(), act, 2)
        assertEquals(Planner.legError(2, 3, RoutingException("No route found.")), r.error)
        assertTrue(r.legs.last().straight)
    }

    @Test fun theNoRouteTextOfAnEmptyLegNamesVisibleNumbers() {
        val e = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) =
                RouteResult(if (waypoints.last() == v2) emptyList() else waypoints, 1.0, 0.0, 0.0, 1.0)
        }
        val r = Planner(e).plan(expanded(), act, 2)
        assertTrue(r.error!!, r.error!!.contains("2") && r.error!!.contains("3"))
        assertFalse(r.error!!, r.error!!.contains("5"))
    }

    @Test fun progressCountsVisibleLegs() {
        val steps = mutableListOf<Pair<Int, Int>>()
        Planner(Engine(), progress = { d, t -> steps += d to t }).plan(expanded(), act, 2)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps)
    }

    @Test fun progressDoesNotCountVisibleLegsThatAreCached() {
        val cache = LegCache()
        cache.put(v0, a, act, 2, RouteResult(listOf(v0, a), 1.0, 0.0, 0.0, 1.0))
        cache.put(a, v1, act, 2, RouteResult(listOf(a, v1), 1.0, 0.0, 0.0, 1.0))
        val steps = mutableListOf<Pair<Int, Int>>()
        Planner(Engine(), legs = cache, progress = { d, t -> steps += d to t }).plan(expanded(), act, 2)
        assertEquals(listOf(0 to 1, 1 to 1), steps)
        // half of a visible leg cached: it still counts, and is done when its last sub-leg is
        val half = LegCache()
        half.put(v0, a, act, 2, RouteResult(listOf(v0, a), 1.0, 0.0, 0.0, 1.0))
        val steps2 = mutableListOf<Pair<Int, Int>>()
        Planner(Engine(), legs = half, progress = { d, t -> steps2 += d to t }).plan(expanded(), act, 2)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), steps2)
    }

    // ---- the fallback ----

    @Test fun aSubLegWithoutARouteIsRoutedInFullWithoutItsAnchors() {
        val e = Engine(fail = { it == pair(a, v1) })
        val steps = mutableListOf<Pair<Int, Int>>()
        val cache = LegCache()
        val r = Planner(e, legs = cache, progress = { d, t -> steps += d to t }).plan(expanded(), act, 2)
        assertEquals(listOf(pair(v0, a), pair(a, v1), pair(v0, v1), pair(v1, b), pair(b, v2)), e.calls)
        assertNull(r.error)
        assertTrue(r.legs.none { it.straight })
        assertEquals(3, r.legs.size) // V0 → V1, V1 → B, B → V2: the anchors before V1 are gone
        assertEquals(3, r.snapped.size)
        assertNotNull("the full leg is stored plain", cache.get(v0, v1, act, 2))
        assertEquals(2 to 2, steps.last())
        assertTrue(Problems.snapshot().any { it.area == "routing" && it.message.contains("detour anchors") })
    }

    @Test fun theFallbackWorksWithoutACache() {
        val e = Engine(fail = { it == pair(v1, b) })
        val r = Planner(e).plan(expanded(), act, 2)
        // V1 → B fails: leg 2 loses B (its 'after' anchor) and is routed V1 → V2; leg 1 (V0, A, V1) comes from this run
        assertEquals(listOf(pair(v0, a), pair(a, v1), pair(v1, b), pair(v1, v2)), e.calls)
        assertNull(r.error)
        assertEquals(3, r.legs.size)
    }

    @Test fun whenTheFullLegFailsToItIsAStraightLegWithTheNormalError() {
        val e = Engine(fail = { it == pair(a, v1) || it == pair(v0, v1) })
        val r = Planner(e).plan(expanded(), act, 2)
        // one fallback only: A → V1, then V0 → V1; no third try for that leg
        assertEquals(listOf(pair(v0, a), pair(a, v1), pair(v0, v1), pair(v1, b), pair(b, v2)), e.calls)
        assertEquals(Planner.legError(1, 2, RoutingException("No route found.")), r.error)
        assertTrue(r.legs[0].straight)
        assertFalse(r.legs[1].straight)
    }

    @Test fun aTimeoutKeepsTodaysBehaviourNoFallback() {
        val e = Engine(slow = { it == pair(a, v1) })
        val r = Planner(e).plan(expanded(), act, 2)
        assertEquals(listOf(pair(v0, a), pair(a, v1)), e.calls) // the rest straight at once
        assertEquals(Planner.TOOK_TOO_LONG, r.error)
        assertTrue(r.legs.drop(1).all { it.straight })
    }

    @Test fun aCancelKeepsTodaysBehaviourNoFallback() {
        val cancel = RouteCancel()
        val e = Engine(fail = { it == pair(a, v1) })
        e.onCall = { if (it == pair(a, v1)) cancel.cancel() }
        try {
            Planner(e, cancel).plan(expanded(), act, 2)
            fail("cancelled")
        } catch (x: RoutingException) {
            assertEquals(RoutingException.Kind.CANCELLED, x.kind)
        }
        assertEquals(listOf(pair(v0, a), pair(a, v1)), e.calls)
    }

    // ---- PlanResult ----

    @Test fun planResultIsInVisiblePoints() {
        val r = PlanResult.plan(Engine(), expanded(setOf(1)), act, 2, emptySet(), Factors())
        assertEquals(visible, r.routed)
        assertEquals(3, r.points.size)
        assertEquals(setOf(1), r.pins)
        assertTrue(r.far.isEmpty())
        assertTrue(r.gaps.isEmpty())
        assertEquals(3, r.route.snapped.size)
        assertEquals(4, r.route.legs.size) // drawn and summed: the expanded legs
    }
}
