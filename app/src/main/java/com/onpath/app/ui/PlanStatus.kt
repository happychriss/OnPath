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

    enum class OnCancel { UNDO, KEEP_POINTS }

    /** Cancel (long-route question or a running calculation): take back the last change, else keep the points unrouted. */
    fun onCancel(canUndo: Boolean): OnCancel = if (canUndo) OnCancel.UNDO else OnCancel.KEEP_POINTS

    /** Second line of the sheet when there is no route yet, for [pointCount] points. */
    fun noRouteLine(pointCount: Int, routing: Boolean, notRouted: NotRouted?): String = when {
        pointCount == 0 -> "Hold the map where you want to start or go. To only record: ☰ → Start recording."
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
