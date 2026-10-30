package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake engine: straight legs through every waypoint (a multi-stop request = the stops in order); records requests. */
internal class PolyEngine : RoutingEngine {
    val calls = mutableListOf<List<GeoPoint>>()
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
        route(waypoints, activity, sacLimit, RouteLimits(30_000))

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
        calls += waypoints
        val pts = ArrayList<GeoPoint>()
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]; val b = waypoints[i + 1]
            val n = maxOf(1, (Geo.distance(a, b) / 10.0).toInt())
            for (k in 0 until n) pts += GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
        }
        pts += waypoints.last()
        val d = Geo.length(pts)
        return RouteResult(pts, d, 0.0, 0.0, d / 1.2)
    }
}

/**
 * Re-routing and the next stop (owner 2026-10-30, docs/plan-auto-reroute.md §5.4): the way back goes via the next stop
 * when it is on the way, skips an ordinary route point that now lies far behind / aside, and asks in between or when
 * the stop was chosen on purpose (POI pinned, named, a turning point).
 */
class StopSkipTest {

    /** True when [l] passes within 30 m of [p]. */
    private fun passes(l: List<GeoPoint>, p: GeoPoint) = l.any { Geo.distance(it, p) < 30.0 }

    /**
     * Bike plan 10 km east with a spike north to route point 1 at (3000, [spikeY]); stop 2 at 6 km, destination at 10 km.
     * The rider left the plan at ~1.5 km and rides on a parallel road 380 m south of it.
     */
    private class Spike(spikeY: Double, val pinned: Boolean = false, val name: String? = null) {
        val corners = listOf(0.0 to 0.0, 2_800.0 to 0.0, 3_000.0 to spikeY, 3_200.0 to 0.0, 6_000.0 to 0.0, 10_000.0 to 0.0)
        val nl = NavLine(line(*corners.toTypedArray()))
        val stopPts = listOf(pt(0.0, 0.0), pt(3_000.0, spikeY), pt(6_000.0, 0.0), pt(10_000.0, 0.0))
        val stops = nl.alongsInOrder(stopPts).mapIndexed { i, a ->
            NavStop(stopPts[i], a, if (i == 1) name else null, pinned = i == 1 && pinned)
        }
        fun req(pos: GeoPoint, progress: Double = 1_500.0, act: Activity = Activity.BIKE_TOURING) =
            Rerouter.Request(pos, nl, progress, stops, emptySet(), act, 3)
    }

    // ---- today's behaviour (pinned before the change) ------------------------------------------------------------

    @Test fun nextStopOnTheWayStaysInTheNewRoute() {
        val s = Spike(spikeY = 100.0)
        val eng = PolyEngine()
        val r = Rerouter(eng).reroute(s.req(pt(2_000.0, -380.0))) as Rerouter.Found
        assertTrue("the new route still goes via route point 1", passes(r.line, s.stopPts[1]))
        assertEquals(1, r.targetStop)
    }

    @Test fun pinnedStopFarAsideStaysInTheNewRoute() {
        val s = Spike(spikeY = 1_500.0, pinned = true, name = "Café")
        val r = Rerouter(PolyEngine()).reroute(s.req(pt(2_000.0, -380.0))) as Rerouter.Found
        assertTrue(passes(r.line, s.stopPts[1]))
    }

    @Test fun ordinaryStopFarAsideIsVisitedToday() {
        // today: the fresh route always goes via every stop not passed – a 1.5 km spike for a plain route point
        val s = Spike(spikeY = 1_500.0)
        val r = Rerouter(PolyEngine()).reroute(s.req(pt(2_000.0, -380.0))) as Rerouter.Found
        assertTrue(passes(r.line, s.stopPts[1]))
    }

    @Test fun wayBackCandidatesNeverPassTheNextStopToday() {
        val s = Spike(spikeY = 1_500.0)
        val c = RerouteTargets.candidates(s.nl, 2_000.0, s.stops, emptySet())
        assertTrue(c.all { it.alongM <= s.stops[1].alongM })
    }
}
