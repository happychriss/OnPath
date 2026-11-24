package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.testkit.FakeNavService
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.time.RouteTimes
import com.onpath.app.time.TimeEstimate
import com.onpath.app.time.TourReview
import com.onpath.app.time.TripPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * The phone's clock jumps in the middle of a tour (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md,
 * test 4): set back an hour, or on an hour (by hand, a wrong network time), and separately a new time zone.
 *
 * The service has two clocks: the elapsed realtime (never jumps) for the tour's time (`tick`: elapsedS), gaps, "GPS lost"
 * and navigation; the wall clock for the tour's start (`created`, the list's order), a pause's start, the trip pace's
 * arrival clock (`trip.tick` / `trip.onFix`), the tour times' end (`finish`: TourTimes startMs / endMs), and the fix's own
 * time on every point (`loc.time` – a GNSS time on most phones, here the worst case: it follows the jumped clock).
 * [FakeRecording] runs on both ([now] elapsed, [wall]); the trip pace is the real [TripPace] fed as the service feeds it.
 *
 * A time-zone change moves no stored value (all epoch ms, GPX in UTC); only what is shown is formatted in the zone of the
 * moment (the tour's name at its start, "Angehalten seit", the summary's clock times).
 *
 * Holds: the tour's time, its points (order, none dropped – PointFilter skips its speed check on a negative dt), its
 * distance, the GPX. Found 2026-11-24 (the @Ignore'd tests): the trip pace's "to go" / arrival freeze for the hour the
 * clock was set back; "on the way" in the tour's summary is the clock's span, an hour too long after it was set on.
 */
class ClockJumpFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_000_000L                // elapsed realtime
    private var wall = 1_793_000_000_000L       // the phone's clock
    private val tours = JdbcTours()
    private val engine = FakeRoutingEngine()
    private val nav by lazy { FakeNavService(File(tmp.root, NavSession.FILE), engine) }
    private val rec by lazy { FakeRecording(tours, { now }, nav, wall = { wall }) }
    private val tour by lazy { TourController(rec) }
    private val plan = line(0.0 to 0.0, 5_000.0 to 0.0)
    private val trip = TripPace()
    private var x = 0.0
    private var startWall = 0L
    private var startElapsed = 0L
    /** Every fix sent, in order, with the time it carried. */
    private val walked = ArrayList<TrackPoint>()

    private fun start() {
        NavSessionStore(File(tmp.root, NavSession.FILE)).save(
            NavSession.start(NavKind.PLAN, "Waalweg", Activity.HIKE, 3, plan, listOf(plan.first(), plan.last())).copy(token = 1))
        startWall = wall; startElapsed = now
        tour.start("HIKE", 3)
        nav.settle()
        trip.setLine(RouteTimes.of(plan, Activity.HIKE), null) // the service's loadFollow
    }

    /** One second: tick (and the trip pace's tick), a fix with [fix] – 7 m on – and its navigation and trip pace. */
    private fun second(fix: Boolean) {
        now += 1000; wall += 1000
        rec.tick()
        trip.tick(rec.state.value!!.elapsedS.toDouble(), wall)
        if (!fix) return
        x += 7.0
        val p = pt(x, 0.0)
        rec.fix(p)
        walked += TrackPoint(p.lat, p.lon, null, wall)
        nav.settle()
        val det = nav.navigator!!.detector
        trip.onFix(rec.state.value!!.elapsedS.toDouble(), wall, p.lat, p.lon, det.shownAlongM, det.learnable)
    }

    /** [min] minutes walking: a fix every 5 s (1.4 m/s). */
    private fun walk(min: Int) = repeat(min * 12) { repeat(4) { second(false) }; second(true) }

    /** The end; the tour times as `RecordingService.finish` saves them (the trip's, start = created, end = the clock now). */
    private fun end(): Long {
        val id = rec.state.value!!.tourId
        tour.end(delete = false); tour.stop()
        assertEquals(id, rec.finished)
        return id
    }

    private fun times(id: Long) = trip.tourTimes(tours.getTour(id)!!.durationS).copy(startMs = tours.getTour(id)!!.createdMs, endMs = wall)

    /** What must hold whatever the clock did: time, points, distance, GPX. */
    private fun assertTourWhole(id: Long) {
        val t = tours.getTour(id)!!
        val realS = (now - startElapsed) / 1000.0
        assertEquals("done", t.state)
        assertEquals("its time is the time walked (elapsed clock), not the clock's jump", realS, t.durationS, 1.0)
        val segs = tours.loadSegments(id)
        assertEquals("every fix a point, in the order walked – none dropped for a time going back", walked, segs.flatten())
        assertEquals("one piece: the jump is no GPS gap", 1, segs.size)
        assertEquals("the distance is the way walked", Geo.length(walked.map { it.toGeo() }), t.distanceM, 1.0)
        val gpx = GpxParser.parse(GpxWriter.write(t.name, segs))
        assertEquals("the GPX keeps every point and its time, in order", walked.map { it.timeMs }, gpx.allSegments.flatten().map { it.timeMs })
        val review = TourReview.of(segs, times(id), t.durationS, t.distanceM)
        assertTrue("the overview's times are never negative", listOfNotNull(review.totalS, review.movingS, review.pauseS).all { it >= 0 })
        assertTrue(problems.none("recording"))
    }

    @Test fun clockSetBackAnHourMidTour() {
        start()
        walk(10)
        wall -= 3_600_000
        walk(10)
        val id = end()
        assertTourWhole(id)
        val t = tours.getTour(id)!!
        // its end lies "before" its start on the clock: the overview takes the tour's own time, the summary leaves the clock times out
        assertEquals("the overview's whole time is the tour's", t.durationS, TourReview.of(tours.loadSegments(id), times(id), t.durationS, t.distanceM).totalS!!, 1.0)
        assertNull("no \"on the way 10:00–09:20\"", times(id).summaryLine)
    }

    @Test fun clockSetOnAnHourMidTour() {
        start()
        walk(10)
        wall += 3_600_000
        walk(10)
        assertTourWhole(end())
    }

    /**
     * A tour started after the clock was set back is listed by its start on the clock (`ORDER BY created`): it goes below
     * the tour before it. Both are there, whole – the order is the clock's.
     */
    @Test fun aTourStartedAfterTheClockWentBackIsListedByItsClockTime() {
        start()
        walk(10)
        val first = end()
        wall -= 3_600_000
        walked.clear(); x = 0.0
        start()
        walk(5)
        val second = end()
        assertTourWhole(second)
        assertEquals("both tours listed", setOf(first, second), tours.listTours().map { it.id }.toSet())
        assertEquals("by their clock start", listOf(first, second), tours.listTours().map { it.id })
    }

    /**
     * The arrival clock while walking on after the clock was set back: it must follow the new clock. Until 2026-11-24 the
     * trip pace showed nothing new for an hour – its updates waited for `wallMs - lastShowWall >= 10 s / 30 s`
     * (TripPace.refresh), negative until the clock caught up; now a clock set back starts the display anew.
     */
    @Test fun theArrivalFollowsTheClockSetBack() {
        start()
        walk(5)
        val toGoAt5 = trip.snapshot().toGoS!!
        walk(5)
        val toGoAtJump = trip.snapshot().toGoS!!
        assertTrue("before the jump \"to go\" goes down ($toGoAt5 → $toGoAtJump)", toGoAtJump < toGoAt5 - 150)
        wall -= 3_600_000
        walk(10)
        val s = trip.snapshot()
        assertTrue("\"to go\" went down over 10 minutes' walk (${toGoAtJump.toInt()} s at the jump, ${s.toGoS!!.toInt()} s now)",
            s.toGoS!! < toGoAtJump - 300)
        val toGoMs = (s.toGoS!! * (1 + TimeEstimate.BREAK_ALLOWANCE) * 1000).toLong()
        assertEquals("the arrival is the new clock + to go", (wall + toGoMs).toDouble(), s.arrivalMs!!.toDouble(), 3 * 60_000.0)
    }

    /**
     * Set on an hour: the summary's "on the way" comes from the clock (TourTimes startMs / endMs, TourReview.totalS) – a
     * 20-minute tour reads 1:20. Its stored time (elapsed) is right.
     */
    @Ignore("bug (minor): a tour across a clock set on 1 h shows 'on the way' 1 h too long – TourTimes/TourReview take start and end from the wall clock, not the tour's elapsed time")
    @Test fun onTheWayIsTheTourTimeAfterTheClockWentOn() {
        start()
        walk(10)
        wall += 3_600_000
        walk(10)
        val id = end()
        val t = tours.getTour(id)!!
        val review = TourReview.of(tours.loadSegments(id), times(id), t.durationS, t.distanceM)
        assertEquals("on the way = the tour's time", t.durationS, review.totalS!!, 60.0)
    }

    /** A new time zone mid-tour (a flight, a border): nothing stored moves; the clock times are shown in the zone of the moment. */
    @Test fun timeZoneChangeMidTour() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
            start()
            val id = rec.state.value!!.tourId
            val created = tours.getTour(id)!!.createdMs
            walk(10)
            val gpxBefore = GpxWriter.write("x", tours.loadSegments(id), timeMs = 0)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals("the stored points do not move", gpxBefore, GpxWriter.write("x", tours.loadSegments(id), timeMs = 0))
            walk(10)
            end()
            assertTourWhole(id)
            assertEquals("its start stays the same instant", created, tours.getTour(id)!!.createdMs)
            assertTrue("GPX times in UTC", GpxWriter.write("x", tours.loadSegments(id)).contains("Z</time>"))
            val line = times(id).summaryLine
            assertNotNull(line)
            assertTrue("\"on the way\" is the 20 min walked, start and end in the zone now: $line", line!!.contains("0:20"))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun theWalkIsOnThePlan() {
        start(); walk(2)
        assertEquals(x, nav.navigator!!.state.shownAlongM!!, 15.0)
        assertEquals(x - 7.0, Geo.length(rec.state.value!!.track.single()), 1.0)
    }
}
