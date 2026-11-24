package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWriter
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
 * GPS lost in the middle of a tour, then back (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md, test 1)
 * – the whole tour, second by second on a virtual clock: a hiking plan followed, ten good fixes, ten minutes without
 * any, fixes again 300 m further on, the end. The service is [FakeRecording] with its real core pieces (PointFilter,
 * TourRun, PointQueue, GpsWatchdog, "lost" by RestWatch) and its navigation [FakeNavService] (the real Navigator); the
 * voice is the real [VoiceGuide] fed as the service's `voiceStep` feeds it; the app side is [TourController].
 * What the user is told is what Navigate reads: `RecState.gpsLost` with `gpsQuiet` (the grey line, then the amber warning).
 *
 * Not proven here: the screen itself (NavigateScreen), the motion sensor (none = MoveWatch), the notification text.
 */
class GpsLostFlowTest {
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
    /** What the voice said: (ms since the start, phrase). */
    private val said = ArrayList<Pair<Long, Phrase>>()
    private var t0 = 0L
    /** The state after every second, with the ms since the start. */
    private val seen = ArrayList<Pair<Long, RecState>>()
    /** Every fix sent, in order, with its time. */
    private val walked = ArrayList<TrackPoint>()

    /** A hiking plan of 2 km due east, followed from its start (the app writes the session, the service reads it). */
    private fun startWithPlan() {
        val plan = line(0.0 to 0.0, 2000.0 to 0.0)
        NavSessionStore(File(tmp.root, NavSession.FILE)).save(
            NavSession.start(NavKind.PLAN, "Waalweg", Activity.HIKE, 3, plan, listOf(plan.first(), plan.last())).copy(token = 1))
        t0 = now
        tour.start("HIKE", 3)
        nav.settle()
    }

    /** One second: the service's tick, a fix when [x] is given (metres east on the plan), its navigation, the voice. */
    private fun second(x: Double? = null) {
        now += 1000
        rec.tick()
        if (x != null) { val p = pt(x, 0.0); rec.fix(p); walked += TrackPoint(p.lat, p.lon, null, now) }
        nav.settle()
        voiceStep()
        seen += (now - t0) to rec.state.value!!
    }

    /** `RecordingService.voiceStep` with a voice on: the navigation's state, "GPS lost" and standing still as the service has them. */
    private fun voiceStep() {
        val st = nav.navigator?.state ?: return
        val s = st.session
        val r = rec.state.value ?: return
        voice.update(VoiceGuide.Input(nowMs = now, line = st.line, version = st.version, alongM = st.shownAlongM,
            onRoute = st.prompt?.offRoute != true && st.shownAlongM != null, speedMps = null, bike = s?.activity != Activity.HIKE,
            offRoute = st.prompt?.offRoute == true, rerouted = s?.rerouted == true, paused = r.paused, gpsLost = r.gpsLost,
            stops = s?.stops.orEmpty(), visited = s?.visited.orEmpty(), quiet = rec.standingStill()))?.let { said += (now - t0) to it }
    }

    /** [n] fixes [stepM] apart, one every 5 s (a walker at 1.4 m/s), from [fromX]; returns where the last one was. */
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

