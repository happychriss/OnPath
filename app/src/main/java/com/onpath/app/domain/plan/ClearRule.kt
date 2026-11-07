package com.onpath.app.domain.plan

/**
 * "Neu planen" / clearing the plan WITHOUT a tour: the followed line goes with the plan when it came from the plan – also
 * when it was the way there ("Guide me") or the app restarted meanwhile (followFromPlan lives in memory only; Pixel
 * 2026-11-01: a blue line without points stayed). A shown GPX track keeps its own line. During a tour
 * [com.onpath.app.ui.PlanGuidance.clearEndsGuidance] decides (every guidance ends, the recording goes on).
 */
object ClearRule {
    fun dropsFollowWithoutTour(following: Boolean, recording: Boolean, followFromPlan: Boolean, followIsPlanLine: Boolean,
                               followIsTrack: Boolean): Boolean =
        following && !recording && (followFromPlan || followIsPlanLine || !followIsTrack)
}
