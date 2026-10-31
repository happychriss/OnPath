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
}
