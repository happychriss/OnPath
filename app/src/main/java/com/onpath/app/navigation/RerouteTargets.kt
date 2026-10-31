package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.max

/** A planned stop on the original line ([alongM] = metres from its start). POI-pinned stops are skipped too (owner). */
data class NavStop(val point: GeoPoint, val alongM: Double, val name: String? = null, val pinned: Boolean = false)

/**
 * Where a way back may rejoin the original line. [stopIndex] = a planned stop, null = a point on the line.
 * [skips] = the next stop, when this target lies past it (the way back would leave that stop out, [StopSkip]).
 */
data class RejoinTarget(val alongM: Double, val point: GeoPoint, val stopIndex: Int?, val skips: Int? = null)

/**
 * Rejoin candidates and splicing for re-routing (pure, unit-tested; docs/plan-auto-reroute.md §5, option C).
 * Candidates are always **ahead** on the original line: never a stop already passed (also not a POI-pinned one),
 * never the start of a round trip, never the end of a track that would skip the track. Past the next stop only when
 * it is an ordinary route point ([StopSkip.deliberate] = false, not the destination) within [STOP_MAX_AHEAD_M]:
 * then up to [PAST_CANDIDATES] more, between it and the stop after (marked [RejoinTarget.skips]; [Rerouter] decides with [StopSkip]).
 */
object RerouteTargets {
    /** The next stop is a candidate only up to this far ahead (along the line). */
    const val STOP_MAX_AHEAD_M = 5_000.0
    /** Line points this far ahead of the base (not beyond the next stop). */
    val LINE_AHEAD_M = listOf(500.0, 1_500.0, 3_000.0)
    const val MAX_CANDIDATES = 4
    /** Candidates closer together than this along the line are one. */
    const val MIN_GAP_M = 100.0
    /** A stop this little ahead of the base counts as passed. */
    const val PASSED_SLACK_M = 20.0
    /** Only look this far ahead for "where am I abeam of the line" (shortcut). */
    const val PROJECT_AHEAD_M = 5_000.0
    /** Past the next stop: a line point this far past it, and the stop after it (when ≤ [PAST_MAX_AHEAD_M] along). */
    const val PAST_STOP_M = 500.0
    const val PAST_CANDIDATES = 2
    /** The stop after the next one is a candidate up to this far along (the plan's detour to the next stop counts in). */
    const val PAST_MAX_AHEAD_M = 10_000.0

    /**
     * "Abeam of a later part" only counts with the line this near. A shortcut runs beside the route within a few hundred
     * metres (the owner's real excursions: up to ~680 m, StopSkipJvmTest); farther away the nearest line point is a
     * coincidence of the geometry – 30 km past a 2.7 km plan it is its end, and every point would count as passed
     * (field 2026-10-31).
     */
    const val ABEAM_MAX_M = 1_000.0

    /**
     * Where the user is along the original line for re-routing: the progress when they left it, or further when they
     * are abeam of a later part within [ABEAM_MAX_M] (took a shortcut). Never behind the progress.
     */
    fun base(line: NavLine, position: GeoPoint, progressM: Double): Double {
        val m = line.match(position, progressM, progressM + PROJECT_AHEAD_M) ?: return progressM
        if (m.distanceM > ABEAM_MAX_M) return progressM
        return max(progressM, m.alongM)
    }

    /** Stops behind [baseM] or visited during the tour (the destination only when really reached). */
    fun passed(stops: List<NavStop>, baseM: Double, visited: Set<Int>): Set<Int> =
        stops.indices.filter { i -> i in visited || (i < stops.lastIndex && stops[i].alongM <= baseM + PASSED_SLACK_M) }.toSet()

