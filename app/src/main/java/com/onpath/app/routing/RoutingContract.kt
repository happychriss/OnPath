package com.onpath.app.routing

/**
 * Shared contract between the app UI and the on-device routing engine.
 * Do not change without updating docs/architecture-contract.md.
 */
enum class Activity(val label: String) {
    BIKE_TOURING("Bike – touring"),
    BIKE_TREKKING("Bike – trekking"),
    EBIKE("Bike – e-bike"),
    MTB("MTB"),
    EMTB("MTB – e-bike"),
    HIKE("Hike"),
}

data class GeoPoint(val lat: Double, val lon: Double, val ele: Double? = null)

data class RouteResult(
    val points: List<GeoPoint>,   // full geometry, with elevation where known
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
)

class RoutingException(message: String) : Exception(message)

interface RoutingEngine {
    /**
     * Calculates a route through all waypoints (start, vias, end), fully offline.
     * Blocking; call from a background thread. Throws RoutingException with a
     * plain-language message the UI can show (e.g. "No route found near this point").
     * @param sacLimit hiking difficulty limit 1..5 (SAC T1..T5), only used for HIKE.
     */
    fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int = 3): RouteResult
}
