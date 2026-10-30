package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class NavigatorTest {
    /** East 1 km, then north 1 km; stops at the start, the corner and the end. */
    private val route = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0)
    private fun session() = NavSession.start(NavKind.PLAN, "Test", Activity.HIKE, 3, route,
        listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 1000.0)))

    private class Run(val nav: Navigator) {
        val steps = mutableListOf<Navigator.Step>()
        val jobs get() = steps.mapNotNull { it.job }
        fun feed(fixes: List<OffRouteDetector.Fix>) { fixes.forEach { steps += nav.onFix(it) } }
    }

    /** A found way back: from the position back to the corner and on along the route. */
    private fun found(from: Pair<Double, Double>, extra: Double = 120.0): Rerouter.Found {
        val leg = line(from, 1000.0 to 0.0, 1000.0 to 200.0)
        val orig = NavLine(route)
        val resume = 1200.0
        val l = RerouteTargets.splice(leg, orig, resume)
        return Rerouter.Found(l, com.onpath.app.data.Geo.length(leg), resume, extra, 110.0, 5.0, null, emptySet(), 2, 15)
    }

    @Test fun missedTurnVibratesAndAsks() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        assertEquals(1, r.steps.count { it.vibrate })
        assertEquals(NavPrompt.Searching, r.nav.prompt)
        val job = r.jobs.single()
        assertEquals(1000.0, job.request.progressM, 50.0)
        // the corner stop was visited (passed): it is in the request
        assertTrue(1 in job.request.visited)
        r.nav.onResult(job.id, found(1150.0 to 0.0))
        val q = r.nav.prompt as NavPrompt.Question
        assertEquals(120.0, q.extraM, 0.1)
        assertNotNull(r.nav.state.toRoute)
    }

    /** Rode past the corner: the way back starts westwards, against the ride – "turn round" + the sign. */
    @Test fun wayBackAgainstTheRideIsATurnRoundWithASign() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        val q = r.nav.prompt as NavPrompt.Question
        assertTrue(q.turnRound)
        assertEquals(q.line.first(), r.nav.state.uTurnAt)
        r.nav.take(q.id)
        assertNotNull("sign stays after taking it, until turned", r.nav.state.uTurnAt)
        r.feed(walk(1150.0 to 0.0, 1080.0 to 0.0, t0Ms = 3_000_000L))
        assertNull("turned and on the way: sign gone", r.nav.state.uTurnAt)
    }

    @Test fun wayBackAheadIsNoTurnRound() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        // a way on eastwards, away from where one came from
        val leg = line(1150.0 to 0.0, 1300.0 to 0.0, 1300.0 to 200.0, 1000.0 to 200.0)
        val l = RerouteTargets.splice(leg, NavLine(route), 1200.0)
        r.nav.onResult(r.jobs.last().id, Rerouter.Found(l, com.onpath.app.data.Geo.length(leg), 1200.0, 300.0, 110.0, 5.0, null, emptySet(), 2, 15))
        assertFalse((r.nav.prompt as NavPrompt.Question).turnRound)
        assertNull(r.nav.state.uTurnAt)
    }

    @Test fun questionStaysUntilAnsweredThenTakeNewRoute() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        // ten minutes standing there: still asking (no timeout)
        r.feed((0 until 600).map { fix(1150.0, 0.0, 2_000_000L + it * 1000) })
        val q = r.nav.prompt as NavPrompt.Question
        val v0 = r.nav.session.version
        val s = r.nav.take(q.id)
        assertTrue(s.lineChanged)
        assertNull(r.nav.prompt)
        assertTrue(r.nav.session.rerouted)
        assertEquals(v0 + 1, r.nav.session.version)
        assertNotNull(r.nav.state.original)
        // a stale id does nothing
        assertFalse(r.nav.take(q.id).lineChanged)
    }

    /** "Off route – re-plan?" (StopSkip): "Yes, skip Café" takes the line without it and it stays out; "Yes" takes the line via it. */
    @Test fun skipQuestionYesLeavesTheStopOutNoGoesViaIt() {
        fun start() = Run(Navigator(NavSession.start(NavKind.PLAN, "Test", Activity.BIKE_TOURING, 3, route,
            listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 500.0), pt(1000.0, 1000.0)), names = listOf(null, null, "Café", null))))
        val skip = found(1150.0 to 0.0).copy(skipStop = 2)
        val via = found(1150.0 to 0.0, extra = 900.0)
        for (yes in listOf(true, false)) {
            val r = start()
            r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
            r.nav.onResult(r.jobs.last().id, skip.copy(ask = via))
            val q = r.nav.prompt as NavPrompt.Question
            assertEquals(2, q.skipStop)
            assertEquals("Café", q.skipName)
            assertNotNull(q.via)
            assertEquals("Off route – re-plan?", NavText.title(q))
            assertTrue(NavText.question(q), NavText.question(q).startsWith("Via Café: +780 m · rejoins in "))
            val st = if (yes) r.nav.take(q.id) else r.nav.takeVia(q.id)
            assertTrue(st.lineChanged)
            assertTrue(r.nav.session.rerouted)
            assertEquals(yes, 2 in r.nav.session.visited)
            // the next re-route never sends back to a stop left out on purpose
            assertEquals(yes, 2 in RerouteTargets.passed(r.nav.session.stops, 0.0, r.nav.session.visited))
        }
        // left out without asking: the card says so
        val r = start()
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, skip)
        val q = r.nav.prompt as NavPrompt.Question
        assertEquals("Off route – turn round, new route found", NavText.title(q))
        assertTrue(NavText.question(q), NavText.question(q).endsWith(" · without Café"))
        assertFalse(r.nav.takeVia(q.id).lineChanged) // nothing to answer "No" to
    }

    @Test fun backOnTheNewRouteThenOriginalIsUsedAgain() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        r.nav.take((r.nav.prompt as NavPrompt.Question).id)
        // walk the new line back to the corner and north: back on the original → grey line gone
        r.steps.clear()
        r.feed(walk(1150.0 to 0.0, 1000.0 to 0.0, 1000.0 to 300.0, t0Ms = 3_000_000))
        assertTrue(r.steps.any { it.lineChanged })
        assertFalse(r.nav.session.rerouted)
        assertNull(r.nav.state.original)
        assertTrue(r.steps.none { it.vibrate })
        assertEquals(1300.0, r.nav.detector.progressM, 30.0)
    }

    @Test fun backOnRouteByYourselfClearsTheQuestion() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        r.steps.clear()
        r.feed(walk(1150.0 to 0.0, 1000.0 to 0.0, 1000.0 to 100.0, t0Ms = 3_000_000))
        assertNull(r.nav.prompt)
        assertTrue(r.steps.any { it.cancelJob })
        assertFalse(r.nav.session.rerouted)
    }

    /**
     * A-015: the card said "+80 m · new route 6.2 km" while the rider walked on from 77 to 183 m away. The numbers follow
     * the rider – same question (id, line), no vibration, no routing job – and walking back makes them smaller again.
     */
    @Test fun openQuestionNumbersFollowTheRider() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0).copy(rejoinInM = Double.NaN))
        val q1 = r.nav.prompt as NavPrompt.Question
        r.steps.clear()
        // 110 m further away (a re-computation may start meanwhile; its result is not delivered here)
        r.feed(walk(1150.0 to 0.0, 1260.0 to 0.0, t0Ms = 900_000))
        val q2 = r.nav.prompt as NavPrompt.Question
        assertEquals(q1.id, q2.id)
        assertEquals(q1.line, q2.line)
        assertEquals(q1.legM, q2.legM, 0.0)
        assertEquals(110.0, q2.movedM - q1.movedM, 20.0)
        assertFalse(r.steps.any { it.vibrate })
        val extra = { q: NavPrompt.Question -> Regex("^\\+(\\d+) m").find(NavText.question(q))!!.groupValues[1].toInt() }
        assertEquals(110.0, (extra(q2) - extra(q1)).toDouble(), 20.0)
        assertTrue(NavText.question(q2), NavText.question(q2).contains("new route"))
        // GPS noise standing still: at most one catch-up, then the numbers do not flicker
        val noise = { t0: Long -> (0 until 20).map { fix(1260.0 + (if (it % 2 == 0) 8.0 else -8.0), if (it % 3 == 0) 6.0 else -6.0, t0 + it * 1000) } }
        r.feed(noise(1_000_000L))
        val held = r.nav.prompt
        r.feed(noise(1_020_000L))
        assertTrue(held === r.nav.prompt)
        // walking back: smaller again; "Take new route" still takes the computed line
        r.feed(walk(1260.0 to 0.0, 1150.0 to 0.0, t0Ms = 1_100_000))
        val q3 = r.nav.prompt as NavPrompt.Question
        assertEquals(q1.movedM, q3.movedM, 20.0)
        r.nav.take(q3.id)
        assertEquals(q1.line, r.nav.session.active)
    }

    /**
     * Owner 2026-10-30: the open question is recomputed every 150 m; the first refresh vibrates once more (a second
     * reminder), later ones of the same off-route episode update the card silently. Back on the route → a new episode
     * vibrates again.
     */
    @Test fun onlyTheFirstRefreshOfAnEpisodeVibrates() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        assertEquals(1, r.steps.count { it.vibrate })
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        var x = 1150.0
        var t = 1_000_000L
        /** Walks on 160 m (one refresh) and delivers its result: did it vibrate? */
        fun refresh(): Boolean {
            r.steps.clear()
            r.feed(walk(x to 0.0, x + 160.0 to 0.0, t0Ms = t)); x += 160.0; t += 200_000L
            val job = r.jobs.single()
            val s = r.nav.onResult(job.id, found(x to 0.0))
            assertTrue(r.nav.prompt is NavPrompt.Question)
            return s.vibrate || r.steps.any { it.vibrate }
        }
        assertTrue("1st refresh: second reminder", refresh())
        assertFalse("2nd refresh: silent", refresh())
        assertFalse("3rd refresh: silent", refresh())
        // back on the route by yourself: the episode ends; leaving again vibrates, and its first refresh too
        r.steps.clear()
        r.feed(walk(x to 0.0, 1000.0 to 0.0, 1000.0 to 300.0, t0Ms = t)); t += 2_000_000L
        assertNull(r.nav.prompt)
        r.feed(walk(1000.0 to 300.0, 1150.0 to 300.0, t0Ms = t)); t += 200_000L
        assertEquals("new episode vibrates", 1, r.steps.count { it.vibrate })
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 300.0))
        x = 1150.0
        r.steps.clear()
        r.feed(walk(x to 300.0, x + 160.0 to 300.0, t0Ms = t)); x += 160.0; t += 200_000L
        assertTrue("1st refresh of the new episode vibrates", r.nav.onResult(r.jobs.single().id, found(x to 300.0)).vibrate)
    }

    @Test fun keepOriginalShowsTheArrowAndAsksAgainOnlyMuchLater() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        r.nav.keep((r.nav.prompt as NavPrompt.Question).id)
        assertEquals(NavPrompt.Kept, r.nav.prompt)
        val t = r.nav.state.toRoute!!
        assertTrue(t.distanceM > 100)
        // 1 min further on: no new question
        r.steps.clear()
        r.feed(walk(1150.0 to 0.0, 1150.0 to 80.0, t0Ms = 1_000_000, speed = 1.3))
        assertTrue(r.jobs.isEmpty())
        // after 2 min and 200 m: asked again
        r.feed(walk(1150.0 to 80.0, 1400.0 to 80.0, t0Ms = 1_200_000))
        assertEquals(1, r.jobs.size)
        val s = r.nav.onResult(r.jobs.last().id, found(1400.0 to 80.0))
        assertTrue("question after a re-ask vibrates", s.vibrate)
    }

    @Test fun noWayFoundShowsArrowAndRetriesAfterMoving() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, Rerouter.Failed(Rerouter.Reason.NO_WAY, "x", 5))
        assertEquals(NavPrompt.NoWay(Rerouter.Reason.NO_WAY), r.nav.prompt)
        assertNotNull(r.nav.state.toRoute)
        r.steps.clear()
        // the first walk ended at ~885 s: 77 s and 100 m later no retry yet
        r.feed(walk(1150.0 to 0.0, 1150.0 to 100.0, t0Ms = 890_000))
        assertTrue(r.jobs.isEmpty())
        r.feed(walk(1150.0 to 100.0, 1150.0 to 250.0, t0Ms = 968_000))
        assertEquals(1, r.jobs.size)
    }

    @Test fun staleResultIsIgnored() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id + 7, found(1150.0 to 0.0))
        assertEquals(NavPrompt.Searching, r.nav.prompt)
    }

    @Test fun planModeAsksWhenBackInNavigate() {
        val n = Navigator(session())
        n.setNavigating(false)
        val r = Run(n)
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        assertNull(n.prompt)
        val s = n.setNavigating(true)
        assertTrue(s.vibrate); assertNotNull(s.job)
    }

    @Test fun warningOffNeverAsks() {
        val n = Navigator(session()).apply { enabled = false }
        val r = Run(n)
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1300.0 to 0.0))
        assertNull(n.prompt); assertTrue(r.jobs.isEmpty())
    }

    @Test fun continueNavigationAwayFromTheRouteAsksAtOnce() {
        val n = Navigator(session())
        n.checkNow()
        val s = n.onFix(fix(500.0, 300.0, 0))
        assertNotNull(s.job)
        assertFalse(s.vibrate) // the user is looking at the screen
        assertEquals(NavPrompt.Searching, n.prompt)
    }

    @Test fun sessionSurvivesJson() {
        val r = Run(Navigator(session()))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 1150.0 to 0.0))
        r.nav.onResult(r.jobs.last().id, found(1150.0 to 0.0))
        r.nav.take((r.nav.prompt as NavPrompt.Question).id)
        val s = r.nav.session
        val back = NavSessionStore.fromJson(JSONObject(NavSessionStore.toJson(s).toString()))!!
        assertEquals(s.copy(original = back.original, current = back.current), back)
        assertEquals(s.original.size, back.original.size)
        assertEquals(s.current!!.size, back.current!!.size)
        assertEquals(s.current!!.last().lat, back.current!!.last().lat, 1e-9)
        // after a restart the navigator continues on the new line, the original is still known
        val n2 = Navigator(back)
        assertTrue(n2.session.rerouted)
        assertNotNull(n2.state.original)
    }

    @Test fun sessionKeepsWayAttributes() {
        val w1 = com.onpath.app.routing.WayAttrs("track", "gravel")
        val w2 = com.onpath.app.routing.WayAttrs("path", "alpine", sac = 4, visibility = "bad")
        val pts = (0..9).map { i -> com.onpath.app.routing.GeoPoint(46.0 + i * 0.001, 11.0, 500.0, if (i < 4) w1 else if (i < 8) w2 else null) }
        val s = NavSession.start(NavKind.PLAN, "x", Activity.HIKE, 3, pts)
        val back = NavSessionStore.fromJson(JSONObject(NavSessionStore.toJson(s).toString()))!!
        assertEquals(pts.map { it.way }, back.original.map { it.way })
        // old files without the way runs load as before
        val old = NavSessionStore.toJson(s).apply { remove("originalWays") }
        assertTrue(NavSessionStore.fromJson(JSONObject(old.toString()))!!.original.all { it.way == null })
    }
}
