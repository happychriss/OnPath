package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteLimits
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Field bug 2026-11-03 (owner, Hilden, tour 106 at 22:20, data in ~/onpath-cells-work/field/2026-11-03-rundtour):
 * "Rundtour mit den Punkten 1, 2 und 3 geplant – dann kam die Meldung, ich soll dich hinführen – ja – plötzlich ist
 * meine Rundtour aufgelöst."
 *
 * Geometry from nav-session.json (metres from point 1): point 1 = start = goal at (0, 0) – off the street; the loop's
 * line leaves it to the north-west street at (−50, 40) and comes back from Benrather Straße at (2, −8); point 2 at
 * (−94, 7), point 3 at (−95, −129); the user stood at (−185, 51), ~195 m from point 1 → far-start question → "Guide me".
 *
 * "Guide me" asks the router for position → 1 → 2 → 3 → 1 (Rerouter.leadThere, fresh route). A real router meets an
 * off-street via point at its snapped spot on the nearest way – for point 1 coming from the west that is (−50, 40), the
 * street corner on the way to point 2. The followed line then goes position → (−50, 40) → 2 → 3 → 1: the loop is entered
 * between 1 and 2, point 1 is only reached at the very end. On the map the route runs to 2 first and ends at 1 – the
 * round trip looks "dissolved" (a one-way 2 → 3 → 1).
 *
 * Wanted (to be confirmed by the owner – see the page "Rundtour bleibt Rundtour"): after "Guide me" on a round trip the
 * followed line reaches point 1 before point 2 and closes at point 1 – the whole loop in its order.
 */
class RoundTripLeadTest {
    /** Field geometry, metres east / north of point 1. */
    private val p1 = pt(0.0, 0.0)
    private val p2 = pt(-94.0, 7.0)
    private val p3 = pt(-95.0, -129.0)
    private val snapWest = pt(-50.0, 40.0)   // point 1 met from the west (the street corner towards point 2)
    private val snapSouth = pt(2.0, -8.0)    // point 1 met from Benrather Straße (the loop's end)
    private val loop = line(0.0 to 0.0, -50.0 to 40.0, -65.0 to 15.0, -94.0 to 7.0, -148.0 to -19.0, -231.0 to -52.0,
        -175.0 to -108.0, -95.0 to -129.0, -51.0 to -145.0, 11.0 to -102.0, 48.0 to -52.0, 2.0 to -8.0, 0.0 to 0.0)

    /**
     * A router that meets point 1 at a snapped spot like BRouter: arriving from the west it is (−50, 40), as the loop's
     * last point (from the south) it is (2, −8); every other via point is met exactly. Legs are straight.
     */
    private class SnappingEngine(val p1: GeoPoint, val west: GeoPoint, val south: GeoPoint) : RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            route(waypoints, activity, sacLimit, RouteLimits(30_000))

        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            val met = waypoints.mapIndexed { i, w ->
                when {
                    Geo.distance(w, p1) > 1.0 || i == 0 -> w
                    i == waypoints.lastIndex -> south
                    else -> west
                }
            }
            val pts = ArrayList<GeoPoint>()
            for (i in 0 until met.size - 1) {
                val a = met[i]; val b = met[i + 1]
                val n = maxOf(1, (Geo.distance(a, b) / 10.0).toInt())
                for (k in 0 until n) pts += GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
            }
            pts += met.last()
            val d = Geo.length(pts)
            return RouteResult(pts, d, 0.0, 0.0, d / 4.0)
        }
    }

    /** Index of the first line point within [m] of [p]; −1 = never. */
    private fun firstNear(line: List<GeoPoint>, p: GeoPoint, m: Double = 15.0) = line.indexOfFirst { Geo.distance(it, p) <= m }

    @Ignore("reproduces the 2026-11-03 field bug – fix after the owner's word (page \"Rundtour bleibt Rundtour\")")
    @Test fun guideMeOnARoundTripReachesPointOneBeforePointTwoAndClosesThere() {
        val nav = Navigator(NavSession.start(NavKind.PLAN, "Route 3. Nov., 22:20", Activity.BIKE_TOURING, 1, loop,
            listOf(p1, p2, p3, p1)))
        // standing 195 m west of point 1, never on the loop yet: the far-start question
        (0 until 40).forEach { i -> nav.onFix(fix(-185.0 + if (i % 2 == 0) 0.0 else 8.0, 51.0, i * 1000L)) }
        val q = nav.prompt as NavPrompt.FarStart
        val job = nav.leadThere(q.id).job!!
        val out = Rerouter(SnappingEngine(p1, snapWest, snapSouth)).reroute(job.request) as Rerouter.Found
        nav.onResult(job.id, out)
        val followed = nav.session.active

        val at1 = firstNear(followed, p1)
        val at2 = firstNear(followed, p2)
        assertTrue("the loop is entered at point 1 (first near 1 at $at1, first near 2 at $at2)", at1 in 0 until at2)
        assertTrue("and closes at point 1", Geo.distance(followed.last(), p1) <= 15.0)
    }
}
