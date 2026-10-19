package com.onpath.app.data

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion

/** What is left of a followed route from the current position (F-NAV-01). Pure, unit-tested. */
class FollowProgress(private val line: List<GeoPoint>) {

    data class Remaining(val index: Int, val distanceM: Double, val ascentM: Double, val descentM: Double, val offM: Double)

    private val cum = Geo.cumulative(line)

    /**
     * Remaining distance/ascent/descent from the point on the line nearest to [p].
     * [hint] = index from the previous fix: a later match is preferred when it is almost as
     * close as the global best, so out-and-back routes do not jump back to the outward leg.
     */
    fun remaining(p: GeoPoint, hint: Int = 0): Remaining? {
        if (line.size < 2) return null
        val best = nearest(p, 0) ?: return null
        val later = if (hint > 0) nearest(p, hint) else null
        val (seg, off) = if (later != null && later.second <= best.second + 30) later else best
        // project onto the segment start; the partial segment is ignored (< one segment error)
        val i = (seg + 1).coerceAtMost(line.size - 1)
        val partial = Geo.distance(p, line[i]).coerceAtMost(cum[i] - cum[seg])
        val dist = cum.last() - cum[i] + partial
        val (up, down) = Geo.climb(line.subList(i, line.size).map { it.ele })
        return Remaining(seg, dist, up, down, off)
    }

    /**
     * Remaining distance/ascent/descent from [alongM] metres along the line – the navigation service's progress, which
     * knows the walking direction (out-and-back, round trips); [offM] = distance to the line.
     */
    fun at(alongM: Double, offM: Double?): Remaining? {
        if (line.size < 2) return null
        val a = alongM.coerceIn(0.0, cum.last())
        val seg = java.util.Arrays.binarySearch(cum, a).let { if (it >= 0) it else -it - 2 }.coerceIn(0, line.size - 2)
        val i = seg + 1
        val (up, down) = Geo.climb(line.subList(i, line.size).map { it.ele })
        return Remaining(seg, cum.last() - a, up, down, offM ?: 0.0)
    }

    private fun nearest(p: GeoPoint, from: Int): Pair<Int, Double>? {
        if (from >= line.size - 1) return null
        var bestI = -1; var bestD = Double.MAX_VALUE
        for (k in from until line.size - 1) {
            val d = WaypointInsertion.distanceToSegment(p, line[k], line[k + 1])
            if (d < bestD) { bestD = d; bestI = k }
        }
        return if (bestI < 0) null else bestI to bestD
    }
}
