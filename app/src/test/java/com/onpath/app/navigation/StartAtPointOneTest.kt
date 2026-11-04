package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.StraightLineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03 ("Rundtour bleibt Rundtour"): every tour starts at point 1 and skips nothing – shortening = the user
 * deletes points. (2) Reached beside a later part of the line → "Du stehst mitten auf der Route" · Hier einsteigen /
 * Zu ① führen; the way there counts as reached only at the start; stops count in plan order; (5) a route loaded on the
 * way starts afresh; TourSplit: a later point hit by chance no longer makes the points before it passed.
 */
class StartAtPointOneTest {
    // a straight plan east along y = 0: point 1 at 0 m, point 2 at 700 m, point 3 (goal) at 1500 m
    private val plan = line(0.0 to 0.0, 1500.0 to 0.0)
    private val s1 = pt(0.0, 0.0)
    private val s2 = pt(700.0, 0.0)
    private val s3 = pt(1500.0, 0.0)

    /** Straight legs between the via points, a point every 10 m (the app's StraightLineEngine only reports "no router"). */
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
            return com.onpath.app.routing.RouteResult(pts, d, 0.0, 0.0, d / 4.0)
        }
    }

    private fun nav() = Navigator(NavSession.start(NavKind.PLAN, "Route", Activity.BIKE_TOURING, 1, plan, listOf(s1, s2, s3)))

    /** Rides east 20 m beside the line from x = [from] for [n] seconds at 5 m/s. */
    private fun ride(nav: Navigator, from: Double, n: Int, y: Double = 20.0, t0: Long = 0L) =
        (0 until n).forEach { i -> nav.onFix(fix(from + i * 5.0, y, t0 + i * 1000L)) }

    @Test fun beginningBesideALaterPart_asksInsteadOfJoiningThere() {
        val nav = nav()
        ride(nav, 900.0, 12)
        val q = nav.prompt
        assertTrue("the mid-route question, not a silent start at 900 m: $q", q is NavPrompt.MidStart)
        assertFalse("not reached while the user decides", nav.session.reached)
        assertTrue("no off-route alarm", !q!!.offRoute)
    }

    @Test fun joinHere_theStopsBeforeArePassed_theRestStaysAhead() {
        val nav = nav()
        ride(nav, 900.0, 12)
        val q = nav.prompt as NavPrompt.MidStart
        nav.joinHere(q.id)
        assertNull(nav.prompt)
        assertTrue(nav.session.reached)
        assertEquals("points 1 and 2 grey (passed), not deleted", setOf(0, 1), nav.session.visited)
        assertEquals("all three points still in the plan", 3, nav.session.stops.size)
    }

    @Test fun leadToOne_isTheWayThereToPointOne() {
        val nav = nav()
        ride(nav, 900.0, 12)
        val q = nav.prompt as NavPrompt.MidStart
        val step = nav.leadThere(q.id)
        assertNotNull(step.job); val job = step.job!!
        assertTrue("the way there: here → point 1 → all points", job.request.toStart)
        assertTrue((nav.prompt as NavPrompt.MidStart).searching)
        // the way there (straight here → 1 → 2 → 3) is taken; riding along the plan's middle on it reaches nothing
        val out = Rerouter(Straight).reroute(job.request) as Rerouter.Found
        nav.onResult(job.id, out)
        assertNull(nav.prompt)
        assertTrue(nav.session.lead)
        // following the way there west along the plan's own line: past its middle and point 2 – nothing reached yet
        (0 until 60).forEach { i -> nav.onFix(fix(950.0 - i * 5.0, 5.0, 20_000L + i * 1000L)) }
        assertFalse("crossing a later part on the way to point 1 is not 'reached'", nav.session.reached)
        (60 until 140).forEach { i -> nav.onFix(fix(950.0 - i * 5.0, 5.0, 20_000L + i * 1000L)) }
        assertFalse("and stop 3 / 2 are not visited out of order", 1 in nav.session.visited || 2 in nav.session.visited)
        // on to point 1: reached there
        (140 until 192).forEach { i -> nav.onFix(fix(950.0 - i * 5.0, 3.0, 20_000L + i * 1000L)) }
        assertTrue("reached at point 1", nav.session.reached)
        assertFalse("point 2, passed on the way there, is still ahead", 1 in nav.session.visited)
    }

    @Test fun beginningAtTheStart_asksNothing() {
        val nav = nav()
        ride(nav, 0.0, 12, y = 5.0)
        assertNull(nav.prompt)
        assertTrue(nav.session.reached)
    }

    @Test fun walkingBackToTheStart_endsTheQuestionByItself() {
        val nav = nav()
        ride(nav, 900.0, 12)
        assertTrue(nav.prompt is NavPrompt.MidStart)
        // rides back west along the line to its start
        (0 until 190).forEach { i -> nav.onFix(fix(955.0 - i * 5.0, 5.0, 20_000L + i * 1000L)) }
        assertFalse(nav.prompt is NavPrompt.MidStart)
    }

    // ---- stops count in plan order ------------------------------------------------------------------------------

    /** A round trip 1 → 2 → back to 1: at the start the closing point (= point 1's place) is never reached. */
    @Test fun roundTrip_theClosingPointIsNotReachedAtTheStart() {
        val loop = line(0.0 to 0.0, 600.0 to 0.0, 600.0 to 300.0, 0.0 to 0.0)
        val nav = Navigator(NavSession.start(NavKind.PLAN, "Rundtour", Activity.HIKE, 1, loop,
            listOf(pt(0.0, 0.0), pt(600.0, 300.0), pt(0.0, 0.0))))
        (0 until 20).forEach { i -> nav.onFix(fix(0.0 + i, 0.5, i * 1000L)) }
        assertTrue("point 1 reached", 0 in nav.session.visited)
        assertFalse("the way back to point 1 is not", 2 in nav.session.visited)
    }

    /** Before the route is reached nothing but point 1 counts (a way there passing point 2 reaches nothing). */
    @Test fun beforeTheRouteIsReached_onlyPointOneCounts() {
        val nav = nav()
        // standing far north, then walking past point 2 at 3 m on a way north of the route (not "on" it: > off distance)
        (0 until 10).forEach { i -> nav.onFix(fix(700.0, 300.0 - i, i * 1000L)) }
        (0 until 10).forEach { i -> nav.onFix(fix(700.0, 3.0, 10_000L + i * 1000L)) }
        assertFalse("point 2 not visited before point 1 / before the route is reached", 1 in nav.session.visited)
    }

    // ---- (1) the way there enters at point 1 -------------------------------------------------------------------

    @Test fun guideMe_aFreshLineEnteringBetweenOneAndTwoIsNotTaken() {
        val stops = listOf(NavStop(s1, 0.0), NavStop(s2, 700.0), NavStop(s3, 1500.0))
        val r = Rerouter(StraightLineEngine())
        // meets point 1 only at the end: refused
        assertFalse(r.entersAtFirst(line(-100.0 to 50.0, 700.0 to 0.0, 0.0 to 0.0), stops, Activity.BIKE_TOURING))
        // reaches point 1 first: taken
        assertTrue(r.entersAtFirst(line(-100.0 to 50.0, 0.0 to 0.0, 700.0 to 0.0), stops, Activity.BIKE_TOURING))
        // a track / one point: always
        assertTrue(r.entersAtFirst(line(5.0 to 5.0, 6.0 to 6.0), stops.take(1), Activity.BIKE_TOURING))
    }

    // ---- (5) a route loaded on the way starts afresh -------------------------------------------------------------

    @Test fun aDifferentPlan_isNotAnEdit_nothingCarriedOver() {
        val old = NavSession.start(NavKind.PLAN, "Alt", Activity.BIKE_TOURING, 1, plan, listOf(s1, s2, s3)).copy(reached = true)
        val other = line(0.0 to 500.0, 900.0 to 500.0)
        val loaded = NavSession.start(NavKind.PLAN, "Geladen", Activity.BIKE_TOURING, 1, other, listOf(pt(0.0, 500.0), pt(900.0, 500.0)))
        assertFalse("a loaded route starts at its point 1", NavSession.afterPlanEdit(old, loaded).reached)
        // an edit (a point of the old plan kept): still reached as before
        val edited = NavSession.start(NavKind.PLAN, "Alt", Activity.BIKE_TOURING, 1, line(700.0 to 0.0, 1500.0 to 0.0), listOf(s2, s3))
        assertTrue(NavSession.afterPlanEdit(old, edited).reached)
    }

    @Test fun loadedOnTheWay_isFollowedAsAWholePlan() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("loadedOnTheWay = duringTour && recState.value != null"))
        val split = vm.substringAfter("private fun splitOf(").substringBefore("\n    }\n")
        assertTrue(split, split.contains("val passed = if (fresh) emptySet() else tourPassed(before)"))
    }

    // ---- TourSplit: passed in plan order -------------------------------------------------------------------------

    @Test fun tourSplit_aLaterPointHitByChance_doesNotPassThePointsBefore() {
        val p1 = pt(0.0, 0.0); val p2 = pt(500.0, 0.0); val p3 = pt(1000.0, 0.0); val p4 = pt(1500.0, 0.0)
        // the way there came by point 3 only; points 1 and 2 never near
        val track = line(1000.0 to 400.0, 1000.0 to 10.0, 900.0 to 400.0)
        val t = TourSplit.Tour(listOf(track), pt(900.0, 400.0), reached = true, bike = true)
        assertEquals(emptySet<Int>(), TourSplit.passed(listOf(p1, p2, p3, p4), t))
    }

    @Test fun tourSplit_aPointPassed45mBeside_isStillBridgedByTheNextOne() {
        val p1 = pt(0.0, 0.0); val p2 = pt(500.0, 0.0); val p3 = pt(1000.0, 0.0); val p4 = pt(1500.0, 0.0)
        val track = line(0.0 to 0.0, 500.0 to 45.0, 1000.0 to 0.0, 1100.0 to 0.0)
        val t = TourSplit.Tour(listOf(track), pt(1100.0, 0.0), reached = true, bike = true)
        assertEquals(setOf(0, 1, 2), TourSplit.passed(listOf(p1, p2, p3, p4), t))
    }

}
