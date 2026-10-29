package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * A GPX track (ridden, or from elsewhere) as a plannable route with as few route points as possible (owner:
 * "not 1000 points – the minimum, so I can plan with it afterwards"). Pure apart from the [RoutingEngine] calls;
 * blocking – call off the main thread.
 *
 * Start with start + goal (a round trip: + the point farthest from the start), route every leg with OUR router and
 * the plan's activity, and where the route leaves the track by more than [DEVIATION_M] (either way: the track not
 * covered, or the route wandering off) put one route point there – on the track, at the worst spot – and route the
 * two halves again. Repeat until every leg follows the track or [MAX_CALLS] routing calls are used. The track's points
 * are real positions on ways, so a route point taken from it lies on a way.
 */
object TrackToPlan {
    /** The route counts as following the track within this. */
    const val DEVIATION_M = 40.0
    /** The track is looked at in steps of this. */
    const val STEP_M = 25.0
    /** Start and goal this close = a round trip. */
    const val ROUND_TRIP_M = 80.0
    const val MAX_CALLS = 60
    /** Route points never closer than this along the track (no point clusters on a messy GPS bit). */
    const val MIN_GAP_M = 75.0

    data class Result(
        val points: List<GeoPoint>,
        /** Share (0..1) of the track within [DEVIATION_M] of the planned legs. */
        val follows: Double,
        val calls: Int,
        /** Legs that could not be routed (kept as they are; the plan shows them as straight legs). */
        val failedLegs: Int,
    )

    fun fit(track: List<GeoPoint>, engine: RoutingEngine, activity: Activity, sacLimit: Int = 3,
            cancelled: () -> Boolean = { false }): Result {
        val t = resample(track, STEP_M)
        if (t.size < 2) return Result(t, 1.0, 0, 0)
        val along = Geo.cumulative(t)
        val idx = sortedSetOf(0, t.lastIndex)
        if (Geo.distance(t.first(), t.last()) < ROUND_TRIP_M && t.size > 4) {
            idx += t.indices.maxBy { Geo.distance(t.first(), t[it]) } // a round trip: start = goal + the far point
        }
        val legLines = HashMap<Pair<Int, Int>, List<GeoPoint>?>()
        var calls = 0
        var failed = 0
        fun line(i: Int, j: Int): List<GeoPoint>? = legLines.getOrPut(i to j) {
            calls++
            try { engine.route(listOf(t[i], t[j]), activity, sacLimit).points.takeIf { it.size >= 2 } }
            catch (e: RoutingException) { null } // expected: no route for this leg – split half-way or count it failed
        }
        // legs to check, in track order; a split adds its two halves
        val todo = ArrayDeque<Pair<Int, Int>>()
        idx.zipWithNext().forEach { todo += it }
        while (todo.isNotEmpty() && calls < MAX_CALLS && !cancelled()) {
            val (i, j) = todo.removeFirst()
            if (j - i < 2) continue
            val l = line(i, j)
            val split = if (l == null) {
                // no route between them: try a point half-way (a gap in the ways, a ferry …)
                (i + j) / 2
            } else worst(t, along, i, j, l)
            if (split == null || split - i < 1 || j - split < 1 ||
                (l == null && (along[split] - along[i] < MIN_GAP_M || along[j] - along[split] < MIN_GAP_M))) {
                if (l == null) failed++
                continue
            }
            idx += split
            todo.addFirst(split to j); todo.addFirst(i to split)
        }
        val pts = idx.map { t[it] }
        // how well the plan follows the track (legs already routed; unrouted ones count as not followed)
        var near = 0
        val ids = idx.toList()
        for (k in 0 until ids.size - 1) {
            val l = legLines[ids[k] to ids[k + 1]] ?: if (ids[k + 1] - ids[k] < 2) listOf(t[ids[k]], t[ids[k + 1]]) else null
            for (s in ids[k]..ids[k + 1]) if (l != null && Geo.distanceToLine(t[s].lat, t[s].lon, l) <= DEVIATION_M) near++
        }
        val total = t.size + ids.size - 2
        return Result(pts, if (total > 0) near.toDouble() / total else 1.0, calls, failed)
    }

    /**
     * The track sample in i..j to split at: where track and route [l] are farthest apart (either way), skipping spots
     * closer than [MIN_GAP_M] to the leg's ends (else a worst spot right at the start is skipped and a detour further
     * on is never looked at). Null when they agree.
     */
    private fun worst(t: List<GeoPoint>, along: DoubleArray, i: Int, j: Int, l: List<GeoPoint>): Int? {
        val cand = HashMap<Int, Double>()
        // the track not covered by the route
        for (k in i + 1 until j) {
            val d = Geo.distanceToLine(t[k].lat, t[k].lon, l)
            if (d > DEVIATION_M) cand[k] = maxOf(cand[k] ?: 0.0, d)
        }
        // the route wandering off the track: split at the track sample nearest to that spot
        val part = t.subList(i, j + 1)
        for (p in l) {
            val d = Geo.distanceToLine(p.lat, p.lon, part)
            if (d > DEVIATION_M) {
                val k = (i + 1 until j).minByOrNull { Geo.distance(t[it], p) } ?: continue
                cand[k] = maxOf(cand[k] ?: 0.0, d)
            }
        }
        return cand.entries.sortedByDescending { it.value }.map { it.key }
            .firstOrNull { along[it] - along[i] >= MIN_GAP_M && along[j] - along[it] >= MIN_GAP_M }
    }

    /** The track every [stepM] (its own points kept where they are closer – no smoothing of corners). */
    fun resample(track: List<GeoPoint>, stepM: Double): List<GeoPoint> {
        if (track.size < 2) return track
        val out = arrayListOf(track.first())
        var acc = 0.0
        for (k in 1 until track.size) {
            acc += Geo.distance(track[k - 1], track[k])
            if (acc >= stepM) { out += track[k]; acc = 0.0 }
        }
        if (out.last() != track.last()) out += track.last()
        return out
    }
}
