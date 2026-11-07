package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * A loaded route's line at once (owner 2026-11-07, "saved routes load without routing again";
 * docs/design/long-bike-routes.md "Saved routes"). Loading a saved route, a Zuletzt draft, a done tour's plan copy or a
 * browser plan:
 * - every leg fresh in the [LegCache] (memory or the phone's leg store, data/LegFiles) → no stand-in: the plan run
 *   takes the legs from there, no router run, the same line ([preview] = null);
 * - else a stand-in line for the time the run routes the other legs: per leg the stored leg even if stale (routed on
 *   older routing data / an older app), else the stored line's piece between the two points ([split]) – a save from
 *   before the leg store, or legs the store let go. Only when every leg has one and the line belongs to these points
 *   (each point within [TOLERANCE_M] of it, in order); else none – the run routes as before.
 *
 * The stand-in is never taken as the route: it carries no way attributes where it comes from the stored line, so the
 * plan run always follows and replaces it (the app says so when the new line differs). Pure.
 */
object StoredLine {
    /** A plan point this far from the stored line (m): the line is not this plan's (a via correction cuts ≤ 400 m). */
    const val TOLERANCE_M = 500.0

    /** Points × line points beyond this: no stand-in ([split]'s table stays a few MB). */
    private const val MAX_CELLS = 4_000_000L

    /** [route] = the stand-in, [stale] = legs from the store routed on other data, [fromLine] = legs cut from the stored line. */
    class Preview(val route: PlannedRoute, val stale: Int, val fromLine: Int)

    /**
     * The stand-in for [via] with [activity] / [sac] (see the object); [line] = the stored line (the activity and T level
     * it was routed with are the plan's – the caller checks), empty = none. null = no stand-in needed or none possible.
     */
    fun preview(via: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int>, legs: LegCache,
                line: List<GeoPoint>): Preview? {
        if (via.size < 2) return null
        val n = via.size - 1
        if ((0 until n).all { legs.has(via[it], via[it + 1], activity, sac) }) return null
        var pieces: List<List<GeoPoint>>? = null
        var piecesTried = false
        val temp = LegCache(capacity = n + 1)
        var stale = 0; var fromLine = 0
        for (i in 0 until n) {
            val known = legs.stale(via[i], via[i + 1], activity, sac)
            val leg = if (known != null) { if (!legs.has(via[i], via[i + 1], activity, sac)) stale++; known } else {
                if (!piecesTried) { pieces = split(via, line); piecesTried = true }
                val p = pieces?.get(i) ?: return null
                fromLine++
                val (up, down) = Planner.climb(p)
                val d = Geo.length(p)
                RouteResult(p, d, up, down, com.onpath.app.time.PaceModel.seconds(d, up, activity), matched = listOf(p.first(), p.last()))
            }
            temp.put(via[i], via[i + 1], activity, sac, leg)
        }
        val route = Planner(NoRouter, legs = temp).plan(via, activity, sac, pinned)
        return Preview(route, stale, fromLine)
    }

    /**
     * [line] cut into one piece per leg between [via]: each point at the line point nearest to it, in order (the
     * smallest sum of distances; the first point at the line's start, the last at its end). null when a point is
     * farther than [toleranceM] from its spot – the line is not this plan's.
     */
    fun split(via: List<GeoPoint>, line: List<GeoPoint>, toleranceM: Double = TOLERANCE_M): List<List<GeoPoint>>? {
        val m = line.size; val k = via.size
        // no more line points than plan points: the points themselves (a browser plan not routed yet, a copy without
        // its line, a straight plan) – no routed line to show
        if (k < 2 || m <= k || k.toLong() * m > MAX_CELLS) return null
        if (Geo.distance(via.first(), line.first()) > toleranceM || Geo.distance(via.last(), line.last()) > toleranceM) return null
        // cost[j] for the current point = its distance to line[j] + the best of the points before at an index ≤ j
        val from = Array(k) { IntArray(m) }
        var cost = DoubleArray(m) { if (it == 0) 0.0 else Double.POSITIVE_INFINITY }
        for (p in 1 until k) {
            val next = DoubleArray(m)
            var best = Double.POSITIVE_INFINITY; var bestJ = 0
            for (j in 0 until m) {
                if (cost[j] < best) { best = cost[j]; bestJ = j }
                next[j] = if (p == k - 1 && j != m - 1) Double.POSITIVE_INFINITY else best + Geo.fastDistance(via[p], line[j])
                from[p][j] = bestJ
            }
            cost = next
        }
        val cut = IntArray(k)
        cut[k - 1] = m - 1
        for (p in k - 1 downTo 1) cut[p - 1] = from[p][cut[p]]
        for (p in 1 until k - 1) if (Geo.distance(via[p], line[cut[p]]) > toleranceM) return null
        return (0 until k - 1).map { i ->
            val a = cut[i]; val b = cut[i + 1]
            if (b > a) line.subList(a, b + 1).toList() else listOf(line[a], line[a])
        }
    }

    /** Never asked: every leg of a stand-in is in its cache. */
    private object NoRouter : RoutingEngine {
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
            throw RoutingException("no router for a stand-in line")
    }
}
