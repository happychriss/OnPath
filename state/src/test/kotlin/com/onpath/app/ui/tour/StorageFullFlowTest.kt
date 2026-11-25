package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.RecState
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * The phone's storage full while recording (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md, test 6):
 * a tour records, then every database write fails as on a full disk (SQLITE_FULL from [JdbcTours]), fixes keep coming,
 * space comes back, more fixes, the end. The service is [FakeRecording] with its real core pieces and the service's
 * write path (`writePoints` / `safeDb`: the failure logged, the notification once per failure, the points waiting in
 * order in the [PointQueue][com.onpath.app.recording.PointQueue]); the app is [TourController]. What the app says is
 * TourSession's rule: "the track cannot be saved" once each time `RecState.saveFailed` turns true ([toldInApp]).
 *
 * The promise (R-07, robust first, nothing silent): no crash, told once, the points that could not be written wait and
 * all go in, in order, numbered without hole or double, once space is back; the stats failing too loses no distance.
 * What a full disk can lose: the waiting points when the process dies, and at the queue's cap the oldest waiting ones.
 *
 * Not here: the notification's text, the vibration, the "Save" of an interrupted tour (TourEndSlice), a database file
 * that is really full (the hook throws on every write; on the phone a WAL write fails the same way).
 */
class StorageFullFlowTest {
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private var x = 0.0
    /** Every fix sent, in order, with its time. */
    private val walked = ArrayList<TrackPoint>()
    /** The database survives a killed process; the service and the app do not. */
    private val tours = JdbcTours()
    private var rec = FakeRecording(tours, { now })
    private var tour = TourController(rec)
    /** Every state the service published after a second, in order (what the app's collector sees). */
    private val seen = ArrayList<RecState>()

    @After fun close() = tours.close()

    /** Every write fails (the disk is full) – or works again. */
    private fun diskFull(full: Boolean) { tours.failWrites = if (full) Int.MAX_VALUE else 0 }

    /** A new app process on the same database: a new service, a new app, nothing of the old memory. */
    private fun newProcess(queueMax: Int = com.onpath.app.recording.PointQueue.MAX) {
        rec = FakeRecording(tours, { now }, queueMax = queueMax); tour = TourController(rec); seen.clear()
    }

    /** One second of the tour; with [fix] a point 7 m on. */
    private fun second(fix: Boolean) {
        now += 1000
        rec.tick()
        if (fix) { val g = pt(x, 0.0); rec.fix(g); walked += TrackPoint(g.lat, g.lon, null, now); x += 7.0 }
        rec.state.value?.let { seen += it }
    }

    /** [n] points, one every 5 s (a walker at 1.4 m/s); the last thing done is a fix. */
    private fun walk(n: Int) = repeat(n) { repeat(4) { second(fix = false) }; second(fix = true) }

    /** TourSession's rule: "the track cannot be saved" once per run of `saveFailed`. */
    private fun toldInApp(): Int {
        var shown = false; var n = 0
        for (st in seen) { if (st.saveFailed && !shown) { n++; shown = true } else if (!st.saveFailed) shown = false }
        return n
    }

