package com.onpath.app.routing

import com.onpath.app.data.Geo

/** One leg of a planned route; [straight] = routing failed, drawn dashed (R-12/R-13). */
data class PlannedLeg(val points: List<GeoPoint>, val straight: Boolean)

data class PlannedRoute(
    val legs: List<PlannedLeg>,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
    /** Plain-language problem description, null if every leg was routed. */
    val error: String?,
) {
    val points: List<GeoPoint> by lazy {
        val out = ArrayList<GeoPoint>()
        legs.forEach { leg -> leg.points.forEach { if (out.isEmpty() || out.last() != it) out += it } }
        out
    }
}

/**
 * Calls the engine for the whole route; if that fails, routes leg by leg and replaces
 * failing legs with straight lines so the user always sees something useful.
 */
class Planner(private val engine: RoutingEngine) {

    fun plan(waypoints: List<GeoPoint>, activity: Activity, sac: Int): PlannedRoute {
        require(waypoints.size >= 2)
        if (engine is StraightLineEngine) {
            val r = engine.route(waypoints, activity, sac)
            val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
            return PlannedRoute(legs, r.distanceM, r.ascentM, r.descentM, r.durationS,
                "Offline router could not start (routing data missing?) – straight lines shown.")
        }
        try {
            val r = engine.route(waypoints, activity, sac)
            if (r.points.size >= 2) {
                return PlannedRoute(listOf(PlannedLeg(r.points, false)), r.distanceM, r.ascentM, r.descentM, r.durationS, null)
            }
        } catch (e: Exception) {
            // Two points: no need to retry leg by leg (BRouter can take ~30 s to fail).
            if (waypoints.size == 2) {
                val d = Geo.distance(waypoints[0], waypoints[1])
                return PlannedRoute(listOf(PlannedLeg(waypoints, true)), d, 0.0, 0.0,
                    Geo.estimateSeconds(d, 0.0, 0.0, activity),
                    "${e.message ?: "No route found."} Shown as a straight dashed line. Try moving the point or changing the activity.")
            }
            // otherwise fall through to per-leg routing
        }
        val legs = mutableListOf<PlannedLeg>()
        var dist = 0.0; var up = 0.0; var down = 0.0; var time = 0.0
        var firstError: String? = null
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]; val b = waypoints[i]
            try {
                val r = engine.route(listOf(a, b), activity, sac)
                if (r.points.size < 2) throw RoutingException("No route found between point $i and ${i + 1}.")
                legs += PlannedLeg(r.points, false)
                dist += r.distanceM; up += r.ascentM; down += r.descentM; time += r.durationS
            } catch (e: Exception) {
                if (firstError == null) firstError = (e.message ?: "No route found.") .let {
                    "Leg ${i}→${i + 1}: $it Shown as a straight dashed line. Try moving the point or changing the activity."
                }
                val d = Geo.distance(a, b)
                legs += PlannedLeg(listOf(a, b), true)
                dist += d
                time += Geo.estimateSeconds(d, 0.0, 0.0, activity)
            }
        }
        return PlannedRoute(legs, dist, up, down, time, firstError)
    }
}
