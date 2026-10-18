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
    /** Komoot-style breakdown summed over all routed legs (may be empty). */
    val wayTypes: List<Share> = emptyList(),
    val surfaces: List<Share> = emptyList(),
    /**
     * Per planned waypoint (same size as the request), where the router put it on the way; null =
     * unknown (straight legs, fallback engine). Empty = nothing known. See [WaypointSnap.apply].
     */
    val snapped: List<GeoPoint?> = emptyList(),
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

    /**
     * @param pinned indices of waypoints that must be passed exactly (e.g. a tapped hut). The engine
     *   may straighten out-and-back spurs at vias; chunk endpoints are never moved, so the list is
     *   split at pinned interior points and each chunk is routed as its own request.
     */
    fun plan(waypoints: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int> = emptySet()): PlannedRoute {
        require(waypoints.size >= 2)
        val cuts = chunkBounds(waypoints.size, pinned)
        if (cuts.size == 2) return planOne(waypoints, activity, sac)
        val parts = cuts.zipWithNext().map { (a, b) -> planOne(waypoints.subList(a, b + 1), activity, sac, a) }
        return PlannedRoute(
            parts.flatMap { it.legs },
            parts.sumOf { it.distanceM }, parts.sumOf { it.ascentM }, parts.sumOf { it.descentM }, parts.sumOf { it.durationS },
            parts.firstNotNullOfOrNull { it.error },
            RouteSummary.mergeShares(parts.map { it.wayTypes }), RouteSummary.mergeShares(parts.map { it.surfaces }),
            mergeSnapped(waypoints.size, cuts, parts.map { it.snapped }),
        )
    }

    /** [offset] = index of this chunk's first point in the full list (for messages). */
    private fun planOne(waypoints: List<GeoPoint>, activity: Activity, sac: Int, offset: Int = 0): PlannedRoute {
        if (engine is StraightLineEngine) {
            val r = engine.route(waypoints, activity, sac)
            val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
            return PlannedRoute(legs, r.distanceM, r.ascentM, r.descentM, r.durationS,
                "Offline router could not start (routing data missing?) – straight lines shown.")
        }
        try {
            val r = engine.route(waypoints, activity, sac)
            if (r.points.size >= 2) {
                return PlannedRoute(listOf(PlannedLeg(r.points, false)), r.distanceM, r.ascentM, r.descentM, r.durationS, null,
                    RouteSummary.mergeShares(listOf(r.wayTypes)), RouteSummary.mergeShares(listOf(r.surfaces)),
                    WaypointSnap.onRoute(waypoints, r.points, r.matched))
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
        var dist = 0.0; var up = 0.0; var down = 0.0; var time = 0.0
        var firstError: String? = null
        val ways = mutableListOf<List<Share>>(); val surf = mutableListOf<List<Share>>()
        val routed = arrayOfNulls<RouteResult>(waypoints.size) // [i] = leg i-1 → i
        val snapped = arrayOfNulls<GeoPoint>(waypoints.size)
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]; val b = waypoints[i]
            try {
                val r = engine.route(listOf(a, b), activity, sac)
                if (r.points.size < 2) throw RoutingException("No route found between point ${i + offset} and ${i + offset + 1}.")
                routed[i] = r
                dist += r.distanceM; up += r.ascentM; down += r.descentM; time += r.durationS
                ways += r.wayTypes; surf += r.surfaces
                // a point between two routed legs: the earlier leg's end wins
                val s = WaypointSnap.onRoute(listOf(a, b), r.points, r.matched)
                if (snapped[i - 1] == null) snapped[i - 1] = s[0]
                snapped[i] = s[1]
            } catch (e: Exception) {
                if (firstError == null) firstError = (e.message ?: "No route found.") .let {
                    "Leg ${i + offset}→${i + offset + 1}: $it Shown as a straight dashed line. Try moving the point or changing the activity."
                }
            }
        }
        // straight legs start/end where their neighbours' routed legs do, so markers and lines meet
        val legs = (1 until waypoints.size).map { i ->
            routed[i]?.let { PlannedLeg(it.points, false) } ?: run {
                val a = snapped[i - 1] ?: waypoints[i - 1]; val b = snapped[i] ?: waypoints[i]
                val d = Geo.distance(a, b)
                dist += d
                time += Geo.estimateSeconds(d, 0.0, 0.0, activity)
                PlannedLeg(listOf(a, b), true)
            }
        }
        return PlannedRoute(legs, dist, up, down, time, firstError,
            RouteSummary.mergeShares(ways), RouteSummary.mergeShares(surf), snapped.toList())
    }

    companion object {
        /** Joins per-chunk [PlannedRoute.snapped] lists (chunks share their boundary point) into one. */
        fun mergeSnapped(size: Int, cuts: List<Int>, parts: List<List<GeoPoint?>>): List<GeoPoint?> {
            val out = arrayOfNulls<GeoPoint>(size)
            cuts.zipWithNext().forEachIndexed { c, (a, _) ->
                parts.getOrNull(c)?.forEachIndexed { j, p -> if (p != null && a + j < size && out[a + j] == null) out[a + j] = p }
            }
            return out.toList()
        }

        /** Chunk boundaries: 0, every pinned interior index (sorted), last index. */
        fun chunkBounds(size: Int, pinned: Set<Int>): List<Int> =
            (listOf(0) + pinned.filter { it in 1 until size - 1 }.sorted() + (size - 1)).distinct()
    }
}
