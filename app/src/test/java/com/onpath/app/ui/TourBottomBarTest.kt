package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour's bottom bar in Navigate, unlocked or with the lock OFF (owner 2026-11-06, variant 2): no buttons in the status card; "⏸ Anhalten | ✎ Planen" at the
 * bottom; stopped "▶ Weiter · ■ Tour beenden" (owner 2026-11-26) – ending only out of a stop; never a cut word. Plus the words
 * "Planen" and "Weiter" and the start screen's question as the title.
 */
class TourBottomBarTest {
    @Test fun layoutNeverCutsAWord() {
        // Pixel width ~390 dp: Pause / Planen (~45 / ~55 dp) fit with icons
        assertTrue(TourBottomBarRules.runningWithIcons(45f, 55f, 358f))
        // a large font: words of 120 dp – no icons, still one row?
        assertFalse(TourBottomBarRules.runningWithIcons(120f, 120f, 358f))
        assertTrue(TourBottomBarRules.runningInOneRow(120f, 120f, 358f))
        assertFalse(TourBottomBarRules.runningInOneRow(140f, 140f, 358f))
    }

    @Test fun startScreenAsksWhereTo() {
        assertTrue(EmptyPlanTitle.asks(pointCount = 0, touring = false))
        assertFalse(EmptyPlanTitle.asks(pointCount = 1, touring = false))
        assertFalse(EmptyPlanTitle.asks(pointCount = 0, touring = true))
    }
}
