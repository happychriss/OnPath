package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.RoutingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Fake engine: legs through every waypoint in order (a multi-stop request = the stops in order); records requests.
 * [detour] (from, to) > 1 makes a leg that much longer (a bulge sideways): "no direct way there from here".
 */
internal class PolyEngine(val detour: (GeoPoint, GeoPoint) -> Double = { _, _ -> 1.0 }) : RoutingEngine {
    val calls = mutableListOf<List<GeoPoint>>()
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
        route(waypoints, activity, sacLimit, RouteLimits(30_000))

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
        calls += waypoints
        val pts = ArrayList<GeoPoint>()
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]; val b = waypoints[i + 1]
            val f = detour(a, b)
            if (f > 1.0) {
                // the leg bulges sideways so that it is f times as long
                val k = sqrt(f * f - 1) / 2
                val mid = GeoPoint((a.lat + b.lat) / 2 - (b.lon - a.lon) * k * LON_TO_LAT, (a.lon + b.lon) / 2 + (b.lat - a.lat) * k / LON_TO_LAT)
                straight(a, mid, pts); straight(mid, b, pts)
            } else straight(a, b, pts)
        }
        pts += waypoints.last()
        val d = Geo.length(pts)
        return RouteResult(pts, d, 0.0, 0.0, d / 1.2)
    }

    private fun straight(a: GeoPoint, b: GeoPoint, out: MutableList<GeoPoint>) {
        val n = maxOf(1, (Geo.distance(a, b) / 10.0).toInt())
        for (k in 0 until n) out += GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
    }

    private companion object {
        /** Degrees of longitude → latitude for the same metres at the test place (Trace: Algund, 46.68° N). */
        val LON_TO_LAT = kotlin.math.cos(Math.toRadians(46.68)) * 111_320.0 / 110_540.0
    }
}

/**
 * Re-routing and the next stop (owner 2026-10-30, docs/plan-auto-reroute.md §5.4): the new route goes via the next
 * stop when it is on the way (of the plan or of the straight way on), leaves out an ordinary route point that now lies
 * behind, and asks in between or when the stop was chosen on purpose (POI pinned, named, a turning point).
 */
class StopSkipTest {

    /** True when [l] passes within 30 m of [p]. */
    private fun passes(l: List<GeoPoint>, p: GeoPoint) = l.any { Geo.distance(it, p) < 30.0 }

    /** True when [p] lies on the plan between x = [from] and [to] (local metres east). */
    private fun inX(p: GeoPoint, from: Double, to: Double) = p.lon in pt(from, 0.0).lon..pt(to, 0.0).lon

    /**
     * Bike plan 10 km east (y = 0): route point 2 at 3 km, stop 3 at 6 km, destination at 10 km. The rider left the plan
     * at ~1.5 km and is on a street 380 m south of it at x = 2 km. [behind] = how much longer a way into the stretch
     * around route point 2 (x 2.3–3.7 km) is from here (a river between: 1 = none).
     */
    private class Plan(val behind: Double = 1.0, pinned: Boolean = false, name: String? = null) {
        val nl = NavLine(line(0.0 to 0.0, 10_000.0 to 0.0))
        val stopPts = listOf(pt(0.0, 0.0), pt(3_000.0, 0.0), pt(6_000.0, 0.0), pt(10_000.0, 0.0))
        val stops = stopPts.mapIndexed { i, p -> NavStop(p, nl.alongsInOrder(stopPts)[i], if (i == 1) name else null, pinned = i == 1 && pinned) }
        val pos = pt(2_000.0, -380.0)
        fun engine(region: Boolean = false) = PolyEngine { a, b ->
            val into = if (region) b.lon in pt(2_300.0, 0.0).lon..pt(3_700.0, 0.0).lon else Geo.distance(b, stopPts[1]) < 1
            if (a.lat < pt(0.0, -100.0).lat && into) behind else 1.0
        }
        fun req(progress: Double = 1_500.0) = Rerouter.Request(pos, nl, progress, stops, emptySet(), Activity.BIKE_TOURING, 3)
    }

    // ---- fresh route (a plan with stops) --------------------------------------------------------------------------

    @Test fun nextStopOnTheWayStaysInTheNewRoute() {
        val p = Plan()
        val eng = p.engine()
        val r = Rerouter(eng).reroute(p.req()) as Rerouter.Found
        assertTrue("the new route still goes via route point 2", passes(r.line, p.stopPts[1]))
        assertEquals(1, r.targetStop)
        assertNull(r.skipStop)
        assertNull(r.ask)
    }

