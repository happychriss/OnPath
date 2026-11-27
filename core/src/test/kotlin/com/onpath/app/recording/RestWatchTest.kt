package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner (battery): slower GPS while standing still during a tour, back to the normal rate at the
 * first sign of movement. The state machine is pure; RecordingService only applies [RestWatch.intervalMs].
 */
class RestWatchTest {
    private val lat0 = 46.670
    private val lon0 = 11.160
    /** Metres north of the start point. */
    private fun north(m: Double) = lat0 + m / 111_195.0

    private fun RestWatch.fix(tS: Double, northM: Double = 0.0, speed: Float? = 0f, acc: Float = 8f, still: Boolean? = null) =
        onFix((tS * 1000).toLong(), north(northM), lon0, acc, speed, still)

    /** The service's one-second tick with the motion sensor's word, from [fromS] for [s] seconds; the last change. */
    private fun RestWatch.ticks(fromS: Double, s: Double, still: Boolean?): RestWatch.Change? {
        var last: RestWatch.Change? = null
        var t = fromS
        while (t <= fromS + s) { onTick((t * 1000).toLong(), still)?.let { last = it }; t += 1.0 }
        return last
    }

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

    // ---------------- the motion sensor decides while the phone lies still (owner 2026-11-24, his Pixel indoors) ----------------

    @Test fun indoorJumpsDoNotWakeWhileTheSensorSaysStill() {
        // his Pixel 2026-11-24 22:01–22:49, lying on a table indoors: 9 times "Moving again" from fixes of ±49 m, 4 satellites,
        // jumping 30 m and reporting 3 m/s – the motion sensor said still all along
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertTrue(w.resting)
        assertNull("a poor fix far away", w.fix(80.0, northM = 30.0, acc = 49f, speed = 3.1f, still = true))
        assertNull("another one", w.fix(90.0, northM = -25.0, acc = 35f, speed = null, still = true))
        assertTrue(w.resting)
        assertEquals(RestWatch.REST_MS, w.intervalMs)
    }

    @Test fun aGoodFixFarAwayWakesEvenWhenTheSensorSaysStill() {
        // a phone lying still in a moving car or train: good GPS that moved is the truth
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertEquals(RestWatch.Change.MOVING, w.fix(80.0, northM = 40.0, acc = 8f, still = true))
        val v = RestWatch()
        v.stand(0.0, 70.0)
        assertEquals("good and fast", RestWatch.Change.MOVING, v.fix(80.0, northM = 2.0, acc = 8f, speed = 5f, still = true))
    }

    @Test fun theSensorMovingWakesAtOnceWithoutAnyFix() {
        // picked up and walked off inside, no GPS fix at all: back to every second at once (never lose the first points)
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertTrue(w.resting)
        assertNull(w.ticks(71.0, 10.0, still = true))
        assertEquals(RestWatch.Change.MOVING, w.onTick(82_000L, still = false))
        assertFalse(w.resting)
    }

    @Test fun halfAMinuteStillByTheSensorRestsWithoutGoodFixes() {
        // indoors the fixes are too poor to start a rest by GPS; the sensor's 30 s do it (owner 2026-11-27: „kürzer“)
        assertEquals(30_000L, RestWatch.SENSOR_REST_AFTER_MS)
        val w = RestWatch()
        for (t in 0..120) assertNull(w.fix(t.toDouble(), acc = 45f, still = true))
        assertNull(w.ticks(0.0, 29.0, still = true))
        assertEquals(RestWatch.Change.REST, w.onTick(30_000L, still = true))
        assertTrue(w.resting)
    }

    @Test fun theSensorMinuteStartsAgainWhenItWasUnsureOrMoving() {
        val w = RestWatch()
        assertNull(w.ticks(0.0, 20.0, still = true))
        assertNull(w.onTick(21_000L, still = false))
        assertNull(w.ticks(22.0, 20.0, still = true))
        assertFalse(w.resting)
        assertNull(w.onTick(43_000L, still = null))
        assertNull(w.ticks(44.0, 29.0, still = true))
        assertFalse("no sensor word breaks the 30 s too", w.resting)
        assertEquals(RestWatch.Change.REST, w.onTick(74_000L, still = true))
    }

