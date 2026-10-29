package com.onpath.app.ui

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * Owner rule: during a tour, deleting the GOAL of a 2-point plan leaves only the start – asked with a persistent
 * question "Route back to the start?". Yes = the plan becomes position → start (the guidance follows it); No = plain
 * recording (PlanGuidance: what Plan shows = what Navigate follows). AppViewModel asks after the edit and applies the
 * answer.
 */
object RouteBack {
    /** Closer than this to the start there is no way back to ask about. */
    const val AT_START_M = 50.0

    /**
     * Ask after this edit? [before] → [after] removed the goal of a start → goal plan while a tour follows the planned
     * route. A followed GPX track and a free recording are not the plan. [position] null (no fix yet) still asks.
     */
    fun asks(
        before: List<GeoPoint>, after: List<GeoPoint>, recording: Boolean, following: Boolean, followIsTrack: Boolean,
        position: GeoPoint?,
    ): Boolean {
        if (!recording || !following || followIsTrack) return false
        if (before.size != 2 || after.size != 1 || after[0] != before[0] || before[1] == before[0]) return false
        return position == null || Geo.distance(position, after[0]) >= AT_START_M
    }

    /** The plan for "Yes": position → start; null when there is no position or the plan is no longer the start alone. */
    fun plan(current: List<GeoPoint>, position: GeoPoint?): List<GeoPoint>? {
        val start = current.singleOrNull() ?: return null
        return listOf(position ?: return null, start)
    }
}