    @Test fun ordinaryStopNowBehindIsLeftOutWithoutAsking() {
        // was: always via it. Now: via 6.2 km to stop 3, straight 4.0 km, plan 4.0 km (×1.55) → left out, not asked
        val p = Plan(behind = 3.0)
        val eng = p.engine()
        val r = Rerouter(eng).reroute(p.req()) as Rerouter.Found
        assertEquals(1, r.skipStop)
        assertNull(r.ask)
        assertFalse(passes(r.line, p.stopPts[1]))
        assertTrue(passes(r.line, p.stopPts[2]))
        assertEquals(2, r.targetStop)
        assertTrue(1 in r.skippedStops)
        assertEquals("via and straight past it: two requests", 2, eng.calls.size)
    }

    @Test fun ordinaryStopSomewhatBehindIsAsked() {
        // via 5.4 km vs straight / plan 4.0 km (×1.33): borderline → "Skip stop 2?"
        val p = Plan(behind = 2.2)
        val r = Rerouter(p.engine()).reroute(p.req()) as Rerouter.Found
        assertEquals(1, r.skipStop)
        val via = r.ask!!
        assertTrue(passes(via.line, p.stopPts[1]))
        assertFalse(passes(r.line, p.stopPts[1]))
        assertNull(via.skipStop)
        assertEquals(1, via.targetStop)
    }

    @Test fun smallDetourStaysVia() {
        // via 4.7 km vs 4.0 km (×1.17): not worth a word
        val p = Plan(behind = 1.6)
        val r = Rerouter(p.engine()).reroute(p.req()) as Rerouter.Found
        assertNull(r.skipStop)
        assertTrue(passes(r.line, p.stopPts[1]))
    }

    @Test fun pinnedOrNamedStopBehindIsAskedNeverLeftOutSilently() {
        for (p in listOf(Plan(behind = 3.0, pinned = true), Plan(behind = 3.0, name = "Café Moser"))) {
            val r = Rerouter(p.engine()).reroute(p.req()) as Rerouter.Found
            assertEquals(1, r.skipStop)
            assertNotNull(r.ask)
            assertTrue(passes(r.ask!!.line, p.stopPts[1]))
        }
    }

    @Test fun aDetourThePlanMakesOnPurposeStays() {
        // the plan goes 2.5 km north to route point 2 and back down (a loop, a nicer path); the rider is beside the plan
        // before it: via 7.2 km vs straight 4.5 km – but the plan itself is 7.9 km from here: via
        val corners = arrayOf(0.0 to 0.0, 2_000.0 to 0.0, 3_000.0 to 2_500.0, 4_000.0 to 0.0, 6_000.0 to 0.0, 10_000.0 to 0.0)
        val l = NavLine(line(*corners))
        val pts = listOf(pt(0.0, 0.0), pt(3_000.0, 2_500.0), pt(6_000.0, 0.0), pt(10_000.0, 0.0))
        val st = l.alongsInOrder(pts).mapIndexed { i, a -> NavStop(pts[i], a) }
        assertFalse(StopSkip.turningPoint(l, st[1].alongM))
        val r = Rerouter(PolyEngine()).reroute(Rerouter.Request(pt(1_500.0, -380.0), l, 1_200.0, st, emptySet(),
            Activity.BIKE_TOURING, 3)) as Rerouter.Found
        assertNull(r.skipStop)
        assertTrue(passes(r.line, pts[1]))
    }

    @Test fun turningPointIsDeliberate() {
        // the plan goes 1.5 km north to a viewpoint and comes back the same way: never left out silently
        val l = NavLine(line(0.0 to 0.0, 2_800.0 to 0.0, 2_800.0 to 1_500.0, 2_800.0 to 0.0, 6_000.0 to 0.0, 10_000.0 to 0.0))
        val pts = listOf(pt(0.0, 0.0), pt(2_800.0, 1_500.0), pt(6_000.0, 0.0), pt(10_000.0, 0.0))
        val st = l.alongsInOrder(pts).mapIndexed { i, a -> NavStop(pts[i], a) }
        assertTrue(StopSkip.turningPoint(l, st[1].alongM))
        assertTrue(StopSkip.deliberate(st[1], l))
        assertFalse(StopSkip.deliberate(st[2], l))
        val r = Rerouter(PolyEngine()).reroute(Rerouter.Request(pt(2_000.0, -380.0), l, 1_500.0, st, emptySet(),
            Activity.BIKE_TOURING, 3)) as Rerouter.Found
        assertTrue(r.skipStop == null || r.ask != null)
    }

    @Test fun hairpinIsNoTurningPoint() {
        // a switchback with legs 30 m apart: a way back on the same way is < 15 m apart, so no probe counts
        val l = NavLine(line(0.0 to 0.0, 1_000.0 to 0.0, 1_000.0 to 30.0, 0.0 to 30.0))
        assertFalse(StopSkip.turningPoint(l, 1_015.0))
    }

