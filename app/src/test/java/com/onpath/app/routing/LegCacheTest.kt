package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The leg cache (owner 2026-11-05, "long bike routes faster"): an edit routes only the legs it changed, same points give
 * the same route, the wait card's progress counts only the legs routed, Cancel and the time-limit question keep what
 * was done.
 */
class LegCacheTest {
    /** Records every request; the line is the request's points. [fail] / [slow] requests throw. */
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

    private val p = (0..6).map { GeoPoint(51.0 + it * 0.05, 7.0) }
    private val act = Activity.BIKE_TOURING

    private fun legs(vararg pts: GeoPoint) = pts.toList().zipWithNext { a, b -> listOf(a, b) }

    private fun run(e: Engine, cache: LegCache, vararg pts: GeoPoint, activity: Activity = act, sac: Int = 2) =
        Planner(e, legs = cache).plan(pts.toList(), activity, sac)

    @Test fun samePointsRouteNothingAndGiveTheSameRoute() {
        val e = Engine(); val cache = LegCache()
        val first = run(e, cache, p[0], p[2], p[4])
        e.calls.clear()
        val again = run(e, cache, p[0], p[2], p[4])
        assertTrue(e.calls.isEmpty())
        assertEquals(first.points, again.points)
        assertEquals(first.distanceM, again.distanceM, 0.0)
        assertEquals(first, again)
    }

