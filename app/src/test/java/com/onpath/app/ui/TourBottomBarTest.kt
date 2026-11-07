package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tour's bottom bar in Navigate, unlocked or with the lock OFF (owner 2026-11-06, variant 2): no buttons in the status card; "⏸ Anhalten | ✎ Planen" at the
 * bottom; stopped "▶ Weiter · ■ Tour beenden · ✎ Planen" – ending only out of a stop; never a cut word. Plus the words
 * "Planen" and "Weiter" and the start screen's question as the title.
 */
class TourBottomBarTest {
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
    private fun xml(dir: String, file: String) = File("../strings/src/commonMain/composeResources/$dir/$file").readText()

    @Test fun layoutNeverCutsAWord() {
        // Pixel width ~390 dp: Pause / Planen (~45 / ~55 dp) fit with icons
        assertTrue(TourBottomBarRules.runningWithIcons(45f, 55f, 358f))
        // a large font: words of 120 dp – no icons, still one row?
        assertFalse(TourBottomBarRules.runningWithIcons(120f, 120f, 358f))
        assertTrue(TourBottomBarRules.runningInOneRow(120f, 120f, 358f))
        assertFalse(TourBottomBarRules.runningInOneRow(140f, 140f, 358f))
    }

    /**
     * Stopped (owner 2026-11-07, "Anhalten" variant 5): "▶ Weiter" · "■ Tour beenden" · "✎ Planen" with icons, today's
     * height; one row only when Weiter stays the widest, else Weiter full width over the two, a large font stacks all.
     */
    @Test fun stoppedRowWeiterIsTheWidest() {
        val r = TourBottomBarRules
        // EN "Resume" ~58 dp, "End tour" ~60 dp, "Plan" ~34 dp: one row on a 358 dp bar
        assertEquals(TourBottomBarRules.Stopped.ONE_ROW, r.stopped(r.stoppedNeed(58f), r.stoppedSide(60f, 34f), 358f))
        // DE "Weiter" ~48 dp, "Tour beenden" ~100 dp: the two sides leave Weiter too little → two rows
        assertEquals(TourBottomBarRules.Stopped.TWO_ROWS, r.stopped(r.stoppedNeed(48f), r.stoppedSide(100f, 52f), 358f))
        // a large font always stacks the three full width
        assertEquals(TourBottomBarRules.Stopped.STACK, r.stopped(r.stoppedNeed(96f), r.stoppedSide(110f, 100f), 358f, fontScale = 2f))
        // a word too long even for half the bar stacks too – never a cut word
        assertEquals(TourBottomBarRules.Stopped.STACK, r.stopped(r.stoppedNeed(48f), r.stoppedSide(200f, 52f), 358f))
        // short words still make a balanced group: the two side buttons are at least 96 dp
        assertEquals(96f, r.stoppedSide(20f, 20f), 0.01f)
    }

    @Test fun startScreenAsksWhereTo() {
        assertTrue(EmptyPlanTitle.asks(pointCount = 0, touring = false))
        assertFalse(EmptyPlanTitle.asks(pointCount = 1, touring = false))
        assertFalse(EmptyPlanTitle.asks(pointCount = 0, touring = true))
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertTrue("the title and the rolled-up row use it", panel.split("str(EmptyPlanTitle.of(ui))").size == 3)
        assertTrue(panel.contains("Res.string.plan_hint_first_point_question") && panel.contains("Res.string.plan_plan_a_route"))
    }
}
