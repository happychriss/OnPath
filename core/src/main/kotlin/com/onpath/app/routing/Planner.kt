package com.onpath.app.routing

import com.onpath.strings.*
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.debug.UserText
import com.onpath.app.domain.PlanGap
import com.onpath.app.domain.PlanGaps

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
     * Interior points (indices in the request) the router matched on a way but whose spot the line misses
     * ([WaypointSnap.missesMatched]). Every plan point is a leg end ([Planner.plan]), so this stays empty unless a leg
     * ends short of its point's spot: the plan keeps the point where it is and shows the gap (PlanGaps), never moves it.
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
 * Routes a plan **leg by leg** (owner 2026-11-05, "long bike routes faster", PLANNER_VERSION 4,
 * docs/design/long-bike-routes.md): every leg point → point is its own request of two points, so every plan point is a
 * request end – a point on a way is always visited (owner rule, core feature C3) without a second pass. A finished leg
 * goes into [legs] ([LegCache]); the next run takes it from there, so an edit routes only the legs it changed. Same
 * points = same legs = same route: the cache saves time, never changes a line.
 *
 * At each join the line may go into a spur and back the same way (the leg ends where the router matched the point, the
 * next starts there). A whole BRouter request cut such a there-and-back at a via point (its via correction, up to 400 m
 * for bikes, 50 m for hikes); the Planner does the same at the join ([trimSpike]) – except at a pinned point and where
 * the point lies on that spur (owner rule: the point is visited – [WaypointSnap.missesMatched], as before).
 *
 * A leg without a route becomes a straight dashed line (R-12/R-13); after a timeout the legs still to route are straight
 * at once (each would wait its own limit) – or, in Plan, the user is asked "Weiter warten?" ([SlowPlan]).
 */
