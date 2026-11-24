package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.InterruptedLog
import com.onpath.app.routing.GeoPoint
import com.onpath.app.testkit.FakeRecording
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The process killed mid-tour – nothing lost, nothing doubled, at the database (hard cases,
 * docs/team/items/2026-11-24-hard-case-test-coverage.md, test 2). 100 points, the process dies (no onDestroy, no last
 * write), a new process on the same database FILE (SQLite in WAL mode, as TourDb), the interrupted tour's question,
 * Continue, 50 more points, the end. The service is [FakeRecording] with its real core pieces (TourRun.resumed from the
 * row and `recordingCursor`, PointQueue, the stats every 10 s by the tick); the app is [TourController] with the start-up
 * check and the answer as TourSession / TourEndSlice play them (their own flows: TourSessionFlowTest, TourEndFlowTest).
 *
 * The torn write: the process dies while the last point's transaction is being written – the WAL file ends in the middle
 * of its last frame. SQLite drops that transaction when the file is opened again; the tour goes on without that one point,
 * every other point whole, the numbering without a hole or a double.
 *
 * Not here: TourInterruptedReceiver (BOOT_COMPLETED / MY_PACKAGE_REPLACED needs an Android context – :app has no
 * Robolectric), the schema upgrade with an active tour.
 */
class TourKillFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private var x = 0.0
    /** Every fix sent, in order, with its time. */
    private val walked = ArrayList<TrackPoint>()
    private val opened = ArrayList<JdbcTours>()

    /** One app process: its connection to the database file, its recording service, its tour state. */
    private inner class Process(val path: String) {
        // WAL with synchronous=NORMAL as Android's setWriteAheadLoggingEnabled; no checkpoint, so the points stay in the WAL
        val tours = JdbcTours(path).also {
            opened += it
            it.exec("PRAGMA journal_mode=WAL"); it.exec("PRAGMA synchronous=NORMAL"); it.exec("PRAGMA wal_autocheckpoint=0")
        }
        val rec = FakeRecording(tours, { now })
        val tour = TourController(rec)
    }

    @After fun close() = opened.forEach { it.close() }

    /** One second of the tour; with [fix] a point 7 m on. */
    private fun second(p: Process, fix: Boolean) {
        now += 1000
        p.rec.tick()
        if (fix) { val g = pt(x, 0.0); p.rec.fix(g); walked += TrackPoint(g.lat, g.lon, null, now); x += 7.0 }
    }

    /** [n] points, one every 5 s (a walker at 1.4 m/s); the last thing done is a fix. */
    private fun walk(p: Process, n: Int) = repeat(n) { repeat(4) { second(p, fix = false) }; second(p, fix = true) }

    /** The rows of [id] as they lie in the table, in the order they were written: (seq, seg, point). */
    private fun rows(tours: JdbcTours, id: Long): List<Triple<Int, Int, TrackPoint>> =
        tours.db.prepareStatement("SELECT seq, seg, lat, lon, time FROM points WHERE tour_id=? ORDER BY rowid").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { r ->
                buildList { while (r.next()) add(Triple(r.getInt(1), r.getInt(2), TrackPoint(r.getDouble(3), r.getDouble(4), null, r.getLong(5)))) }
            }
        }

    private fun count(tours: JdbcTours, sql: String): Int = tours.db.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getInt(1) } }

    /** The process dies; [torn] = in the middle of writing its last point. Returns the database file the next process finds. */
    private fun kill(p: Process, torn: Boolean): String {
        p.rec.kill()
        val dir = tmp.newFolder()
        val copy = File(dir, "onpath.db")
        File(p.path).copyTo(copy)
        val wal = File(p.path + "-wal")
        assertTrue("the points are in the WAL, as on the phone", wal.length() > 0)
        wal.copyTo(File(copy.path + "-wal"))
        if (torn) RandomAccessFile(copy.path + "-wal", "rw").use { it.setLength(it.length() - 100) } // its last frame half written
        return copy.path
    }

    /**
     * The new process: the start-up check finds the tour still "active" and asks (no service came back by itself) – Continue.
     */
    private fun restartAndContinue(p: Process, id: Long) {
        val active = p.tours.findActiveRecording()
        assertNotNull("the tour is still marked as recording", active)
        assertEquals(id, active!!.id)
        assertNull("no service came back", p.rec.state.value)
        p.tour.showUnfinished(active, p.tours.loadSegments(id).map { s -> s.map { GeoPoint(it.lat, it.lon) } })
        assertFalse("no new tour beside it while it is asked", p.tour.canStartNew)
        p.tour.start("HIKE", 3) // held
        assertFalse(p.rec.running)
        var noteCleared = false
        InterruptedLog.chose(active.name, InterruptedLog.Choice.CONTINUE) { noteCleared = true } // TourEndSlice.continueRecording
        p.tour.continueTour(active)
        assertTrue(noteCleared)
        assertNull(p.tour.unfinished)
        assertEquals("the same tour records again", id, p.rec.state.value!!.tourId)
    }

    /** 100 points, killed, Continue, 50 more, the end: returns the second process and the tour's id. */
    private fun tourKilledAfter100(torn: Boolean): Pair<Process, Long> {
        val first = Process(File(tmp.root, "onpath.db").path)
        first.tour.start("HIKE", 3)
        val id = first.rec.state.value!!.tourId
        walk(first, 100)
        assertEquals(100, first.tours.loadSegments(id).single().size)
        val second = Process(kill(first, torn))
        now += 120_000 // the phone is unlocked, OnPath opened again
        restartAndContinue(second, id)
        walk(second, 50)
        second.tour.end(delete = false); second.tour.stop()
        assertEquals(id, second.rec.finished)
        return second to id
    }

    @Test fun killedAfter100PointsContinuedFor50_onePieceEach_noneLostNoneDoubled() {
        val (p, id) = tourKilledAfter100(torn = false)
        assertEquals("one tour", 1, count(p.tours, "SELECT COUNT(*) FROM tours"))
        assertEquals(TourKind.RECORDED, p.tours.listTours().single().kind)
        assertEquals("done", p.tours.getTour(id)!!.state)
        assertNull("nothing left marked as recording", p.tours.findActiveRecording())
        val rows = rows(p.tours, id)
        assertEquals("exactly 150 points", 150, rows.size)
        assertEquals("numbered 0–149, written in that order", (0 until 150).toList(), rows.map { it.first })
        assertEquals("every fix once, in order", walked, rows.map { it.third })
        assertEquals("the break is a new piece", List(100) { 0 } + List(50) { 1 }, rows.map { it.second })
        assertEquals(listOf(100, 50), p.tours.loadSegments(id).map { it.size })
        assertTrue(problems.none("recording"))
    }

    @Test fun killedWhileWritingTheLastPoint_thatOneIsGone_theRestWholeAndNumberedOn() {
        val (p, id) = tourKilledAfter100(torn = true)
        assertEquals(1, count(p.tours, "SELECT COUNT(*) FROM tours"))
        val rows = rows(p.tours, id)
        assertEquals("the half-written point is gone, nothing else", walked.take(99) + walked.drop(100), rows.map { it.third })
        assertEquals("numbered on from the last whole one: no hole, no double", (0 until 149).toList(), rows.map { it.first })
        assertEquals(listOf(99, 50), p.tours.loadSegments(id).map { it.size })
        assertEquals("done", p.tours.getTour(id)!!.state)
    }

    /** The system brings the service back by itself (START_STICKY): no question, the same tour, numbered on. */
    @Test fun killedAndRestartedBySystem_noQuestion_sameTourNumberedOn() {
        val first = Process(File(tmp.root, "onpath.db").path)
        first.tour.start("HIKE", 3)
        val id = first.rec.state.value!!.tourId
        walk(first, 100)
        val second = Process(kill(first, torn = false))
        now += 3_000
        second.rec.restartSticky()
        assertEquals(id, second.rec.state.value!!.tourId)
        walk(second, 50)
        assertEquals("GPS came: no \"no GPS after a restart\" alarm", 0, second.rec.watchdogAlarms)
        second.tour.end(delete = false); second.tour.stop()
        val rows = rows(second.tours, id)
        assertEquals((0 until 150).toList(), rows.map { it.first })
        assertEquals(walked, rows.map { it.third })
        assertEquals(1, count(second.tours, "SELECT COUNT(*) FROM tours"))
    }

    /**
     * The points are whole, the distance is not: the service writes the distance to the row only every 10 s (tick) and a
     * continued tour starts from the row (TourRun.resumed(existing.distanceM …)) – the metres since the last stats write
     * are lost with the process (here the 100th point's 7 m: 1027.8 m saved, its points 1034.8 m). The time the same way.
     */
    @Ignore("bug: after a kill the saved distance misses the metres walked since the last 10 s stats write (RecordingService.tick → TourRun.resumed)")
    @Test fun theDistanceWalkedJustBeforeTheKillIsCounted() {
        val (p, id) = tourKilledAfter100(torn = false)
        val pieces = p.tours.loadSegments(id)
        val walkedM = pieces.sumOf { s -> Geo.length(s.map { it.toGeo() }) }
        assertEquals("the two pieces: 99 + 49 steps of 7 m", 1036.0, walkedM, 2.0)
        assertEquals("the saved distance is its points'", walkedM, p.tours.getTour(id)!!.distanceM, 0.5)
    }
}
