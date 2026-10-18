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
}
