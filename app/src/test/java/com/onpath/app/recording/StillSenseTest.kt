package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Owner 2026-11-02 (phone on the table, onpath.private "GPS auf dem Tisch", A): no GPS while the phone lies still →
 * nothing at all; "still" from the phone's own motion sensor (no Google), so it works without fixes; the amber warning
 * only when the phone moves and GPS is missing. Without a sensor (or not sure yet) everything stays as before (MoveWatch).
 */
class StillSenseTest {
    private val g = 9.81f

    /** Field case: tour started at the table, no GPS indoors – MoveWatch counts the first 2 min as moving → amber. */
    @Test fun phoneOnTheTableShowsNothing() {
        assertEquals(NoGpsShow.Line.NONE, NoGpsShow.line(sensorStill = true, moveWatchMoving = true, shortGap = false))
        assertEquals(NoGpsShow.Line.NONE, NoGpsShow.line(sensorStill = true, moveWatchMoving = false, shortGap = true))
        assertTrue("still = quiet for the voice too", NoGpsShow.standingStill(sensorStill = true, moveWatchMoving = true))
    }

    @Test fun movingWithoutGpsWarns() {
        assertEquals(NoGpsShow.Line.WARN, NoGpsShow.line(sensorStill = false, moveWatchMoving = false, shortGap = false))
        assertEquals("a short gap is not worth a warning yet", NoGpsShow.Line.QUIET,
            NoGpsShow.line(sensorStill = false, moveWatchMoving = false, shortGap = true))
        assertFalse(NoGpsShow.standingStill(sensorStill = false, moveWatchMoving = false))
    }

    /** No sensor, or not enough samples yet: today's rule (MoveWatch) unchanged. */
    @Test fun withoutTheSensorAsBefore() {
        assertEquals(NoGpsShow.Line.WARN, NoGpsShow.line(sensorStill = null, moveWatchMoving = true, shortGap = false))
        assertEquals(NoGpsShow.Line.QUIET, NoGpsShow.line(sensorStill = null, moveWatchMoving = false, shortGap = false))
        assertEquals(NoGpsShow.Line.QUIET, NoGpsShow.line(sensorStill = null, moveWatchMoving = true, shortGap = true))
        assertTrue(NoGpsShow.standingStill(sensorStill = null, moveWatchMoving = false))
        assertFalse(NoGpsShow.standingStill(sensorStill = null, moveWatchMoving = true))
    }

    @Test fun theDetectorTellsTableFromWalking() {
        val d = StillDetector()
        assertNull("nothing heard yet", d.still(0))
        // table: gravity + sensor noise
        for (i in 0..50) d.add(i * 200L, 0.02f * (i % 3), 0.01f, g)
        assertEquals(true, d.still(10_000))
        // walking: ~2 steps a second, ±3 m/s²
        d.reset()
        for (i in 0..50) d.add(i * 200L, 0f, 3f * sin(i * 200 / 1000.0 * 2 * Math.PI * 2).toFloat(), g)
        assertEquals(false, d.still(10_000))
    }

    @Test fun theDetectorIsUnsureWithTooFewOrOldSamples() {
        val d = StillDetector()
        for (i in 0..5) d.add(i * 200L, 0f, 0f, g) // 1 s only
        assertNull("too short a window", d.still(1_000))
        for (i in 0..50) d.add(i * 200L, 0f, 0f, g)
        assertNull("the sensor went silent", d.still(10_000 + StillDetector.STALE_MS + 1))
    }

    /** Wiring: the sensor only without a fix while recording (battery); the card shows nothing about GPS when still. */
    @Test fun wiredOnlyWithoutAFixAndHidesTheLine() {
        val svc = java.io.File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.contains("motion.listen(tourId >= 0 && !paused && gpsLost())"))
        assertTrue(svc.contains("motion.listen(false)"))
        assertTrue(svc.contains("gpsStill = gpsLost() && noGpsLine() == NoGpsShow.Line.NONE"))
        val nav = java.io.File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("ui.waitingGps && !ui.stillGps && ui.approx == null"))
        assertTrue("no screen wake-up for it either", nav.contains("gpsLost = rec?.gpsLost == true && rec.gpsStill != true"))
    }

    /** A pocket while sitting moves a little – still counts as still; walking does not (above). */
    @Test fun aPocketAtTheTableIsStill() {
        val d = StillDetector()
        for (i in 0..50) d.add(i * 200L, 0.2f * sin(i * 0.7).toFloat(), 0.15f, g + 0.2f * sin(i * 1.3).toFloat())
        assertEquals(true, d.still(10_000))
    }

    /**
     * Owner 2026-11-02: lying still = the notification does not say "GPS wird gesucht" either; owner 2026-11-06 23:35:
     * nor while the app is in Plan during the tour (Navigate says it again). Was a source-text check – now the rule.
     */
    @Test fun theNotificationStaysQuietWhileStillOrInPlan() {
        assertTrue("moving, no GPS, Navigate", NoGpsShow.notifSearching(gpsLost = true, line = NoGpsShow.Line.WARN, planQuiet = false))
        assertTrue("short gap, Navigate", NoGpsShow.notifSearching(gpsLost = true, line = NoGpsShow.Line.QUIET, planQuiet = false))
        assertFalse("lying still", NoGpsShow.notifSearching(gpsLost = true, line = NoGpsShow.Line.NONE, planQuiet = false))
        assertFalse("in Plan", NoGpsShow.notifSearching(gpsLost = true, line = NoGpsShow.Line.WARN, planQuiet = true))
        assertFalse("GPS there", NoGpsShow.notifSearching(gpsLost = false, line = NoGpsShow.Line.WARN, planQuiet = false))
    }
}
