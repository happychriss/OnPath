package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Sheet drag: …" numbers the owner's swipe writes into the events trace (FrameJankMeter). */
class FrameStatsTest {
    @Test fun noFramesNoLine() = assertNull(FrameStats.of(emptyList()))

    @Test fun countsJankyFramesAndPercentile() {
        // 20 frames: 17 smooth (5 ms), two over 16 ms, one over 33 ms
        val ms = List(17) { 5.0 } + listOf(17.0, 20.0, 40.0)
        val s = FrameStats.of(ms, refreshHz = 120f)!!
        assertEquals(20, s.frames)
        assertEquals(3, s.over16)
        assertEquals(1, s.over33)
        assertEquals(3, s.overBudget)  // 8.3 ms budget at 120 Hz
        assertEquals(20.0, s.p95Ms, 0.0)  // nearest rank: 19th of 20
        assertEquals(40.0, s.maxMs, 0.0)
        assertEquals("Sheet drag: 20 frames, 3 janky > 16 ms, 1 > 33 ms (p95 20.0 ms, max 40.0 ms; 120 Hz: 3 over 8.3 ms)",
            s.line("Sheet drag"))
    }

    @Test fun budgetFollowsTheDisplay() {
        val s = FrameStats.of(listOf(10.0, 12.0), refreshHz = 60f)!!
        assertEquals(0, s.overBudget)
        assertEquals(60, s.hz)
    }
}
