package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ✕ Neu planen during a tour (owner 2026-11-05: he saw "Navigate without a route" – is that what the app does?).
 *
 * Reproduced: AppViewModel.clearPlan() during a recording goes through change { clear() } → endGuidanceIfPlanEmpty(): a tour
 * that followed the PLAN's route already lost its guidance (Navigate = free tour) – that is what the owner saw, and it is
 * wanted. The gap: a followed GPX track (followIsTrack) and an already empty plan kept their guidance after "Neu planen".
 * Now every guidance ends; the recording never stops; the question says so first.
 */
class ClearPlanDuringTourTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    @Test fun clearingThePlanDuringATourEndsThePlansGuidance() {
        // today's path (kept): the plan's route followed → empty plan ends it
        assertTrue(PlanGuidance.endsGuidance(0, recording = true, following = true, followIsTrack = false))
        val clear = body("fun clearPlan() {")
        assertTrue(clear, clear.contains("change { clear() }"))
        assertTrue(body("private fun change(").contains("endGuidanceIfPlanEmpty()"))
    }

    @Test fun clearingEndsEveryGuidanceButNeverTheRecording() {
        // the gap: a followed track was untouched by the empty-plan rule
        assertFalse(PlanGuidance.endsGuidance(0, recording = true, following = true, followIsTrack = true))
        assertTrue(PlanGuidance.clearEndsGuidance(recording = true, following = true))
        assertFalse("no tour: the old rule (fromPlan) decides", PlanGuidance.clearEndsGuidance(recording = false, following = true))
        val clear = body("fun clearPlan() {")
        assertTrue(clear, clear.contains("PlanGuidance.clearEndsGuidance(recState.value != null, followLine != null)") &&
            clear.contains("clearFollow()") && clear.contains("guidanceEndedByEmptyPlan = true"))
        listOf("stopRecording", "endTour", "ACTION_STOP").forEach { assertFalse(it, clear.contains(it)) }
    }

    @Test fun theQuestionSaysTheTourGoesOn() {
        assertTrue(ClearAsk.asks(touring = true, planPoints = 3))
        // owner 2026-11-06: without a tour nothing asks – a plan not saved goes under My routes → Zuletzt
        assertFalse(ClearAsk.asks(touring = false, planPoints = 3))
        assertFalse("nothing to clear", ClearAsk.asks(touring = true, planPoints = 0))
        val dialogs = File("src/main/java/com/onpath/app/ui/RouteDialogs.kt").readText()
        assertTrue(dialogs.contains("if (vm.discardAsk) DiscardPlanDialog(onDiscard = vm::discardConfirmed, onCancel = { vm.discardAsk = false })"))
        assertTrue(dialogs.contains("Res.string.plan_clear_tour_title") && dialogs.contains("Res.string.plan_clear_tour_text") &&
            dialogs.contains("Res.string.plan_clear_tour_confirm"))
    }

    @Test fun theEmptySheetAndNavigateSayItPlainly() {
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertTrue(panel.contains("if (ui.tour != null || ui.tourRunning) TourGoesOnHint() else FirstPointHint()"))
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("if (ui.toGo == null && ui.recordedM != null) Text(str(Res.string.nav_free_tour)"))
    }
}
