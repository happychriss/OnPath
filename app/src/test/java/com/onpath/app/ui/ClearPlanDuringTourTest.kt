package com.onpath.app.ui

import com.onpath.app.domain.plan.ClearRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ✕ Neu planen during a tour (owner 2026-11-05: he saw "Navigate without a route" – is that what the app does?): every
 * guidance ends, the recording never stops; owner 2026-11-06: no question any more, the plan goes under Zuletzt, Undo
 * brings it back (E2E CoreFieldE2ETest.clearingThePlanDuringATourAsksNothing). The empty sheet ("Die Tour läuft weiter")
 * and Navigate's free tour line are in the screenshot frames `planEmptyDuringTour` / `navigateFreeTour`.
 */
class ClearPlanDuringTourTest {
    @Test fun clearingThePlanDuringATourEndsThePlansGuidance() {
        assertTrue(PlanGuidance.endsGuidance(0, recording = true, following = true, followIsTrack = false))
    }

    @Test fun clearingEndsEveryGuidanceButNeverTheRecording() {
        // the gap: a followed track was untouched by the empty-plan rule
        assertFalse(PlanGuidance.endsGuidance(0, recording = true, following = true, followIsTrack = true))
        assertTrue(PlanGuidance.clearEndsGuidance(recording = true, following = true))
        assertFalse("no tour: the rule below decides", PlanGuidance.clearEndsGuidance(recording = false, following = true))
    }

    /** Pixel 2026-11-01: Close plan after an app restart left the old followed line (the way there) without points. */
    @Test fun withoutATourTheFollowedLineGoesWithThePlan() {
        // the way there ("Guide me") or a line from before an app restart: it goes
        assertTrue(ClearRule.dropsFollowWithoutTour(following = true, recording = false, followFromPlan = false,
            followIsPlanLine = false, followIsTrack = false))
        assertTrue(ClearRule.dropsFollowWithoutTour(true, false, followFromPlan = true, followIsPlanLine = false, followIsTrack = true))
        assertTrue(ClearRule.dropsFollowWithoutTour(true, false, followFromPlan = false, followIsPlanLine = true, followIsTrack = true))
        // a shown GPX track keeps its own line
        assertFalse(ClearRule.dropsFollowWithoutTour(true, false, followFromPlan = false, followIsPlanLine = false, followIsTrack = true))
        // during a tour PlanGuidance.clearEndsGuidance decides; nothing followed: nothing to drop
        assertFalse(ClearRule.dropsFollowWithoutTour(true, recording = true, followFromPlan = true, followIsPlanLine = true, followIsTrack = false))
        assertFalse(ClearRule.dropsFollowWithoutTour(following = false, recording = false, followFromPlan = true, followIsPlanLine = false, followIsTrack = false))
    }
}
