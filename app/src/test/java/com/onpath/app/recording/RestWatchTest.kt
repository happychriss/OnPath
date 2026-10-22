package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision 10 (2026-10-22, battery): slower GPS while standing still during a tour, back to the normal rate at the
 * first sign of movement. The state machine is pure; RecordingService only applies [RestWatch.intervalMs].
 */
class RestWatchTest {
    private val lat0 = 46.670
    private val lon0 = 11.160
    /** Metres north of the start point. */
    private fun north(m: Double) = lat0 + m / 111_195.0

    private fun RestWatch.fix(tS: Double, northM: Double = 0.0, speed: Float? = 0f, acc: Float = 8f) =
        onFix((tS * 1000).toLong(), north(northM), lon0, acc, speed)

    /** Standing for [s] seconds at [northM] with small drift, one fix per [stepS]. */
    private fun RestWatch.stand(fromS: Double, s: Double, northM: Double = 0.0, stepS: Double = 1.0): RestWatch.Change? {
        var last: RestWatch.Change? = null
        var t = fromS
        var i = 0
        while (t <= fromS + s) { fix(t, northM + if (i++ % 2 == 0) 2.0 else -2.0)?.let { last = it }; t += stepS }
        return last
    }

    @Test fun standingAMinuteSlowsTheGps() {
        val w = RestWatch()
        assertNull(w.stand(0.0, 59.0))
        assertFalse(w.resting)
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
        assertEquals(RestWatch.Change.REST, w.fix(61.0))
        assertTrue(w.resting)
        assertEquals(RestWatch.REST_MS, w.intervalMs)
    }

    @Test fun walkingNeverSlows() {
        val w = RestWatch()
        for (t in 0..600) assertNull(w.fix(t.toDouble(), northM = t * 1.2, speed = 1.2f))
        assertFalse(w.resting)
    }

    @Test fun slowWalkWithoutSpeedNeverSlows() {
        // 0.4 m/s, no speed in the fixes: leaves the 10 m circle within 30 s every time
        val w = RestWatch()
        for (t in 0..600) assertNull(w.fix(t.toDouble(), northM = t * 0.4, speed = null))
        assertFalse(w.resting)
    }

    @Test fun firstMoveWakesAtOnce() {
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertTrue(w.resting)
        assertNull("drift inside the circle: still resting", w.fix(80.0, northM = 6.0))
        assertEquals(RestWatch.Change.MOVING, w.fix(90.0, northM = 15.0))
        assertFalse(w.resting)
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
    }

    @Test fun speedWakesBeforeTheDistance() {
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertEquals(RestWatch.Change.MOVING, w.fix(80.0, northM = 3.0, speed = 1.0f))
    }

    @Test fun aPoorFixFarAwayWakesToo() {
        // waking costs only battery; staying slow while walking would cost track points
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertEquals(RestWatch.Change.MOVING, w.fix(80.0, northM = 40.0, acc = 45f))
    }

    @Test fun poorFixesNeverStartARest() {
        val w = RestWatch()
        for (t in 0..300) assertNull(w.fix(t.toDouble(), acc = 40f))
        assertFalse(w.resting)
    }

    @Test fun aSpeedReadingRestartsTheWindow() {
        val w = RestWatch()
        w.stand(0.0, 50.0)
        w.fix(51.0, speed = 0.9f) // moving on the spot (turning the bike round)
        assertNull(w.stand(52.0, 50.0))
        assertFalse("the minute starts again after the speed reading", w.resting)
        assertEquals(RestWatch.Change.REST, w.stand(103.0, 15.0))
    }

    @Test fun aGapInTheFixesRestartsTheWindow() {
        // no fix for a while (tunnel, house): the minute counts from fixes, not from wall time
        val w = RestWatch()
        w.fix(0.0)
        assertNull(w.fix(90.0))
        assertFalse(w.resting)
    }

    @Test fun restAgainAfterWalkingOn() {
        val w = RestWatch()
        w.stand(0.0, 70.0)
        w.fix(80.0, northM = 20.0, speed = 1.2f)
        assertFalse(w.resting)
        assertEquals(RestWatch.Change.REST, w.stand(81.0, 70.0, northM = 20.0))
        // at the slow rate the new rest point is 20 m north: a fix there does not wake
        assertNull(w.fix(160.0, northM = 21.0))
        assertTrue(w.resting)
    }

    @Test fun resetGoesBackToNormal() {
        val w = RestWatch()
        w.stand(0.0, 70.0)
        w.reset()
        assertFalse(w.resting)
        assertNull("the minute starts anew", w.stand(100.0, 30.0))
    }

    @Test fun gpsLostAllowsForTheSlowRate() {
        assertEquals(8_000L, RestWatch.lostAfterMs(resting = false))
        assertTrue(RestWatch.lostAfterMs(resting = true) > RestWatch.REST_MS)
    }
}
