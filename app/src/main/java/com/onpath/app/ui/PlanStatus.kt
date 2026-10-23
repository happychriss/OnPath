package com.onpath.app.ui

/** Why a plan with two or more points has no route on purpose (the sheet says so and offers to calculate it). */
enum class NotRouted {
    /** "This route is very long" was answered Cancel and there was no change to take back (e.g. a plan restored at start). */
    LONG,
    /** "Calculating the route… Cancel" was tapped and there was no change to take back. */
    STOPPED,
}

/**
 * Plan sheet status without a route, and what Cancel does (field bug 2026-10-22, 0.9.3: app start with a saved 8-day
 * plan → Cancel in the long-route question → "Calculating the route…" forever, Start disabled, no way out).
 * Invariant: "Calculating the route…" only while a routing request runs.
 */
object PlanStatus {
    const val CALCULATING = "Calculating the route…"

    /** What Cancel does: Undo / Redo one plan step, put the activity and T level back, or keep the points unrouted. */
    enum class OnCancel { UNDO, REDO, RESTORE_ACTIVITY, KEEP_POINTS }

    /**
     * What started a routing run (UX review 2026-10-23, problem 1): Cancel takes back only *that* change – never the
     * last point edit after an activity change.
     */
    sealed interface Cause {
        /** A point edit (add, delete, move, reverse, clear …): one Undo step. */
        data object PointEdit : Cause
        /** Undo: Cancel brings the step back (Redo). */
        data object Undo : Cause
        /** Redo: Cancel takes it back (Undo). */
        data object Redo : Cause
        /** The activity / T level changed from [activity] / [sac]: Cancel puts them back. */
        data class ActivityChange(val activity: com.onpath.app.routing.Activity, val sac: Int) : Cause
        /** Anything else (app start, newer routing data, a plan loaded, route back …): Cancel just stops. */
        data object Other : Cause
    }

    /** Cancel (long-route question or a running calculation) for a run started by [cause]. */
    fun onCancel(cause: Cause, canUndo: Boolean, canRedo: Boolean): OnCancel = when (cause) {
        Cause.PointEdit -> if (canUndo) OnCancel.UNDO else OnCancel.KEEP_POINTS
        Cause.Redo -> if (canUndo) OnCancel.UNDO else OnCancel.KEEP_POINTS
        Cause.Undo -> if (canRedo) OnCancel.REDO else OnCancel.KEEP_POINTS
        is Cause.ActivityChange -> OnCancel.RESTORE_ACTIVITY
        Cause.Other -> OnCancel.KEEP_POINTS
    }

    /** Second line of the sheet when there is no route yet, for [pointCount] points. */
    fun noRouteLine(pointCount: Int, routing: Boolean, notRouted: NotRouted?): String = when {
        pointCount == 0 -> "Hold the map to start there – or to go there from your location. Hold a point or place for options. " +
            "To just record, use ☰ → Start recording."
        pointCount == 1 -> "Hold the map to add the destination. Hold a point or place for options."
        routing -> CALCULATING
        else -> notRoutedText(notRouted)
    }

    /** Notice for a plan that is not routed (no request running). */
    fun notRoutedText(notRouted: NotRouted?): String = when (notRouted) {
        NotRouted.LONG -> "Route not calculated – very long plan."
        NotRouted.STOPPED -> "Route not calculated – calculation stopped."
        null -> "Route not calculated."
    }

    /** Button of that notice. */
    fun notRoutedAction(notRouted: NotRouted): String = when (notRouted) {
        NotRouted.LONG -> "Plan anyway"
        NotRouted.STOPPED -> "Calculate"
    }
}
