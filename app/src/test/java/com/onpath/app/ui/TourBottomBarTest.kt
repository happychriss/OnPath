package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The tour's bottom bar in Navigate, unlocked or with the lock OFF (owner 2026-11-06, variant 2): no buttons in the status card; "⏸ Pause | ✎ Planen" at the
 * bottom; paused "Weiter · Beenden · round Planen" – ending only out of a pause; never a cut word. Plus the words
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
        // paused: Weiter / Beenden (~50 / ~68 dp) fit beside the round 56 dp button; a word of 130 dp does not
        assertTrue(TourBottomBarRules.pausedInOneRow(50f, 68f, 358f))
        assertFalse(TourBottomBarRules.pausedInOneRow(75f, 130f, 358f))
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
