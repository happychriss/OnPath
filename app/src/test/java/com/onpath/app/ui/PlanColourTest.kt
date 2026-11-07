package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan's colours (owner 2026-11-06 23:00, variant C of the blue page): ink blue #335A85 (a tick lighter than #2B4C6F, owner 2026-11-07) for everything that plans, no light
 * blue surfaces and no blue glow – Plan is told by its signs (PLANEN pill, the sheet's blue edge, blue buttons). Values
 * only; where the tokens are used shows the screenshot tests.
 */
class PlanColourTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun inkBlueIsThePlanColour() {
        assertEquals(Color(0xFF335A85), OnPathColors.ActionEdit)
        assertEquals(OnPathColors.ActionEdit, OnPathColors.PlanAccent)
        assertEquals("the FRAGE edge is the same ink blue", OnPathColors.ActionEdit, OnPathColors.InfoBlue)
    }

    @Test fun noBlueSurfaces() {
        assertEquals(OnPathColors.Paper, OnPathColors.PlanTint)
        assertEquals(OnPathColors.Paper, PlanTopBand.Color)
        assertEquals(OnPathColors.PaperDeep, PillBlueLight)
    }

    @Test fun readable() {
        assertTrue("white on the ink blue", contrast(OnPathColors.CardWhite, OnPathColors.ActionEdit) >= 7.0)
        // owner 2026-11-07 chose #335A85 (6.8:1) over the 8.5:1 that looked almost black on the phone – still well above 4.5
        assertTrue("ink blue on paper", contrast(OnPathColors.ActionEdit, OnPathColors.Paper) >= 6.0) // 6.4:1 on paper B
        assertTrue("pill words", contrast(PillBlueInk, PillBlueLight) >= 5.5) // 5.9:1 with #335A85 on PaperDeep
        assertTrue("tonal plan button", contrast(OnPathColors.OnEditTonal, OnPathColors.EditTonal) >= 7.0)
        assertTrue("tags", contrast(OnPathColors.TagInk, OnPathColors.TagContainer) >= 7.0)
        assertTrue("Ohne Netz ✓ on the pill", contrast(OnPathColors.OnPlanGood, OnPathColors.PlanAccent) >= 4.5)
        assertTrue("Ohne Netz ⚠ on the pill", contrast(OnPathColors.OnPlanWarn, OnPathColors.PlanAccent) >= 4.5)
    }

    @Test fun planAndGoAreTwoHues() {
        // forest green vs ink blue: blue channel clearly apart, also for red-green weakness (luminance alone is close)
        val go = OnPathColors.ActionGo; val edit = OnPathColors.ActionEdit
        assertTrue(edit.blue - edit.green > 0.1f && go.green - go.blue > 0.05f)
    }

    /** Selected chips / segments are neutral (owner 2026-11-06: neutral = walnut), readable. */
    @Test fun selectedChipsAreWalnut() {
        assertEquals(OnPathColors.WalnutLight, OnPathThemeValues.colors.secondaryContainer)
        assertTrue(contrast(OnPathThemeValues.colors.onSecondaryContainer, OnPathThemeValues.colors.secondaryContainer) >= 7.0)
    }
}
