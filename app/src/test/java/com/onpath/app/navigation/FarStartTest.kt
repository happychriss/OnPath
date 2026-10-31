package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tour started far from the route it follows (field 2026-10-31: a Bike tour following "punkt 3", 2.7 km, started at
 * home ~30 km away). Owner rule: never on the route yet → always from the START – no off-route question, no vibration,
 * a calm question "Route starts 31 km away" · Guide me · Just record. Once on the route: the off-route flow as before.
 */
class FarStartTest {
    /** 2.7 km west → east, points at 0, 700, 1400, 2000, 2700 m. */
    private val route = line(0.0 to 0.0, 2_700.0 to 0.0)
    private val stops = listOf(pt(0.0, 0.0), pt(700.0, 0.0), pt(1_400.0, 0.0), pt(2_000.0, 0.0), pt(2_700.0, 0.0))
    private fun session() = NavSession.start(NavKind.PLAN, "punkt 3", Activity.BIKE_TOURING, 2, route, stops)

    private class Run(val nav: Navigator) {
        val steps = mutableListOf<Navigator.Step>()
        val jobs get() = steps.mapNotNull { it.job }
        fun feed(fixes: List<OffRouteDetector.Fix>) { fixes.forEach { steps += nav.onFix(it) } }
    }

    /** Standing at ([x], [y]) with ±20–40 m GPS drift for [s] seconds, one fix a second. */
    private fun drift(x: Double, y: Double = 0.0, s: Int = 40, t0: Long = 0) =
        (0 until s).map { i -> fix(x + if (i % 2 == 0) 0.0 else 40.0, y + if (i % 3 == 0) 20.0 else -20.0, t0 + i * 1000L) }

    @Test fun farBeforeTheStartAsksCalmlyWithoutAnOffRouteQuestion() {
        val r = Run(Navigator(session()))
        r.feed(drift(-30_000.0))
        assertTrue("no vibration", r.steps.none { it.vibrate })
        assertTrue("no off-route routing job", r.jobs.isEmpty())
        val p = r.nav.prompt as NavPrompt.FarStart
        assertEquals(30_000.0, p.distanceM, 100.0)
        assertEquals(90.0, p.bearingDeg, 5.0) // the start lies east
        assertEquals(0.0, r.nav.detector.progressM, 0.0)
        // the arrow / dashed guide line point to the start, not to the nearest part of the line
        assertEquals(0.0, Geo.distance(r.nav.state.toRoute!!.target, route.first()), 1.0)
        assertEquals("Route starts 30 km away", NavText.title(p))
        assertEquals("east (direct) · recording", NavText.state(p))
    }

    /** The field case: 30 km past the END – never "+30 km · new route" skipping every point, never the end as target. */
    @Test fun farPastTheEndNeverSkipsThePointsAndGuidesFromTheStart() {
        val r = Run(Navigator(session()))
        r.feed(drift(2_700.0 + 30_000.0))
        assertTrue(r.steps.none { it.vibrate })
        assertTrue(r.jobs.isEmpty())
        val p = r.nav.prompt as NavPrompt.FarStart
        assertEquals("distance to the route's FIRST point", 32_700.0, p.distanceM, 100.0)
        assertEquals(270.0, p.bearingDeg, 5.0)
        assertEquals("Route starts 33 km away", NavText.title(p))
        assertEquals(0.0, r.nav.detector.progressM, 0.0)
        assertEquals(0.0, Geo.distance(r.nav.state.toRoute!!.target, route.first()), 1.0)

        // "Guide me": here → start → ALL points → destination, taken at once
        val s = r.nav.leadThere(p.id)
        val job = s.job!!
        assertTrue(job.request.toStart)
        assertEquals(0.0, job.request.progressM, 0.0)
        assertTrue((r.nav.prompt as NavPrompt.FarStart).searching)
        assertFalse(s.vibrate)
        val out = Rerouter(PolyEngine()).reroute(job.request) as Rerouter.Found
        assertTrue(out.skippedStops.isEmpty())
        stops.forEach { st -> assertTrue("via every point", out.line.any { Geo.distance(it, st) < 15 }) }
        val taken = r.nav.onResult(job.id, out)
        assertTrue(taken.lineChanged)
        assertNull(r.nav.prompt)
        assertTrue(r.nav.session.rerouted)
        assertEquals(out.line, r.nav.session.active)
        assertTrue(r.nav.session.visited.isEmpty())
    }

    /** Same risk at the start of a round trip: begun 80 m beside the line near its END – progress is the start. */
    @Test fun roundTripStartedBesideTheEndCountsFromTheStart() {
        val loop = line(0.0 to 0.0, 1_000.0 to 0.0, 1_000.0 to 1_000.0, 0.0 to 1_000.0, 0.0 to 0.0)
        val nav = Navigator(NavSession.start(NavKind.PLAN, "Loop", Activity.HIKE, 2, loop,
            listOf(pt(0.0, 0.0), pt(1_000.0, 0.0), pt(1_000.0, 1_000.0), pt(0.0, 0.0))))
        val r = Run(nav)
        // nearest line part = the end (x = 0, along ~3.9 km), 70–80 m beside it, ~130 m from the start
        r.feed((0 until 60).map { i -> fix(-80.0 + if (i % 2 == 0) 0.0 else 10.0, 100.0 + if (i % 3 == 0) 10.0 else -10.0, i * 1000L) })
        assertTrue(r.steps.none { it.vibrate })
        assertTrue("no off-route question near the end", r.jobs.isEmpty())
        assertTrue(nav.prompt is NavPrompt.FarStart)
        assertEquals(0.0, nav.detector.progressM, 0.0)
        assertEquals(0.0, nav.detector.leftAtM, 0.0)
        // walking to the start and along the route: counted from the start, the question goes
        r.feed(walk(-80.0 to 100.0, 0.0 to 0.0, 400.0 to 0.0, t0Ms = 100_000))
        assertNull(nav.prompt)
        assertTrue(nav.session.reached)
        assertEquals(400.0, nav.detector.progressM, 30.0)
    }

