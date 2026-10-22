package com.onpath.app.ui

/**
 * Owner rule (2026-10-22, after the field test's invisible followed route): what Plan shows = what Navigate follows;
 * an empty plan = nothing followed. Pure, unit-tested (PlanGuidanceTest); AppViewModel applies it after every change of
 * the planned points (edit, Clear, Undo, Redo).
 */
object PlanGuidance {
    /**
     * After a plan change: end the route guidance? Yes when the plan is now empty while a tour follows a planned route
     * (the recording goes on). A followed GPX track and a free recording are not the plan: untouched.
     */
    fun endsGuidance(planPoints: Int, recording: Boolean, following: Boolean, followIsTrack: Boolean): Boolean =
        planPoints == 0 && recording && following && !followIsTrack

    /**
     * A route for the plan is ready: follow it again? Yes when the guidance was ended by emptying the plan and points
     * are back (Undo, or a new plan) while the tour still records.
     */
    fun resumes(endedByEmptyPlan: Boolean, recording: Boolean, following: Boolean, routePoints: Int): Boolean =
        endedByEmptyPlan && recording && !following && routePoints >= 2
}
