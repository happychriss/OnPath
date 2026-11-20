package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * A point put in ON a routed leg's line is no reason to route (owner 2026-11-19: „Übernachtung hier“ at a spot of the
 * route re-routed the whole tour – the point lies on the route, the route cannot change): the leg is cut at that line
 * point into two legs, both put into the [LegCache] under their plan points, so the plan that follows takes them from
 * there – nothing routed, the line point for point the same, distance, climb and time the same, only a point (a day
 * border) more.
 *
 * Only a point within [ON_LINE_M] of one of the leg's own line points (what the planner's line hands out: the night's
 * spot, the route's hover point) and not one of its ends; a point beside the line (a click a few metres off, a hotel
 * away from the route) is routed as before – the router may take another way through it.
 *
 * One implementation for both (owner 2026-11-20: the app cuts like the planner): the web planner calls [seed] before
 * each plan (web/engine WebApi), the app before each run of the plan (state/ PlanRecalcSlice). Pure Kotlin – the
 * planner compiles the core folder as it is.
 */
object LegSplit {
    /** A point this close to a line point of the leg lies on it (the page's 6 decimals are ≤ 0.08 m). */
    const val ON_LINE_M = 0.5

    /**
     * Before a plan of [wps]: each point [k] whose two legs are not known while the leg [k − 1] → [k + 1] is (the point was
     * put in on it) – that leg cut at the point ([split]) into the [cache]. Returns the number of legs cut.
     */
    fun seed(cache: LegCache, wps: List<GeoPoint>, activity: Activity, sac: Int): Int {
        var cut = 0
        for (k in 1 until wps.lastIndex) {
            val a = wps[k - 1]; val p = wps[k]; val c = wps[k + 1]
            if (cache.get(a, p, activity, sac) != null || cache.get(p, c, activity, sac) != null) continue
            val whole = cache.get(a, c, activity, sac) ?: continue
            val (x, y) = split(whole, p) ?: continue
            cache.put(a, p, activity, sac, x)
            cache.put(p, c, activity, sac, y)
            cut++
        }
        return cut
    }

    /**
     * [leg] cut at its line point nearest [at] (within [ON_LINE_M], never an end): the two legs, their numbers in
     * proportion to the line's length on each side (the second takes the rest, so the two add up to the whole), the way
     * types and surfaces by the line points' own way attributes. Null = [at] is not on the leg's line.
     */
    fun split(leg: RouteResult, at: GeoPoint): Pair<RouteResult, RouteResult>? {
        val pts = leg.points
        if (pts.size < 3) return null
        var j = -1; var best = Double.MAX_VALUE
        for (i in 1 until pts.lastIndex) {
            val d = Geo.distance(pts[i], at)
            if (d < best) { best = d; j = i }
        }
        if (j < 0 || best > ON_LINE_M) return null
        val a = pts.subList(0, j + 1).toList(); val b = pts.subList(j, pts.size).toList()
        val len = Geo.length(pts)
        val f = if (len > 0.0) (Geo.length(a) / len).coerceIn(0.0, 1.0) else 0.5
        val scale = if (len > 0.0) leg.distanceM / len else 1.0
        val (wa, wb) = shares(leg.wayTypes, a, scale) { it.wayType }
        val (sa, sb) = shares(leg.surfaces, a, scale) { it.surface }
        val m = leg.matched.takeIf { it.size >= 2 }
        val first = RouteResult(a, leg.distanceM * f, leg.ascentM * f, leg.descentM * f, leg.durationS * f, wa, sa,
            m?.let { listOf(it.first(), pts[j]) } ?: emptyList())
        val second = RouteResult(b, leg.distanceM - first.distanceM, leg.ascentM - first.ascentM, leg.descentM - first.descentM,
            leg.durationS - first.durationS, wb, sb, m?.let { listOf(pts[j], it.last()) } ?: emptyList())
        return first to second
    }

    /**
     * [shares] split: the first part's metres per key from [line]'s way attributes (× [scale], BRouter's metres per drawn
     * metre; metres without attributes spread like the whole), the second part the rest – the two merge to [shares].
     */
    private fun shares(shares: List<Share>, line: List<GeoPoint>, scale: Double,
                       key: (WayAttrs) -> String): Pair<List<Share>, List<Share>> {
        val known = HashMap<String, Double>(); var unknown = 0.0
        for (i in 1 until line.size) {
            val d = Geo.distance(line[i - 1], line[i]) * scale
            val w = line[i - 1].way
            if (w == null) unknown += d else known.merge(key(w), d, Double::plus)
        }
        val sum = shares.sumOf { it.meters }
        val first = shares.map { s ->
            Share(s.key, ((known[s.key] ?: 0.0) + if (sum > 0.0) unknown * s.meters / sum else 0.0).coerceIn(0.0, s.meters))
        }
        val second = shares.indices.map { Share(shares[it].key, shares[it].meters - first[it].meters) }
        return first.filter { it.meters > 0.0 } to second.filter { it.meters > 0.0 }
    }
}