    fun candidates(line: NavLine, baseM: Double, stops: List<NavStop>, visited: Set<Int>): List<RejoinTarget> {
        if (!line.isUsable) return emptyList()
        val passed = passed(stops, baseM, visited)
        val nextStop = stops.indices.firstOrNull { it !in passed && stops[it].alongM > baseM }
        val limit = nextStop?.let { stops[it].alongM } ?: line.lengthM
        val out = ArrayList<RejoinTarget>()
        if (nextStop != null && stops[nextStop].alongM - baseM <= STOP_MAX_AHEAD_M) {
            out += RejoinTarget(stops[nextStop].alongM, stops[nextStop].point, nextStop)
        }
        for (d in LINE_AHEAD_M) {
            val a = baseM + d
            if (a >= limit - MIN_GAP_M) {
                // up to the next stop / the end: that is the last sensible target
                if (out.none { it.stopIndex != null } && limit - baseM <= STOP_MAX_AHEAD_M) {
                    out += RejoinTarget(limit, line.pointAt(limit), nextStop)
                }
                break
            }
            out += RejoinTarget(a, line.pointAt(a), null)
        }
        if (out.isEmpty()) out += RejoinTarget(line.lengthM, line.points.last(), nextStop)
        val sorted = out.sortedBy { it.alongM }
        val kept = ArrayList<RejoinTarget>()
        for (t in sorted) {
            val prev = kept.lastOrNull()
            if (prev != null && t.alongM - prev.alongM < MIN_GAP_M) {
                if (t.stopIndex != null) kept[kept.lastIndex] = t // a stop wins over a line point next to it
            } else kept += t
        }
        return kept.take(MAX_CANDIDATES) + pastNext(line, baseM, stops, nextStop)
    }

    /** Candidates past the next stop [n] when it may be skipped; empty for a deliberate stop or the destination. */
    private fun pastNext(line: NavLine, baseM: Double, stops: List<NavStop>, n: Int?): List<RejoinTarget> {
        if (n == null || n >= stops.lastIndex || stops[n].alongM - baseM > STOP_MAX_AHEAD_M) return emptyList()
        if (StopSkip.deliberate(stops[n], line)) return emptyList()
        val after = n + 1
        val from = stops[n].alongM
        val to = stops[after].alongM
        val out = ArrayList<RejoinTarget>()
        if (from + PAST_STOP_M < to - MIN_GAP_M) out += RejoinTarget(from + PAST_STOP_M, line.pointAt(from + PAST_STOP_M), null, n)
        if (to - baseM <= PAST_MAX_AHEAD_M) out += RejoinTarget(to, stops[after].point, after, n)
        else if (from + 3 * PAST_STOP_M < to - MIN_GAP_M) out += RejoinTarget(from + 3 * PAST_STOP_M, line.pointAt(from + 3 * PAST_STOP_M), null, n)
        return out.take(PAST_CANDIDATES)
    }

    /** New navigation line: the way back ([leg]) and then the original line from [targetAlongM] to the end. */
    fun splice(leg: List<GeoPoint>, line: NavLine, targetAlongM: Double): List<GeoPoint> {
        val out = ArrayList<GeoPoint>(leg.size + line.points.size)
        out.addAll(leg)
        val rest = line.restFrom(targetAlongM)
        // skip the joint when the leg already ends on it
        val from = if (out.isNotEmpty() && rest.isNotEmpty() && Geo.distance(out.last(), rest.first()) < 2.0) 1 else 0
        for (k in from until rest.size) out += rest[k]
        return out
    }

    /**
     * Metres along [leg] until it joins the original line for good (a way back that first returns to a missed junction
     * and then follows the route "rejoins" at the junction, not at the target). Searched in [fromM]..[toM].
     */
    fun rejoinInM(leg: List<GeoPoint>, line: NavLine, fromM: Double, toM: Double, onM: Double = 20.0): Double {
        if (leg.size < 2) return 0.0
        val cum = Geo.cumulative(leg)
        var k = leg.size - 1
        while (k > 0) {
            val m = line.match(leg[k - 1], fromM, toM)
            if (m == null || m.distanceM > onM) break
            k--
        }
        return cum[k]
    }
}