    /** The rows of [id] as they lie in the table, in the order they were written: (seq, seg, point). */
    private fun rows(id: Long): List<Triple<Int, Int, TrackPoint>> =
        tours.db.prepareStatement("SELECT seq, seg, lat, lon, time FROM points WHERE tour_id=? ORDER BY rowid").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { r ->
                buildList { while (r.next()) add(Triple(r.getInt(1), r.getInt(2), TrackPoint(r.getDouble(3), r.getDouble(4), null, r.getLong(5)))) }
            }
        }

    private fun pieces(id: Long) = tours.loadSegments(id).map { it.size }
    private fun length(id: Long) = tours.loadSegments(id).sumOf { s -> Geo.length(s.map { it.toGeo() }) }

    @Test fun fullForTwoAndAHalfMinutesThenSpaceAgain_toldOnce_nothingLost_theTourWhole() {
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(20)
        val before = rows(id)
        assertEquals(walked, before.map { it.third })

        diskFull(true)
        walk(30)
        val st = rec.state.value!!
        assertTrue("no crash, still recording", rec.running)
        assertTrue(st.saveFailed)
        assertEquals("told once, not once per point", 1, rec.saveFailNotices)
        assertTrue(rec.saveFailNoticeUp)
        assertEquals(1, toldInApp())
        assertEquals("the 30 new points wait in memory", 30, rec.waitingPoints)
        assertEquals("the 20 from before untouched", before, rows(id))
        assertEquals("the screen draws all 50", 50, st.track.single().size)
        assertEquals("the distance counts on in memory", 49 * 7.0, st.distanceM, 2.0)
        assertEquals("the row keeps the last stats written (19 points)", 18 * 7.0, tours.getTour(id)!!.distanceM, 2.0)
        // the problem log: one line per kind of write with a counter, not one per point
        val db = Problems.snapshot().filter { it.area == "db" }
        assertEquals(setOf("Recording: database write failed (point)", "Recording: database write failed (stats)"), db.map { it.message }.toSet())
        assertTrue(db.all { it.level == Problems.Level.ERROR })
        assertTrue(db.single { it.message.endsWith("(point)") }.count >= 30)

        diskFull(false)
        walk(20)
        val back = rec.state.value!!
        assertFalse("the next point is written: the warning goes", back.saveFailed)
        assertFalse("and its notification", rec.saveFailNoticeUp)
        assertEquals(0, rec.waitingPoints)
        assertEquals("still told once", 1, rec.saveFailNotices)
        assertEquals(1, toldInApp())
        assertTrue(problems.said("recording").contains("30 track point(s) written late – none lost"))

        tour.end(delete = false); tour.stop()
        assertEquals(id, rec.finished)
        val rows = rows(id)
        assertEquals("every fix once, in order (R-07)", walked, rows.map { it.third })
        assertEquals("numbered 0–69 in the order written: no hole, no double", (0 until 70).toList(), rows.map { it.first })
        assertEquals("the earlier points as they were", before, rows.take(20))
        assertEquals("one piece – a full disk is no break", listOf(70), pieces(id))
        val t = tours.getTour(id)!!
        assertEquals("done", t.state)
        assertEquals("the saved distance is its points'", length(id), t.distanceM, 0.5)
        assertEquals(69 * 7.0, t.distanceM, 2.0)
        assertEquals("and the time, the minutes while full too", 350.0, t.durationS, 0.0)
    }

    /**
     * The process dies while points wait (the queue is in memory): exactly those are lost. The row's stats are from the
     * last write before the disk was full; Continue counts at least the saved points (TourRun.resumed), the time while
     * full is lost with the process.
     */
    @Test fun killedWhilePointsWait_onlyThoseAreLost_theTourGoesOnCleanly() {
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(20)
        diskFull(true)
        walk(10)
        assertEquals(10, rec.waitingPoints)
        val saved = tours.getTour(id)!!
        assertEquals("the last stats written: 19 points, 100 s", 18 * 7.0, saved.distanceM, 2.0)
        assertEquals(100.0, saved.durationS, 0.0)

        rec.kill()
        diskFull(false) // space freed meanwhile
        now += 120_000
        newProcess()
        val active = tours.findActiveRecording()!!
        assertEquals("the tour is still marked as recording", id, active.id)
        tour.continueTour(active)
        assertEquals(id, rec.state.value!!.tourId)
        walk(20)
        assertFalse(rec.state.value!!.saveFailed)
        assertEquals("the continued tour is told nothing", 0, rec.saveFailNotices)
        tour.end(delete = false); tour.stop()

        val rows = rows(id)
        assertEquals("exactly the 10 that waited are gone", walked.take(20) + walked.drop(30), rows.map { it.third })
        assertEquals("numbered on from the last written one: no hole, no double", (0 until 40).toList(), rows.map { it.first })
        assertEquals("the break is a new piece", listOf(20, 20), pieces(id))
        val t = tours.getTour(id)!!
        assertEquals("done", t.state)
        assertEquals("the saved distance is its points'", length(id), t.distanceM, 0.5)
        assertEquals(2 * 19 * 7.0, t.distanceM, 2.0)
        assertEquals("the time: the row's 100 s + the 100 s continued (the 50 s while full went with the process)", 200.0, t.durationS, 0.0)
    }

    /** The system brings the service back by itself while the disk is still full: told again (a new process), nothing more lost. */
    @Test fun restartedBySystemWhileStillFull_toldAgain_thenAllWritten() {
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(20)
        diskFull(true)
        walk(10)
        rec.kill()
        now += 3_000
        newProcess()
        rec.restartSticky()
        assertEquals(id, rec.state.value!!.tourId)
        assertTrue("its first write fails: said at once", rec.state.value!!.saveFailed)
        assertEquals(1, rec.saveFailNotices)
        walk(10)
        assertEquals(10, rec.waitingPoints)
        diskFull(false)
        walk(10)
        assertEquals(0, rec.waitingPoints)
        assertEquals("told once in this process", 1, rec.saveFailNotices)
        assertEquals(1, toldInApp())
        tour.end(delete = false); tour.stop()

        val rows = rows(id)
        assertEquals("only the 10 that waited in the killed process are gone", walked.take(20) + walked.drop(30), rows.map { it.third })
        assertEquals((0 until 40).toList(), rows.map { it.first })
        assertEquals(listOf(20, 20), pieces(id))
        assertEquals("the saved distance is its points'", length(id), tours.getTour(id)!!.distanceM, 0.5)
        assertEquals("done", tours.getTour(id)!!.state)
    }

    /**
     * The queue's cap (PointQueue.MAX, ~5 h of points; here 5): beyond it the oldest waiting point is given up – said in
     * the problem log at the 1st, 1001st … one. Their numbers stay free (a hole in seq); the distance walked stays counted.
     */
    @Test fun atTheQueuesCap_theOldestWaitingAreGivenUp_saidOnce() {
        newProcess(queueMax = 5)
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(3)
        diskFull(true)
        walk(8)
        assertEquals(5, rec.waitingPoints)
        assertEquals(3, rec.droppedPoints)
        assertEquals(listOf("Storage failed for hours: 1 oldest unwritten point(s) given up"),
            problems.said("recording", Problems.Level.ERROR))
        assertEquals("the screen still draws all 11", 11, rec.state.value!!.track.single().size)
        diskFull(false)
        walk(2)
        tour.end(delete = false); tour.stop()

        val rows = rows(id)
        assertEquals("the 3 oldest waiting are gone, the rest in order", walked.take(3) + walked.drop(6), rows.map { it.third })
        assertEquals("their numbers stay free", listOf(0, 1, 2) + (6 until 13), rows.map { it.first })
        assertEquals(listOf(10), pieces(id))
        assertEquals("the distance walked is counted (12 steps)", 12 * 7.0, tours.getTour(id)!!.distanceM, 2.0)
        assertEquals("done", tours.getTour(id)!!.state)
    }

    /**
     * End tour while the disk is still full: no crash; the "done" write fails, so the tour stays marked as recording and
     * the next start asks Continue / Save; its written points intact. The waiting points are in the service's memory
     * only – gone when the service goes (never dropped while it runs, R-07).
     */
    @Test fun endedWhileStillFull_theTourStaysUnfinished_itsWrittenPointsIntact() {
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(10)
        diskFull(true)
        walk(5)
        tour.end(delete = false); tour.stop()
        assertEquals(id, rec.finished)
        assertEquals("still told: the notification stays", true, rec.saveFailNoticeUp)
        assertEquals(1, rec.saveFailNotices)
        assertEquals("the 5 still wait in the service's memory", 5, rec.waitingPoints)
        assertEquals("the \"done\" write failed: asked at the next start", "active", tours.getTour(id)!!.state)
        assertEquals(walked.take(10), rows(id).map { it.third })

        rec.kill() // the service is gone, the process with it
        diskFull(false)
        newProcess()
        val active = tours.findActiveRecording()!!
        assertEquals(id, active.id)
        tour.continueTour(active)
        walk(5)
        tour.end(delete = false); tour.stop()
        assertEquals(walked.take(10) + walked.drop(15), rows(id).map { it.third })
        assertEquals((0 until 15).toList(), rows(id).map { it.first })
        assertEquals(listOf(10, 5), pieces(id))
        assertEquals("the saved distance is its points'", length(id), tours.getTour(id)!!.distanceM, 0.5)
        assertEquals("done", tours.getTour(id)!!.state)
    }

    /**
     * Space comes back while the walker stands still (a break at the hut): the tick writes the waiting points – but
     * the warning is cleared only by the next point written on a fix (RecordingService.onFix), so the "Recording cannot
     * be saved" notification (ongoing, not swipeable) stays up though everything is written, and after End it stays
     * for good: finish() never cancels SAVE_FAIL_ID.
     */
    @Test fun spaceBackWhileStandingStill_everythingWritten_theWarningGoes() {
        tour.start("HIKE", 3)
        val id = rec.state.value!!.tourId
        walk(10)
        diskFull(true)
        walk(10)
        diskFull(false)
        repeat(60) { second(fix = false) } // standing still: no new point
        assertEquals("the tick wrote them", 0, rec.waitingPoints)
        assertEquals(walked, rows(id).map { it.third })
        assertFalse("everything is written: no warning any more", rec.state.value!!.saveFailed)
        tour.end(delete = false); tour.stop()
        assertEquals("done", tours.getTour(id)!!.state)
        assertFalse("no \"cannot be saved\" notification after a tour saved whole", rec.saveFailNoticeUp)
    }

    /**
     * The problem log's "written late" line when the tick writes them (no new point with them): it counts one too few
     * (`waited - 1` is right only on a fix, where the newest point is not late).
     */
    @Test fun theLateLineCountsEveryLatePoint() {
        tour.start("HIKE", 3)
        walk(10)
        diskFull(true)
        walk(10)
        diskFull(false)
        repeat(60) { second(fix = false) }
        assertEquals(0, rec.waitingPoints)
        assertTrue(problems.said("recording").toString(), problems.said("recording").contains("10 track point(s) written late – none lost"))
    }
}
