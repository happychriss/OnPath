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
     * Closer than this to the one point left there is nowhere to go (the same 50 m as "Route back to the start?",
     * [RouteBack.AT_START_M]): GPS alone wanders ~10–30 m, and a plan "position → a point under my feet" only made
     * "Punkt 1 → 2: kein Weg gefunden" and came back after every delete (field bug 2026-11-06 21:18).
     */
    const val ONE_LEFT_NEAR_M = RouteBack.AT_START_M

    enum class OneLeft { NONE, FROM_HERE, EMPTY }

    /**
     * What an edit during a tour that left one point does (owner 2026-11-06, #39 + field bug 21:18 "es gibt keinen Punkt
     * 1 mehr"): [OneLeft.FROM_HERE] = plan position → that point (like Hierhin); [OneLeft.EMPTY] = the plan empties like
     * "all points deleted" (guidance ends, recording goes on, Undo brings it back) – when the point left is the start the
     * rule itself put in ([leftIsAutoStart]: my position then, never a point of mine; also no "Route back to the start?"
     * for it), or when it lies closer than [ONE_LEFT_NEAR_M] to me ([distanceToLeftM]; null = no position → FROM_HERE,
     * the view model's no-position path). [OneLeft.NONE] = not this case (no tour on the plan, or the goal was deleted and
     * "Route back to the start?" is asked).
     */
    fun oneLeft(pointsBefore: Int, pointsAfter: Int, recording: Boolean, following: Boolean, followIsTrack: Boolean,
                routeBackAsk: Boolean, leftIsAutoStart: Boolean, distanceToLeftM: Double?): OneLeft = when {
        pointsBefore < 2 || pointsAfter != 1 || !recording || !following || followIsTrack -> OneLeft.NONE
        leftIsAutoStart -> OneLeft.EMPTY
        routeBackAsk -> OneLeft.NONE
        distanceToLeftM != null && distanceToLeftM < ONE_LEFT_NEAR_M -> OneLeft.EMPTY
        else -> OneLeft.FROM_HERE
    }

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