    @Test fun roundTripEndIsNeverTheSkip() {
        // 6 km loop with one route point at the far corner; just after the start the end (= start) is 180 m away
        val l = NavLine(line(0.0 to 0.0, 1_500.0 to 0.0, 1_500.0 to 1_500.0, 0.0 to 1_500.0, 0.0 to 0.0))
        val pts = listOf(pt(0.0, 0.0), pt(1_500.0, 1_500.0), pt(0.0, 0.0))
        val st = l.alongsInOrder(pts).mapIndexed { i, a -> NavStop(pts[i], a) }
        val r = Rerouter(PolyEngine()).reroute(Rerouter.Request(pt(100.0, -150.0), l, 100.0, st, emptySet(),
            Activity.BIKE_TOURING, 3)) as Rerouter.Found
        assertNull(r.skipStop)
        assertTrue(passes(r.line, pts[1]))
    }

    @Test fun destinationIsNeverSkipped() {
        val p = Plan(behind = 3.0)
        val eng = p.engine()
        // stops 2 and 3 visited: only the destination is left – one request, no skip
        val r = Rerouter(eng).reroute(Rerouter.Request(pt(7_000.0, -380.0), p.nl, 6_500.0, p.stops, setOf(1, 2),
            Activity.BIKE_TOURING, 3)) as Rerouter.Found
        assertNull(r.skipStop)
        assertEquals(1, eng.calls.size)
    }

    @Test fun decisionTable() {
        assertEquals(StopSkip.Decision.VIA, StopSkip.decide(600.0, 540.0, 560.0, deliberate = false))     // +7 %
        assertEquals(StopSkip.Decision.VIA, StopSkip.decide(300.0, 220.0, 210.0, deliberate = true))      // +80 s only
        assertEquals(StopSkip.Decision.ASK, StopSkip.decide(1_300.0, 1_000.0, 900.0, deliberate = false)) // +30 %
        assertEquals(StopSkip.Decision.SKIP, StopSkip.decide(1_600.0, 1_000.0, 1_000.0, deliberate = false)) // +60 %
        assertEquals(StopSkip.Decision.ASK, StopSkip.decide(1_600.0, 1_000.0, 1_000.0, deliberate = true))
        // far more than straight on, but the plan itself goes that way (a loop): via
        assertEquals(StopSkip.Decision.VIA, StopSkip.decide(1_600.0, 1_000.0, 1_550.0, deliberate = false))
        assertTrue(StopSkip.keepsTheTour(4_000.0, 2_000.0, 8_000.0))
        assertFalse(StopSkip.keepsTheTour(180.0, 100.0, 6_000.0))
    }

    // ---- the way back (no fresh route): candidates past the next stop ---------------------------------------------

    /** A request with stops in between fails: the fresh route is out, the way back is used. */
    private class LegsOnly(val poly: PolyEngine) : RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            if (waypoints.size > 2) throw RoutingException("No route found")
            return poly.route(waypoints, activity, sacLimit, limits)
        }
    }

    @Test fun wayBackPastAnOrdinaryStopNowBehind() {
        // the whole stretch around route point 2 is 5× as far from here: rejoin past it, at stop 3
        val p = Plan(behind = 5.0)
        val r = Rerouter(LegsOnly(p.engine(region = true))).reroute(p.req()) as Rerouter.Found
        assertEquals(1, r.skipStop)
        assertTrue("rejoins past route point 2 (${r.resumeAlongM})", r.resumeAlongM > 3_000.0)
        assertFalse(passes(r.line, p.stopPts[1]))
    }

    @Test fun wayBackViaANearbyStop() {
        val p = Plan()
        val r = Rerouter(LegsOnly(p.engine(region = true))).reroute(p.req()) as Rerouter.Found
        assertNull(r.skipStop)
        assertTrue(r.resumeAlongM <= 3_000.0)
        assertTrue(passes(r.line, p.stopPts[1]))
    }

    @Test fun wayBackCandidatesPassAnOrdinaryNextStopOnly() {
        val p = Plan()
        val c = RerouteTargets.candidates(p.nl, 2_000.0, p.stops, emptySet())
        // was: never past the next stop. Now: past it, marked, when it is an ordinary route point
        assertTrue(c.any { it.skips == 1 && it.alongM > 3_000.0 })
        assertTrue(c.filter { it.skips == null }.all { it.alongM <= 3_000.0 })
        val pinned = Plan(pinned = true)
        assertTrue(RerouteTargets.candidates(pinned.nl, 2_000.0, pinned.stops, emptySet()).all { it.alongM <= 3_000.0 })
    }
}