    @Test fun aPointSetInBetweenRoutesOnlyItsTwoLegs() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[2], p[4])
        e.calls.clear()
        run(e, cache, p[0], p[1], p[2], p[4])
        assertEquals(legs(p[0], p[1], p[2]), e.calls)
    }

    @Test fun aMovedPointRoutesItsTwoLegs() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[2], p[4], p[6])
        e.calls.clear()
        run(e, cache, p[0], p[3], p[4], p[6])
        assertEquals(legs(p[0], p[3], p[4]), e.calls)
    }

    @Test fun aDeletedPointRoutesOneLeg() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[2], p[4], p[6])
        e.calls.clear()
        run(e, cache, p[0], p[4], p[6])
        assertEquals(legs(p[0], p[4]), e.calls)
    }

    @Test fun reverseRoutesEveryLegTheOtherWay() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[2], p[4])
        e.calls.clear()
        run(e, cache, p[4], p[2], p[0])
        assertEquals(legs(p[4], p[2], p[0]), e.calls)
    }

    @Test fun activityAndHikeLevelArePartOfTheKey() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[1])
        e.calls.clear()
        run(e, cache, p[0], p[1], activity = Activity.MTB)
        assertEquals(1, e.calls.size)
        run(e, cache, p[0], p[1], sac = 4) // a bike ignores the T level
        assertEquals(1, e.calls.size)
        run(e, cache, p[0], p[1], activity = Activity.HIKE, sac = 2)
        run(e, cache, p[0], p[1], activity = Activity.HIKE, sac = 3)
        run(e, cache, p[0], p[1], activity = Activity.HIKE, sac = 3)
        assertEquals(3, e.calls.size)
    }

    @Test fun aFailedLegIsNotKeptAndRoutedAgain() {
        var broken = true
        val e = Engine(fail = { broken && it == listOf(p[1], p[2]) }); val cache = LegCache()
        val r = run(e, cache, p[0], p[1], p[2])
        assertTrue(r.legs[1].straight)
        broken = false; e.calls.clear()
        val again = run(e, cache, p[0], p[1], p[2])
        assertEquals(legs(p[1], p[2]), e.calls)
        assertTrue(again.legs.none { it.straight })
    }

    @Test fun clearRoutesEverythingAgain() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[1], p[2])
        cache.clear()
        e.calls.clear()
        run(e, cache, p[0], p[1], p[2])
        assertEquals(2, e.calls.size)
    }

    @Test fun theOldestLegsGoFirst() {
        val cache = LegCache(capacity = 2)
        val e = Engine()
        run(e, cache, p[0], p[1], p[2], p[3]) // 3 legs, room for 2: 0 → 1 dropped
        assertEquals(2, cache.size)
        e.calls.clear()
        run(e, cache, p[0], p[1], p[2], p[3])
        assertEquals(legs(p[0], p[1]), e.calls.take(1))
    }

    @Test fun progressCountsOnlyTheLegsRouted() {
        val e = Engine(); val cache = LegCache()
        run(e, cache, p[0], p[2], p[4])
        val seen = ArrayList<Pair<Int, Int>>()
        Planner(e, legs = cache, progress = { d, t -> seen += d to t }).plan(listOf(p[0], p[1], p[2], p[3], p[4]), act, 2)
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), seen)
        seen.clear()
        Planner(e, legs = cache, progress = { d, t -> seen += d to t }).plan(listOf(p[0], p[1], p[2], p[4], p[5]), act, 2)
        assertEquals("p4 → p5 is new, the rest from the cache", listOf(0 to 1, 1 to 1), seen)
        seen.clear()
        val pl = Planner(e, legs = cache, progress = { d, t -> seen += d to t })
        pl.plan(listOf(p[0], p[1], p[2], p[4], p[5]), act, 2)
        assertTrue("everything from the cache: no progress at all", seen.isEmpty())
        assertEquals(4, pl.cachedLegs)
        assertEquals("4 legs from the cache", pl.describeTries())
    }

    @Test fun aFailedLegCountsAsDoneInTheProgress() {
        val e = Engine(fail = { it == listOf(p[1], p[2]) })
        val seen = ArrayList<Pair<Int, Int>>()
        Planner(e, legs = LegCache(), progress = { d, t -> seen += d to t }).plan(listOf(p[0], p[1], p[2], p[3]), act, 2)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test fun cancelStopsAtOnceAndKeepsTheLegsDone() {
        val cancel = RouteCancel()
        val e = Engine(); val cache = LegCache()
        e.onCall = { if (it == listOf(p[1], p[2])) cancel.cancel() }
        try {
            Planner(e, cancel, legs = cache).plan(listOf(p[0], p[1], p[2], p[3]), act, 2)
            fail("cancelled")
        } catch (x: RoutingException) {
            assertEquals(BRouterEngine.CANCELLED, x.message)
        }
        assertTrue("leg 3 → 4 was not started", e.calls.none { it == listOf(p[2], p[3]) })
        e.calls.clear(); e.onCall = {}
        run(e, cache, p[0], p[1], p[2], p[3])
        assertTrue("the leg done before Cancel is not routed again", e.calls.none { it == listOf(p[0], p[1]) })
    }

    @Test fun atTheTimeLimitInPlanTheLegsDoneStayForTheNextRound() {
        var slow = true
        val e = Engine(slow = { slow && it == listOf(p[1], p[2]) }); val cache = LegCache()
        try {
            Planner(e, RouteCancel(), askAtLimit = true, legs = cache).plan(listOf(p[0], p[1], p[2]), act, 2)
            fail("asks")
        } catch (x: SlowPlan.TimeLimit) {
            assertEquals(1, x.rounds)
        }
        slow = false; e.calls.clear()
        // "Weiter warten": the next round starts after the legs already done
        Planner(e, RouteCancel(), askAtLimit = true, rounds = 2, legs = cache).plan(listOf(p[0], p[1], p[2]), act, 2)
        assertEquals(legs(p[1], p[2]), e.calls)
    }

    @Test fun withoutACacheEveryLegIsRouted() {
        val e = Engine()
        Planner(e).plan(listOf(p[0], p[1], p[2]), act, 2)
        Planner(e).plan(listOf(p[0], p[1], p[2]), act, 2)
        assertEquals(4, e.calls.size)
        assertNull(LegCache().get(p[0], p[1], act, 2))
    }
}
