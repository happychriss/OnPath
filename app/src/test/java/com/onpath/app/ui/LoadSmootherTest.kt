package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map loading % follows the time – it must not race to 50 and then stand still. */
class LoadSmootherTest {
    /** Feeds one value per second for [seconds], time left from [left] (elapsed s → s left). */
    private fun run(s: LoadSmoother, seconds: Int, left: (Int) -> Int?): List<Int> =
        (0..seconds).map { t -> s.next(t * 1000L, left(t)) }

    @Test fun evenWhenTheEstimateHolds() {
        // 120 s job, estimate correct: half way at 60 s, no jump at the start
        val p = run(LoadSmoother(), 120) { t -> 120 - t }
        assertTrue("no jump at the start: ${p[5]}", p[5] <= 6)
        assertTrue("about half at 60 s: ${p[60]}", p[60] in 45..55)
        assertTrue("near the end at 115 s: ${p[115]}", p[115] >= 90)
    }

    @Test fun quickRoutingDataDoesNotCountAsHalf() {
        // routing data done after 5 s, the server then needs 3 more minutes – the bar must not be at 50 %
        val p = run(LoadSmoother(), 10) { t -> if (t < 5) 190 - t else 180 }
        assertTrue("not half after 10 s: ${p[10]}", p[10] < 10)
    }

    @Test fun neverBackwardsAndNeverFrozenWhenTheServerIsSlower() {
        // estimate says 60 s, then the server needs much longer (estimate jumps to 300 s at 50 s)
        val p = run(LoadSmoother(), 200) { t -> if (t < 50) 60 - t else 300 }
        for (i in 1 until p.size) assertTrue("never backwards at $i: ${p[i - 1]} → ${p[i]}", p[i] >= p[i - 1])
        // after the estimate grew it still moves (creeps) – no 30 s without change
        val after = p.subList(50, 200)
        assertTrue("still moving: ${after.first()} → ${after.last()}", after.last() > after.first())
        assertTrue(p.max() <= 99)
    }

    @Test fun unknownTimeLeftUsesTheDefault() {
        val p = run(LoadSmoother(), LoadSmoother.DEFAULT_LEFT_S) { null }
        assertTrue("about half after the default time: ${p.last()}", p.last() in 45..55)
    }

    @Test fun resetStartsAgain() {
        val s = LoadSmoother()
        run(s, 60) { 30 }
        s.reset()
        assertEquals(0, s.next(0, 100))
    }
}
