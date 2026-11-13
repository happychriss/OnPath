package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * Where the map goes after "Abbrechen" at the very-long-route question (owner 2026-11-13): back to what was planned
 * before the far point – e.g. the start in Hilden after Paris was picked in the search and taken as the destination.
 * Pure.
 *
 * - during a tour: my position, following as before (when it is known);
 * - two or more points left: the whole plan, as "Show whole route" fits it;
 * - one point: that point, centred at the one-place zoom (MapController.fitTo);
 * - no point: the map stays.
 */
object LongRouteCancelCamera {
    sealed interface Target {
        /** Follow my position again (during a tour). */
        data object Me : Target
        /** Fit these points – the whole plan (route line + points), or the one point left. */
        data class Fit(val points: List<GeoPoint>) : Target
        /** Nothing to show: the camera stays. */
        data object Stay : Target
    }

    /**
     * @param points the plan after the cancel (the far point taken back)
     * @param routeLine the plan's route line, when one is known (fitted together with the points)
     * @param touring a tour runs; [hasPosition] my position is known
     */
    fun after(points: List<GeoPoint>, routeLine: List<GeoPoint>?, touring: Boolean, hasPosition: Boolean): Target = when {
        touring && hasPosition -> Target.Me
        points.size >= 2 -> Target.Fit(WholeRoute.bounds(listOfNotNull(routeLine), points, null))
        points.size == 1 -> Target.Fit(points)
        else -> Target.Stay
    }
}
