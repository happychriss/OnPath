package com.onpath.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-01: quiet while standing still – warnings only while moving. */
class MoveWatchTest {
    @Test fun movingWithinTwoMinutesOfTheLastAcceptedPoint() {
        val w = MoveWatch()
        assertFalse("nothing known", w.moving(0))
        w.start(1_000)
        assertTrue("tour start counts as moving (far start)", w.moving(60_000))
        assertFalse("sat down right at the start", w.moving(1_000 + MoveWatch.WINDOW_MS + 1))
        w.moved(200_000)
        assertTrue(w.moving(200_000 + MoveWatch.WINDOW_MS))
        assertFalse("restaurant: 3 min without a point", w.moving(200_000 + 3 * 60_000))
    }

    /** GPS lost while walking (a forest) is said; lost after sitting 10 min in a restaurant is not. */
    @Test fun aLossCountsAsMovingOnlyWhenMovedBeforeIt() {
        val w = MoveWatch(); w.start(0)
        w.moved(600_000)
        val lostWhileWalking = 610_000L
        assertTrue(w.moving(lostWhileWalking))
        val lostAfterSitting = 600_000L + 10 * 60_000L
        assertFalse(w.moving(lostAfterSitting))
    }
}
