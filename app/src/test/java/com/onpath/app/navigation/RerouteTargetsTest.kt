package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteTargetsTest {

    /** 10 km straight east. */
    private val long = NavLine(line(0.0 to 0.0, 10_000.0 to 0.0))

    private fun stops(l: NavLine, vararg along: Double) = along.map { NavStop(l.pointAt(it), it) }

    @Test fun candidatesAreAheadOnly() {
        val c = RerouteTargets.candidates(long, 4_000.0, emptyList(), emptySet())
        assertEquals(listOf(4_500.0, 5_500.0, 7_000.0), c.map { it.alongM })
        assertTrue(c.all { it.alongM > 4_000.0 })
    }

    @Test fun nextStopIsACandidateAndLimitsTheLinePoints() {
        val s = stops(long, 0.0, 2_000.0, 5_000.0, 10_000.0)
        val c = RerouteTargets.candidates(long, 1_200.0, s, emptySet())
        // next stop at 2 km; +0.5 km = 1.7 km is a line point, the plain ones never beyond the stop
        assertEquals(listOf(1_700.0, 2_000.0), c.filter { it.skips == null }.map { it.alongM })
        assertEquals(1, c.filter { it.skips == null }.last().stopIndex)
        // an ordinary route point (owner 2026-10-30): also 500 m past it and the stop after – marked, StopSkip decides
        assertEquals(listOf(2_500.0, 5_000.0), c.filter { it.skips == 1 }.map { it.alongM })
        assertEquals(2, c.last().stopIndex)
    }

    @Test fun nothingPastAPinnedNamedOrLastStop() {
        val pinned = listOf(NavStop(long.pointAt(0.0), 0.0), NavStop(long.pointAt(2_000.0), 2_000.0, pinned = true),
            NavStop(long.pointAt(5_000.0), 5_000.0), NavStop(long.pointAt(10_000.0), 10_000.0))
        assertTrue(RerouteTargets.candidates(long, 1_200.0, pinned, emptySet()).all { it.skips == null && it.alongM <= 2_000.0 })
        val named = pinned.mapIndexed { i, st -> if (i == 1) st.copy(pinned = false, name = "Café") else st }
        assertTrue(RerouteTargets.candidates(long, 1_200.0, named, emptySet()).all { it.skips == null && it.alongM <= 2_000.0 })
        // the destination is the next stop: nothing past it
        val two = stops(long, 0.0, 10_000.0)
        assertTrue(RerouteTargets.candidates(long, 8_000.0, two, emptySet()).none { it.skips != null })
    }

    @Test fun passedStopsAreSkippedAlsoPinnedOnes() {
        val s = listOf(NavStop(long.pointAt(0.0), 0.0), NavStop(long.pointAt(2_000.0), 2_000.0, "Hut", pinned = true),
            NavStop(long.pointAt(3_000.0), 3_000.0), NavStop(long.pointAt(10_000.0), 10_000.0))
        // shortcut past the hut: base 2.4 km → the hut is behind
        val c = RerouteTargets.candidates(long, 2_400.0, s, emptySet())
        assertTrue(c.none { it.stopIndex == 1 })
        assertEquals(2, c.first { it.stopIndex != null }.stopIndex)
        // visited (came within 40 m) counts as passed even when ahead of the base
        val c2 = RerouteTargets.candidates(long, 1_000.0, s, visited = setOf(1))
        assertTrue(c2.none { it.stopIndex == 1 })
        assertEquals(setOf(0, 1), RerouteTargets.passed(s, 2_400.0, emptySet()))
    }

    @Test fun farStopIsNoCandidateButLimits() {
        val s = stops(long, 0.0, 9_000.0, 10_000.0)
        val c = RerouteTargets.candidates(long, 1_000.0, s, emptySet())
        assertEquals(listOf(1_500.0, 2_500.0, 4_000.0), c.map { it.alongM })
    }

    @Test fun nearTheEndTheDestinationIsTheTarget() {
        val c = RerouteTargets.candidates(long, 9_700.0, emptyList(), emptySet())
        assertEquals(listOf(long.lengthM), c.map { it.alongM })
    }

    @Test fun baseMovesAheadWhenAbeamOfALaterPart() {
        assertEquals(3_000.0, RerouteTargets.base(long, pt(3_000.0, 200.0), 1_000.0), 5.0)
        // never behind the progress
        assertEquals(1_000.0, RerouteTargets.base(long, pt(500.0, 200.0), 1_000.0), 0.1)
    }

    /**
     * Field 2026-10-31: 30 km past the end of a 2.7 km plan the nearest line point is its END – "abeam" of it would mark
     * every point as passed. Only a line within [RerouteTargets.ABEAM_MAX_M] moves the base ahead.
     */
    @Test fun baseStaysWhenTheLineIsFarAway() {
        val short = NavLine(line(0.0 to 0.0, 2_700.0 to 0.0))
        assertEquals(0.0, RerouteTargets.base(short, pt(32_700.0, 0.0), 0.0), 0.1)
        assertEquals(0.0, RerouteTargets.base(short, pt(2_700.0, 1_500.0), 0.0), 0.1)
        // a shortcut beside a later part (within 1 km): ahead as before
        assertEquals(2_000.0, RerouteTargets.base(short, pt(2_000.0, 900.0), 0.0), 5.0)
        val s = stops(short, 0.0, 700.0, 1_400.0, 2_000.0, 2_700.0)
        assertEquals(setOf(0), RerouteTargets.passed(s, RerouteTargets.base(short, pt(32_700.0, 0.0), 0.0), emptySet()))
    }

    @Test fun spliceKeepsTheRestAndTheDestination() {
        val leg = line(5_000.0 to 300.0, 5_500.0 to 0.0)
        val out = RerouteTargets.splice(leg, long, 5_500.0)
        assertEquals(leg.first(), out.first())
        assertEquals(long.points.last(), out.last())
        assertEquals(Geo.length(leg) + long.lengthM - 5_500.0, Geo.length(out), 10.0)
    }

    @Test fun rejoinIsWhereTheLegMeetsTheRouteForGood() {
        // missed turn: 100 m back to the junction, then along the route for 500 m
        val leg = line(1_000.0 to 100.0, 1_000.0 to 0.0, 1_500.0 to 0.0)
        assertEquals(80.0, RerouteTargets.rejoinInM(leg, long, 700.0, 1_510.0), 15.0)
    }
}

