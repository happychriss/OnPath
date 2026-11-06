package com.onpath.app.ui

/**
 * Owner rule: what Plan shows = what Navigate follows; an empty plan = nothing followed. AppViewModel applies it after
 * every change of the planned points (edit, Clear, Undo, Redo).
 */
object PlanGuidance {
    /**
     * After a plan change: end the route guidance? Yes when the plan is now empty while a tour follows a planned route
     * (the recording goes on). A followed GPX track and a free recording are not the plan: untouched.
     */
    fun endsGuidance(planPoints: Int, recording: Boolean, following: Boolean, followIsTrack: Boolean): Boolean =
        planPoints == 0 && recording && following && !followIsTrack

    /**
     * ✕ Neu planen during a tour (owner 2026-11-05): every guidance ends – the plan's route and a followed GPX track
     * alike; Navigate is a free tour, the recording goes on.
     */
    fun clearEndsGuidance(recording: Boolean, following: Boolean): Boolean = recording && following

    /**
     * One point left during a tour (owner 2026-11-06, onpath.private#39 – the start of start → goal deleted on the way):
     * it means "Hierhin" – the plan becomes position → that point and the tour follows it. Only after an edit that
     * left exactly one of two or more points while a tour follows the planned route; not while "Route back to the
     * start?" ([routeBackAsk], the goal was deleted) is open. A followed GPX track and a free recording are untouched.
     */
    fun oneLeftGoesFromHere(pointsBefore: Int, pointsAfter: Int, recording: Boolean, following: Boolean,
                            followIsTrack: Boolean, routeBackAsk: Boolean): Boolean =
        pointsBefore >= 2 && pointsAfter == 1 && recording && following && !followIsTrack && !routeBackAsk

    /**
     * A route for the plan is ready: follow it again? Yes when the guidance was ended by emptying the plan and points
     * are back (Undo, or a new plan) while the tour still records.
     */
    fun resumes(endedByEmptyPlan: Boolean, recording: Boolean, following: Boolean, routePoints: Int): Boolean =
        endedByEmptyPlan && recording && !following && routePoints >= 2

    /**
     * Owner rule: a GPX track is followed during the tour and the plan was edited since ([planAtTrackFollow] =
     * the plan's points when the track was taken; null = unknown, e.g. after an app restart). Then "Continue" follows the
     * plan again, and Plan draws the followed track grey behind it. An unedited plan (or Undo back to it) keeps the track.
     */
    fun planReplacesTrack(followIsTrack: Boolean, plan: List<com.onpath.app.routing.GeoPoint>,
                          planAtTrackFollow: List<com.onpath.app.routing.GeoPoint>?): Boolean =
        followIsTrack && plan.size >= 2 && planAtTrackFollow != null && plan != planAtTrackFollow
}

/**
 * ✕ Neu planen asks first only during a tour with a plan (owner 2026-11-05) – "Plan leeren? Deine Tour läuft weiter und
 * wird weiter aufgezeichnet." (the guidance ends). Otherwise it clears at once: nothing to save any more (owner 2026-11-06,
 * a plan not saved goes under My routes → Zuletzt), one Undo brings it back.
 */
object ClearAsk {
    fun asks(touring: Boolean, planPoints: Int): Boolean = touring && planPoints > 0
}