    @Test fun justRecordStaysQuietUntilTheRouteIsReachedThenTheNormalFlow() {
        val r = Run(Navigator(session()))
        r.feed(drift(-3_000.0))
        val p = r.nav.prompt as NavPrompt.FarStart
        val s = r.nav.justRecord(p.id)
        assertFalse(s.vibrate)
        assertNull(r.nav.prompt)
        // riding towards the route: quiet (no new far-start question, no off-route question)
        r.steps.clear()
        r.feed(walk(-3_000.0 to 0.0, 0.0 to 0.0, speed = 5.0, t0Ms = 100_000))
        assertTrue(r.steps.none { it.vibrate })
        assertTrue(r.jobs.isEmpty())
        // on the route, then off it: the off-route flow as before (vibration, way back from where one left)
        r.feed(walk(0.0 to 0.0, 1_000.0 to 0.0, 1_000.0 to 300.0, speed = 5.0, t0Ms = 1_000_000))
        assertTrue(r.nav.session.reached)
        assertEquals(1, r.steps.count { it.vibrate })
        assertEquals(NavPrompt.Searching, r.nav.prompt)
        val job = r.jobs.single()
        assertFalse(job.request.toStart)
        assertEquals(1_000.0, job.request.progressM, 60.0)
    }

    @Test fun guideMeWithoutAWayIsARedNoteAndThenQuiet() {
        val r = Run(Navigator(session()))
        r.feed(drift(-30_000.0))
        val p = r.nav.prompt as NavPrompt.FarStart
        val job = r.nav.leadThere(p.id).job!!
        r.nav.onResult(job.id, Rerouter.Failed(Rerouter.Reason.NO_WAY, "x", 5))
        val e = r.nav.prompt as NavPrompt.LeadFailed
        assertEquals("No way to the route found – recording continues", NavText.title(e))
        r.nav.leadFailedOk(e.id)
        assertNull(r.nav.prompt)
        r.feed(drift(-30_000.0, t0 = 200_000))
        assertNull("quiet after OK", r.nav.prompt)
    }

    @Test fun reachingTheRouteClearsTheQuestion() {
        val r = Run(Navigator(session()))
        r.feed(drift(-500.0))
        assertTrue(r.nav.prompt is NavPrompt.FarStart)
        r.feed(walk(-500.0 to 0.0, 0.0 to 0.0, 200.0 to 0.0, t0Ms = 100_000))
        assertNull(r.nav.prompt)
        assertTrue(r.steps.any { it.sessionChanged }) // "reached" is saved with the session
        assertTrue(r.nav.session.reached)
        assertTrue(r.steps.none { it.vibrate })
    }

    @Test fun planModeAsksNothingAndContinueAsksAtOnce() {
        val nav = Navigator(session())
        nav.setNavigating(false)
        val r = Run(nav)
        r.feed(drift(-30_000.0))
        assertNull(nav.prompt)
        val s = nav.setNavigating(true)
        assertFalse(s.vibrate); assertNull(s.job)
        assertTrue(nav.prompt is NavPrompt.FarStart)
        // "Continue navigation" far away: the far-start question at once, never an off-route job
        val n2 = Navigator(session())
        n2.checkNow()
        val s2 = n2.onFix(fix(-30_000.0, 0.0, 0))
        assertNull(s2.job); assertFalse(s2.vibrate)
        assertTrue(n2.prompt is NavPrompt.FarStart)
    }

    /** "Reached the route" survives an app / phone restart: then far away = off route as before (nearest rejoin). */
    @Test fun reachedIsPersistedAcrossARestart() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 500.0 to 0.0, speed = 5.0))
        assertTrue(r.nav.session.reached)
        val back = NavSessionStore.fromJson(JSONObject(NavSessionStore.toJson(r.nav.session).toString()))!!
        assertTrue(back.reached)
        val n2 = Run(Navigator(back))
        n2.feed(walk(500.0 to 400.0, 900.0 to 400.0, speed = 5.0, t0Ms = 5_000_000))
        assertTrue(n2.nav.detector.reached)
        assertEquals(1, n2.steps.count { it.vibrate })
        assertEquals(NavPrompt.Searching, n2.nav.prompt)
        assertFalse(n2.jobs.single().request.toStart)
        // a new session is not reached; an older file without the key (written during a tour) counts as reached
        assertFalse(NavSessionStore.fromJson(JSONObject(NavSessionStore.toJson(session()).toString()))!!.reached)
        val old = NavSessionStore.toJson(session()).apply { remove("reached") }
        assertTrue(NavSessionStore.fromJson(JSONObject(old.toString()))!!.reached)
    }

    @Test fun farStartNumbersFollowTheRiderWithoutANewQuestion() {
        val r = Run(Navigator(session()))
        r.feed(drift(-30_000.0))
        val p1 = r.nav.prompt as NavPrompt.FarStart
        r.feed(walk(-30_000.0 to 0.0, -25_000.0 to 0.0, speed = 8.0, t0Ms = 100_000))
        val p2 = r.nav.prompt as NavPrompt.FarStart
        assertEquals(p1.id, p2.id)
        assertEquals(25_000.0, p2.distanceM, 50.0)
        assertTrue(r.steps.none { it.vibrate })
    }
}
