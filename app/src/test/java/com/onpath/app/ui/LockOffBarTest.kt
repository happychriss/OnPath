package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Navigate with the lock OFF (owner 2026-11-06, variant 2): no buttons in the status card; "⏸ Pause | ✎ Planen" at the
 * bottom; paused "Weiter · Beenden · round Planen" – ending only out of a pause; never a cut word. Plus the words
 * "Planen" and "Weiter" and the start screen's question as the title.
 */
class LockOffBarTest {
    private val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
    private fun xml(dir: String, file: String) = File("../strings/src/commonMain/composeResources/$dir/$file").readText()

    @Test fun lockOffShowsTheBarAndNoCardButtons() {
        assertTrue(nav, nav.contains("val lockOffBar = !lock && ui.recordedM != null"))
        assertTrue(nav.contains("if (lockOffBar) LockOffBar(ui.paused, onPause, onEnd"))
        assertTrue("the card has no tour buttons with the lock off", nav.contains("ui.recordedM != null && !lockOffBar) ({ TourCardActions("))
    }

    @Test fun runningIsPauseAndPlan_pausedIsWeiterBeendenAndRoundPlan() {
        val f = bar.substringAfter("fun LockOffBar(").substringBefore("/** Layout rules of [LockOffBar]")
        assertTrue(f.contains("onPause(true)") && f.contains("Res.string.nav_action_pause") && f.contains("Res.string.nav_change_plan"))
        assertTrue(f.contains("onPause(false)") && f.contains("Res.string.common_resume") && f.contains("Res.string.nav_end_short"))
        assertTrue("Beenden asks first (onEnd = askEndTour)", f.contains("OutlinedButton(onClick = onEnd"))
        assertTrue("round blue Planen while paused", f.contains("FilledIconButton(onClick = onPlan") && f.contains("OnPathColors.ActionEdit"))
        assertTrue("green = navigation", f.contains("containerColor = OnPathColors.ActionGo"))
    }

    @Test fun layoutNeverCutsAWord() {
        // Pixel width ~390 dp: Pause / Planen (~45 / ~55 dp) fit with icons
        assertTrue(LockOffBarRules.runningWithIcons(45f, 55f, 358f))
        // a large font: words of 120 dp – no icons, still one row?
        assertFalse(LockOffBarRules.runningWithIcons(120f, 120f, 358f))
        assertTrue(LockOffBarRules.runningInOneRow(120f, 120f, 358f))
        assertFalse(LockOffBarRules.runningInOneRow(140f, 140f, 358f))
        // paused: Weiter / Beenden (~50 / ~68 dp) fit beside the round 56 dp button; a word of 130 dp does not
        assertTrue(LockOffBarRules.pausedInOneRow(50f, 68f, 358f))
        assertFalse(LockOffBarRules.pausedInOneRow(75f, 130f, 358f))
    }

    @Test fun wordsPlanenAndWeiter() {
        val de = xml("values-de", "strings_navigate.xml")
        assertTrue(de.contains("<string name=\"nav_change_plan\">Planen</string>"))
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub\">Pause · Beenden · Planen</string>"))
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub_paused\">Weiter · Beenden · Planen</string>"))
        assertTrue(xml("values-de", "strings_common.xml").contains("<string name=\"common_resume\">Weiter</string>"))
        // the interrupted tour after a restart keeps its own word (another case)
        assertTrue(xml("values-de", "strings_routes.xml").contains("<string name=\"routes_unfinished_continue\">Fortsetzen</string>"))
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
