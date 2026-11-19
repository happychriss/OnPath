package com.onpath.app.ui

import com.onpath.app.domain.PlanAutoSave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-06: the tour and the plan are two things (like Komoot / Strava). "Los geht's" saves a plan never saved;
 * the end of a tour neither asks about the plan nor takes it off the map. What "Los geht's" does with each kind of plan
 * (never saved, from Zuletzt, saved and unchanged, no plan) is a behaviour test on the real `SaveSlice.atTourStart` now
 * (`:state` `SaveFlowTest.losGehts…`); here stay the owner's 2026-11-07 decision on the rule and the view model's call.
 */
class PlanAutoSaveTest {
    /**
     * Owner 2026-11-07: a loaded saved plan changed and started with "Los geht's" updates the ORIGINAL route (like
     * ☰ Plan speichern) – no "Name (2)" copies; the version before goes under Zuletzt.
     */
    @Test fun aSavedPlanChangedSinceUpdatesTheOriginal() =
        assertEquals(PlanAutoSave.AtStart.SAVE_OVERWRITE, PlanAutoSave.atStart(points = 3, unsaved = true, changesASavedPlan = true))

    // the start moved to the tour session slice (piece 19); the flow is :state TourSessionFlowTest.losGehtsOnAPlanFollowsItSecuresTheCorridorSavesThenSendsSTART
    private val vm = File("../state/src/main/kotlin/com/onpath/app/ui/tour/TourSession.kt").readText()

    @Test fun losGehtsSavesTheNewPlan() =
        assertTrue(vm.contains("PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); save.atTourStart() }"))

    // the end of a tour asks nothing about the plan and clears it the "Neu planen" way (owner 2026-11-07): E2E
    // CoreFieldE2ETest.endTourSavesTheTourKeepsThePlanAndClearsTheMap / endTourDeleteBinsTheTourAndKeepsThePlan /
    // arrivalEndTourBringsTheStartScreen
}
