package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.PointFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointFilterTest {
    // ~0.001° longitude at 46.67° N ≈ 76 m
    private fun p(lonOffset: Double, t: Long) = TrackPoint(46.67, 11.10 + lonOffset, null, t)

    @Test fun accuracyUpTo50mIsKept() {
        assertTrue(PointFilter.accuracyOk(true, 48f))
        assertFalse(PointFilter.accuracyOk(true, 51f))
        assertFalse(PointFilter.accuracyOk(false, 5f))
    }

    @Test fun firstPointAndNewSegmentAlwaysAccepted() {
        assertEquals(Verdict.ACCEPT to 0.0, PointFilter.judge(null, p(0.0, 0), 10.0, continuous = true))
        assertEquals(Verdict.ACCEPT to 0.0, PointFilter.judge(p(0.0, 0), p(0.01, 1000), 10.0, continuous = false))
    }

    @Test fun walkingAddsDistance() {
        val (v, d) = PointFilter.judge(p(0.0, 0), p(0.001, 60_000), 10.0, continuous = true)
        assertEquals(Verdict.ACCEPT, v)
        assertTrue(d > 70 && d < 80)
    }

    @Test fun impossibleSpeedIsAJump() {
        // 760 m in 2 s
        assertEquals(Verdict.JUMP, PointFilter.judge(p(0.0, 0), p(0.01, 2_000), 10.0, continuous = true).first)
    }

    @Test fun driftWhileStandingStillIsIgnored() {
        // 7.6 m in 5 s with 30 m accuracy (minMove = 15 m): a break, not a step
        assertEquals(Verdict.STANDING_STILL, PointFilter.judge(p(0.0, 0), p(0.0001, 5_000), 30.0, continuous = true).first)
        // the same move with a precise fix (minMove = 3 m) counts
        assertEquals(Verdict.ACCEPT, PointFilter.judge(p(0.0, 0), p(0.0001, 5_000), 5.0, continuous = true).first)
    }

    @Test fun slowMovementStillCountsAfterTheStillWindow() {
        // 7.6 m in 40 s: slow but real (steep climb)
        assertEquals(Verdict.ACCEPT, PointFilter.judge(p(0.0, 0), p(0.0001, 40_000), 30.0, continuous = true).first)
    }

    @Test fun minMoveFollowsAccuracyWithinBounds() {
        assertEquals(3.0, PointFilter.minMoveM(2.0), 0.0)
        assertEquals(15.0, PointFilter.minMoveM(30.0), 0.0)
        assertEquals(25.0, PointFilter.minMoveM(80.0), 0.0)
    }

    /** A 30 s+ gap under forest must not break the drawn track or lose its distance. */
    @Test fun shortGpsGapAtWalkingSpeedIsBridged() {
        // 0.002° lon ≈ 153 m in 90 s ≈ 1.7 m/s
        assertTrue(PointFilter.bridgesGap(p(0.0, 0), p(0.002, 90_000)))
    }

    @Test fun longOrFastGapStartsANewPiece() {
        assertFalse("over 5 min", PointFilter.bridgesGap(p(0.0, 0), p(0.002, 6 * 60_000L)))
        // 0.05° ≈ 3.8 km in 60 s ≈ 64 m/s: a bus or a wrong fix
        assertFalse("too fast", PointFilter.bridgesGap(p(0.0, 0), p(0.05, 60_000)))
        assertFalse("no time", PointFilter.bridgesGap(TrackPoint(46.67, 11.1, null, null), p(0.001, 60_000)))
    }

    /** The gap trace tells weak fixes (> 50 m) from no fix at all. */
    @Test fun gapTraceSaysFixesWereTooInaccurate() {
        val w = PointFilter.GapWatch()
        w.onDropped(true, 80f); w.onDropped(true, 63f); w.onDropped(true, 120f); w.onDropped(false, 0f)
        assertEquals("4 fixes dropped for accuracy, best 63 m", w.why())
        w.reset()
        assertEquals("no GPS fix at all", w.why())
    }

    @Test fun gapTraceWithoutAccuracyValues() {
        val w = PointFilter.GapWatch()
        w.onDropped(false, 0f)
        assertEquals("1 fix dropped for accuracy, best unknown", w.why())
    }

    /**
     * Owner's Pixel 2026-11-27 (tour indoors at Hilden, 16:19–16:46): the motion sensor said "standing still", GPS came
     * every 10 s at 30–80 m, and every fix 25–60 m off the last point was kept – an orange scribble on the map and in
     * the live share. Still by the sensor: a fix within twice its accuracy of the last point is drift.
     */
    @Test fun aScribbleWhileTheSensorSaysStillIsDrift() {
        // 40 m off (≈ 0.00052° of longitude at 46.67°), 10 s later, accuracy 35 m: kept today, drift when still
        val drift = p(0.00052, 10_000)
        assertEquals(Verdict.ACCEPT, PointFilter.judge(p(0.0, 0), drift, 35.0, continuous = true).first)
        assertEquals(Verdict.STANDING_STILL, PointFilter.judge(p(0.0, 0), drift, 35.0, continuous = true, still = true).first)
        // long after the last point too (the 30 s window does not end it)
        assertEquals(Verdict.STANDING_STILL, PointFilter.judge(p(0.0, 0), p(0.00052, 300_000), 35.0, continuous = true, still = true).first)
        // never lose a real move: beyond twice the accuracy it counts, sensor or not (a smooth train, a wrong sensor)
        val (v, d) = PointFilter.judge(p(0.0, 0), p(0.002, 10_000), 35.0, continuous = true, still = true)
        assertEquals(Verdict.ACCEPT, v); assertTrue(d > 140)
        // a new track piece starts after a gap – unless the phone lay still (next test)
        assertEquals(Verdict.ACCEPT, PointFilter.judge(p(0.0, 0), drift, 35.0, continuous = false, still = false).first)
    }

    /**
     * Owner's Pixel 2026-11-27 23:00–23:33 (Hilden, indoors, tour 24, the phone on the table): GPS came only every few
     * minutes (gaps of 41–517 s), each fix 18–49 m off the last point at 26–49 m accuracy – every one became a new track
     * piece („No usable GPS fix for 189 s; 33 m since the last point – new track piece“): the track and the live share
     * jumped about. Still by the sensor, a fix within twice its accuracy of the last point is drift after a gap too.
     */
    @Test fun afterAGapAFixNearTheLastPointWhileStillIsDrift() {
        // the real points of tour 24: 33 m after 189 s at 49 m, 38 m after 66 s at 47 m, 47 m after 517 s at 47 m
        val last = TrackPoint(51.1692447372274, 6.92546263224504, null, 0)
        for ((fix, acc) in listOf(TrackPoint(51.1693647853983, 6.92590769386294, null, 189_000) to 49.4,
                TrackPoint(51.1691751927047, 6.92545369745193, null, 66_000) to 46.7,
                TrackPoint(51.1691831427485, 6.92587754032961, null, 517_000) to 46.7))
            assertEquals("$fix", Verdict.STANDING_STILL, PointFilter.judge(last, fix, acc, continuous = false, still = true).first)
        // a real move during the gap still starts its piece (300 m off), and without the sensor's word nothing changes
        assertEquals(Verdict.ACCEPT, PointFilter.judge(last, TrackPoint(51.1692447, 6.9297, null, 300_000), 40.0, continuous = false, still = true).first)
        assertEquals(Verdict.ACCEPT, PointFilter.judge(last, TrackPoint(51.1693647853983, 6.92590769386294, null, 189_000), 49.4,
            continuous = false, still = false).first)
    }
}
