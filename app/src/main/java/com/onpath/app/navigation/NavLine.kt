package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A followed line with cumulative distances, for matching a position to "how far along" (pure, unit-tested).
 * All searches can be limited to an along-window ([fromM]..[toM]), which keeps out-and-back routes and round
 * trips apart (the way back does not count while you are on the way out) and is cheap on long lines.
 */
class NavLine(val points: List<GeoPoint>) {

    /** Position on the line: metres from the start, distance from the line, segment index. */
    data class Match(val alongM: Double, val distanceM: Double, val seg: Int, val point: GeoPoint)

    val cum: DoubleArray = Geo.cumulative(points)
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum.last()
    val isUsable: Boolean get() = points.size >= 2

    /**
     * Nearest point of the line to [p] within the along-window. When several parts of the line are about as near
     * (within [tieM]), the one whose along value is closest to [preferAlongM] wins (stay on the leg you are on).
     */
    fun match(p: GeoPoint, fromM: Double = 0.0, toM: Double = lengthM, preferAlongM: Double? = null, tieM: Double = 15.0): Match? {
        if (!isUsable) return null
        val lo = max(0.0, fromM); val hi = min(lengthM, toM)
        if (hi < lo) return null
        val kx = cos(Math.toRadians(p.lat)) * 111_320.0
        val ky = 110_540.0
        val n = points.size - 1
        val segD = DoubleArray(n) { Double.MAX_VALUE }
        val segT = DoubleArray(n)
        var best = Double.MAX_VALUE
        val first = segmentAt(lo); val last = segmentAt(hi)
        for (i in first..last) {
            val a = points[i]; val b = points[i + 1]
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - a.lon) * kx; val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            var t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            // clamp to the window inside the first / last segment
            val segLen = cum[i + 1] - cum[i]
            if (segLen > 0) {
                val tLo = ((lo - cum[i]) / segLen).coerceIn(0.0, 1.0)
                val tHi = ((hi - cum[i]) / segLen).coerceIn(0.0, 1.0)
                t = t.coerceIn(tLo, tHi)
            }
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            segD[i] = d; segT[i] = t
            if (d < best) best = d
        }
        var pick = -1
        if (preferAlongM == null) {
            for (i in first..last) if (segD[i] == best) { pick = i; break }
        } else {
            // Runs of consecutive segments about as near as the best = separate parts of the line (out and back).
            // Take the part nearest to the preferred along position, and in it its nearest segment.
            var bestGap = Double.MAX_VALUE
            var i = first
            while (i <= last) {
                if (segD[i] > best + tieM) { i++; continue }
                var runBest = i
                while (i <= last && segD[i] <= best + tieM) { if (segD[i] < segD[runBest]) runBest = i; i++ }
                val along = cum[runBest] + segT[runBest] * (cum[runBest + 1] - cum[runBest])
                val gap = kotlin.math.abs(along - preferAlongM)
                if (gap < bestGap) { bestGap = gap; pick = runBest }
            }
        }
        if (pick < 0) return null
        val along = cum[pick] + segT[pick] * (cum[pick + 1] - cum[pick])
        return Match(along, segD[pick], pick, pointAt(along))
    }

    /** Earliest part of the line within [toleranceM] of the nearest one (start of a round trip, not its end). */
    fun matchEarliest(p: GeoPoint, fromM: Double = 0.0, toleranceM: Double = 30.0): Match? {
        val best = match(p, fromM) ?: return null
        // walk forward from the window start and take the first segment that is about as near
        var from = fromM
        while (from < lengthM) {
            val m = match(p, from, min(lengthM, from + 500.0)) ?: break
            if (m.distanceM <= best.distanceM + toleranceM) return m
            from += 500.0
        }
        return best
    }

    /** Segment index containing [alongM] (clamped). */
    fun segmentAt(alongM: Double): Int {
        if (points.size < 2) return 0
        if (alongM <= 0) return 0
        if (alongM >= lengthM) return points.size - 2
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= alongM) lo = m else hi = m }
        return lo.coerceAtMost(points.size - 2)
    }

    /** Interpolated point at [alongM] (elevation interpolated where both ends have one). */
    fun pointAt(alongM: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        if (points.size == 1 || alongM <= 0) return points.first()
        if (alongM >= lengthM) return points.last()
        val i = segmentAt(alongM)
        val seg = cum[i + 1] - cum[i]
        val f = if (seg <= 0) 0.0 else (alongM - cum[i]) / seg
        val a = points[i]; val b = points[i + 1]
        val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, ele, a.way) // keeps the span's way (time model)
    }

    /** The line from [alongM] to the end, starting with the interpolated point there. */
    fun restFrom(alongM: Double): List<GeoPoint> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<GeoPoint>()
        out += pointAt(alongM)
        for (k in segmentAt(alongM) + 1 until points.size) if (cum[k] > alongM + 0.5) out += points[k]
        if (out.size == 1 && points.size > 1) out += points.last()
        return out
    }

    /** Along positions of [stops] in their order (each at or after the previous one). */
    fun alongsInOrder(stops: List<GeoPoint>): List<Double> {
        var from = 0.0
        return stops.map { s ->
            val m = matchEarliest(s, from)
            val a = m?.alongM ?: from
            from = a
            a
        }
    }
}
