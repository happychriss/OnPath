package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.Phrase
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.VoiceGuide
import com.onpath.app.navigation.offRoute
import com.onpath.app.recording.RecState
import com.onpath.app.routing.Activity
import com.onpath.app.setup.LocationSaverMode
import com.onpath.app.testkit.FakeNavService
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Battery saver comes on in the middle of a tour (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md):
 * on many phones it stops GPS while the screen is off ([LocationSaverMode.GPS_OFF_SCREEN_OFF]). A hiking plan followed,
 * ten good fixes, the saver comes on and the fixes stop for ten minutes, the saver goes off and fixes come back 300 m
 * further on, then the end. Built like [GpsLostFlowTest] (virtual clock, [FakeRecording] with the real core pieces,
 * the real Navigator and VoiceGuide).
 * What the user is told: one notification per tour ([FakeRecording.saverNotices]); during the silence the same as for
 * any lost GPS (`gpsLost`, then the warning, no off-route alarm, no arrival).
 *
 * Not proven here: the notification text, the receiver itself (Android), the screen.
 */
class BatterySaverFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private val tours = JdbcTours()
    private val engine = FakeRoutingEngine()
    private val nav by lazy { FakeNavService(File(tmp.root, NavSession.FILE), engine) }
    private val rec by lazy { FakeRecording(tours, { now }, nav) }
    private val tour by lazy { TourController(rec) }
    private val voice = VoiceGuide()
    private val said = ArrayList<Pair<Long, Phrase>>()
    private var t0 = 0L
    private val seen = ArrayList<Pair<Long, RecState>>()
    private val walked = ArrayList<TrackPoint>()

    private fun startWithPlan(token: Long = 1) {
        val plan = line(0.0 to 0.0, 2000.0 to 0.0)
        NavSessionStore(File(tmp.root, NavSession.FILE)).save(
            NavSession.start(NavKind.PLAN, "Waalweg", Activity.HIKE, 3, plan, listOf(plan.first(), plan.last())).copy(token = token))
        t0 = now
        tour.start("HIKE", 3)
        nav.settle()
    }

    private fun second(x: Double? = null) {
        now += 1000
        rec.tick()
        if (x != null) { val p = pt(x, 0.0); rec.fix(p); walked += TrackPoint(p.lat, p.lon, null, now) }
        nav.settle()
        val st = nav.navigator?.state
        val r = rec.state.value
        if (st != null && r != null) {
            val s = st.session
            voice.update(VoiceGuide.Input(nowMs = now, line = st.line, version = st.version, alongM = st.shownAlongM,
                onRoute = st.prompt?.offRoute != true && st.shownAlongM != null, speedMps = null, bike = s?.activity != Activity.HIKE,
                offRoute = st.prompt?.offRoute == true, rerouted = s?.rerouted == true, paused = r.paused, gpsLost = r.gpsLost,
                stops = s?.stops.orEmpty(), visited = s?.visited.orEmpty(), quiet = rec.standingStill()))?.let { said += (now - t0) to it }
        }
        seen += (now - t0) to rec.state.value!!
    }

    private fun walk(fromX: Double, n: Int, stepM: Double = 7.0): Double {
        for (i in 0 until n) {
            if (i > 0) repeat(4) { second() }
            second(fromX + i * stepM)
        }
        return fromX + (n - 1) * stepM
    }

    private fun silence(s: Int) = repeat(s) { second() }
    private fun pieces(segs: List<List<Any>>) = segs.map { it.size }
    private fun length(segs: List<List<TrackPoint>>) = segs.sumOf { s -> Geo.length(s.map { it.toGeo() }) }
    private val warning: (RecState) -> Boolean = { it.gpsLost && !it.gpsQuiet }
    private fun saverOn() = rec.batterySaverChanged(true, LocationSaverMode.GPS_OFF_SCREEN_OFF)
    private fun saverLines() = problems.said("recording").count { it.contains("Battery saver") }

    @Test fun saverStopsGpsForTenMinutesThenBackOnce() {
        startWithPlan()
        val id = rec.state.value!!.tourId
        val endA = walk(0.0, 10)
        assertEquals("no saver, no news", 0, rec.saverNotices)
        val beforeGap = rec.state.value!!
        assertEquals(63.0, beforeGap.distanceM, 0.5)

        saverOn()
        assertEquals("told at once, loud", 1, rec.saverNotices)
        assertEquals("and logged", 1, saverLines())
        val lastFixAt = now - t0
        saverOn() // the system sends the change again (the level moves): not said twice
        assertEquals("said once per tour", 1, rec.saverNotices)
        assertEquals("the recording is untouched by the news", beforeGap.distanceM, rec.state.value!!.distanceM, 0.0)

        val gapFrom = seen.size
        silence(600)
        val gap = seen.subList(gapFrom, seen.size).map { (at, s) -> (at - lastFixAt) to s }
        assertTrue("then Navigate knows: GPS lost", gap.filter { it.first > 8_000 }.all { it.second.gpsLost })
        assertTrue("first the quiet line", gap.filter { it.first < VoiceGuide.GPS_LOST_MS }.none { warning(it.second) })
        assertTrue("then the warning", gap.filter { it.first >= VoiceGuide.GPS_LOST_MS }.all { warning(it.second) })
        assertTrue("no off-route alarm without GPS", gap.none { it.second.offRouteAlert })
        assertTrue("no false arrival", gap.none { it.second.arrived })
        assertNull(nav.navigator!!.prompt)
        assertTrue("no re-route asked", engine.calls.isEmpty())
        assertEquals("the points before are kept", listOf(10), pieces(rec.state.value!!.track))
        assertEquals("nothing counted while silent", beforeGap.distanceM, rec.state.value!!.distanceM, 0.0)
        assertEquals("\"No GPS signal\" said once", 1, said.count { it.second == Phrase.NoGps })
        assertEquals("not the restart watchdog", 0, rec.watchdogAlarms)

        // the saver goes off, fixes come back 300 m on
        rec.batterySaverChanged(false, LocationSaverMode.GPS_OFF_SCREEN_OFF)
        val backFrom = seen.size
        walk(endA + 300.0, 10)
        assertFalse("the first fix ends \"GPS lost\"", seen[backFrom].second.gpsLost)
        assertEquals("a new piece after the long gap", listOf(10, 10), pieces(rec.state.value!!.track))
        assertEquals("no 300 m jump in the distance", 126.0, rec.state.value!!.distanceM, 1.0)
        assertTrue("no off-route alarm", seen.subList(backFrom, seen.size).none { it.second.offRouteAlert })
        assertTrue(engine.calls.isEmpty())
        saverOn() // on again in the same tour: still only once
        assertEquals("not a second time in the same tour", 1, rec.saverNotices)

        tour.end(delete = false); tour.stop()
        val segs = tours.loadSegments(id)
        assertEquals("every fix saved, two pieces", listOf(walked.take(10), walked.drop(10)), segs)
        assertEquals(length(segs), tours.getTour(id)!!.distanceM, 0.5)
        assertEquals("done", tours.getTour(id)!!.state)

        // the next tour says it again
        startWithPlan(token = 2L)
        walk(0.0, 3)
        saverOn()
        assertEquals("a new tour, a new warning", 2, rec.saverNotices)
    }

    @Test fun aSaverThatKeepsGpsOrIsOffIsNoNews() {
        startWithPlan()
        walk(0.0, 3)
        rec.batterySaverChanged(true, LocationSaverMode.FOREGROUND_ONLY)
        rec.batterySaverChanged(true, LocationSaverMode.NO_CHANGE)
        rec.batterySaverChanged(true, null) // below API 28
        rec.batterySaverChanged(false, LocationSaverMode.ALL_OFF_SCREEN_OFF)
        assertEquals(0, rec.saverNotices)
        rec.batterySaverChanged(true, LocationSaverMode.ALL_OFF_SCREEN_OFF)
        assertEquals("the other GPS-stopping mode counts", 1, rec.saverNotices)
    }

    @Test fun noTourNoWarning() {
        rec.batterySaverChanged(true, LocationSaverMode.GPS_OFF_SCREEN_OFF)
        assertEquals(0, rec.saverNotices)
    }
}
