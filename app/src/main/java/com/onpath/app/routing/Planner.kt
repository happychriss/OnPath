package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.debug.UserText

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
        // at a join the next leg's first point wins: its way attributes (GeoPoint.way) describe the way onward
        legs.forEach { leg -> leg.points.forEach { if (out.isEmpty() || out.last() != it) out += it else out[out.size - 1] = it } }
        out
    }
}

/**
 * Calls the engine for the whole route; if that fails, routes leg by leg and replaces
 * failing legs with straight lines so the user always sees something useful.
 */
class Planner(
    private val engine: RoutingEngine,
    /** "Cancel" while a route is calculated (owner 2026-10-22): stops the router at once, no leg-by-leg fallback. */
    private val cancel: RouteCancel? = null,
) {
    /** One engine request of this planner: points, time, outcome ("ok", "timeout", "failed"), wait for the engine lock. */
    data class Try(val points: Int, val ms: Long, val outcome: String, val lockWaitMs: Long)

    /** Every engine request so far (for the per-route log line, [describeTries]). One planner = one plan, one thread. */
    val tries = ArrayList<Try>()

    /** "whole 4 pts 34.2 s timeout (lock 0 ms); leg 2 pts 1.2 s ok" – short, for the event log. */
    fun describeTries(): String = tries.joinToString("; ") { t ->
        "${t.points} pts ${String.format(java.util.Locale.ROOT, "%.1f", t.ms / 1000.0)} s ${t.outcome}" +
            if (t.lockWaitMs > 0) " (waited ${t.lockWaitMs} ms for the router)" else ""
    }

    private fun route(pts: List<GeoPoint>, activity: Activity, sac: Int): RouteResult {
        if (cancel?.isCancelled == true) throw RoutingException(BRouterEngine.CANCELLED)
        val t0 = System.currentTimeMillis()
        var outcome = "failed"
        try {
            return (if (cancel == null) engine.route(pts, activity, sac)
            else engine.route(pts, activity, sac, RouteLimits(PLAN_LIMIT_MS, cancel))).also { outcome = "ok" }
        } catch (e: RoutingException) {
            outcome = if (e.timedOut) "timeout" else if (cancel?.isCancelled == true) "cancelled" else "failed"
            throw e
        } finally {
            tries += Try(pts.size, System.currentTimeMillis() - t0, outcome,
                if (engine is BRouterEngine) BRouterEngine.lastLockWaitMs() else 0L)
        }
    }

    private fun stopIfCancelled() { if (cancel?.isCancelled == true) throw RoutingException(BRouterEngine.CANCELLED) }


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
                "Routes can't be calculated right now – straight lines shown.")
        }
        try {
            val r = route(waypoints, activity, sac)
            if (r.points.size >= 2) {
                return PlannedRoute(listOf(PlannedLeg(r.points, false)), r.distanceM, r.ascentM, r.descentM, r.durationS, null,
                    RouteSummary.mergeShares(listOf(r.wayTypes)), RouteSummary.mergeShares(listOf(r.surfaces)),
                    WaypointSnap.onRoute(waypoints, r.points, r.matched))
            }
        } catch (e: Exception) {
            stopIfCancelled() // cancelled: no fallback, the caller shows nothing new
            // expected: RoutingException = no route (the user sees its message); anything else is an engine bug
            if (e !is RoutingException) Problems.error("routing", "Route request threw unexpectedly", e)
            // Two points: no need to retry leg by leg (BRouter can take ~30 s to fail).
            if (waypoints.size == 2) {
                val d = Geo.distance(waypoints[0], waypoints[1])
                return PlannedRoute(listOf(PlannedLeg(waypoints, true)), d, 0.0, 0.0,
                    Geo.estimateSeconds(d, 0.0, 0.0, activity),
                    legError(1, 2, e))
            }
            // Timed out: each leg would get its own limit and the wait multiplies (perf report 2026-10-22: a 4-point
            // plan up to 120 s) – straight lines at once, the user adds points or tries again.
            if (e is RoutingException && e.timedOut) return straight(waypoints, activity, TOOK_TOO_LONG)
            // otherwise fall through to per-leg routing
        }
        var dist = 0.0; var up = 0.0; var down = 0.0; var time = 0.0
        var firstError: String? = null
        val ways = mutableListOf<List<Share>>(); val surf = mutableListOf<List<Share>>()
        val routed = arrayOfNulls<RouteResult>(waypoints.size) // [i] = leg i-1 → i
        val snapped = arrayOfNulls<GeoPoint>(waypoints.size)
        for (i in 1 until waypoints.size) {
            val a = waypoints[i - 1]; val b = waypoints[i]
            stopIfCancelled()
            try {
                val r = route(listOf(a, b), activity, sac)
                if (r.points.size < 2) throw RoutingException("No route found between point ${i + offset} and ${i + offset + 1}.")
                routed[i] = r
                dist += r.distanceM; up += r.ascentM; down += r.descentM; time += r.durationS
                ways += r.wayTypes; surf += r.surfaces
                // a point between two routed legs: the earlier leg's end wins
                val s = WaypointSnap.onRoute(listOf(a, b), r.points, r.matched)
                if (snapped[i - 1] == null) snapped[i - 1] = s[0]
                snapped[i] = s[1]
            } catch (e: Exception) {
                stopIfCancelled()
                // expected: RoutingException = no route for this leg (shown as a straight dashed line)
                if (e !is RoutingException) Problems.error("routing", "Leg route request threw unexpectedly", e)
                if (firstError == null) firstError = legError(i + offset, i + offset + 1, e)
            }
        }
        // straight legs start/end where their neighbours' routed legs do, so markers and lines meet
        val legs = (1 until waypoints.size).map { i ->
            routed[i]?.let { PlannedLeg(it.points, false) } ?: run {
                val a = snapped[i - 1] ?: waypoints[i - 1]; val b = snapped[i] ?: waypoints[i]
                val d = Geo.distance(a, b)
                dist += d
                time += Geo.estimateSeconds(d, 0.0, 0.0, activity)
                PlannedLeg(listOf(a.copy(way = null), b.copy(way = null)), true) // no known way on a straight line
            }
        }
        return PlannedRoute(legs, dist, up, down, time, firstError,
            RouteSummary.mergeShares(ways), RouteSummary.mergeShares(surf), snapped.toList())
    }

    private fun straight(waypoints: List<GeoPoint>, activity: Activity, error: String): PlannedRoute {
        val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
        val d = (1 until waypoints.size).sumOf { Geo.distance(waypoints[it - 1], waypoints[it]) }
        return PlannedRoute(legs, d, 0.0, 0.0, Geo.estimateSeconds(d, 0.0, 0.0, activity), error)
    }

    companion object {
        /** The whole plan timed out (straight lines at once). */
        const val TOOK_TOO_LONG = "Route took too long – straight lines for now"

        /**
         * One short ERROR line for a leg the router could not do (review 2026-10-24 B4): "Point 1 → 2: no path found" –
         * no "Leg", no "shown as a straight dashed line" (the map shows it), no advice sentence. Missing routing data
         * keeps the engine's text so the plain "map loading" notice still recognises it (RoutingDataNotice).
         */
        fun legError(from: Int, to: Int, e: Exception): String {
            val msg = UserText.of(e, "No route found.")
            if (msg.contains("No routing data") || msg.contains(".rd5")) return msg
            val why = when {
                e is RoutingException && e.timedOut -> "took too long"
                msg.startsWith("Not enough memory") || msg.startsWith("Route too complex") -> "too long – add a point between"
                else -> "no path found"
            }
            return "Point $from → $to: $why"
        }

        /** With a cancel signal: the overall wait for the router (each request keeps its own search budget). */
        const val PLAN_LIMIT_MS = 10 * 60_000L

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
