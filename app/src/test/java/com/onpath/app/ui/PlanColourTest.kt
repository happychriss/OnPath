package com.onpath.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03 (design page §3, version 3 "deutlich", Plan only): Plan is blue – light blue sheet, search pill, ☰ and
 * map buttons, a soft edge (no band at the top, owner 2026-11-03), the "PLANEN" / "ROUTE ÄNDERN" label and the blue position sign
 * while the route is changed during a tour. Navigate stays as it is.
 */
class PlanColourTest {
    private fun src(f: String) = File("src/main/java/com/onpath/app/ui/$f").readText()
    private val planTokens = listOf("PlanTint", "PlanAccent", "PlanEdge", "PlanFrame", "PlanModeLabel")

    @Test fun navigateUsesNoPlanBlue() {
        for (f in listOf("NavigateScreen.kt", "TourActionBar.kt", "SlideToUnlock.kt")) {
            val s = src(f)
            for (t in planTokens) assertFalse("$f uses $t", s.contains(t))
        }
        // the map buttons are blue only in the Plan layer
        assertTrue(src("MapScreen.kt").contains("plan = mode == AppMode.PLAN"))
    }

    @Test fun planIsBlue() {
        val map = src("MapScreen.kt")
        assertTrue("soft edge in PlanMode", map.substringAfter("private fun PlanMode(").contains("PlanFrame("))
        assertTrue("the label says Plan ändern during a tour", map.contains("editing = rec != null"))
        val top = src("PlanTopBar.kt")
        assertTrue(top.contains("val pill: androidx.compose.ui.graphics.Color = OnPathColors.PlanTint") && top.contains("color = LocalTopBarLook.current.pill") && top.contains("tint = OnPathColors.PlanAccent"))
        assertTrue(src("PlanPanel.kt").contains("surfaceColor = OnPathColors.PlanTint, accent = OnPathColors.PlanAccent"))
        val footer = src("MapFooter.kt")
        assertTrue(footer.contains("if (plan) OnPathColors.PlanTint else MaterialTheme.colorScheme.surface"))
        assertTrue("the position sign while changing the route is blue",
            src("PlanPuck.kt").contains("val blue = OnPathColors.PlanAccent") && !src("PlanPuck.kt").contains("OnPathColors.Green"))
    }

    @Test fun textStaysReadableOnTheTint() {
        fun contrast(a: Color, b: Color): Double {
            val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }
        assertTrue(contrast(OnPathColors.Ink, OnPathColors.PlanTint) >= 4.5)
        assertTrue(contrast(OnPathColors.InkMuted, OnPathColors.PlanTint) >= 4.5)
        assertTrue("the label: white on the accent", contrast(OnPathColors.CardWhite, OnPathColors.PlanAccent) >= 4.5)
        assertTrue("blue icons on the tint", contrast(OnPathColors.PlanAccent, OnPathColors.PlanTint) >= 4.5)
    }

    /** Owner 2026-11-03: the half-transparent blue band at the top goes. */
    @Test fun noBandAtTheTop() {
        val bar = java.io.File("src/main/java/com/onpath/app/ui/PlanTopBar.kt").readText()
        assertTrue(!bar.contains("PlanBand") && !bar.contains("PLAN_BAND"))
    }
}
