package com.onpath.app.routing

// The Planner as of PLANNER_VERSION 3 (whole requests per chunk, cut-stop splice) – ONLY for the JVM comparison
// old vs leg by leg (tools/routing/jvm-test/LegsVsWholeTest.kt, owner 2026-11-05). Not compiled into the app.

import com.onpath.strings.*
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.debug.UserText

/**
 * Calls the engine for the whole route; if that fails, routes leg by leg and replaces
 * failing legs with straight lines so the user always sees something useful.
 */
class PlannerV3(
    private val engine: RoutingEngine,
    /** "Cancel" while a route is calculated: stops the router at once, no leg-by-leg fallback. */
    private val cancel: RouteCancel? = null,
    /** Wall clock in ms (tests pass their own: the short-trip retry's time budget, [retryShortDetours]). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** false = no short-trip detour retry ([retryShortDetours]) – only for A/B runs (tools/routing/jvm-test/ShortDetourTest.kt). */
    private val shortDetourRetry: Boolean = true,
    /**
     * A plan run the user started in Plan (owner 2026-11-03, [SlowPlan]): at the time limit [plan] throws
     * [SlowPlan.TimeLimit] – the caller asks "Weiter warten?" – instead of straight lines; false = straight lines as before.
     */
    private val askAtLimit: Boolean = false,
    /** Time-limit periods of this run (2+ after "Weiter warten", [RouteLimits.rounds]). */
    private val rounds: Int = 1,
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
            else if (cancel == null && rounds <= 1) engine.route(pts, activity, sac)
            else engine.route(pts, activity, sac, RouteLimits(PLAN_LIMIT_MS * rounds.coerceAtLeast(1), cancel, rounds))).also { outcome = "ok" }
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
        val cuts = chunkBounds(waypoints.size, pinned)
        val parts = cuts.zipWithNext().map { (a, b) -> planChunk(waypoints, a, b, activity, sac) }
        return if (parts.size == 1) parts[0] else merge(cuts, parts)
    }

    /**
     * One chunk [from]..[to] (no pinned point inside). A point ON a way, reachable only there and back, can be cut out by
     * BRouter's via correction (up to 400 m for bikes) and then reads as "no way" or the stop silently moves onto the
     * line. Owner rule: a point on a way is always visited – it is routed to as a request end (request ends are never
     * corrected). Owner 2026-10-31 ("Option 1", speed): the chunk's first line stays and only the stretch around a cut
     * stop i is routed again, to i and on with i as a request end, spliced into the first line ([splice]) – not the whole
     * chunk once more (a 68 km hike chunk took 2.5 s twice). The result depends only on the points, never on an earlier
     * plan (same points, same route – app, web, plan link). Where a splice does not fit cleanly (a U-turn or a gap at a
     * splice point, a leg without a route) the chunk is routed again with the cut stops as chunk ends, as before
     * ([replan]). A stop still cut after [MAX_SPUR_PASSES] passes stays in [PlannedRoute.skipped]; the plan sheet shows it.
     */
    private fun planChunk(waypoints: List<GeoPoint>, from: Int, to: Int, activity: Activity, sac: Int): PlannedRoute {
        val sub = waypoints.subList(from, to + 1)
        val first = planOne(sub, activity, sac, from)
        var route = first.route
        var line = first.whole?.let { Line.of(sub, from, it) }
        var ends = emptySet<Int>()
        for (pass in 0 until MAX_SPUR_PASSES) {
            val spurs = route.skipped - ends
            if (spurs.isEmpty()) return route
            Problems.trace("routing", "Point(s) ${spurs.sorted().joinToString { "${it + 1}" }} on a way but cut as a detour – routed to them")
            ends = ends + spurs
            val spliced = line?.let { l ->
                // a safety net for the young splice code (review routing H3): an unforeseen geometry must not fail the
                // whole plan with a raw exception text – the chunk is routed again as before the splice existed
                try { splice(l, spurs, activity, sac) } catch (e: RoutingException) { throw e } catch (e: SlowPlan.TimeLimit) { throw e } catch (e: Exception) {
                    Problems.error("routing", "Splice failed unexpectedly; the stretch is routed again as a whole", e)
                    null
                }
            } ?: return replan(waypoints, from, to, activity, sac, ends, MAX_SPUR_PASSES - pass - 1)
            line = spliced
            route = spliced.planned()
        }
        if (route.skipped.isNotEmpty()) Problems.trace("routing", "Point(s) ${route.skipped.sorted().joinToString { "${it + 1}" }} still cut after $MAX_SPUR_PASSES passes – shown as skipped")
        return route
    }

    /**
     * The fallback when a splice does not fit ([planChunk]): the chunk [from]..[to] routed again with [ends] as chunk
     * ends, then up to [passes] more passes that each pin the stops still cut (the planner before 2026-10-31).
     */
    private fun replan(waypoints: List<GeoPoint>, from: Int, to: Int, activity: Activity, sac: Int, ends: Set<Int>, passes: Int): PlannedRoute {
        var pins = ends
        var plan = planPinned(waypoints, from, to, activity, sac, pins)
        repeat(passes) {
            val spurs = plan.skipped - pins
            if (spurs.isEmpty()) return plan
            Problems.trace("routing", "Point(s) ${spurs.sorted().joinToString { "${it + 1}" }} on a way but cut as a detour – routed to them")
            pins = pins + spurs
            plan = planPinned(waypoints, from, to, activity, sac, pins)
        }
        if (plan.skipped.isNotEmpty()) Problems.trace("routing", "Point(s) ${plan.skipped.sorted().joinToString { "${it + 1}" }} still cut after $MAX_SPUR_PASSES passes – shown as skipped")
        return plan
    }

    /** [from]..[to] of [waypoints] split at [pinned] (global indices), every piece one request ([planOne]). */
    private fun planPinned(waypoints: List<GeoPoint>, from: Int, to: Int, activity: Activity, sac: Int, pinned: Set<Int>): PlannedRoute {
        val cuts = chunkBounds(to - from + 1, pinned.map { it - from }.toSet()).map { it + from }
        val parts = cuts.zipWithNext().map { (a, b) -> planOne(waypoints.subList(a, b + 1), activity, sac, a).route }
        return if (parts.size == 1) parts[0] else merge(cuts, parts)
    }

    /** Chunks [parts] between [cuts] (indices, sharing their boundary points) as one route. */
    private fun merge(cuts: List<Int>, parts: List<PlannedRoute>): PlannedRoute {
        val local = cuts.map { it - cuts.first() }
        return PlannedRoute(
            parts.flatMap { it.legs },
            parts.sumOf { it.distanceM }, parts.sumOf { it.ascentM }, parts.sumOf { it.descentM }, parts.sumOf { it.durationS },
            parts.firstNotNullOfOrNull { it.error },
            RouteSummary.mergeShares(parts.map { it.wayTypes }), RouteSummary.mergeShares(parts.map { it.surfaces }),
            mergeSnapped(local.last() + 1, local, parts.map { it.snapped }),
            parts.flatMap { it.skipped }.toSet(),
        )
    }

    /** A chunk's route, and the engine's result when it was routed in one request (null: leg by leg, straight lines). */
    private class Chunk(val route: PlannedRoute, val whole: RouteResult? = null)

    /**
     * The numbers of a piece of a routed line, to take it out of its route's ([spliceRun]): metres ([scale] = BRouter's
     * metres per drawn metre), filtered ascent / descent ([climb]) and metres per way type / surface from the points' way
     * attributes ([GeoPoint.way] = the way to the next point); [unknown] = metres without attributes.
     */
    private class Piece(val dist: Double, val up: Double, val down: Double,
                        val ways: Map<String, Double>, val surf: Map<String, Double>, val unknown: Double) {
        /** [shares] (a route's breakdown) without this piece's metres per key ([ways] or [surf]); [unknown] in proportion. */
        fun minus(shares: List<Share>, known: Map<String, Double>): List<Share> {
            val left = shares.map { Share(it.key, (it.meters - (known[it.key] ?: 0.0)).coerceAtLeast(0.0)) }
            val sum = left.sumOf { it.meters }
            val keep = if (sum > 0.0) ((sum - unknown) / sum).coerceIn(0.0, 1.0) else 0.0
            return left.map { Share(it.key, it.meters * keep) }.filter { it.meters > 0.0 }
        }

        companion object {
            fun of(line: List<GeoPoint>, scale: Double): Piece {
                val ways = HashMap<String, Double>(); val surf = HashMap<String, Double>()
                var dist = 0.0; var unknown = 0.0
                for (i in 1 until line.size) {
                    val d = Geo.distance(line[i - 1], line[i]) * scale
                    dist += d
                    val w = line[i - 1].way
                    if (w == null) unknown += d else { ways.merge(w.wayType, d, Double::plus); surf.merge(w.surface, d, Double::plus) }
                }
                val (up, down) = climb(line)
                return Piece(dist, up, down, ways, surf, unknown)
            }
        }
    }

    /**
     * A chunk routed in one request: its points [sub] (global index of the first: [offset]), its line, where BRouter
     * matched each point ([matched], same size), the line index at each point ([at], [cutIndices]) and its numbers.
     * [splice] replaces pieces of [points] and keeps the rest; [planned] is the chunk as a [PlannedRoute].
     */
    private class Line(
        val sub: List<GeoPoint>, val offset: Int, val points: List<GeoPoint>, val matched: List<GeoPoint?>, val at: IntArray,
        val dist: Double, val up: Double, val down: Double, val time: Double, val ways: List<Share>, val surf: List<Share>,
    ) {
        fun planned() = PlannedRoute(listOf(PlannedLeg(points, false)), dist, up, down, time, null,
            RouteSummary.mergeShares(listOf(ways)), RouteSummary.mergeShares(listOf(surf)),
            WaypointSnap.onRoute(sub, points, matched), WaypointSnap.skipped(sub, points, matched).map { it + offset }.toSet())

        companion object {
            /** null = no splice possible (BRouter reported no matched spots – then no stop counts as cut either). */
            fun of(sub: List<GeoPoint>, offset: Int, r: RouteResult): Line? {
                if (r.matched.size != sub.size) return null
                val at = cutIndices(sub, r.points, r.matched) ?: return null
                return Line(sub, offset, r.points, r.matched, at, r.distanceM, r.ascentM, r.descentM, r.durationS, r.wayTypes, r.surfaces)
            }
        }
    }

    /**
     * Routes to the cut stops [spurs] (global indices, interior points of [line]) without routing the chunk again: for
     * each run of consecutive cut stops i..j the legs A → i → … → j → B are routed one by one (2-point requests, so every
     * stop is a request end; with the short-trip retry like any leg) and replace the first line between A and B
     * ([spliceRun]). A and B are points of the first line [SPLICE_REACH_M] before / after where it passes the cut stops
     * (BRouter's via correction cuts at most 400 m there and back), or the plan points i−1 / j+1 when those are nearer –
     * a 50 km leg is not routed again for a spur at its start. The rest of the line keeps its route, short-trip retries
     * included. null = it does not fit cleanly (a leg without a route, see [spliceRun]); the caller routes the chunk again.
     */
    private fun splice(line: Line, spurs: Set<Int>, activity: Activity, sac: Int): Line? {
        val local = spurs.map { it - line.offset }.filter { it in 1 until line.sub.lastIndex }.sorted()
        if (local.isEmpty()) return null
        val runs = ArrayList<IntRange>()
        for (k in local) if (runs.isNotEmpty() && runs.last().last == k - 1) runs[runs.lastIndex] = runs.last().first..k else runs += k..k
        var cur = line
        for (run in runs.asReversed()) { // last first: a splice moves only the line indices after it
            val a = run.first - 1; val b = run.last + 1
            // splice points: SPLICE_REACH_M along the first line before / after where it passes the cut stops – or the
            // plan points a and b when they are nearer (a long leg need not be routed again for a spur at its end)
            val ca = back(cur.points, cur.at[run.first], SPLICE_REACH_M).coerceAtLeast(cur.at[a])
            val cb = ahead(cur.points, cur.at[run.last], SPLICE_REACH_M).coerceAtMost(cur.at[b])
            val stops = listOf(if (ca == cur.at[a]) cur.sub[a] else cur.points[ca]) + cur.sub.subList(run.first, run.last + 1) +
                (if (cb == cur.at[b]) cur.sub[b] else cur.points[cb])
            val legs = (0 until stops.lastIndex).map { n ->
                val k = a + n
                stopIfCancelled()
                try { routeLeg(stops[n], stops[n + 1], activity, sac, cur.offset + k) } catch (e: Exception) {
                    stopIfCancelled()
                    // expected: RoutingException = no route for this leg – the caller routes the whole chunk again as before
                    if (e !is RoutingException) Problems.error("routing", "Leg route request threw unexpectedly", e)
                    Problems.trace("routing", "Leg ${cur.offset + k + 1} → ${cur.offset + k + 2} to a cut stop: no route (${e.message}) – the whole stretch again")
                    return null
                }
            }
            cur = spliceRun(cur, a, b, ca, cb, legs) ?: return null
        }
        return cur
    }

    /** The line index [m] metres along [line] before index [from] (0 when the line is shorter). */
    private fun back(line: List<GeoPoint>, from: Int, m: Double): Int {
        var k = from; var d = 0.0
        while (k > 0 && d < m) { d += Geo.distance(line[k - 1], line[k]); k-- }
        return k
    }

    /** The line index [m] metres along [line] after index [from] (the last when the line is shorter). */
    private fun ahead(line: List<GeoPoint>, from: Int, m: Double): Int {
        var k = from; var d = 0.0
        while (k < line.lastIndex && d < m) { d += Geo.distance(line[k], line[k + 1]); k++ }
        return k
    }

    /**
     * [line] with its piece between the line indices [ca] and [cb] replaced by [legs] (from there to the chunk points
     * a+1 … b−1 and on to [cb]; [a] / [b] = the chunk points before / after them). Distance, ascent, descent, time, way
     * types and surfaces: the old piece out ([Piece]), the legs in. null = the legs do not start / end within [JOIN_M] of
     * the line at [ca] / [cb], or the line would turn back on itself there ([doubles]).
     */
    private fun spliceRun(line: Line, a: Int, b: Int, ca: Int, cb: Int, legs: List<RouteResult>): Line? {
        val p = line.points
        if (ca >= cb || ca < line.at[a] || cb > line.at[b]) return null
        val where = "Splice between points ${line.offset + a + 1} and ${line.offset + b + 1}"
        val joined = ArrayList<GeoPoint>()
        val ends = IntArray(legs.size) // [k] = index in joined of leg k's last point
        legs.forEachIndexed { k, leg ->
            if (joined.isNotEmpty()) {
                // at a stop the next leg's first point wins: its way attributes describe the way onward
                if (Geo.distance(joined.last(), leg.points.first()) > JOIN_M) {
                    Problems.trace("routing", "$where: the legs do not meet at point ${line.offset + a + k + 1} – the whole stretch again")
                    return null
                }
                joined[joined.lastIndex] = leg.points.first()
                joined.addAll(leg.points.subList(1, leg.points.size))
            } else joined += leg.points
            ends[k] = joined.lastIndex
        }
        val gapA = Geo.distance(p[ca], joined.first()); val gapB = Geo.distance(joined.last(), p[cb])
        if (gapA > JOIN_M || gapB > JOIN_M) {
            Problems.trace("routing", "$where: the legs start / end ${gapA.toInt()} / ${gapB.toInt()} m off the line – the whole stretch again")
            return null
        }
        val before = p.subList(0, ca + 1); val after = p.subList(cb, p.size)
        // a new U-turn only: a stop at a dead end that the first line already turned back at may stay one
        val inner = p.subList(ca, cb + 1)
        if ((doubles(before, joined) && !doubles(before, inner)) ||
            (doubles(after.asReversed(), joined.asReversed()) && !doubles(after.asReversed(), inner.asReversed()))) {
            Problems.trace("routing", "$where: the line would turn back on itself – the whole stretch again")
            return null
        }
        // the first line's point at b carries the way onward (the legs' last point is the same spot, ≤ JOIN_M)
        val points = ArrayList<GeoPoint>(ca + joined.size + after.size)
        points += p.subList(0, ca); points += joined.subList(0, joined.lastIndex); points += after
        val shift = ca + joined.lastIndex - cb
        val at = IntArray(line.at.size) { k -> if (k <= a) line.at[k] else if (k < b) ca + ends[k - a - 1] else line.at[k] + shift }
        val drawn = Geo.length(p)
        val old = Piece.of(p.subList(ca, cb + 1), if (drawn > 0.0) line.dist / drawn else 1.0)
        val rest = if (line.dist > 0.0) (1.0 - old.dist / line.dist).coerceIn(0.0, 1.0) else 0.0
        Problems.trace("routing", "$where: ${old.dist.toInt()} m of the first line → ${legs.sumOf { it.distanceM }.toInt()} m to the cut stop(s)")
        return Line(line.sub, line.offset, points, line.matched, at,
            (line.dist - old.dist).coerceAtLeast(0.0) + legs.sumOf { it.distanceM },
            (line.up - old.up).coerceAtLeast(0.0) + legs.sumOf { it.ascentM },
            (line.down - old.down).coerceAtLeast(0.0) + legs.sumOf { it.descentM },
            line.time * rest + legs.sumOf { it.durationS },
            RouteSummary.mergeShares(listOf(old.minus(line.ways, old.ways)) + legs.map { it.wayTypes }),
            RouteSummary.mergeShares(listOf(old.minus(line.surf, old.surf)) + legs.map { it.surfaces }))
    }

    /** One leg [a] → [b] as its own request, with the short-trip retry ([retryShortDetours]); [index] = global index of [a]. */
    private fun routeLeg(a: GeoPoint, b: GeoPoint, activity: Activity, sac: Int, index: Int): RouteResult {
        val t0 = now()
        val first = route(listOf(a, b), activity, sac)
        if (first.points.size < 2) throw RoutingException(Txt.s(Res.string.plan_err_no_route_between, index + 1, index + 2))
        return retryShortDetours(listOf(a, b), first, activity, sac, index, t0)
    }

    /** [offset] = index of this chunk's first point in the full list (for messages and [PlannedRoute.skipped]). */
    private fun planOne(waypoints: List<GeoPoint>, activity: Activity, sac: Int, offset: Int = 0): Chunk {
        if (engine is StraightLineEngine) {
            val r = engine.route(waypoints, activity, sac)
            val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
            return Chunk(PlannedRoute(legs, r.distanceM, r.ascentM, r.descentM, r.durationS,
                Txt.s(Res.string.plan_err_straight)))
        }
        try {
            val t0 = now()
            val r = retryShortDetours(waypoints, route(waypoints, activity, sac), activity, sac, offset, t0)
            if (r.points.size >= 2) {
                return Chunk(PlannedRoute(listOf(PlannedLeg(r.points, false)), r.distanceM, r.ascentM, r.descentM, r.durationS, null,
                    RouteSummary.mergeShares(listOf(r.wayTypes)), RouteSummary.mergeShares(listOf(r.surfaces)),
                    WaypointSnap.onRoute(waypoints, r.points, r.matched), spurs(waypoints, r, offset)), r)
            }
        } catch (e: Exception) {
            stopIfCancelled() // cancelled: no fallback, the caller shows nothing new
            // expected: RoutingException = no route (the user sees its message); anything else is an engine bug
            if (e !is RoutingException) Problems.error("routing", "Route request threw unexpectedly", e)
            // at the time limit in Plan: the user is asked "Weiter warten?" (owner 2026-11-03, SlowPlan)
            if (e is RoutingException && e.timedOut && SlowPlan.atLimit(askAtLimit) == SlowPlan.AtLimit.ASK) throw SlowPlan.TimeLimit(rounds)
            // Two points: no need to retry leg by leg (BRouter can take ~30 s to fail).
            if (waypoints.size == 2) {
                val d = Geo.distance(waypoints[0], waypoints[1])
                return Chunk(PlannedRoute(listOf(PlannedLeg(waypoints, true)), d, 0.0, 0.0,
                    flatSeconds(d, activity),
                    legError(1, 2, e)))
            }
            // Timed out: each leg would get its own limit and the wait multiplies (a 4-point plan up to 120 s) –
            // straight lines at once, the user adds points or tries again.
            if (e is RoutingException && e.timedOut) return Chunk(straight(waypoints, activity, TOOK_TOO_LONG))
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
                val r = routeLeg(a, b, activity, sac, offset + i - 1)
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
                if (e is RoutingException && e.timedOut && SlowPlan.atLimit(askAtLimit) == SlowPlan.AtLimit.ASK) throw SlowPlan.TimeLimit(rounds)
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
        return Chunk(PlannedRoute(legs, dist, up, down, time, firstError,
            RouteSummary.mergeShares(ways), RouteSummary.mergeShares(surf), snapped.toList()))
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
            val cut = cutIndices(waypoints, line, matched) ?: return null
            val cum = Geo.cumulative(line)
            return DoubleArray(waypoints.size - 1) { cum[cut[it + 1]] - cum[cut[it]] }
        }

        /** The index in [line] at each of [waypoints] (first 0, last the line's last), found as in [legLengths]. */
        fun cutIndices(waypoints: List<GeoPoint>, line: List<GeoPoint>, matched: List<GeoPoint?>): IntArray? {
            if (line.size < 2 || waypoints.size < 2) return null
            val cut = IntArray(waypoints.size); cut[waypoints.lastIndex] = line.lastIndex
            for (k in 1 until waypoints.lastIndex) {
                val target = matched.getOrNull(k) ?: waypoints[k]
                val from = cut[k - 1]
                val d = DoubleArray(line.size - from) { Geo.distance(line[from + it], target) }
                val best = d.minOrNull() ?: return null
                cut[k] = from + d.indexOfFirst { it <= best + 5.0 }
            }
            return cut
        }

        /** A splice ([splice]) starts / ends this far along the first line from where it passes a cut stop (m) … */
        const val SPLICE_REACH_M = 1_000.0
        /** … and its legs must start / end this close to the first line there (m). */
        const val JOIN_M = 5.0
        /** [doubles]: how far along both lines from the join the new line is checked (m) … */
        const val TURN_BACK_M = 20.0
        /** … and how close it may come to the old line's last metres there (m) – closer = it goes back the same way. */
        const val TURN_BACK_NEAR_M = 3.0

        /**
         * The new line [next] (starting at the join) goes back along [prev] (ending at the join): a U-turn at the splice
         * point, a piece walked twice. Its point [TURN_BACK_M] from the join lies within [TURN_BACK_NEAR_M] of [prev]'s
         * last [TURN_BACK_M] + 10 m. An ordinary turn – even a sharp 30° one – stays clear of that.
         */
        fun doubles(prev: List<GeoPoint>, next: List<GeoPoint>): Boolean {
            if (prev.size < 2 || next.size < 2) return false
            val ahead = along(next, TURN_BACK_M) ?: return false
            var back = 0.0; var k = prev.lastIndex
            while (k > 0 && back < TURN_BACK_M + 10.0) { back += Geo.distance(prev[k - 1], prev[k]); k-- }
            return WaypointSnap.distToLine(ahead, prev.subList(k, prev.size)) <= TURN_BACK_NEAR_M
        }

        /** The point [m] metres along [line] from its start; null = the line is shorter. */
        private fun along(line: List<GeoPoint>, m: Double): GeoPoint? {
            var done = 0.0
            for (i in 1 until line.size) {
                val d = Geo.distance(line[i - 1], line[i])
                if (done + d >= m) {
                    val t = if (d > 0.0) (m - done) / d else 0.0
                    val a = line[i - 1]; val b = line[i]
                    return GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
                }
                done += d
            }
            return null
        }

        /** BRouter's hysteresis for ascent / descent (10 m, as [BRouterEngine] mirrors it for descent). */
        const val ELE_FILTER_M = 10.0

        /** Filtered ascent and descent of [line]'s elevations (BRouter's hysteresis, [ELE_FILTER_M]). */
        fun climb(line: List<GeoPoint>): Pair<Double, Double> = Geo.routeClimb(line.map { it.ele }, ELE_FILTER_M)

        /** Routes to points cut as a detour at most this often per chunk ([planChunk]); each pass ends at least one more point. */
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
