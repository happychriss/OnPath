package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map compass needle (owner 2026-11-07): ink-blue north, walnut-grey south, both readable on paper. */
class CompassColourTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Owner 2026-11-07: "blau, nicht rot" – the north half is ink blue, never red. Which colour it is, is not pinned here
     * (owner 2026-11-20: the pictures `ScreenshotTest_mapControlsAll.png` and `GermanScreenshotTest_plan.png` show it);
     * what stays is that both halves read on the paper button.
     */
    @Test fun bothHalvesStandOutOnThePaperButton() {
        assertTrue(contrast(OnPathColors.CompassNorth, OnPathColors.Paper) >= 3.0)
        assertTrue(contrast(OnPathColors.CompassSouth, OnPathColors.Paper) >= 3.0)
    }

    @Test fun theHalvesAreToldApart() {
        assertTrue(contrast(OnPathColors.CompassNorth, OnPathColors.CompassSouth) >= 1.8)
    }
}
