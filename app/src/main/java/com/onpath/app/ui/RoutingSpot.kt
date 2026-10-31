package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * Bug 02 (owner 2026-10-31): progress shows where the user acted. While the route for an edit is computed, a small
 * spinner turns around the point that edit added or moved – on the map, not only in the plan sheet ("nothing happened"
 * for 13 s after a hold far away). The sheet's line stays.
 */
object RoutingSpot {
    /** The point an edit added or moved: the first one in [after] that [before] did not have; null for deletes / reorders. */
    fun of(before: List<GeoPoint>, after: List<GeoPoint>): GeoPoint? = after.firstOrNull { it !in before }

    /** Where the spinner turns now: [spot] while a route is computed and the point is still planned; else null. */
    fun shown(routing: Boolean, spot: GeoPoint?, points: List<GeoPoint>): GeoPoint? = spot?.takeIf { routing && it in points }

    /** Quick routes show no spinner (no flicker): it appears after this long. */
    const val DELAY_MS = 400L
}
