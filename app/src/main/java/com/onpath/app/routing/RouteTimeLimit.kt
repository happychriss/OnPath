package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * BRouter's search time limit for one request, by straight-line distance and profile.
 *
 * A short route that is not found quickly is almost always unreachable (only via paths above the SAC limit, an island),
 * so it keeps a short limit. Search cost grows with distance and differs by profile: measured on a PC for
 * Bozen → Munich (~260 km) hike 5 s, trekking 8 s, touring 12 s, MTB 26 s; a phone is 3–5× slower, so a flat 30 s cap
 * makes long bike / MTB routes time out. No limit is below 15 s + 0.5 s/km (max 30 s). Cancel stays possible at any
 * time. Pure.
 */
object RouteTimeLimit {
    const val MIN_MS = 15_000L

    /** Extra ms per straight-line km and the cap, per profile. */
    private class Rule(val msPerKm: Double, val capMs: Long)

    private fun rule(a: Activity) = when (a) {
        Activity.HIKE -> Rule(500.0, 45_000L)
        Activity.BIKE_TREKKING, Activity.EBIKE -> Rule(500.0, 90_000L)
        Activity.BIKE_TOURING -> Rule(600.0, 90_000L)
        Activity.MTB, Activity.EMTB -> Rule(1_000.0, 120_000L)
    }

    /** Limit for [km] straight-line kilometres with [activity]. */
    fun forKm(km: Double, activity: Activity): Long {
        val r = rule(activity)
        return (MIN_MS + km.coerceAtLeast(0.0) * r.msPerKm).toLong().coerceIn(MIN_MS, r.capMs)
    }

    /** Limit for a request through [waypoints] (straight-line sum of the legs). */
    fun forRoute(waypoints: List<GeoPoint>, activity: Activity): Long = forKm(straightKm(waypoints), activity)

    fun straightKm(waypoints: List<GeoPoint>): Double {
        var m = 0.0
        for (i in 1 until waypoints.size) m += Geo.distance(waypoints[i - 1], waypoints[i])
        return m / 1000.0
    }
}
