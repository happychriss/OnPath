package com.onpath.app.routing

import com.onpath.strings.*
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
    /**
     * Interior points (indices in the request) the router matched on a way but whose spot the line misses – cut out as
     * a there-and-back ([WaypointSnap.missesMatched]). [Planner.plan] routes to them again; what is left here is a stop
     * the route still skips: the plan keeps the point where it is and shows the gap (PlanGaps), never moves it.
     */
    val skipped: Set<Int> = emptySet(),
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
    /** "Cancel" while a route is calculated: stops the router at once, no leg-by-leg fallback. */
    private val cancel: RouteCancel? = null,
    /** Wall clock in ms (tests pass their own: the short-trip retry's time budget, [retryShortDetours]). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** false = no short-trip detour retry ([retryShortDetours]) – only for A/B runs (tools/routing/jvm-test/ShortDetourTest.kt). */
    private val shortDetourRetry: Boolean = true,
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

    /** [limitMs] = this request's own time limit (the short-trip retry, [retryShortDetours]); null = the engine's usual one. */
    private fun route(pts: List<GeoPoint>, activity: Activity, sac: Int, limitMs: Long? = null): RouteResult {
        if (cancel?.isCancelled == true) throw RoutingException(BRouterEngine.CANCELLED)
        val t0 = now()
        var outcome = "failed"
        try {
            return (if (limitMs != null) engine.route(pts, activity, sac, RouteLimits(minOf(limitMs, PLAN_LIMIT_MS), cancel))
            else if (cancel == null) engine.route(pts, activity, sac)
            else engine.route(pts, activity, sac, RouteLimits(PLAN_LIMIT_MS, cancel))).also { outcome = "ok" }
        } catch (e: RoutingException) {
            outcome = if (e.timedOut) "timeout" else if (cancel?.isCancelled == true) "cancelled" else "failed"
            throw e
        } finally {
            tries += Try(pts.size, now() - t0, outcome,
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
        // A point ON a way, reachable only there and back, can be cut out by BRouter's via correction (up to 400 m
        // for bikes) and then reads as "no way" or the stop silently moves onto the line. Owner rule: a point on a way is
        // always visited – route again with it as a chunk end (chunk ends are never corrected). A new chunk can cut
        // another point, so a few passes (the chunks routed before come from the engine's cache); a stop still cut
        // after them stays in [PlannedRoute.skipped] and the plan sheet shows it.
        var plan = planPinned(waypoints, activity, sac, pinned)
        var ends = pinned
        repeat(MAX_SPUR_PASSES) {
            val spurs = plan.skipped - ends
            if (spurs.isEmpty()) return plan
            Problems.trace("routing", "Point(s) ${spurs.sorted().joinToString { "${it + 1}" }} on a way but cut as a detour – routed to them")
            ends = ends + spurs
            plan = planPinned(waypoints, activity, sac, ends)
        }
        if (plan.skipped.isNotEmpty()) Problems.trace("routing", "Point(s) ${plan.skipped.sorted().joinToString { "${it + 1}" }} still cut after $MAX_SPUR_PASSES passes – shown as skipped")
        return plan
    }

    private fun planPinned(waypoints: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int>): PlannedRoute {
        val cuts = chunkBounds(waypoints.size, pinned)
        if (cuts.size == 2) return planOne(waypoints, activity, sac)
        val parts = cuts.zipWithNext().map { (a, b) -> planOne(waypoints.subList(a, b + 1), activity, sac, a) }
        return PlannedRoute(
            parts.flatMap { it.legs },
            parts.sumOf { it.distanceM }, parts.sumOf { it.ascentM }, parts.sumOf { it.descentM }, parts.sumOf { it.durationS },
            parts.firstNotNullOfOrNull { it.error },
            RouteSummary.mergeShares(parts.map { it.wayTypes }), RouteSummary.mergeShares(parts.map { it.surfaces }),
            mergeSnapped(waypoints.size, cuts, parts.map { it.snapped }),
            parts.flatMap { it.skipped }.toSet(),
        )
    }

    /** [offset] = index of this chunk's first point in the full list (for messages). */
    private fun planOne(waypoints: List<GeoPoint>, activity: Activity, sac: Int, offset: Int = 0): PlannedRoute {
        if (engine is StraightLineEngine) {
            val r = engine.route(waypoints, activity, sac)
            val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
            return PlannedRoute(legs, r.distanceM, r.ascentM, r.descentM, r.durationS,
                Txt.s(Res.string.plan_err_straight))
        }
        try {
            val t0 = now()
            val r = retryShortDetours(waypoints, route(waypoints, activity, sac), activity, sac, offset, t0)
            if (r.points.size >= 2) {
                return PlannedRoute(listOf(PlannedLeg(r.points, false)), r.distanceM, r.ascentM, r.descentM, r.durationS, null,
                    RouteSummary.mergeShares(listOf(r.wayTypes)), RouteSummary.mergeShares(listOf(r.surfaces)),
                    WaypointSnap.onRoute(waypoints, r.points, r.matched), spurs(waypoints, r, offset))
            }
        } catch (e: Exception) {
            stopIfCancelled() // cancelled: no fallback, the caller shows nothing new
            // expected: RoutingException = no route (the user sees its message); anything else is an engine bug
            if (e !is RoutingException) Problems.error("routing", "Route request threw unexpectedly", e)
            // Two points: no need to retry leg by leg (BRouter can take ~30 s to fail).
            if (waypoints.size == 2) {
                val d = Geo.distance(waypoints[0], waypoints[1])
                return PlannedRoute(listOf(PlannedLeg(waypoints, true)), d, 0.0, 0.0,
                    flatSeconds(d, activity),
                    legError(1, 2, e))
            }
            // Timed out: each leg would get its own limit and the wait multiplies (a 4-point plan up to 120 s) –
            // straight lines at once, the user adds points or tries again.
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
                val t0 = now()
                val first = route(listOf(a, b), activity, sac)
                if (first.points.size < 2) throw RoutingException(Txt.s(Res.string.plan_err_no_route_between, i + offset, i + offset + 1))
                val r = retryShortDetours(listOf(a, b), first, activity, sac, offset + i - 1, t0)
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
                time += flatSeconds(d, activity)
                PlannedLeg(listOf(a.copy(way = null), b.copy(way = null)), true) // no known way on a straight line
            }
        }
        return PlannedRoute(legs, dist, up, down, time, firstError,
            RouteSummary.mergeShares(ways), RouteSummary.mergeShares(surf), snapped.toList())
    }

    /**
     * Short-trip detour retry (owner 2026-10-31, field case Hilden; docs/design/bike-routing.md decision 12): BRouter's
     * cheapest way between two points close together can be a long detour – 2.1 km over signed cycle routes for
     * 0.73 km straight, because the direct 1.1 km has 13 right-angle turns and a short pedestrian zone. A leg with a
     * straight distance under [SHORT_LEG_M] routed more than [DETOUR_FACTOR] × that (and at least [MIN_DETOUR_M] longer,
     * [isShortDetour]) is routed once more through the middle of the straight line ([midpoint]), a normal via BRouter
     * snaps onto the nearest way. The retry is taken only when it is at least [SHORTER_BY] shorter; a retry that fails
     * keeps the first route (it never makes a straight leg). The extra via is internal to the leg: [waypoints] – the
     * plan's points – stay as they are, and the result's [RouteResult.matched] keeps their size and order.
     *
     * In a request of more than two points each leg is tried on its own (a short 3-point request); the vias taken go
     * into ONE more request of the whole chunk, kept only when it is shorter than the first and skips no stop the first
     * passed (Bug 01 stays in charge of skipped stops, [plan]).
     *
     * Time: the retries count against the time limit of the request that gave the legs ([RouteTimeLimit.forRoute] of
     * [waypoints], started at [t0]); each gets only what is left of it, and none starts with less than [MIN_RETRY_MS].
     * Returns [r] when nothing was taken.
     */
    private fun retryShortDetours(waypoints: List<GeoPoint>, r: RouteResult, activity: Activity, sac: Int, offset: Int, t0: Long): RouteResult {
        if (!shortDetourRetry || r.points.size < 2 || waypoints.size < 2) return r
        val legs = legLengths(waypoints, r.points, r.matched) ?: return r
        val budget = RouteTimeLimit.forRoute(waypoints, activity)
        fun left() = budget - (now() - t0)
        val mids = HashMap<Int, GeoPoint>() // leg i (waypoints i → i+1) → the via taken for it
        var single: RouteResult? = null     // the retry taken, for a 2-point request
        for (i in legs.indices) {
            val a = waypoints[i]; val b = waypoints[i + 1]
            val straight = Geo.distance(r.matched.getOrNull(i) ?: a, r.matched.getOrNull(i + 1) ?: b)
            val routed = legs[i]
            if (!isShortDetour(straight, routed)) continue
            val leg = "Leg ${i + offset + 1}: short-trip detour ${fmt(routed / straight)}×"
            if (left() < MIN_RETRY_MS) { Problems.trace("routing", "$leg → no time left to retry via the midpoint"); continue }
            val mid = midpoint(a, b)
            val retry = try { route(listOf(a, mid, b), activity, sac, left()) } catch (e: Exception) {
                stopIfCancelled()
                // expected: RoutingException = no route via the midpoint (an island, a timeout) – the first route stays
                if (e !is RoutingException) Problems.error("routing", "Short-trip retry threw unexpectedly; kept the first route", e)
                Problems.trace("routing", "$leg → retried via midpoint: no route (${e.message}) – kept ${routed.toInt()} m"); continue
            }
            // line lengths on both sides: [routed] is a piece of the first line
            val len = Geo.length(retry.points)
            val take = retry.points.size >= 2 && len <= (1.0 - SHORTER_BY) * routed
            Problems.trace("routing", "$leg → retried via midpoint: ${routed.toInt()} → ${len.toInt()} m (${if (take) "taken" else "kept the first"})")
            if (take) { mids[i] = mid; single = retry }
        }
        if (mids.isEmpty()) return r
        if (waypoints.size == 2) {
            val t = single ?: return r
            if (t.distanceM >= r.distanceM) return r // never longer by the router's own count either
            return t.copy(matched = if (t.matched.size == 3) listOf(t.matched[0], t.matched[2]) else emptyList())
        }
        // the whole chunk once more with the vias taken: one request keeps ascent, breakdown and matching consistent
        val withVias = ArrayList<GeoPoint>(); val user = ArrayList<Int>() // user[k] = index of waypoints[k] in withVias
        waypoints.forEachIndexed { k, p -> user += withVias.size; withVias += p; mids[k]?.let { withVias += it } }
        if (left() < MIN_RETRY_MS) { Problems.trace("routing", "Short-trip retry: no time left for the whole request – kept the first route"); return r }
        val again = try { route(withVias, activity, sac, left()) } catch (e: Exception) {
            stopIfCancelled()
            // expected: RoutingException = the whole request with the extra vias fails or times out – the first route stays
            if (e !is RoutingException) Problems.error("routing", "Short-trip retry threw unexpectedly; kept the first route", e)
            Problems.trace("routing", "Short-trip retry: the whole request failed (${e.message}) – kept the first route"); return r
        }
        val out = again.copy(matched = if (again.matched.size == withVias.size) user.map { again.matched[it] } else emptyList())
        val skippedBefore = WaypointSnap.skipped(waypoints, r.points, r.matched)
        val ok = out.points.size >= 2 && out.distanceM < r.distanceM &&
            skippedBefore.containsAll(WaypointSnap.skipped(waypoints, out.points, out.matched))
        Problems.trace("routing", "Short-trip retry, whole request: ${r.distanceM.toInt()} → ${out.distanceM.toInt()} m (${if (ok) "taken" else "kept the first"})")
        return if (ok) out else r
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    /** Interior points matched on a way whose matched spot the route line misses ([WaypointSnap.missesMatched]). */
    private fun spurs(waypoints: List<GeoPoint>, r: RouteResult, offset: Int): Set<Int> =
        WaypointSnap.skipped(waypoints, r.points, r.matched).map { it + offset }.toSet()

    /** A straight leg (no router): flat, timed by the one time model (the plan sheet re-times the whole line anyway). */
    private fun flatSeconds(d: Double, activity: Activity) = com.onpath.app.time.PaceModel.seconds(d, 0.0, activity)

    private fun straight(waypoints: List<GeoPoint>, activity: Activity, error: String): PlannedRoute {
        val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
        val d = (1 until waypoints.size).sumOf { Geo.distance(waypoints[it - 1], waypoints[it]) }
        return PlannedRoute(legs, d, 0.0, 0.0, flatSeconds(d, activity), error)
    }

    companion object {
        /** The whole plan timed out (straight lines at once). */
        val TOOK_TOO_LONG: String get() = Txt.s(Res.string.plan_err_took_too_long)

        /**
         * One short ERROR line for a leg the router could not do: "Point 1 → 2: no path found" –
         * no "Leg", no "shown as a straight dashed line" (the map shows it), no advice sentence. Missing routing data
         * keeps the engine's text so the plain "map loading" notice still recognises it (RoutingDataNotice).
         */
        fun legError(from: Int, to: Int, e: Exception): String {
            val msg = UserText.of(e, Txt.s(Res.string.plan_err_no_route))
            val kind = (e as? RoutingException)?.kind
            if (kind == RoutingException.Kind.NO_DATA || msg.contains(".rd5")) return msg
            val why = when {
                e is RoutingException && e.timedOut -> Txt.s(Res.string.plan_leg_took_too_long)
                kind == RoutingException.Kind.TOO_COMPLEX -> Txt.s(Res.string.plan_leg_too_long)
                else -> Txt.s(Res.string.plan_leg_no_path)
            }
            return Txt.s(Res.string.plan_leg_error, from, to, why)
        }

        /** Short-trip detour retry ([retryShortDetours]): legs shorter than this (straight, metres) … */
        const val SHORT_LEG_M = 2_000.0
        /** … routed more than this many times their straight distance … */
        const val DETOUR_FACTOR = 2.0
        /** … and at least this much longer (metres; going round a block is no detour) are retried. */
        const val MIN_DETOUR_M = 100.0
        /** The retry is taken when it is at least this share shorter than the leg's first route. */
        const val SHORTER_BY = 0.30
        /** A retry does not start with less than this left of its request's time limit (ms). */
        const val MIN_RETRY_MS = 1_000L

        /** A leg [straight] metres apart, routed [routed] metres: a short trip with a long detour ([retryShortDetours]). */
        fun isShortDetour(straight: Double, routed: Double): Boolean =
            straight > 0.0 && straight < SHORT_LEG_M && routed > DETOUR_FACTOR * straight && routed - straight >= MIN_DETOUR_M

        /** The middle of the straight line [a] → [b] (short legs: the flat mean is exact enough). */
        fun midpoint(a: GeoPoint, b: GeoPoint) = GeoPoint((a.lat + b.lat) / 2.0, (a.lon + b.lon) / 2.0)

        /**
         * Routed metres per leg of [line] through [waypoints] (size − 1 values): the line is split at the line point
         * nearest each interior point's matched spot ([matched], else the point itself), searched forward from the last
         * split, at the first pass within 5 m of the nearest (a line that passes a spot twice). null = no line.
         */
        fun legLengths(waypoints: List<GeoPoint>, line: List<GeoPoint>, matched: List<GeoPoint?>): DoubleArray? {
            if (line.size < 2 || waypoints.size < 2) return null
            val cum = Geo.cumulative(line)
            val cut = IntArray(waypoints.size); cut[waypoints.lastIndex] = line.lastIndex
            for (k in 1 until waypoints.lastIndex) {
                val target = matched.getOrNull(k) ?: waypoints[k]
                val from = cut[k - 1]
                val d = DoubleArray(line.size - from) { Geo.distance(line[from + it], target) }
                val best = d.minOrNull() ?: return null
                cut[k] = from + d.indexOfFirst { it <= best + 5.0 }
            }
            return DoubleArray(waypoints.size - 1) { cum[cut[it + 1]] - cum[cut[it]] }
        }

        /** Re-plans at most this often for points cut as a detour ([plan]); each pins at least one more point. */
        const val MAX_SPUR_PASSES = 3

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
