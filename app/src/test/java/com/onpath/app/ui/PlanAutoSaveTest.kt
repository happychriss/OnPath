package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06: the tour and the plan are two things (like Komoot / Strava). "Los geht's" saves a plan never saved;
 * the end of a tour neither asks about the plan nor takes it off the map.
 */
class PlanAutoSaveTest {
    @Test fun aPlanNeverSavedIsSavedAtTheStart() =
        assertTrue(PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = false))

    @Test fun aSavedPlanChangedSinceIsNotOverwrittenSilently() =
        assertFalse(PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = true))

    @Test fun aSavedUnchangedPlanIsLeftAlone() =
        assertFalse(PlanAutoSave.atStart(points = 3, unsaved = false, changesASavedPlan = true))

    @Test fun noPlanNothingToSave() = assertFalse(PlanAutoSave.atStart(points = 1, unsaved = true, changesASavedPlan = false))

    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    @Test fun losGehtsSavesTheNewPlan() =
        assertTrue(vm.contains("PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); saveNewPlanAtStart() }"))

    @Test fun theEndOfATourLeavesThePlanAlone() {
        for (f in listOf("fun endTour() {", "fun endTourAndDelete() {")) {
            val b = body(f)
            assertFalse("$f must not open Save's dialog: $b", b.contains("requestSave") || b.contains("askSave"))
        }
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertFalse("the plan stays on the map after the tour", finished.contains("clearPlan()"))
        val sheet = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        assertFalse("the end sheet asks nothing about the plan", sheet.contains("EndPlanOption") || sheet.contains("routeLabel"))
    }

    /** "Tour beenden und laden": the route asked for loads after the tour – an unsaved plan asks first (save-paths P3). */
    @Test fun loadingAfterTheTourAsksForAnUnsavedPlan() {
        val finished = vm.substringAfter("RecordingService.finished.collect { id ->").substringBefore("fun initialCamera()")
        assertTrue(finished, finished.contains("unlessUnsaved(\"Load \\\"\${t.name}\\\" after the tour\") { openTour(t) }"))
    }
}
