package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.poi.inGerman
import com.onpath.app.ui.PlanGap
import com.onpath.app.ui.PlanGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plan point the route does not reach (owner 2026-11-07, Pixel "Meran → Mutkopf" at Hike T1: the line stopped 552 m
 * short of ② and the sheet only said "kein Weg für Wandern"): the app finds the easiest level / activity that does
 * reach it – routing only the missing piece – and the red line says so: "② erst ab T2 erreichbar".
 */
class PlanReachTest {
    /** Where the T1 route stopped (on a way) and ② ~550 m further up, as in the owner's case. */
    private val end = GeoPoint(46.70580, 11.13740)
    private val two = GeoPoint(46.71077, 11.13787)
    private val gap = PlanGap(1, two, end, Geo.distance(two, end))

    /** Reaches the target when [reaches] says so for (activity, T level); else stops at the start of the request. */
    private class Engine(val reaches: (Activity, Int) -> Boolean, val fail: (Activity, Int) -> Boolean = { _, _ -> false }) : RoutingEngine {
        val calls = mutableListOf<Triple<List<GeoPoint>, Activity, Int>>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            calls += Triple(waypoints, activity, sacLimit)
            if (fail(activity, sacLimit)) throw RoutingException("No route found near this point")
            val a = waypoints.first(); val b = waypoints.last()
            val endAt = if (reaches(activity, sacLimit)) b else a
            val line = listOf(a, GeoPoint((a.lat + endAt.lat) / 2, (a.lon + endAt.lon) / 2), endAt)
            return RouteResult(line, Geo.distance(a, endAt), 0.0, 0.0, 60.0, matched = listOf(a, endAt))
        }
    }

    @Test fun ownerCaseHikeT1IsReachedAtT2() {
        val e = Engine({ a, t -> a == Activity.HIKE && t >= 2 })
        val r = PlanReach.find(e, gap, Activity.HIKE, 1)!!
        assertEquals(PlanReach.Fix.Level(2), r.fix)
        // only the missing piece is routed, once
        assertEquals(listOf(Triple(listOf(end, two), Activity.HIKE, 2)), e.calls)
        assertEquals(two, r.way.last())
        assertEquals("② erst ab T2 erreichbar", inGerman { PlanGaps.reachText(listOf(gap), r) })
        assertEquals("② only reachable from T2", PlanGaps.reachText(listOf(gap), r))
    }

    @Test fun triesTheLevelsUpToT5InOrder() {
        val e = Engine({ _, t -> t >= 4 }, fail = { _, t -> t == 3 })
        assertEquals(PlanReach.Fix.Level(4), PlanReach.find(e, gap, Activity.HIKE, 2)!!.fix)
        assertEquals(listOf(3, 4), e.calls.map { it.third })
    }

    @Test fun nothingUpToT5() {
        val e = Engine({ _, _ -> false })
        val r = PlanReach.find(e, gap, Activity.HIKE, 1)!!
        assertEquals(PlanReach.Fix.None, r.fix)
        assertEquals(listOf(2, 3, 4, 5), e.calls.map { it.third })
        assertEquals("② nicht erreichbar", inGerman { PlanGaps.reachText(listOf(gap), r) })
        assertEquals("② not reachable", PlanGaps.reachText(listOf(gap), r))
        // the missing piece itself is what "Show" draws when nothing reaches the point
        assertEquals(listOf(end, two), r.way)
    }

    @Test fun atT5ThereIsNothingToTry() {
        val e = Engine({ _, _ -> true })
        assertEquals(PlanReach.Fix.None, PlanReach.find(e, gap, Activity.HIKE, 5)!!.fix)
        assertTrue(e.calls.isEmpty())
    }

    @Test fun bikeTheRougherBikeThatReachesIt() {
        val e = Engine({ a, _ -> a == Activity.BIKE_TREKKING })
        val r = PlanReach.find(e, gap, Activity.BIKE_TOURING, 2)!!
        assertEquals(PlanReach.Fix.Bike(Activity.BIKE_TREKKING), r.fix)
        assertEquals("② nur mit Gravelbike erreichbar", inGerman { PlanGaps.reachText(listOf(gap), r) })
        assertEquals("② only reachable by Gravelbike", PlanGaps.reachText(listOf(gap), r))
    }

    @Test fun bikeOrderGravelThenMtb() {
        val e = Engine({ a, _ -> a == Activity.MTB })
        assertEquals(PlanReach.Fix.Bike(Activity.MTB), PlanReach.find(e, gap, Activity.BIKE_TOURING, 2)!!.fix)
        assertEquals(listOf(Activity.BIKE_TREKKING, Activity.MTB), e.calls.map { it.second })
        val ee = Engine({ a, _ -> a == Activity.EMTB })
        assertEquals(PlanReach.Fix.Bike(Activity.EMTB), PlanReach.find(ee, gap, Activity.EBIKE, 2)!!.fix)
    }

    @Test fun bikeOnlyOnFoot() {
        val e = Engine({ a, t -> a == Activity.HIKE && t >= 2 })
        val r = PlanReach.find(e, gap, Activity.MTB, 2)!!
        assertEquals(PlanReach.Fix.OnFoot(2), r.fix)
        assertEquals(listOf(Activity.HIKE to 1, Activity.HIKE to 2), e.calls.map { it.second to it.third })
        assertEquals("② mit dem Rad nicht erreichbar – zu Fuß T2", inGerman { PlanGaps.reachText(listOf(gap), r) })
        assertEquals("② not reachable by bike – on foot T2", PlanGaps.reachText(listOf(gap), r))
    }

    @Test fun bikeNothingAtAll() {
        val r = PlanReach.find(Engine({ _, _ -> false }), gap, Activity.BIKE_TREKKING, 2)!!
        assertEquals(PlanReach.Fix.None, r.fix)
        assertEquals("② nicht erreichbar", inGerman { PlanGaps.reachText(listOf(gap), r) })
    }

    @Test fun cancelledMeansUnknown() {
        val e = Engine({ _, _ -> true })
        assertNull(PlanReach.find(e, gap, Activity.HIKE, 1) { true })
        assertTrue(e.calls.isEmpty())
    }

    @Test fun aSkippedStopIsNotSearched() {
        val e = Engine({ _, _ -> true })
        assertNull(PlanReach.find(e, gap.copy(skipped = true), Activity.HIKE, 1))
        assertTrue(e.calls.isEmpty())
    }

    @Test fun textForTheFirstGapOnlyWithMore() {
        val r = PlanReach.find(Engine({ _, t -> t >= 2 }), gap, Activity.HIKE, 1)!!
        val other = PlanGap(3, two, end, 300.0)
        assertEquals("② erst ab T2 erreichbar (+1 weitere)", inGerman { PlanGaps.reachText(listOf(gap, other), r) })
        // a hint for another point (an older result) is not used
        assertNull(PlanGaps.reachText(listOf(other), r))
    }

    @Test fun pointNumbersAsCircledDigits() {
        assertEquals("①", PlanGaps.pointSign(0))
        assertEquals("⑳", PlanGaps.pointSign(19))
        assertEquals("Punkt 21", inGerman { PlanGaps.pointSign(20) })
        assertEquals("point 21", PlanGaps.pointSign(20))
    }
}