    @Test fun withoutASensorEverythingIsAsBefore() {
        val w = RestWatch()
        assertNull(w.ticks(0.0, 120.0, still = null))
        assertFalse(w.resting)
        w.stand(0.0, 70.0)
        assertTrue(w.resting)
        assertEquals(RestWatch.Change.MOVING, w.fix(80.0, northM = 40.0, acc = 45f, still = null))
    }

    @Test fun resetForgetsTheSensorMinute() {
        val w = RestWatch()
        w.ticks(0.0, 20.0, still = true)
        w.reset()
        assertNull(w.ticks(21.0, 28.0, still = true))
        assertFalse(w.resting)
    }

    // ---------------- GPS off after an hour still (owner 2026-11-24: „dann ist das Handy wenigstens nicht leer“) ----------------

    @Test fun anHourStillByTheSensorSwitchesGpsOff() {
        val w = RestWatch()
        assertNull(w.ticks(0.0, 29.0, still = true))
        assertEquals(RestWatch.Change.REST, w.onTick(30_000L, still = true))
        assertNull("an hour not over yet", w.onTick(RestWatch.OFF_AFTER_MS - 1, still = true))
        assertFalse(w.off)
        assertEquals(RestWatch.Change.OFF, w.onTick(RestWatch.OFF_AFTER_MS, still = true))
        assertTrue(w.off)
        assertTrue(w.resting)
        assertNull("said once", w.onTick(RestWatch.OFF_AFTER_MS + 1_000, still = true))
    }

    @Test fun movingTurnsGpsBackOnAtTheNormalRate() {
        val w = RestWatch()
        w.onTick(0L, still = true); w.onTick(60_000L, still = true); w.onTick(RestWatch.OFF_AFTER_MS, still = true)
        assertTrue(w.off)
        assertEquals(RestWatch.Change.MOVING, w.onTick(RestWatch.OFF_AFTER_MS + 5_000, still = false))
        assertFalse(w.off)
        assertFalse(w.resting)
        assertEquals(RestWatch.NORMAL_MS, w.intervalMs)
    }

    @Test fun theSensorLosingItsWordTurnsGpsBackOn() {
        // never stay blind on a guess: no sensor word while GPS is off = GPS on again
        val w = RestWatch()
        w.onTick(0L, still = true); w.onTick(60_000L, still = true); w.onTick(RestWatch.OFF_AFTER_MS, still = true)
        assertEquals(RestWatch.Change.MOVING, w.onTick(RestWatch.OFF_AFTER_MS + 1_000, still = null))
        assertFalse(w.off)
    }

    @Test fun aBreakInTheStillHourStartsItAgain() {
        val w = RestWatch()
        w.onTick(0L, still = true); w.onTick(60_000L, still = true)
        assertNull("unsure for a moment while resting: rest stays", w.onTick(30 * 60_000L, still = null))
        assertTrue(w.resting)
        assertNull(w.onTick(30 * 60_000L + 1_000, still = true))
        assertNull("the hour counts from the break", w.onTick(RestWatch.OFF_AFTER_MS, still = true))
        assertEquals(RestWatch.Change.OFF, w.onTick(30 * 60_000L + 1_000 + RestWatch.OFF_AFTER_MS, still = true))
    }

    @Test fun neverOffWithoutTheSensor() {
        val w = RestWatch()
        w.stand(0.0, 70.0)
        assertTrue(w.resting)
        assertNull(w.ticks(71.0, 2.0 * 3600, still = null))
        assertFalse(w.off)
    }

    @Test fun resetTurnsOffBackOn() {
        val w = RestWatch()
        w.onTick(0L, still = true); w.onTick(60_000L, still = true); w.onTick(RestWatch.OFF_AFTER_MS, still = true)
        w.reset()
        assertFalse(w.off)
        assertFalse(w.resting)
    }
}
