package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * Where the user is on a tour (pure, unit-tested): which stops are behind them ("Continue
 * navigation" re-routes through the rest) and whether they have arrived.
 */
object RouteProgress {
    /** A stop counts as passed once the user came this close to it during the tour. */
    const val PASSED_RADIUS_M = 40.0

    /** Within this distance of the destination the app asks "You have arrived – end tour?". */
    const val ARRIVAL_RADIUS_M = 50.0

    /** Arrival also needs the followed line to be nearly done (a round trip ends where it starts). */
    const val ARRIVAL_MAX_REMAINING_M = 150.0

    /** Indices of [waypoints] the recorded [track] came within [radiusM] of. */
    fun visitedStops(waypoints: List<GeoPoint>, track: List<GeoPoint>, radiusM: Double = PASSED_RADIUS_M): Set<Int> =
        waypoints.indices.filter { i -> track.any { Geo.distance(it, waypoints[i]) <= radiusM } }.toSet()

    /**
     * The stops still ahead of [position], in order; the destination is always kept.
     * A stop is passed when it is in [visited] or lies at or before the route segment nearest to
     * the user; everything up to the furthest passed stop is dropped (skipped stops are not sent
     * back to). The start is the first stop, so it is dropped as soon as the user is on the route.
     * Without a position fix the stops are returned unchanged.
     */
    fun remainingStops(
        waypoints: List<GeoPoint>,
        routePoints: List<GeoPoint>?,
        position: GeoPoint?,
        visited: Set<Int>,
    ): List<GeoPoint> {
        if (position == null || waypoints.size < 2) return waypoints
        val last = waypoints.lastIndex
        val route = routePoints.orEmpty()
        val nearestSeg = earliestNearSegment(route, position)
        val onRoute = if (nearestSeg >= 0) WaypointInsertion.routeIndices(waypoints, route) else null
        var lastPassed = -1
        for (i in 0 until last) {
            val behind = onRoute != null && onRoute[i] <= nearestSeg
            if (i in visited || behind) lastPassed = i
        }
        return waypoints.subList(lastPassed + 1, waypoints.size).toList()
    }

    /**
     * Segment of [route] nearest to [p]; on out-and-back or round trips the earliest one within
     * [toleranceM] of the best wins, so a stop is never dropped just because the way back passes
     * close by (stops really reached are in "visited"). -1 without a usable route.
     */
    internal fun earliestNearSegment(route: List<GeoPoint>, p: GeoPoint, toleranceM: Double = 30.0): Int {
        if (route.size < 2) return -1
        val d = DoubleArray(route.size - 1) { WaypointInsertion.distanceToSegment(p, route[it], route[it + 1]) }
        val best = d.min()
        return d.indexOfFirst { it <= best + toleranceM }
    }

    /**
     * True when the user has reached [destination]: within [ARRIVAL_RADIUS_M] and, when a followed
     * line is known, with at most [ARRIVAL_MAX_REMAINING_M] of it left ([remainingAlongM]).
     */
    fun arrived(position: GeoPoint?, destination: GeoPoint?, remainingAlongM: Double?): Boolean {
        if (position == null || destination == null) return false
        if (Geo.distance(position, destination) > ARRIVAL_RADIUS_M) return false
        return remainingAlongM == null || remainingAlongM <= ARRIVAL_MAX_REMAINING_M
    }
}
