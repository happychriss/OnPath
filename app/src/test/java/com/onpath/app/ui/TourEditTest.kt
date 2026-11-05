package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan during a tour (owner 2026-11-05, R5 – was the tile bar of 2026-11-02): the plan row's **"✕ Neu planen"** (owner
 * 2026-11-03, was "Änderungen verwerfen" – it doubled Undo), the green "▶ Weiter geht's": the plan is emptied, the recording goes on, the sheet shows how to begin again; a saved route clears without a
 * question (it stays in My routes), an unsaved plan asks "Discard changes?" first; Undo brings the plan back.
 * ☰ → Save route is the one Save, also during a tour.
 */
class TourEditTest {
    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.18, 6.94)
    private fun src(f: String) = File("src/main/java/com/onpath/app/$f").readText()
    private val vm = src("ui/AppViewModel.kt")

    /** Owner 2026-11-05 (R5): ✕ Neu planen in the plan row, the same with and without a tour – the one clear path. */
    @Test fun newPlanIsThePlanRowsClearThroughTheOneClearPath() {
        val panel = src("ui/PlanPanel.kt")
        val head = panel.substringAfter("private fun PlanHead(").substringBefore("\n}\n")
        assertTrue(head, head.contains("val clear = str(Res.string.plan_close_plan)"))
        assertTrue(head, head.contains("PlanAction(Icons.Filled.Close, clear, canClear, cb.onClear)"))
        assertTrue(head, head.contains("PlanIconPill(Icons.Filled.Close, clear, canClear, cb.onClear)"))
        // the green button also resumes a paused recording
        assertTrue(panel.substringAfter("private fun goClick(").substringBefore("\n}\n")
            .contains("if (tour.paused) cb.onPause(false); cb.onContinue()"))
        // the same path as without a tour: requestClear (saved → clear, unsaved → "Discard changes?")
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

    /** After "Neu planen" the summary step shows how to begin again – with and without a tour (owner 2026-11-03). */
    @Test fun theEmptyPlanDuringATourShowsTheHint() {
        val body = src("ui/PlanPanel.kt").substringAfter("private fun ColumnScope.PlanBody(").substringBefore("\n}\n")
        assertTrue(body, body.contains("if (ui.pointCount == 0) Box(") && body.contains("FirstPointHint()"))
        assertTrue("before the summary step's end", body.indexOf("FirstPointHint()") < body.indexOf("stepEnd(PlanSize.MINI.step)"))
    }

    /** Owner 2026-11-05: the sheet has no Save – ☰ → Save route is the one Save, also during a tour. */
    @Test fun saveIsInTheMenuAlsoDuringATour() {
        assertTrue(src("ui/MapScreen.kt").contains("onSaveRoute = if (RouteRules.saveInMenu(vm.waypoints.size)) vm::requestSave else null"))
        assertFalse(src("ui/MapScreen.kt").contains("rec == null && RouteRules.saveInMenu"))
        assertFalse(Regex("onSave\\w*").containsMatchIn(src("ui/PlanPanel.kt")))
    }
}