    @Test fun gpsLostForTenMinutesThenBackThreeHundredMetresOn() {
        startWithPlan()
        val id = rec.state.value!!.tourId
        val endA = walk(0.0, 10)
        val beforeGap = rec.state.value!!
        assertEquals("ten good fixes, one piece", listOf(10), pieces(beforeGap.track))
        assertEquals(63.0, beforeGap.distanceM, 0.5)
        assertTrue("walking: GPS never lost", seen.none { it.second.gpsLost })
        assertTrue("the start sentence was said", said.any { it.second is Phrase.Start })
        val lastFixAt = now - t0

        // ten minutes without a single fix
        val gapFrom = seen.size
        silence(600)
        val gap = seen.subList(gapFrom, seen.size).map { (at, s) -> (at - lastFixAt) to s }
        assertTrue("a few seconds without a fix are no news", gap.filter { it.first <= 8_000 }.none { it.second.gpsLost })
        assertTrue("then Navigate knows: GPS lost", gap.filter { it.first > 8_000 }.all { it.second.gpsLost })
        assertTrue("first the quiet grey line (a short gap)", gap.filter { it.first < VoiceGuide.GPS_LOST_MS }.none { warning(it.second) })
        assertTrue("after 2½ min the warning, until the end of the gap",
            gap.filter { it.first >= VoiceGuide.GPS_LOST_MS }.all { warning(it.second) })
        assertTrue("no off-route alarm without GPS", gap.none { it.second.offRouteAlert })
        assertNull(nav.navigator!!.prompt)
        assertTrue("no re-route asked", engine.calls.isEmpty())
        assertEquals("the track stays as it was", listOf(10), pieces(rec.state.value!!.track))
        assertEquals("nothing counted while lost", beforeGap.distanceM, rec.state.value!!.distanceM, 0.0)
        val noGps = said.filter { it.second == Phrase.NoGps }
        assertEquals("\"No GPS signal\" said once", 1, noGps.size)
        assertTrue("and not before 2½ min", noGps.single().first - lastFixAt >= VoiceGuide.GPS_LOST_MS)
        assertEquals("a lost signal in a running tour is not the restart watchdog", 0, rec.watchdogAlarms)

        // fixes again, 300 m further on along the plan
        val backFrom = seen.size
        val endB = walk(endA + 300.0, 10)
        val back = seen[backFrom].second
        assertFalse("the first fix ends \"GPS lost\" at once", back.gpsLost)
        assertFalse("and the warning", warning(back))
        assertTrue(seen.subList(backFrom, seen.size).none { it.second.gpsLost })
        assertEquals("a new piece after the long gap", listOf(10, 10), pieces(rec.state.value!!.track))
        assertEquals("no 300 m jump in the distance", 126.0, rec.state.value!!.distanceM, 1.0)
        assertTrue("no off-route alarm when GPS is back", seen.subList(backFrom, seen.size).none { it.second.offRouteAlert })
        assertNull(nav.navigator!!.prompt)
        assertTrue("no re-route", engine.calls.isEmpty())
        assertEquals("the navigation picks up where the walker is", endB, nav.navigator!!.state.shownAlongM!!, 15.0)
        assertEquals("\"No GPS signal\" only the once", 1, said.count { it.second == Phrase.NoGps })

        // the end
        tour.end(delete = false); tour.stop()
        assertEquals(id, rec.finished)
        val saved = tours.getTour(id)!!
        val segs = tours.loadSegments(id)
        assertEquals("done", saved.state)
        assertEquals("the saved tour: two pieces, every fix in order", listOf(walked.take(10), walked.drop(10)), segs)
        assertEquals("its distance is its two pieces, nothing across the gap", length(segs), saved.distanceM, 0.5)
        assertEquals("its time is the whole tour, the gap included", (now - t0) / 1000.0, saved.durationS, 1.0)
        val gpx = GpxParser.parse(GpxWriter.write(saved.name, segs))
        assertEquals("the GPX has both pieces whole", listOf(10, 10), pieces(gpx.allSegments))
        assertEquals(walked.map { it.timeMs }, gpx.allSegments.flatten().map { it.timeMs })
        assertTrue(problems.none("recording"))
    }

    /** The other side of the rule (PointFilter.bridgesGap): two minutes under trees, walked on – one line, the metres count. */
    @Test fun aShortGapIsBridgedAndCounted() {
        startWithPlan()
        val endA = walk(0.0, 10)
        val lastFixAt = now - t0
        val gapFrom = seen.size
        silence(119)
        val gap = seen.subList(gapFrom, seen.size).map { (at, s) -> (at - lastFixAt) to s }
        assertTrue(gap.last().second.gpsLost)
        assertTrue("under 2½ min only the grey line, no warning, nothing said", gap.none { warning(it.second) })
        assertTrue(said.none { it.second == Phrase.NoGps })
        walk(endA + 160.0, 10)
        assertEquals("one line, kept connected", listOf(20), pieces(rec.state.value!!.track))
        assertEquals("the 160 m under the trees count", 63.0 + 160.0 + 63.0, rec.state.value!!.distanceM, 1.0)
        assertNull(nav.navigator!!.prompt)
        assertTrue(engine.calls.isEmpty())
    }
}
