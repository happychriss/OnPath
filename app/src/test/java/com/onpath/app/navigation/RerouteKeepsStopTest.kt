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

/**
 * Bug G02 (website pictures 2026-11-07, emulator, 0.17.3; core feature C5): Hike Vellau → Leiteralm with Gasthof Hochmuth
 * added as stop ②. The plan goes out to the inn and comes back on the Meraner Höhenweg above the way out. Walking off the
 * way out, the off-route box offered only "Neue Route bis zum Ziel: 3,8 km (4,5 km kürzer)" – the inn left out, no
 * "Über ②" / "② auslassen". Cause: the walker was within 1 km of the way back past the inn, so [RerouteTargets.base]
 * counted them "abeam" of it (a shortcut) and the inn as passed. Rebuilt with real data in
 * `tools/routing/jvm-test/RerouteStopJvmTest.kt` (3 of 138 walk-offs dropped the inn).
 *
 * Owner's rule (2026-10-30 / 11-01 / 11-03): a re-route never drops a stop without the question – via it, or asked in the
 * one question shape.
 */
class RerouteKeepsStopTest {
    // start (0, 0) → east 3 km → north 400 m to the inn (along 3.4 km) → back west 2.5 km above the way out (destination)
    private val plan = NavLine(line(0.0 to 0.0, 3_000.0 to 0.0, 3_000.0 to 400.0, 500.0 to 400.0))
    private val inn = pt(3_000.0, 400.0)
    private val stops = listOf(NavStop(pt(0.0, 0.0), 0.0, "Vellau"), NavStop(inn, 3_400.0, "Gasthof Hochmuth"),
        NavStop(pt(500.0, 400.0), plan.lengthM, "Leiteralm"))
    // left the way out at km 2 and walked 250 m north: 150 m below the way back (along 4.4 km), 250 m from the way out
    private val pos = pt(2_000.0, 250.0)

    /** Straight legs between all waypoints (a line through every stop), a point every 10 m. */
    private object Straight : RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
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

    private fun passes(l: List<GeoPoint>, p: GeoPoint) = (NavLine(l).match(p)?.distanceM ?: 1e9) <= VisitRadius.of(Activity.HIKE)

    @Test fun abeamOfTheWayBackPastAStop_theStopIsNotPassed() {
        val base = RerouteTargets.base(plan, pos, 2_000.0, stops, emptySet())
        assertTrue("base $base must stay before the inn", base < stops[1].alongM - RerouteTargets.PASSED_SLACK_M)
        assertEquals(setOf(0), RerouteTargets.passed(stops, base, emptySet()))
        // a stop really visited does not hold the base back (the shortcut past it counts as before)
        assertEquals(4_400.0, RerouteTargets.base(plan, pos, 2_000.0, stops, setOf(0, 1)), 15.0)
        // without stops the shortcut rule is unchanged
        assertEquals(4_400.0, RerouteTargets.base(plan, pos, 2_000.0), 15.0)
    }

    @Test fun reroute_viaTheInnOrAsked_neverDroppedSilently() {
        val out = Rerouter(Straight).reroute(Rerouter.Request(pos, plan, 2_000.0, stops, emptySet(), Activity.HIKE, 2))
        assertTrue("found: $out", out is Rerouter.Found)
        out as Rerouter.Found
        val via = passes(out.line, inn)
        val asked = out.skipStop == 1 && out.ask != null && passes(out.ask!!.line, inn)
        assertTrue("the new route leaves the inn out unasked (skipStop ${out.skipStop}, ask ${out.ask != null})", via || asked)
        assertTrue(1 !in out.skippedStops || out.skipStop == 1)
    }
}
