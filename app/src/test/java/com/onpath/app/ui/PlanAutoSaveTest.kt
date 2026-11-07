package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06: the tour and the plan are two things (like Komoot / Strava). "Los geht's" saves a plan never saved;
 * the end of a tour neither asks about the plan nor takes it off the map.
 */
class PlanAutoSaveTest {
    private val NEW = PlanAutoSave.AtStart.SAVE_NEW
    private val COPY = PlanAutoSave.AtStart.SAVE_COPY
    private val NOTHING = PlanAutoSave.AtStart.NOTHING

    @Test fun aPlanNeverSavedIsSavedAtTheStart() =
        assertEquals(NEW, PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = false))

    /** Owner 2026-11-06 (Zuletzt): never overwritten silently – the ridden version is a new plan "Name (2)". */
    @Test fun aSavedPlanChangedSinceIsSavedAsACopy() =
        assertEquals(COPY, PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = true))

    @Test fun aSavedUnchangedPlanIsLeftAlone() =
        assertEquals(NOTHING, PlanAutoSave.atStart(points = 3, unsaved = false, changesASavedPlan = true))

    /** A plan loaded from Zuletzt is saved when you ride it (like Komoot), changed or not. */
    @Test fun aPlanFromZuletztIsSavedAtTheStart() {
        assertEquals(NEW, PlanAutoSave.atStart(points = 3, unsaved = false, changesASavedPlan = false, fromDraft = true))
        assertEquals(NEW, PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = false, fromDraft = true))
    }

    @Test fun noPlanNothingToSave() = assertEquals(NOTHING, PlanAutoSave.atStart(points = 1, unsaved = true, changesASavedPlan = false))

    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vm.substringAfter(start).substringBefore("\n    }\n")

    @Test fun losGehtsSavesTheNewPlan() =
        assertTrue(vm.contains("PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); saveNewPlanAtStart() }"))

    // the end of a tour leaves the plan alone: E2E CoreFieldE2ETest.endTourKeepsThePlanSavedAtTheStart / endTourDeleteBinsOnlyTheTour
}
