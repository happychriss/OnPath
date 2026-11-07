package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field bug 2026-11-06 23:26 (owner on the Pixel, Hilden, round trip "Hilden → Meider Rodelhügel", 6 points, tour 182
 * started in the middle of the loop, demo ride): "Ich bekomme dauernd die Meldung 'Du stehst mitten auf der Route – zu ①
 * führen', es geht ein bisschen weiter, dann kommt wieder dieselbe Meldung. Das darf nur einmal kommen."
 *
 * Log: "reroute #1: found … leg 4980 m" → "Followed line v12: 69 junctions" (the way to ① taken) → 2 s later
 * "Followed line v13: 56 junctions" (the plan's own line again) → the question back → "Zu ① führen" → v14 / v15 … four
 * times, "Neue Route." said each time. Cause: on the way to ① the user still stands beside the plan's own line (the
 * middle of the loop), and the off-route rule "walked back onto the original near where it was left" took the original
 * back after three fixes – which, never reached, asked the mid-route question again.
 *
 * Wanted: asked once per plan; while it is open the followed line does not change; the answer holds (no flip, no new
 * question) until the plan changes; a position at the 150 m boundary never toggles it.
 */
class MidRouteOnceTest {
    // a loop: point 1 at (0, 0) → east 600 m → north 300 m (point 2) → west 600 m → back south to point 1
    private val loop = line(0.0 to 0.0, 600.0 to 0.0, 600.0 to 300.0, 0.0 to 300.0, 0.0 to 0.0)
    private val stops = listOf(pt(0.0, 0.0), pt(600.0, 300.0), pt(0.0, 0.0))

    private fun nav() = Navigator(NavSession.start(NavKind.PLAN, "Rundtour", Activity.HIKE, 1, loop, stops))

    /** Straight legs between the via points, a point every 10 m. */
    private object Straight : com.onpath.app.routing.RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): com.onpath.app.routing.RouteResult =
            route(waypoints, activity, sacLimit, com.onpath.app.routing.RouteLimits(30_000))
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int,
                           limits: com.onpath.app.routing.RouteLimits): com.onpath.app.routing.RouteResult {
            val pts = ArrayList<GeoPoint>()
            for (i in 0 until waypoints.size - 1) {
                val a = waypoints[i]; val b = waypoints[i + 1]
                val n = maxOf(1, (Geo.distance(a, b) / 10.0).toInt())
                for (k in 0 until n) pts += GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
            }
            pts += waypoints.last()
            val d = Geo.length(pts)
            return com.onpath.app.routing.RouteResult(pts, d, 0.0, 0.0, d / 1.3)
        }
    }

    /** Stands / walks north on the loop's east side (x = 600, 2 m beside it) from y = [from], one fix a second. */
    private fun walkEastSide(nav: Navigator, from: Double, n: Int, t0: Long, step: Double = 1.3) =
        (0 until n).forEach { i -> nav.onFix(fix(602.0, from + i * step, t0 + i * 1000L)) }

    @Test fun zuEinsFuehren_theWayThereHoldsBesideThePlansOwnLine_noSecondQuestion() {
        val nav = nav()
        walkEastSide(nav, 100.0, 10, 0L)
        val q = nav.prompt as NavPrompt.MidStart
        val job = nav.leadThere(q.id).job!!
        nav.onResult(job.id, Rerouter(Straight).reroute(job.request) as Rerouter.Found)
        assertNull(nav.prompt)
        assertTrue("the way to ① is followed", nav.session.rerouted && nav.session.lead)
        val led = nav.session.version
        // the demo / the user still walks on the loop's east side for a minute (beside the plan's own line)
        walkEastSide(nav, 113.0, 60, 20_000L)
        assertFalse("never the mid-route question again: ${nav.prompt}", nav.prompt is NavPrompt.MidStart)
        assertTrue("the way to ① is still followed (no flip back to the plan's line)", nav.session.rerouted)
        assertEquals("the followed line did not change", led, nav.session.version)
        assertFalse("not reached in the middle", nav.session.reached)
    }

    @Test fun whileTheQuestionIsOpen_theLineDoesNotChange() {
        val nav = nav()
        walkEastSide(nav, 100.0, 10, 0L)
        val v = nav.session.version
        walkEastSide(nav, 113.0, 60, 20_000L)
        assertTrue(nav.prompt is NavPrompt.MidStart)
        assertEquals("one question, the same one", (nav.prompt as NavPrompt.MidStart).id,
            (nav.prompt as NavPrompt.MidStart).id)
        assertEquals("nothing re-routed while it is open", v, nav.session.version)
    }

    @Test fun theLeadFailed_ok_noSecondQuestionWhileStillInTheMiddle() {
        val nav = nav()
        walkEastSide(nav, 100.0, 10, 0L)
        val q = nav.prompt as NavPrompt.MidStart
        val job = nav.leadThere(q.id).job!!
        nav.onResult(job.id, Rerouter.Failed(Rerouter.Reason.NO_WAY, "test", 0L))
        val failed = nav.prompt as NavPrompt.LeadFailed
        nav.leadFailedOk(failed.id)
        walkEastSide(nav, 113.0, 60, 20_000L)
        assertNull("answered once – not asked again in the middle: ${nav.prompt}", nav.prompt)
    }

    /** At the 150 m boundary a fix 10 m on either side never ends the question as "walked back to the start". */
    @Test fun atTheBoundary_theQuestionDoesNotToggle() {
        val nav = nav()
        // on the first side of the loop, 2 m beside it, at 165 m from point 1
        (0 until 6).forEach { i -> nav.onFix(fix(165.0, 2.0, i * 1000L)) }
        val q = nav.prompt as NavPrompt.MidStart
        (0 until 20).forEach { i -> nav.onFix(fix(if (i % 2 == 0) 140.0 else 165.0, 2.0, 10_000L + i * 1000L)) }
        assertEquals("still the same open question", q.id, (nav.prompt as? NavPrompt.MidStart)?.id)
        assertFalse("not counted as reached at 140 m", nav.session.reached)
        // walking back to the start for real ends it
        (0 until 30).forEach { i -> nav.onFix(fix(140.0 - i * 5.0, 2.0, 40_000L + i * 1000L)) }
        assertFalse(nav.prompt is NavPrompt.MidStart)
        assertTrue(nav.session.reached)
    }
}
