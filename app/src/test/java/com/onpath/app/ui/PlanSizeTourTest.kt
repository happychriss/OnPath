package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plan sheet during a tour (owner 2026-10-31): opens at its one-row step; End tour is one drag (or handle tap) away. */
class PlanSizeTourTest {
    /** Owner 2026-11-27: every way into Plan starts at the summary step (pills, no profile) – with or without a tour, after its end. */
    @Test fun comingToPlanAlwaysOpensAtTheSummaryStep() {
        assertEquals(PlanSize.MINI, PlanSize.ON_ARRIVAL)
        val session = java.io.File("../state/src/main/kotlin/com/onpath/app/ui/tour/TourSession.kt").readText()
        assertEquals(true, session.contains("if (m == AppMode.PLAN && was == AppMode.NAVIGATE) planEdit.planSize = PlanSize.ON_ARRIVAL"))
        val end = java.io.File("../state/src/main/kotlin/com/onpath/app/ui/tour/TourEndSlice.kt").readText()
        assertEquals(true, end.contains("host.planSize = PlanSize.ON_ARRIVAL"))
        assertEquals("the plan's first route rises to the same step", PlanSize.ON_ARRIVAL, SheetRise.TO)
    }

    @Test fun endTourIsOneStepUp() {
        // one drag (or a tap on the handle) from the one-row step reaches the step with Pause / End tour
        assertEquals(PlanSize.COMPACT, PlanSize.MINI.tapped())
        assertEquals(PlanSize.COMPACT, PlanSize.ofStep(PlanSize.MINI.step + 1))
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
        assertEquals(true, nav.contains("onPause = vm.tourEnd::pauseRecording, onEnd = { vm.tourEnd.askEndTour() }"))
        // owner 2026-11-06: one bottom bar where the slider was – Anhalten | Planen, stopped ▶ Weiter · ■ Tour beenden · ✎ Planen
        val bar = java.io.File("src/main/java/com/onpath/app/ui/TourActionBar.kt").readText()
        for (s in listOf("Res.string.nav_action_pause", "Res.string.common_resume", "Res.string.nav_change_plan", "Res.string.nav_end_tour"))
            assertEquals(s, true, bar.contains(s))
        val panel = java.io.File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertEquals(false, panel.contains("cb.onEndTour"))
    }
}
