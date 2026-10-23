package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCheckTest {
    /** East 1 km, then north 1 km; stops at the start, the corner and the end. */
    private val route = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0)
    private fun session() = NavSession.start(NavKind.PLAN, "Test", Activity.HIKE, 3, route,
        listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 1000.0)), names = listOf(null, "Hut", "Peak"))

    private fun result(points: List<GeoPoint>, ways: List<Share> = emptyList(), surfaces: List<Share> = emptyList()) =
        RouteResult(points, Geo.length(points), 0.0, 0.0, 0.0, ways, surfaces)

    // ---- what is routed --------------------------------------------------------------------------------------

    @Test fun restAtTheStartGoesThroughAllStopsAhead() {
        val r = ActivityCheck.rest(session(), 0.0, pt(0.0, 0.0))!!
        assertEquals(3, r.waypoints.size) // position, corner, end – the start is passed
        assertEquals(listOf("Hut", "Peak"), r.stops.map { it.name })
        assertEquals(2000.0, Geo.length(r.line), 5.0)
    }

    @Test fun restAfterTheCornerDropsThePassedStop() {
        val r = ActivityCheck.rest(session(), 1300.0, pt(1000.0, 300.0))!!
        assertEquals(listOf("Peak"), r.stops.map { it.name })
        assertEquals(2, r.waypoints.size)
        assertEquals(700.0, Geo.length(r.line), 5.0)
    }

    @Test fun visitedStopsStayPassedAndNothingLeftAtTheEnd() {
        val s = session().copy(visited = setOf(1))
        assertEquals(listOf("Peak"), ActivityCheck.rest(s, 500.0, pt(500.0, 0.0))!!.stops.map { it.name })
        assertNull(ActivityCheck.rest(session(), 1990.0, pt(1000.0, 990.0)))
    }

    @Test fun onAWayBackTheSkippedStopStaysSkipped() {
        // a new route was taken that rejoins north of the corner (the "Hut" stop is skipped)
        val leg = line(800.0 to 200.0, 1000.0 to 300.0)
        val s = session().copy(current = RerouteTargets.splice(leg, NavLine(route), 1300.0), spliceAtM = Geo.length(leg),
            resumeAlongM = 1300.0, leftAtM = 800.0)
        val r = ActivityCheck.rest(s, 50.0, pt(850.0, 220.0))!!
        assertEquals(listOf("Peak"), r.stops.map { it.name })
    }

    @Test fun aTrackKeepsItsShapeThroughVias() {
        val long = line(0.0 to 0.0, 10_000.0 to 0.0, stepM = 50.0)
        val s = NavSession.start(NavKind.TRACK, "GPX", Activity.HIKE, 3, long)
        val r = ActivityCheck.rest(s, 1000.0, pt(1000.0, 0.0))!!
        // 9 km left: vias every 2 km (3, 5, 7 km – none within 1 km of the end) and the end
        assertEquals(1 + 3 + 1, r.waypoints.size)
        assertTrue(r.stops.isEmpty())
    }

    // ---- does it fit -----------------------------------------------------------------------------------------

    @Test fun sameWayFits() {
        val rest = NavLine(route).restFrom(500.0)
        val f = ActivityCheck.evaluate(rest, Activity.MTB, 3, result(rest))
        assertTrue(f.fits)
        assertEquals(0.0, f.extraM, 1.0)
        assertTrue(f.timeS > 0)
    }

    @Test fun aDetourMeansThePartIsNotSuitedForThatActivity() {
        val rest = NavLine(route).restFrom(0.0)
        // the bike router takes another way: most of both legs (~1.5 km) are not used by it
        val bike = line(0.0 to 0.0, 300.0 to 0.0, 1300.0 to 300.0, 1300.0 to 1000.0, 1000.0 to 1000.0)
        assertEquals(1500.0, ActivityCheck.avoidedM(rest, bike), 150.0)
        val f = ActivityCheck.evaluate(rest, Activity.BIKE_TREKKING, 3, result(bike))
        assertFalse(f.fits)
        assertTrue(f.problems.first(), f.problems.first().endsWith("not suited for trekking bikes"))
        assertTrue(f.extraM > 0)
    }

    @Test fun smallDifferencesAreNoise() {
        val rest = NavLine(route).restFrom(0.0)
        val almost = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 900.0, 1040.0 to 940.0, 1000.0 to 1000.0)
        assertTrue(ActivityCheck.evaluate(rest, Activity.MTB, 3, result(almost)).fits)
    }

    @Test fun stepsAreAProblemForBikesOnly() {
        val rest = NavLine(route).restFrom(0.0)
        val ways = listOf(Share("path", 1960.0), Share("steps", 40.0))
        val mtb = ActivityCheck.evaluate(rest, Activity.MTB, 3, result(rest, ways))
        assertEquals(listOf("steps 40 m"), mtb.problems)
        assertTrue(ActivityCheck.evaluate(rest, Activity.HIKE, 3, result(rest, ways)).fits)
    }

    @Test fun roadBikesWarnAboutUnpavedHikersAboutBusyRoads() {
        val rest = NavLine(route).restFrom(0.0)
        val surf = listOf(Share("asphalt", 1200.0), Share("gravel", 800.0))
        assertEquals(listOf("800 m unpaved"), ActivityCheck.evaluate(rest, Activity.BIKE_TOURING, 3, result(rest, surfaces = surf)).problems)
        // gravel is fine for a trekking bike
        assertTrue(ActivityCheck.evaluate(rest, Activity.BIKE_TREKKING, 3, result(rest, surfaces = surf)).fits)
        val ways = listOf(Share("busy_road", 1500.0), Share("path", 500.0))
        assertEquals(listOf("1.5 km busy road"), ActivityCheck.evaluate(rest, Activity.HIKE, 3, result(rest, ways)).problems)
    }

    @Test fun hikingNamesTheSacLimit() {
        assertEquals("hiking (T4)", ActivityCheck.who(Activity.HIKE, 4))
        assertEquals("Hike (T3)", NavText.activityName(Activity.HIKE, 3))
        assertEquals("MTB", NavText.activityName(Activity.MTB, 3))
    }

    // ---- the switch question (Navigator) ---------------------------------------------------------------------

    private fun proposal(from: Pair<Double, Double>): NavSession {
        val l = line(from, 1000.0 to 300.0, 1000.0 to 1000.0)
        return NavSession.start(NavKind.PLAN, "Test", Activity.BIKE_TREKKING, 3, l, listOf(l.first(), pt(1000.0, 1000.0)))
    }

    @Test fun switchQuestionStaysOnTheRouteUntilAnsweredThenTheTourIsForTheNewActivity() {
        val nav = Navigator(session())
        walk(0.0 to 0.0, 600.0 to 0.0).forEach { nav.onFix(it) }
        nav.proposeSwitch(proposal(600.0 to 0.0), 1800.0)
        val q = nav.prompt as NavPrompt.Question
        assertNotNull(q.switch)
        assertEquals("New route for Trekking bike", NavText.title(q))
        assertTrue(NavText.question(q), NavText.question(q).startsWith("Rest of the route"))
        // walking on along the route (on route!) does not clear it – nothing disappears by itself
        walk(600.0 to 0.0, 700.0 to 0.0, t0Ms = 1_000_000).forEach { nav.onFix(it) }
        assertEquals(q, nav.prompt)
        val v0 = nav.session.version
        val step = nav.take(q.id)
        assertTrue(step.lineChanged && step.activityChanged)
        assertNull(nav.prompt)
        assertEquals(Activity.BIKE_TREKKING, nav.session.activity)
        assertEquals(Activity.BIKE_TREKKING, nav.state.session?.activity)
        assertFalse(nav.session.rerouted) // the new line is the original now: no grey line of the wrong activity
        assertEquals(v0 + 1, nav.session.version)
        assertEquals(q.line, nav.state.line)
    }

    @Test fun keepClosesTheSwitchQuestionAndNothingChanges() {
        val nav = Navigator(session())
        walk(0.0 to 0.0, 600.0 to 0.0).forEach { nav.onFix(it) }
        nav.proposeSwitch(proposal(600.0 to 0.0), 1800.0)
        val q = nav.prompt as NavPrompt.Question
        val s = nav.keep(q.id)
        assertFalse(s.lineChanged)
        assertNull(nav.prompt)
        assertEquals(Activity.HIKE, nav.session.activity)
    }

    @Test fun keepWhileOffRouteGoesBackToTheWayBackQuestion() {
        val nav = Navigator(session())
        walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0).forEach { nav.onFix(it) }
        assertEquals(NavPrompt.Searching, nav.prompt)
        nav.proposeSwitch(proposal(1150.0 to 0.0), 1800.0)
        val q = nav.prompt as NavPrompt.Question
        // a stale way-back result does not replace the switch question
        val stale = nav.onResult(1, Rerouter.Failed(Rerouter.Reason.NO_WAY, "x", 1))
        assertFalse(stale.vibrate)
        assertEquals(q, nav.prompt)
        val s = nav.keep(q.id)
        assertNotNull(s.job)
        assertEquals(NavPrompt.Searching, nav.prompt)
    }

    @Test fun switchedSessionSurvivesJson() {
        val nav = Navigator(session())
        nav.onFix(fix(0.0, 0.0, 0))
        nav.proposeSwitch(proposal(0.0 to 0.0), 1800.0)
        nav.take((nav.prompt as NavPrompt.Question).id)
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(nav.session))!!
        assertEquals(Activity.BIKE_TREKKING, back.activity)
        assertEquals(2, back.stops.size)
    }
}
