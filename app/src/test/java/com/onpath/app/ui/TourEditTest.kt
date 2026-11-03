package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan during a tour = the same tile bar as Navigate (owner 2026-11-02, refines onpath.private#19): green "Navigation
 * fortsetzen", blue "Route speichern", red "Änderungen verwerfen" – back to the plan as it was when "Route ändern"
 * opened, then Navigate; greyed while nothing changed. "✕ Neu planen" and ☰ → Save route are hidden during a tour.
 */
class TourEditTest {
    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.18, 6.94)
    private val c = GeoPoint(51.19, 6.95)
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()

    @Test fun theSnapshotIsTakenWhenATourSwitchesToPlan() {
        assertEquals(listOf(a, b), TourEdit.startOf(AppMode.NAVIGATE, AppMode.PLAN, touring = true, plan = listOf(a, b), current = null))
        // edits in Plan keep the snapshot of the switch
        assertEquals(listOf(a, b), TourEdit.startOf(AppMode.PLAN, AppMode.PLAN, true, listOf(a, b, c), listOf(a, b)))
        // back to Navigate, or no tour: none
        assertNull(TourEdit.startOf(AppMode.PLAN, AppMode.NAVIGATE, true, listOf(a, b, c), listOf(a, b)))
        assertNull(TourEdit.startOf(AppMode.NAVIGATE, AppMode.PLAN, touring = false, plan = listOf(a, b), current = null))
    }

    @Test fun discardIsLiveOnlyAfterAChange() {
        assertFalse(TourEdit.changed(null, listOf(a, b)))
        assertFalse(TourEdit.changed(listOf(a, b), listOf(a, b)))
        assertTrue(TourEdit.changed(listOf(a, b), listOf(a, b, c)))
    }

    @Test fun discardRestoresThenNavigates() {
        val vm = src("ui/AppViewModel.kt")
        val enter = vm.substringAfter("private fun enter(m: AppMode)").substringBefore("\n    }\n")
        assertTrue(enter, enter.contains("tourEditStart = TourEdit.startOf(was, m, recState.value != null, waypoints.toList(), tourEditStart)"))
        val discard = vm.substringAfter("fun discardTourEdits()").substringBefore("\n    }\n")
        assertTrue(discard, discard.indexOf("set(start)") in 0 until discard.indexOf("continueNavigation()"))
    }

    @Test fun theBarIsTheSameTilesAndHidesNewPlanAndMenuSave() {
        val bar = src("ui/TourActionBar.kt").substringAfter("fun TourEditBar(").substringBefore("\n}\n")
        for (t in listOf(
            "Res.string.nav_continue_navigation), OnPathColors.ActionGo",
            "Res.string.plan_save_route), OnPathColors.ActionEdit, enabled = onSave != null",
            "Res.string.nav_discard_changes), OnPathColors.ActionStop, enabled = changed",
        )) assertTrue(t, bar.contains(t))
        assertTrue(bar.contains("ActionTiles("))
        val panel = src("ui/PlanPanel.kt")
        assertTrue(panel.contains("if (ui.tour == null) PlanAction(Icons.Filled.Close, str(Res.string.plan_close_plan)"))
        assertTrue(src("ui/MapScreen.kt").contains("onSaveRoute = if (rec == null && RouteRules.saveInMenu(vm.waypoints.size)) vm::requestSave else null"))
    }
}
