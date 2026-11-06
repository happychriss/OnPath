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
    private val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
    private val bar = File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
    private fun xml(dir: String, file: String) = File("../strings/src/commonMain/composeResources/$dir/$file").readText()

    @Test fun runningIsPauseAndPlan_pausedIsWeiterBeendenAndRoundPlan() {
        val f = bar.substringAfter("fun TourBottomBar(").substringBefore("/** Layout rules of [TourBottomBar]")
        assertTrue(f.contains("onPause(true)") && f.contains("Res.string.nav_action_pause") && f.contains("Res.string.nav_change_plan"))
        assertTrue(f.contains("onPause(false)") && f.contains("Res.string.common_resume") && f.contains("Res.string.nav_end_short"))
        assertTrue("Beenden asks first (onEnd = askEndTour)", f.contains("OutlinedButton(onClick = onEnd"))
        assertTrue("round blue Planen while paused", f.contains("FilledIconButton(onClick = onPlan") && f.contains("OnPathColors.ActionEdit"))
        assertTrue("green = navigation", f.contains("containerColor = OnPathColors.ActionGo"))
    }

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

    @Test fun wordsPlanenAndWeiter() {
        val de = xml("values-de", "strings_navigate.xml")
        assertTrue(de.contains("<string name=\"nav_change_plan\">Planen</string>"))
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub\">Pause · Beenden · Planen</string>"))
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub_paused\">Weiter · Beenden · Planen</string>"))
        assertTrue(xml("values-de", "strings_common.xml").contains("<string name=\"common_resume\">Weiter</string>"))
        // owner 2026-11-06 (consistency): "Weiter" also after a restart, at the end sheet's way back – one word for going on
        assertTrue(xml("values-de", "strings_routes.xml").contains("<string name=\"routes_unfinished_continue\">Weiter</string>"))
        assertTrue(!de.contains("„Fortsetzen“"))
        val tourBar = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        assertTrue(tourBar.contains("Txt.s(Res.string.nav_continue) // owner 2026-11-06"))
    }

    @Test fun pillSaysPlanenAndNoRecBadge() {
        val top = File("src/main/java/com/onpath/app/ui/PlanTopBar.kt").readText()
        assertTrue(top.contains("val mode = str(Res.string.plan_mode_label).uppercase()"))
        assertTrue(!nav.contains("RecBadge(") && !File("src/main/java/com/onpath/app/ui/TourBar.kt").readText().contains("fun RecBadge("))
        assertTrue("paused: the icon is grey", nav.contains("tint = if (ui.paused && ui.recordedM != null) OnPathColors.Grey else OnPathColors.Green"))
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
