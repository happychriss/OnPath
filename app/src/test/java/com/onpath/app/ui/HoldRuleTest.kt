package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock as "hold to confirm" (owner 2026-11-06, variant A): only a full band fires; an early release goes back + hint. */
class HoldRuleTest {
    @Test fun theBandFillsInOneSecond() {
        assertEquals(0f, HoldRule.progress(0), 0.001f)
        assertEquals(0.5f, HoldRule.progress(500), 0.001f)
        assertEquals(1f, HoldRule.progress(1_000), 0.001f)
        assertEquals(1f, HoldRule.progress(5_000), 0.001f)
        assertEquals(0f, HoldRule.progress(-20), 0.001f)
    }

    @Test fun onlyAFullBandFires() {
        assertFalse(HoldRule.fires(HoldRule.progress(999)))
        assertTrue(HoldRule.fires(HoldRule.progress(1_000)))
    }

    @Test fun aShortTapGoesBackWithTheHint() {
        assertEquals(HoldRule.Release.BACK_WITH_HINT, HoldRule.release(HoldRule.progress(120)))
        assertEquals(HoldRule.Release.BACK_WITH_HINT, HoldRule.release(HoldRule.progress(990)))
        assertEquals(HoldRule.Release.FIRED, HoldRule.release(HoldRule.progress(1_200)))
    }

    @Test fun aSecondTouchWhileTheBandRunsBackContinuesFromThere() {
        assertEquals(600, HoldRule.remainingMs(0.4f))
        assertEquals(1f, HoldRule.progress(600, from = 0.4f), 0.001f)
        assertEquals(0, HoldRule.remainingMs(1f))
    }

    @Test fun theBandRunsBackQuicklyAndShorterFromLess() {
        assertEquals(HoldRule.BACK_MS, HoldRule.backMs(1f))
        assertEquals(HoldRule.BACK_MS / 2, HoldRule.backMs(0.5f))
        assertEquals(0, HoldRule.backMs(0f))
    }

    @Test fun theChosenTimeSetsTheBand() {
        // Settings → Navigation 2 s (owner 2026-11-06 22:50): half full after 1 s, full after 2 s
        assertEquals(0.5f, HoldRule.progress(1_000, holdMs = 2_000), 0.001f)
        assertFalse(HoldRule.fires(HoldRule.progress(1_999, holdMs = 2_000)))
        assertTrue(HoldRule.fires(HoldRule.progress(2_000, holdMs = 2_000)))
        assertEquals(1_000, HoldRule.remainingMs(0.5f, holdMs = 2_000))
        // 0,5 s
        assertTrue(HoldRule.fires(HoldRule.progress(500, holdMs = 500)))
        // 0 never divides by zero: full at once
        assertEquals(1f, HoldRule.progress(0, holdMs = 0), 0.001f)
        assertEquals(0, HoldRule.remainingMs(0f, holdMs = 0))
    }

    @Test fun theHintSitsRightAboveTheTappedButtonAndInsideTheBar() {
        // owner 2026-11-07 09:30: nothing at rest, "Gedrückt halten" only after a short tap, right above that button
        assertEquals("centred over a button in the middle", 400f, HoldRule.hintLeft(buttonCenter = 500f, hintWidth = 200f, barWidth = 1000f), 0.01f)
        assertEquals("a button at the left edge: the hint starts at the bar's edge", 0f, HoldRule.hintLeft(50f, 200f, 1000f), 0.01f)
        assertEquals("a button at the right edge: the hint ends at the bar's edge", 800f, HoldRule.hintLeft(990f, 200f, 1000f), 0.01f)
        assertEquals("a hint wider than the bar starts at its edge", 0f, HoldRule.hintLeft(500f, 1200f, 1000f), 0.01f)
    }
}