/** Fake engine: a straight leg with a fixed detour factor; records requests. */
private class FakeEngine(
    val detour: (GeoPoint, GeoPoint) -> Double = { _, _ -> 1.0 },
    val fail: (GeoPoint) -> String? = { null },
) : RoutingEngine {
    val calls = mutableListOf<Pair<List<GeoPoint>, RouteLimits?>>()
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
        route(waypoints, activity, sacLimit, RouteLimits(30_000))

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
        calls += waypoints to limits
        fail(waypoints.last())?.let { throw RoutingException(it) }
        val a = waypoints.first(); val b = waypoints.last()
        val n = 20
        val pts = (0..n).map { GeoPoint(a.lat + (b.lat - a.lat) * it / n, a.lon + (b.lon - a.lon) * it / n) }
        val d = Geo.distance(a, b) * detour(a, b)
        return RouteResult(pts, d, 0.0, 0.0, d / 1.2)
    }
}

class RerouterTest {
    private val long = NavLine(line(0.0 to 0.0, 10_000.0 to 0.0))

    private fun req(pos: GeoPoint, progress: Double, l: NavLine = long, stops: List<NavStop> = emptyList()) =
        Rerouter.Request(pos, l, progress, stops, emptySet(), Activity.HIKE, 3)

    @Test fun picksAWayBackAheadAndSplices() {
        val eng = FakeEngine()
        val r = Rerouter(eng).reroute(req(pt(1_100.0, 100.0), 1_000.0)) as Rerouter.Found
        assertTrue(eng.calls.size in 1..4)
        assertTrue(eng.calls.all { it.second!!.timeoutMs <= Rerouter.PER_REQUEST_MS })
        assertTrue(r.resumeAlongM > 1_000.0)
        assertEquals(long.points.last(), r.line.last())
        assertTrue("extra ${r.extraM}", r.extraM > 0 && r.extraM < 200)
    }

