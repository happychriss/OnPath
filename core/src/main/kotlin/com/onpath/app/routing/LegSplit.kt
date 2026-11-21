package com.onpath.app.routing

import com.onpath.app.data.Geo
import kotlin.math.abs

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

    /** A parent leg of [seed] over an [ExpandedPlan] spans at most this many legs of the expanded list. */
    const val MAX_SPAN = 40

    /**
     * [seed] for an [ExpandedPlan] (docs/design/detour-stitch.md §8): the legs of a stitch's chain are cut out of a
     * known longer leg – `prev → a` and `b → next` of `[prev, a, P, b, next]` from the old `prev → next` (or, for a second
     * stitch, from the cut leg `b1 → next`). Parents are the legs (i, j) of the expanded list with 2..[MAX_SPAN] legs
     * between their ends, the smallest first, read from MEMORY only (a cold load must not do thousands of file lookups);
     * a parent with an anchor at an end is the cut leg, else the plain one. Its inner points that lie on its line exactly
     * once and in order ([positionsOn]) cut it by repeated [split] (the numbers keep adding up); only the pieces that are
     * exactly one leg of the list and not known yet are stored – a piece around an off-line point (the detour's P) is
     * routed. A piece that touches an anchor is stored as a cut leg; one between two visible points only from a plain
     * parent (cuts never reach a leg that touches no anchor, §8.9). Without anchors this is the plain [seed]. Returns
     * the number of legs stored.
     */
    fun seed(cache: LegCache, plan: ExpandedPlan, activity: Activity, sac: Int): Int {
        if (!plan.hasAnchors) return seed(cache, plan.points, activity, sac)
        val pts = plan.points
        var stored = 0
        for (span in 2..MAX_SPAN) for (i in 0 until pts.size - span) {
            val j = i + span
            var cutParent = false
            var parent: RouteResult? = null
            if (plan.visibleOf(i) < 0 || plan.visibleOf(j) < 0) {
                parent = cache.inMemory(pts[i], pts[j], activity, sac, cut = true)
                cutParent = parent != null
            }
            parent = parent ?: cache.inMemory(pts[i], pts[j], activity, sac) ?: continue
            val inner = pts.subList(i + 1, j)
            val at = positionsOn(parent.points, inner)
            var rest = parent
            var from = i
            var whole = true
            var last = -1
            for (m in inner.indices) {
                if (at[m] <= last) continue // off the line, ambiguous or out of order: not a cut
                val e = i + 1 + m
                val cut = split(rest, pts[e])
                if (cut == null) { whole = false; break } // the point sits at the rest's end after all: stop, store what is cut
                val (first, second) = cut
                last = at[m]
                stored += keep(cache, plan, from, e, first, cutParent, activity, sac)
                rest = second; from = e
            }
            if (whole) stored += keep(cache, plan, from, j, rest, cutParent, activity, sac)
        }
        return stored
    }

    /** Leg [from] → [to] of [plan] into the [cache] when it is exactly one leg, not known yet, and its key allows it; 1 if stored. */
    private fun keep(cache: LegCache, plan: ExpandedPlan, from: Int, to: Int, leg: RouteResult, cutParent: Boolean,
                     activity: Activity, sac: Int): Int {
        if (to != from + 1) return 0
        val anchored = plan.touchesAnchor(from)
        if (!anchored && cutParent) return 0
        if (cache.lookupLeg(plan, from, activity, sac) != null) return 0
        cache.put(plan.points[from], plan.points[to], activity, sac, leg, cut = anchored)
        return 1
    }

    /**
     * For each of [pts] the index of its line point on [line], or -1: within [ON_LINE_M] of a line point, not at the
     * line's first or last point, and exactly ONE occurrence (line points within [ON_LINE_M] next to each other are one;
     * two separate passes are ambiguous – never guessed). Order is for the caller to check.
     */
    fun positionsOn(line: List<GeoPoint>, pts: List<GeoPoint>): IntArray {
        val n = pts.size
        val runs = IntArray(n); val best = IntArray(n) { -1 }; val bestD = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -2 }; val atEnd = BooleanArray(n)
        val boxLat = 1e-5; val boxLon = DoubleArray(n) { Geo.degLon(1.1, pts[it].lat) }
        for (k in line.indices) {
            val g = line[k]
            for (q in 0 until n) {
                val p = pts[q]
                if (abs(g.lat - p.lat) > boxLat || abs(g.lon - p.lon) > boxLon[q]) continue
                val d = Geo.distance(g, p)
                if (d > ON_LINE_M) continue
                if (prev[q] != k - 1) runs[q]++
                prev[q] = k
                if (k == 0 || k == line.lastIndex) atEnd[q] = true
                if (d < bestD[q]) { bestD[q] = d; best[q] = k }
            }
        }
        return IntArray(n) { if (runs[it] == 1 && !atEnd[it]) best[it] else -1 }
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
