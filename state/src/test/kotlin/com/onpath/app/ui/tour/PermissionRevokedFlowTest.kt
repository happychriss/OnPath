package com.onpath.app.ui.tour

import com.onpath.app.data.Geo
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.recording.InterruptedLog
import com.onpath.app.routing.GeoPoint
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.PhoneCheck
import com.onpath.app.setup.PhoneState
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Location taken away in the middle of a tour (hard cases, docs/team/items/2026-11-24-hard-case-test-coverage.md, test 3):
 * the user switches location off for OnPath in App info, or from precise to approximate – both take away precise location.
 *
 * What Android does: a revoked runtime permission kills the app's process at once (no onDestroy, no last write) – for
 * OnPath the same as a kill mid-tour (TourKillFlowTest). What OnPath does then:
 * - the system brings the service back (START_STICKY): `RecordingService.onStartCommand` finds the active tour, checks
 *   `tracker.hasFinePermission()` (~line 413) and refuses loudly – `failLoud`: the problem log, a "Recording stopped"
 *   notification with the reason, a vibration, `startFailed` (the app says it: TourSessionFlowTest rule 13) – the tour row
 *   is left as it was, "active", every point in it;
 * - the app opened: its start-up check finds the active tour and asks (Continue · Save · Delete); Continue goes through
 *   the phone setup before a tour (MainActivity: UnfinishedDialog → openSetup(BEFORE_TOUR)), which goes on only with
 *   precise location ([PhoneCheck.tourReady]) – so Continue is sent after the grant, and the same tour records on.
 *
 * The service is [FakeRecording] (its permission check copied, pinned in TestKitTest) on the real tables, a new process on
 * the same database file. Not here: the notification itself, the phone setup screen, whether Android restarts the
 * service at all after this kill (if not, nothing is said until the app is opened – the tour waits as "active").
 */
class PermissionRevokedFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val problems = ProblemsRule()
    @get:Rule val composeState = OwnComposeSnapshot()

    private var now = 1_793_000_000_000L
    private var x = 0.0
    /** Precise location for OnPath, as `tracker.hasFinePermission()` reads it. */
    private var precise = true
    private val walked = ArrayList<TrackPoint>()
    private val opened = ArrayList<JdbcTours>()
    private val path by lazy { File(tmp.root, "onpath.db").path }

    /** One app process on the database file. */
    private inner class Process {
        val tours = JdbcTours(path).also { opened += it }
        val rec = FakeRecording(tours, { now }, permitted = { precise })
        val tour = TourController(rec)
    }

    @After fun close() = opened.forEach { it.close() }

    /** [n] points, one every 5 s, 7 m apart. */
    private fun walk(p: Process, n: Int) = repeat(n) {
        repeat(4) { now += 1000; p.rec.tick() }
        now += 1000; p.rec.tick()
        val g = pt(x, 0.0); p.rec.fix(g); walked += TrackPoint(g.lat, g.lon, null, now); x += 7.0
    }

    /** The phone setup before a tour on this phone ([PhoneCheck.tourReady]): Continue goes on only with it. */
    private fun setupLetsContinue(grant: LocationGrant) = PhoneCheck.tourReady(PhoneState(sdk = 34, location = grant))

    /** 100 points, precise location taken away: the process dies; the system brings the service back. */
    private fun revokedAfter100(): Pair<Process, Long> {
        val first = Process()
        first.tour.start("HIKE", 3)
        val id = first.rec.state.value!!.tourId
        walk(first, 100)
        precise = false
        first.rec.kill()
        first.tours.close()
        val second = Process()
        now += 3_000
        second.rec.restartSticky()
        return second to id
    }

    @Test fun revokedMidTour_saidAtTheRestart_trackKept_sameTourAfterTheGrant() {
        val (p, id) = revokedAfter100()

        // the service came back without precise location: it says so and records nothing – it never crashes
        assertNull("no recording without precise location", p.rec.state.value)
        assertFalse(p.rec.running)
        assertEquals("the reason goes to the app (startFailed)", FakeRecording.NO_PERMISSION, p.rec.startFailed)
        assertTrue("and to the problem log", problems.said("recording").any { it.contains("needs precise location") })

        // the track so far: whole, readable, still the active tour
        val t = p.tours.getTour(id)!!
        assertEquals("the tour waits as it was", "active", t.state)
        assertEquals(id, p.tours.findActiveRecording()!!.id)
        val segs = p.tours.loadSegments(id)
        assertEquals("every point before the revoke is there, in order", walked, segs.single())
        assertEquals("its distance is its points'", Geo.length(segs.single().map { it.toGeo() }), t.distanceM, 8.0)
        val gpx = GpxParser.parse(GpxWriter.write(t.name, segs))
        assertEquals("and it exports whole", walked.map { it.timeMs }, gpx.allSegments.flatten().map { it.timeMs })

        // the app opened: the interrupted tour's question; Continue waits for precise location (the phone setup)
        p.tour.showUnfinished(t, segs.map { s -> s.map { GeoPoint(it.lat, it.lon) } })
        assertNotNull(p.tour.unfinished)
        assertFalse("no new tour beside it", p.tour.canStartNew)
        assertFalse("Continue without location: the setup asks first", setupLetsContinue(LocationGrant.NONE))
        assertFalse("approximate is not enough", setupLetsContinue(LocationGrant.APPROXIMATE))

        // granted again: Continue – the same tour records on
        precise = true
        assertTrue(setupLetsContinue(LocationGrant.PRECISE))
        var noteCleared = false
        InterruptedLog.chose(t.name, InterruptedLog.Choice.CONTINUE) { noteCleared = true } // TourEndSlice.continueRecording
        p.rec.startFailedSeen()
        p.tour.continueTour(t)
        assertTrue(noteCleared)
        assertEquals("the same tour", id, p.rec.state.value!!.tourId)
        assertNull(p.rec.startFailed)
        walk(p, 50)
        p.tour.end(delete = false); p.tour.stop()

        assertEquals(id, p.rec.finished)
        assertEquals("one tour", 1, p.tours.listTours().size)
        val done = p.tours.getTour(id)!!
        assertEquals("done", done.state)
        val pieces = p.tours.loadSegments(id)
        assertEquals("before and after the break: two pieces, every fix once", listOf(walked.take(100), walked.drop(100)), pieces)
        assertEquals("no line across the break in the distance", pieces.sumOf { s -> Geo.length(s.map { it.toGeo() }) }, done.distanceM, 1.0)
    }

    /** Continue sent while precise location is still missing (were the setup skipped): refused the same way, nothing lost. */
    @Test fun continueWithoutTheGrantIsRefusedAndTheTourStillWaits() {
        val (p, id) = revokedAfter100()
        p.rec.startFailedSeen()
        val t = p.tours.getTour(id)!!
        p.tour.continueTour(t)
        assertNull(p.rec.state.value)
        assertEquals("said again", FakeRecording.NO_PERMISSION, p.rec.startFailed)
        assertEquals("active", p.tours.getTour(id)!!.state)
        assertEquals(100, p.tours.loadSegments(id).single().size)
        // the next start-up check finds it again
        assertEquals(id, p.tours.findActiveRecording()!!.id)
        precise = true
        p.tour.continueTour(t)
        assertEquals(id, p.rec.state.value!!.tourId)
    }

    /** A new tour without precise location: refused, no empty tour row left behind. */
    @Test fun aNewTourWithoutPreciseLocationIsRefusedAndLeavesNothing() {
        precise = false
        val p = Process()
        p.tour.start("HIKE", 3)
        assertNull(p.rec.state.value)
        assertEquals(FakeRecording.NO_PERMISSION, p.rec.startFailed)
        assertTrue(p.tours.listTours().isEmpty())
    }
}
