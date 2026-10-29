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
}