class Planner(
    private val engine: RoutingEngine,
    /** "Cancel" while a route is calculated: stops the router at once, no straight lines. */
    private val cancel: RouteCancel? = null,
    /** Wall clock in ms (tests pass their own: the short-trip retry's time budget, [retryShortDetour]). */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** false = no short-trip detour retry ([retryShortDetour]) – only for A/B runs (tools/routing/jvm-test/ShortDetourTest.kt). */
    private val shortDetourRetry: Boolean = true,
    /**
     * A plan run the user started in Plan (owner 2026-11-03, [SlowPlan]): at the time limit [plan] throws
     * [SlowPlan.TimeLimit] – the caller asks "Weiter warten?" – instead of straight lines; false = straight lines as before.
     */
    private val askAtLimit: Boolean = false,
    /** Time-limit periods of this run (2+ after "Weiter warten", [RouteLimits.rounds]). */
    private val rounds: Int = 1,
    /** Legs of earlier runs ([LegCache]); null = route every leg. */
    private val legs: LegCache? = null,
    /**
     * Progress for the wait card (owner 2026-11-05): (legs done, legs to route) – only the legs the router works on count,
     * legs from [legs] are there at once. Called with (0, n) before the first and after each leg; not at all when every leg
     * comes from the cache. On the planning thread.
     */
    private val progress: ((Int, Int) -> Unit)? = null,
) {
    /** One engine request of this planner: points, time, outcome ("ok", "timeout", "failed"), wait for the engine lock. */
    data class Try(val points: Int, val ms: Long, val outcome: String, val lockWaitMs: Long)

    /** Every engine request so far (for the per-route log line, [describeTries]). One planner = one plan, one thread. */
    val tries = ArrayList<Try>()

    /** Legs of the last [plan] taken from [legs] (not routed) – for the per-route log line. */
    var cachedLegs = 0
        private set

    /** Of [cachedLegs], the legs read from the phone's leg store (a saved route after a restart – no router run). */
    var storedLegs = 0
        private set

    /** "2 pts 1.2 s ok; 3 pts 0.4 s ok (2 legs from the cache)" – short, for the event log. */
    fun describeTries(): String = tries.joinToString("; ") { t ->
        "${t.points} pts ${String.format(java.util.Locale.ROOT, "%.1f", t.ms / 1000.0)} s ${t.outcome}" +
            if (t.lockWaitMs > 0) " (waited ${t.lockWaitMs} ms for the router)" else ""
    } + if (cachedLegs > 0) "${if (tries.isEmpty()) "" else "; "}$cachedLegs legs from the cache" +
        (if (storedLegs > 0) " ($storedLegs stored)" else "") else ""

    /** [limitMs] = this request's own time limit (the short-trip retry, [retryShortDetour]); null = the engine's usual one. */
    private fun route(pts: List<GeoPoint>, activity: Activity, sac: Int, limitMs: Long? = null): RouteResult {
        if (cancel?.isCancelled == true) throw RoutingException(RouterRules.CANCELLED)
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
                engine.lastLockWaitMs())
        }
    }

    private fun stopIfCancelled() { if (cancel?.isCancelled == true) throw RoutingException(RouterRules.CANCELLED) }

    /**
     * Routes [waypoints] leg by leg ([Planner]). [pinned] = indices of waypoints that must be passed exactly (a tapped
     * hut, a POI): no there-and-back is trimmed at them.
     */
    fun plan(waypoints: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int> = emptySet()): PlannedRoute {
        require(waypoints.size >= 2) { "a plan needs two points, got ${waypoints.size}" }
        if (engine is StraightLineEngine) {
            val r = engine.route(waypoints, activity, sac)
            val legs = (1 until waypoints.size).map { PlannedLeg(listOf(waypoints[it - 1], waypoints[it]), true) }
            return PlannedRoute(legs, r.distanceM, r.ascentM, r.descentM, r.durationS, Txt.s(Res.string.plan_err_straight))
        }
        val n = waypoints.size - 1
        val results = arrayOfNulls<RouteResult>(n)
        var stored = 0
        for (i in 0 until n) results[i] = legs?.lookup(waypoints[i], waypoints[i + 1], activity, sac)?.also { if (it.stored) stored++ }?.leg
        cachedLegs = results.count { it != null }
        storedLegs = stored
        val total = n - cachedLegs
        var done = 0
        if (total > 0) progress?.invoke(0, total)
        var error: String? = null
        var timedOut = false
        for (i in 0 until n) {
            if (results[i] != null) continue
            stopIfCancelled()
            if (timedOut) {
                if (error == null) error = TOOK_TOO_LONG
            } else results[i] = try {
                routeLeg(waypoints[i], waypoints[i + 1], activity, sac, i).also { legs?.put(waypoints[i], waypoints[i + 1], activity, sac, it) }
            } catch (e: Exception) {
                stopIfCancelled() // cancelled: no straight lines, the caller shows nothing new
                // expected: RoutingException = no route for this leg (a straight dashed line); anything else is an engine bug
                if (e !is RoutingException) Problems.error("routing", "Leg route request threw unexpectedly", e)
                if (e is RoutingException && e.timedOut) {
                    // at the time limit in Plan: the user is asked "Weiter warten?" (owner 2026-11-03, SlowPlan) – the legs
                    // done so far are in the cache, the next run starts after them
                    if (SlowPlan.atLimit(askAtLimit) == SlowPlan.AtLimit.ASK) throw SlowPlan.TimeLimit(rounds)
                    // each leg would get its own limit and the wait multiplies: the rest straight at once
                    timedOut = true
                    if (error == null) error = if (n == 1) legError(1, 2, e) else TOOK_TOO_LONG
                } else if (error == null && !isMiniLeg(Geo.distance(waypoints[i], waypoints[i + 1]))) error = legError(i + 1, i + 2, e)
                null
            }
            done++
            progress?.invoke(done, total)
        }
        if (!timedOut && error == null && n >= 2 && results.all { it != null }) passUnreachable(waypoints, results, activity, sac)
        return join(waypoints, results, activity, pinned, error)
    }

    /**
     * A stop the chosen level cannot reach no longer pulls the route on a long detour (owner 2026-11-21, field case Hike T1
     * Vellau → Mutkopf → Leiteralm: 30 km, 38 % of the line twice, the stop still 552 m off – round the mountain to the
     * nearest T1 way under it and back). For a run of **interior** plan points that the legs do not reach (≥ [PlanGaps.FAR_M]
     * from where the router put them; mostly one stop) the leg a → b over the run's reached neighbours is routed directly
     * ([routeLeg], kept in [legs] – the whole leg a plan without the run has) and cut at its line points nearest the stops
     * ([LegSplit.split]), the pieces replacing the run's legs (and kept in [legs] under their plan points) – one leg per
     * pair of plan points stays. Only when all hold: the way via the run is a real detour ([isPastDetour]); something else
     * reaches every stop of it ([PlanReach.find] is not [PlanReach.Fix.None]: a higher level, a rougher bike, on foot – a
     * stop nothing reaches keeps today's line, and so does a useful short spur); the direct leg can be cut. A failure (no
     * direct route, a timeout, the time budget used up) keeps the legs as they are. The stops stay gaps: red line "② erst
     * ab T2 erreichbar", the grey dashed piece from the cut point. [results] is changed in place; all legs are routed.
     */
    private fun passUnreachable(waypoints: List<GeoPoint>, results: Array<RouteResult?>, activity: Activity, sac: Int) {
        val n = results.size
        // where the router put each plan point (the start / the end of the leg beside it); empty = not known → no gap
        fun spot(i: Int): GeoPoint? =
            if (i < n) results[i]?.matched?.firstOrNull() else results[n - 1]?.matched?.takeIf { it.size >= 2 }?.last()
        val gap = BooleanArray(n + 1) { i -> spot(i)?.let { Geo.distance(waypoints[i], it) >= PlanGaps.FAR_M } == true }
        if ((1 until n).none { gap[it] }) return
        val t0 = now()
        val budget = RouteTimeLimit.forRoute(waypoints, activity) * rounds.coerceAtLeast(1)
        var k = 1
        while (k < n) {
            if (!gap[k]) { k++; continue }
            var m = k
            while (m + 1 < n && gap[m + 1]) m++
            val run = k..m
            k = m + 1
            val a = waypoints[run.first - 1]; val b = waypoints[run.last + 1]
            val old = (run.first - 1)..run.last // the legs of the run's points
            val via = old.sumOf { results[it]!!.distanceM }
            val straight = Geo.distance(a, b)
            // the way via the run is at least the direct way, the direct way at least the straight line: no detour
            // without this much – saves the direct route for the common short spur
            if (straight < MIN_PAST_M || via < PAST_FACTOR * straight || via - straight < PAST_MIN_EXTRA_M) continue
            if (now() - t0 > budget) { Problems.trace("routing", "Point ${run.first + 1}: time budget used – no route past it tried"); break }
            stopIfCancelled()
            val direct = try {
                legs?.get(a, b, activity, sac) ?: routeLeg(a, b, activity, sac, run.first - 1).also { legs?.put(a, b, activity, sac, it) }
            } catch (e: Exception) {
                stopIfCancelled()
                // expected: RoutingException = no direct route (an island, a timeout) – the legs via the stop stay
                if (e !is RoutingException) Problems.error("routing", "Route past point ${run.first + 1} threw unexpectedly; kept the route via it", e)
                Problems.trace("routing", "Point ${run.first + 1}: no direct route past it (${e.message}) – kept the route via it")
                if (e is RoutingException && e.timedOut) break
                continue
            }
            if (!isPastDetour(via, direct.distanceM)) continue
            var fix: PlanReach.Fix? = null
            val reached = run.all { i ->
                val s = spot(i) ?: return@all false
                val r = PlanReach.find(engine, PlanGap(i, waypoints[i], s, Geo.distance(waypoints[i], s)), activity, sac, cancel)
                stopIfCancelled()
                fix = r?.fix
                r != null && r.fix != PlanReach.Fix.None
            }
            if (!reached) continue
            // the direct leg cut at each stop's nearest line point, in order (each cut inside what is left of the line)
            val pieces = ArrayList<RouteResult>()
            var rest = direct
            for (i in run) {
                val (x, y) = LegSplit.split(rest, waypoints[i], Double.MAX_VALUE) ?: break
                pieces += x; rest = y
            }
            if (pieces.size != run.count()) continue
            pieces += rest
            for ((j, i) in old.withIndex()) {
                results[i] = pieces[j]
                legs?.put(waypoints[i], waypoints[i + 1], activity, sac, pieces[j])
            }
            Problems.trace("routing", "Points ${run.first + 1}..${run.last + 1} not reached at ${activity.name} T$sac ($fix): " +
                "route past them, ${via.toInt()} → ${direct.distanceM.toInt()} m")
        }
    }

    /** One leg [a] → [b] as its own request, with the short-trip retry ([retryShortDetour]); [index] = index of [a]. */
    private fun routeLeg(a: GeoPoint, b: GeoPoint, activity: Activity, sac: Int, index: Int): RouteResult {
        val t0 = now()
        val first = route(listOf(a, b), activity, sac)
        if (first.points.size < 2) throw RoutingException(Txt.s(Res.string.plan_err_no_route_between, index + 1, index + 2))
        return retryShortDetour(a, b, first, activity, sac, index, t0)
    }

    /** A routed leg, its line as drawn after trimming ([trimSpike]) and its numbers. */
    private class Leg(val r: RouteResult) {
        var points: List<GeoPoint> = r.points
        /** BRouter's metres per drawn metre (its distance counts the way's own shape, the line only its points). */
        val scale: Double = Geo.length(r.points).let { if (it > 0.0) r.distanceM / it else 1.0 }
        val cut = ArrayList<Piece>()
        val start: GeoPoint? get() = r.matched.firstOrNull()
        val end: GeoPoint? get() = r.matched.takeIf { it.size >= 2 }?.last()
    }

    /** The legs as one route: spikes at the joins trimmed ([trimSpike]), straight dashed lines where a leg failed. */
    private fun join(waypoints: List<GeoPoint>, results: Array<RouteResult?>, activity: Activity, pinned: Set<Int>,
                     error: String?): PlannedRoute {
        val n = results.size
        val legs = results.map { r -> r?.takeIf { it.points.size >= 2 }?.let { Leg(it) } }
        for (k in 1 until n) {
            val a = legs[k - 1] ?: continue; val b = legs[k] ?: continue
            if (k !in pinned) trimSpike(waypoints[k], k, a, b, activity)
        }
        val snapped = arrayOfNulls<GeoPoint>(waypoints.size)
        for (i in 0 until n) legs[i]?.let { l ->
            // a point between two routed legs: the earlier leg's end wins (as before)
            if (snapped[i] == null) snapped[i] = l.points.first()
            snapped[i + 1] = l.points.last()
        }
        val all = legs.all { it != null }
        val matched = if (all) waypoints.indices.map { k ->
            if (k == 0) legs[0]!!.start else legs[k - 1]!!.end ?: legs.getOrNull(k)?.start
        } else emptyList()
        val planned = ArrayList<PlannedLeg>(n)
        var dist = 0.0; var up = 0.0; var down = 0.0; var time = 0.0
        val ways = ArrayList<List<Share>>(); val surf = ArrayList<List<Share>>()
        for (i in 0 until n) {
            val l = legs[i]
            if (l != null) {
                planned += PlannedLeg(l.points, false)
                val gone = l.cut.sumOf { it.dist }
                dist += (l.r.distanceM - gone).coerceAtLeast(0.0)
                up += l.r.ascentM; down += l.r.descentM // only without elevations on the line ([lineClimb])
                time += l.r.durationS * if (l.r.distanceM > 0.0) ((l.r.distanceM - gone) / l.r.distanceM).coerceIn(0.0, 1.0) else 1.0
                ways += l.cut.fold(l.r.wayTypes) { s, p -> p.minus(s, p.ways) }
                surf += l.cut.fold(l.r.surfaces) { s, p -> p.minus(s, p.surf) }
            } else {
                // straight legs start / end where their neighbours' routed legs do, so markers and lines meet
                val a = snapped[i] ?: waypoints[i]; val b = snapped[i + 1] ?: waypoints[i + 1]
                val d = Geo.distance(a, b)
                dist += d; time += flatSeconds(d, activity)
                planned += PlannedLeg(listOf(a.copy(way = null), b.copy(way = null)), true) // no known way on a straight line
            }
        }
        val route = lineClimb(PlannedRoute(planned, dist, up, down, time, error, RouteSummary.mergeShares(ways),
            RouteSummary.mergeShares(surf), snapped.toList()))
        if (!all) return route
        // every leg routed: where each point lies on the whole line, as for one request (the matched spot when the line
        // passes it, else the point projected onto the line), and the points whose spot the line still misses
        val line = route.points
        return route.copy(snapped = WaypointSnap.onRoute(waypoints, line, matched), skipped = WaypointSnap.skipped(waypoints, line, matched))
    }

    /**
     * Ascent and descent of the joined line itself ([climb], one 10 m filter over the whole line – what the days of a
     * multi-day tour, the big profile's range and Navigate's "remaining ascent" count), not the legs' router numbers:
     * BRouter's own ascent uses a 5 m filter on fine elevation data (descent was counted with 10 m), so a ring on the
     * Meraner Höhenweg read ↑ 5891 m ↓ 5420 m while its days added up to ↑ 5422 m. The legs' sum only when the line
     * carries no elevations at all (an engine without them).
     */
    private fun lineClimb(r: PlannedRoute): PlannedRoute {
        val line = r.points
        if (line.none { it.ele != null }) return r
        val (up, down) = climb(line)
        return r.copy(ascentM = up, descentM = down)
    }

    /**
     * The join at plan point [k] (index): leg [a] ends where the router matched the point, [b] starts there. When [a]'s
     * last stretch and [b]'s first are the same way there and back (a spike – a point tapped beside the road, snapped onto
     * a side way), both are cut back to where they part, as BRouter's via correction did inside a whole request: at most
     * [RouterRules.viaCorrectionM] one way, and never when the point lies on that way – the owner rule "a point on a way
     * is always visited" ([WaypointSnap.missesMatched]: the trimmed line would miss its spot).
     */
    private fun trimSpike(point: GeoPoint, k: Int, a: Leg, b: Leg, activity: Activity) {
        val pa = a.points; val pb = b.points
        if (Geo.distance(pa.last(), pb.first()) > JOIN_M) return
        // shared segments back from the join: pa[last - j] = pb[j]
        var m = 0
        val max = minOf(pa.size, pb.size) - 2
        while (m < max && Geo.distance(pa[pa.lastIndex - (m + 1)], pb[m + 1]) <= SAME_M) m++
        if (m == 0) return
        val fa = pa.lastIndex - m // the fork in pa (= pb[m])
        val spike = Geo.length(pa.subList(fa, pa.size))
        if (spike > RouterRules.viaCorrectionM(activity)) return
        val spot = a.end ?: pa.last()
        val near = pa.subList(maxOf(0, fa - NEAR_POINTS), fa + 1) + pb.subList(m + 1, minOf(pb.size, m + 1 + NEAR_POINTS))
        if (WaypointSnap.missesMatched(point, spot, near)) {
            Problems.trace("routing", "Point ${k + 1} on a way at the end of a ${spike.toInt()} m spur – visited, not trimmed")
            return
        }
        a.cut += Piece.of(pa.subList(fa, pa.size), a.scale)
        b.cut += Piece.of(pb.subList(0, m + 1), b.scale)
        a.points = pa.subList(0, fa + 1)
        // the fork carries b's way onward (the next leg's first point wins at a join)
        b.points = pb.subList(m, pb.size)
        Problems.trace("routing", "Point ${k + 1}: ${spike.toInt()} m there and back at the join trimmed (beside the way)")
    }

    /**
     * The numbers of a piece of a routed line, to take it out of its leg's ([trimSpike]): metres ([scale] = BRouter's
     * metres per drawn metre) and metres per way type / surface from the points' way
     * attributes ([GeoPoint.way] = the way to the next point); [unknown] = metres without attributes.
     */
    private class Piece(val dist: Double,
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
                return Piece(dist, ways, surf, unknown)
            }
        }
    }

    /**
     * Short-trip detour retry (owner 2026-10-31, field case Hilden; docs/design/bike-routing.md decision 12): BRouter's
     * cheapest way between two points close together can be a long detour – 2.1 km over signed cycle routes for
     * 0.73 km straight, because the direct 1.1 km has 13 right-angle turns and a short pedestrian zone. A leg with a
     * straight distance under [SHORT_LEG_M] routed more than [DETOUR_FACTOR] × that (and at least [MIN_DETOUR_M] longer,
     * [isShortDetour]) is routed once more through the middle of the straight line ([midpoint]), a normal via BRouter
     * snaps onto the nearest way. The retry is taken only when it is at least [SHORTER_BY] shorter and not longer by the
     * router's own count; a retry that fails keeps the first route (it never makes a straight leg). The extra via is
     * internal to the leg: the result's [RouteResult.matched] keeps the leg's two ends.
     *
     * Time: the retry counts against the leg's time limit ([RouteTimeLimit.forRoute], started at [t0]); it gets only what
     * is left of it and does not start with less than [MIN_RETRY_MS]. Returns [r] when nothing was taken.
     */
    private fun retryShortDetour(a: GeoPoint, b: GeoPoint, r: RouteResult, activity: Activity, sac: Int, index: Int, t0: Long): RouteResult {
        if (!shortDetourRetry || r.points.size < 2) return r
        val straight = Geo.distance(r.matched.getOrNull(0) ?: a, r.matched.getOrNull(1) ?: b)
        val routed = Geo.length(r.points)
        if (!isShortDetour(straight, routed)) return r
        val leg = "Leg ${index + 1}: short-trip detour ${fmt(routed / straight)}×"
        val left = RouteTimeLimit.forRoute(listOf(a, b), activity) - (now() - t0)
        if (left < MIN_RETRY_MS) { Problems.trace("routing", "$leg → no time left to retry via the midpoint"); return r }
        val retry = try { route(listOf(a, midpoint(a, b), b), activity, sac, left) } catch (e: Exception) {
            stopIfCancelled()
            // expected: RoutingException = no route via the midpoint (an island, a timeout) – the first route stays
            if (e !is RoutingException) Problems.error("routing", "Short-trip retry threw unexpectedly; kept the first route", e)
            Problems.trace("routing", "$leg → retried via midpoint: no route (${e.message}) – kept ${routed.toInt()} m"); return r
        }
        val len = Geo.length(retry.points)
        val take = retry.points.size >= 2 && len <= (1.0 - SHORTER_BY) * routed && retry.distanceM < r.distanceM
        Problems.trace("routing", "$leg → retried via midpoint: ${routed.toInt()} → ${len.toInt()} m (${if (take) "taken" else "kept the first"})")
        if (!take) return r
        return retry.copy(matched = if (retry.matched.size == 3) listOf(retry.matched[0], retry.matched[2]) else emptyList())
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

    /** A straight leg (no router): flat, timed by the one time model (the plan sheet re-times the whole line anyway). */
    private fun flatSeconds(d: Double, activity: Activity) = com.onpath.app.time.PaceModel.seconds(d, 0.0, activity)

    companion object {
        /** The legs still to route after a timeout (straight lines at once). */
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

        /**
         * A leg shorter than this (straight, metres) that the router cannot do is no "no path found" (field bug
         * 2026-11-06: the goal 11 m from the start): the straight line between two points this close is the way.
         */
        const val MINI_LEG_M = 20.0

        fun isMiniLeg(straightM: Double): Boolean = straightM < MINI_LEG_M

        /** Short-trip detour retry ([retryShortDetour]): legs shorter than this (straight, metres) … */
        const val SHORT_LEG_M = 2_000.0
        /** … routed more than this many times their straight distance … */
        const val DETOUR_FACTOR = 2.0
        /** … and at least this much longer (metres; going round a block is no detour) are retried. */
        const val MIN_DETOUR_M = 100.0
        /** The retry is taken when it is at least this share shorter than the leg's first route. */
        const val SHORTER_BY = 0.30
        /** A retry does not start with less than this left of its leg's time limit (ms). */
        const val MIN_RETRY_MS = 1_000L

        /** A leg [straight] metres apart, routed [routed] metres: a short trip with a long detour ([retryShortDetour]). */
        fun isShortDetour(straight: Double, routed: Double): Boolean =
            straight > 0.0 && straight < SHORT_LEG_M && routed > DETOUR_FACTOR * straight && routed - straight >= MIN_DETOUR_M

        /** The middle of the straight line [a] → [b] (short legs: the flat mean is exact enough). */
        fun midpoint(a: GeoPoint, b: GeoPoint) = GeoPoint((a.lat + b.lat) / 2.0, (a.lon + b.lon) / 2.0)

        /** Route past an unreachable stop ([passUnreachable]): the way via it must be at least this many times the direct way … */
        const val PAST_FACTOR = 1.5
        /** … and at least this much (metres) longer – a short spur to the stop stays as it is. */
        const val PAST_MIN_EXTRA_M = 2_000.0
        /** The two neighbours of the stop must be at least this far apart (straight, metres): a there-and-back has no "past". */
        const val MIN_PAST_M = 500.0

        /** The way [via] a stop [direct] metres otherwise: a real detour worth routing past the stop. */
        fun isPastDetour(via: Double, direct: Double): Boolean =
            direct > 0.0 && via >= PAST_FACTOR * direct && via - direct >= PAST_MIN_EXTRA_M

        /** Two legs meet at a plan point when their ends are this close (m) – else nothing is trimmed there. */
        const val JOIN_M = 5.0
        /** Line points of two legs this close (m) are the same node: the line goes back the way it came. */
        const val SAME_M = 0.5
        /** Line points on each side of a trimmed join checked for the point's spot ([trimSpike]). */
        private const val NEAR_POINTS = 30

        /**
         * The plan's hysteresis for ascent / descent (10 m, as [BRouterEngine] counts descent; BRouter's own ascent filter
         * is 5 m on fine elevation data, so a plan's totals come from its line – [lineClimb]).
         */
        const val ELE_FILTER_M = 10.0

        /** Filtered ascent and descent of [line]'s elevations (BRouter's hysteresis, [ELE_FILTER_M]). */
        fun climb(line: List<GeoPoint>): Pair<Double, Double> = Geo.routeClimb(line.map { it.ele }, ELE_FILTER_M)

        /** With a cancel signal: the overall wait for the router (each request keeps its own search budget). */
        const val PLAN_LIMIT_MS = 10 * 60_000L
    }
}
