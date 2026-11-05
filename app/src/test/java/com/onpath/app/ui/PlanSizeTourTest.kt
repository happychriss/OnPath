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

    /** Owner 2026-11-05 (R5): during a tour the sheet rolls up too – one row with "▶ Weiter geht's". */
    @Test fun duringATourTheSheetRollsUpToo() {
        val tour = PlanUi(2, null, false, null, canUndo = true, canRedo = false,
            activity = com.onpath.app.routing.Activity.HIKE, size = PlanSize.COLLAPSED, tourRunning = true,
            tour = TourState(1_000.0, 600, paused = false))
        assertEquals(PlanSize.COLLAPSED, tour.shownSize)
        assertEquals(true, tour.collapsed)
        PlanSize.entries.forEach { assertEquals(it, tour.copy(size = it).shownSize) }
    }

    @Test fun aPlanOpensAtTheSummaryAndStaysRolledUp() {
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertEquals(true, vm.contains("var planSize by mutableStateOf(PlanSize.MINI)"))
        // nothing but the user (and an emptied plan) opens a rolled-up sheet: no route update sets the size
        val sets = Regex("planSize = PlanSize\\.([A-Z_]+)\\b").findAll(vm).map { it.groupValues[1] }.toList()
        // Points list row tap → COMPACT; clearPlan → MINI; a tap on the open map → MINI (the "hold to set a point" line, owner 2026-11-03);
        // a tap on the "Ohne Netz" pill → MINI (its line sits in the summary step, owner 2026-11-03)
        assertEquals(listOf("COMPACT", "MINI", "MINI", "MINI"), sets.sorted())
    }

    @Test fun statusBreaksOnlyAfterADot() {
        // never "1,7 / km" at a large font (owner: numbers in full)
        assertEquals("Tour\u00A0läuft\u00A0· 1,7\u00A0km\u00A0· 0:14:38", breakOnlyAtDots("Tour läuft · 1,7 km · 0:14:38"))
    }

    @Test fun continueSitsBesideTheStatusUnlessThatIsTaller() {
        assertEquals(true, TourRowFit.beside(statusBesideH = 50, statusFullH = 50, buttonH = 140))   // normal font: one row
        assertEquals(true, TourRowFit.beside(statusBesideH = 200, statusFullH = 200, buttonH = 150)) // two lines either way
        assertEquals(false, TourRowFit.beside(statusBesideH = 300, statusFullH = 200, buttonH = 150)) // three lines beside
    }

    @Test fun endTourIsNeverCut() {
        assertEquals(true, TourRowFit.sideBySide(pauseW = 300, endW = 400, width = 1000, gap = 20))
        // large font: „Tour beenden“ wider than half the row → one under the other
        assertEquals(false, TourRowFit.sideBySide(pauseW = 400, endW = 520, width = 1000, gap = 20))
    }

    /**
     * Owner 2026-11-01 (night draft): the slider in Navigate unlocks Pause · Plan ändern · Beenden – words, no ■; End
     * opens the same "Aufnahme beenden?" question as before. The plan sheet during a tour has only "Navigation
     * fortsetzen" (+ "Route speichern" with a plan) – no Pause / End there any more.
     */
    @Test fun theSliderUnlocksPauseChangeRouteAndEnd() {
        val nav = java.io.File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertEquals(true, nav.contains("onPause = vm::pauseRecording, onEnd = { vm.askEndTour() }"))
        // owner 2026-11-05 (Navigate C): Pause / Tour beenden in the status card, "Plan ändern" where the slider was
        assertEquals(true, nav.contains("TourCardActions(ui.paused, onPause, onEnd, onClose = { open = false })"))
        assertEquals(true, nav.contains("ChangePlanButton({ open = false; onSlideToPlan() }, bottom)"))
        val bar = java.io.File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
        for (s in listOf("Res.string.nav_action_pause", "Res.string.common_resume", "Res.string.nav_change_plan", "Res.string.nav_end_tour"))
            assertEquals(s, true, bar.contains(s))
        val panel = java.io.File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertEquals(false, panel.contains("TourPauseEnd("))
        assertEquals(false, panel.contains("cb.onEndTour"))
    }

    @Test fun aTapOnTheSliderOnlyNudgesTheThumb() {
        assertEquals(true, SlideThreshold.NUDGE < SlideThreshold.COMPLETE)
        assertEquals(false, SlideThreshold.isComplete(SlideThreshold.NUDGE * 300f, 300f))
    }
}
