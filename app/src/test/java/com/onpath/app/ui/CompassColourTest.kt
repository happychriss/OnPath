package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map compass needle (owner 2026-11-07: blue, not red): ink-blue north, walnut-grey south, both readable on paper. */
class CompassColourTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun northIsInkBlueAndNeverRed() {
        assertEquals(OnPathColors.ActionEdit, OnPathColors.CompassNorth)
        assertNotEquals(OnPathColors.Bad, OnPathColors.CompassNorth)
        assertNotEquals(OnPathColors.Error, OnPathColors.CompassNorth)
    }

    @Test fun bothHalvesStandOutOnThePaperButton() {
        assertTrue(contrast(OnPathColors.CompassNorth, OnPathColors.Paper) >= 3.0)
        assertTrue(contrast(OnPathColors.CompassSouth, OnPathColors.Paper) >= 3.0)
    }

    @Test fun theHalvesAreToldApart() {
        assertTrue(contrast(OnPathColors.CompassNorth, OnPathColors.CompassSouth) >= 1.8)
    }
}