    @Test fun avoidsACandidateBehindAValley() {
        // the nearest candidate (+0.5 km) is "across the river": 8x detour, so a further one wins
        val eng = FakeEngine(detour = { _, b -> if (kotlin.math.abs(b.lon - long.pointAt(1_600.0).lon) < 1e-6) 8.0 else 1.0 })
        val r = Rerouter(eng).reroute(req(pt(1_100.0, 100.0), 1_100.0)) as Rerouter.Found
        assertEquals(2_600.0, r.resumeAlongM, 1.0)
    }

    @Test fun roundTripEndNextToYouIsNotTaken() {
        // 2.4 km loop; the user is 60 m beside the start shortly after leaving: the end (= start) is right there
        val loop = NavLine(line(0.0 to 0.0, 600.0 to 0.0, 600.0 to 600.0, 0.0 to 600.0, 0.0 to 0.0))
        val r = Rerouter(FakeEngine()).reroute(req(pt(40.0, -60.0), 40.0, loop)) as Rerouter.Found
        assertTrue("resume ${r.resumeAlongM}", r.resumeAlongM < 1_000.0)
    }

    @Test fun noDataFailsFastWithReason() {
        val eng = FakeEngine(fail = { com.onpath.app.routing.BRouterEngine.NO_DATA })
        val r = Rerouter(eng).reroute(req(pt(1_100.0, 100.0), 1_000.0)) as Rerouter.Failed
        assertEquals(Rerouter.Reason.NO_DATA, r.reason)
        assertEquals(1, eng.calls.size)
    }

    @Test fun oneFailingCandidateIsSkipped() {
        val bad = long.pointAt(1_600.0)
        val eng = FakeEngine(fail = { p -> if (Geo.distance(p, bad) < 1) "No route found between these points for Hike." else null })
        val r = Rerouter(eng).reroute(req(pt(1_100.0, 100.0), 1_000.0))
        assertTrue(r is Rerouter.Found)
        assertFalse((r as Rerouter.Found).resumeAlongM == 1_600.0)
    }

    @Test fun totalBudgetIsRespected() {
        var now = 0L
        val eng = object : RoutingEngine {
            var n = 0
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult = throw IllegalStateException()
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
                n++; now += limits.timeoutMs; throw RoutingException("No route found within 8 s.", timedOut = true)
            }
        }
        val r = Rerouter(eng) { now }.reroute(req(pt(1_100.0, 100.0), 1_000.0)) as Rerouter.Failed
        assertEquals(2, eng.n) // 8 s + 4 s = 12 s
        assertEquals(Rerouter.Reason.TIMEOUT, r.reason)
    }

    @Test fun cancelledStopsAtOnce() {
        val c = RouteCancel().apply { cancel() }
        val r = Rerouter(FakeEngine()).reroute(req(pt(1_100.0, 100.0), 1_000.0), c) as Rerouter.Failed
        assertEquals(Rerouter.Reason.CANCELLED, r.reason)
    }

    @Test fun straightLineEngineIsNoReroute() {
        val r = Rerouter(com.onpath.app.routing.StraightLineEngine()).reroute(req(pt(1_100.0, 100.0), 1_000.0)) as Rerouter.Failed
        assertEquals(Rerouter.Reason.UNAVAILABLE, r.reason)
    }
}
