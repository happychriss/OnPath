package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan during a tour = the same tile bar as Navigate (owner 2026-11-02, onpath.private#19): green "Navigation
 * fortsetzen", blue "Route speichern", red **"Neu planen"** (owner 2026-11-03, was "Änderungen verwerfen" – it doubled
 * Undo): the plan is emptied, the recording goes on, the sheet shows how to begin again; a saved route clears without a
 * question (it stays in My routes), an unsaved plan asks "Discard changes?" first; Undo brings the plan back.
 * "✕ Neu planen" in the pill row and ☰ → Save route are hidden during a tour (the tiles do it).
 */
class TourEditTest {
    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.18, 6.94)
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")

    @Test fun theRedTileIsNewPlanThroughTheOneClearPath() {
        val bar = src("ui/TourActionBar.kt").substringAfter("fun TourEditBar(").substringBefore("\n}\n")
        for (t in listOf(
            "Res.string.nav_continue_navigation), OnPathColors.ActionGo",
            "Res.string.plan_save_route), OnPathColors.ActionEdit, enabled = onSave != null",
            "Icons.Filled.Close, str(Res.string.plan_close_plan), OnPathColors.ActionStop, enabled = canNewPlan, onClick = onNewPlan",
        )) assertTrue(t, bar.contains(t))
        // variant B (owner 2026-11-03): one row of filled pills in the same colours
        assertTrue(bar.contains("ActionPills("))
        // the same path as ✕ New plan without a tour: requestClear (saved → clear, unsaved → "Discard changes?")
        val panel = src("ui/PlanPanel.kt")
        assertTrue(panel.contains("canNewPlan = ui.pointCount > 0, onNewPlan = cb.onClear"))
        assertTrue(src("ui/MapScreen.kt").contains("onClear = vm::requestClear"))
        assertTrue(vm.contains("fun requestClear() { if (planUnsaved) discardAsk = true else clearPlan() }"))
        // no second, separate discard path any more
        assertFalse(vm.contains("discardTourEdits"))
        assertFalse(File("src/main/java/com/onpath/app/ui/TourEdit.kt").exists())
    }

    /** Saved → no question; new or changed → "Discard changes?" first. */
    @Test fun onlyAnUnsavedPlanAsks() {
        assertFalse("saved, unchanged", RouteRules.isUnsaved(listOf(a, b), listOf(a, b)))
        assertTrue("new", RouteRules.isUnsaved(listOf(a, b), null))
        assertTrue("changed", RouteRules.isUnsaved(listOf(a, b, a), listOf(a, b)))
    }

    /** The recording goes on: an empty plan ends only the guidance (the followed line goes), Undo resumes it. */
    @Test fun theRecordingGoesOnAndTheFollowedLineGoes() {
        assertTrue(PlanGuidance.endsGuidance(planPoints = 0, recording = true, following = true, followIsTrack = false))
        assertFalse("a followed GPX track is not the plan", PlanGuidance.endsGuidance(0, true, true, followIsTrack = true))
        val end = vm.substringAfter("private fun endGuidanceIfPlanEmpty()").substringBefore("\n    }\n")
        assertTrue(end, end.contains("clearFollow()") && end.contains("guidanceEndedByEmptyPlan = true"))
        // clearPlan never stops the recording itself
        val clear = vm.substringAfter("fun clearPlan()").substringBefore("\n    }\n")
        assertFalse(clear, clear.contains("stopRecording") || clear.contains("endTour"))
        assertEquals(0, com.onpath.app.navigation.TourSplit.outcome(listOf(a, b), emptyList(), setOf(0)).let {
            if (it == com.onpath.app.navigation.TourSplit.Outcome.Empty) 0 else 1 })
    }

    /** After "Neu planen" the first step of the sheet shows how to begin again (the empty-plan hint). */
    @Test fun theEmptyPlanDuringATourShowsTheHint() {
        val body = src("ui/PlanPanel.kt").substringAfter("private fun ColumnScope.PlanBody(")
        val tourBlock = body.substringAfter("if (ui.tour != null) {").substringBefore("\n    }\n")
        assertTrue(tourBlock, tourBlock.contains("if (ui.pointCount == 0) Box(") && tourBlock.contains("FirstPointHint()"))
        assertTrue("before the first step's end", tourBlock.indexOf("FirstPointHint()") < tourBlock.indexOf("stepEnd(PlanSize.MINI.step)"))
    }

    @Test fun newPlanAndMenuSaveAreHiddenDuringATour() {
        assertTrue(src("ui/PlanPanel.kt").contains("if (ui.tour == null) PlanAction(Icons.Filled.Close, str(Res.string.plan_close_plan)"))
        assertTrue(src("ui/MapScreen.kt").contains("onSaveRoute = if (rec == null && RouteRules.saveInMenu(vm.waypoints.size)) vm::requestSave else null"))
    }
}
