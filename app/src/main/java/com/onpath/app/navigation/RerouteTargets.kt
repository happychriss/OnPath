package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.max

/** A planned stop on the original line ([alongM] = metres from its start). POI-pinned stops are skipped too (owner). */
data class NavStop(val point: GeoPoint, val alongM: Double, val name: String? = null, val pinned: Boolean = false)

/** Where a way back may rejoin the original line. [stopIndex] = a planned stop, null = a point on the line. */
data class RejoinTarget(val alongM: Double, val point: GeoPoint, val stopIndex: Int?)

/**
 * Rejoin candidates and splicing for re-routing (pure, unit-tested; docs/plan-auto-reroute.md §5, option C).
 * Candidates are always **ahead** on the original line: never a stop already passed (also not a POI-pinned one),
 * never the start of a round trip, never the end of a track that would skip the track.
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

    /**
     * Where the user is along the original line for re-routing: the progress when they left it, or further when they
     * are abeam of a later part (took a shortcut). Never behind the progress.
     */
    fun base(line: NavLine, position: GeoPoint, progressM: Double): Double {
        val m = line.match(position, progressM, progressM + PROJECT_AHEAD_M) ?: return progressM
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
        return kept.take(MAX_CANDIDATES)
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
