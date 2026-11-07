package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan sheet during a tour (owner 2026-10-31): opens at its one-row step; End tour is one drag (or handle tap) away. */
class PlanSizeTourTest {
    @Test fun backInPlanDuringATourOpensAtTheOneRowStep() = PlanSize.entries.forEach {
        assertEquals(PlanSize.MINI, PlanSize.backInPlan(it, tourRunning = true))
    }

    @Test fun backInPlanWithoutATourKeepsTheSheet() = PlanSize.entries.forEach {
        assertEquals(it, PlanSize.backInPlan(it, tourRunning = false))
    }

    @Test fun endTourIsOneStepUp() {
        // one drag (or a tap on the handle) from the one-row step reaches the step with Pause / End tour
        assertEquals(PlanSize.COMPACT, PlanSize.MINI.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.ofStep(PlanSize.MINI.step + 1))
    }

    @Test fun afterTheTourTheSheetShowsTheProfileAgain() {
        assertEquals(PlanSize.COMPACT, PlanSize.afterTour(PlanSize.MINI))
        assertEquals(PlanSize.COMPACT, PlanSize.afterTour(PlanSize.COMPACT))
        assertEquals(PlanSize.FULL, PlanSize.afterTour(PlanSize.FULL))
    }

    /** Owner 2026-10-31: the rolled-up step below the summary; a tap / drag up opens the summary. */
    @Test fun rolledUpIsTheSmallestStepAndOpensToTheSummary() {
        assertEquals(listOf(PlanSize.COLLAPSED, PlanSize.MINI, PlanSize.COMPACT, PlanSize.FULL),
            PlanSize.entries.sortedBy { it.step })
        PlanSize.entries.forEach { assertEquals(it, PlanSize.ofStep(it.step)) }
        assertEquals(PlanSize.MINI, PlanSize.COLLAPSED.tapped())
        assertEquals(PlanSize.MINI, PlanSize.ofStep(PlanSize.COLLAPSED.step + 1))
    }

    /**
     * Navigate's one bottom bar (owner 2026-11-06): Pause | Planen, paused Weiter · Beenden · ✎ – words, no ■; Beenden
     * opens the end sheet. The plan sheet during a tour has no Pause / End.
     */
    @Test fun theBottomBarHasPausePlanAndEnd() {
        val nav = java.io.File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertEquals(true, nav.contains("onPause = vm::pauseRecording, onEnd = { vm.askEndTour() }"))
        // owner 2026-11-06: one bottom bar where the slider was – Pause | Planen, paused Weiter · Beenden · ✎
        val bar = java.io.File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
        for (s in listOf("Res.string.nav_action_pause", "Res.string.common_resume", "Res.string.nav_change_plan", "Res.string.nav_end_short"))
            assertEquals(s, true, bar.contains(s))
        val panel = java.io.File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertEquals(false, panel.contains("cb.onEndTour"))
    }
}
