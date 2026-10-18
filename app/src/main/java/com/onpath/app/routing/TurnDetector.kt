package com.onpath.app.routing

import com.onpath.app.data.Geo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A direction change on a followed line (Navigate: "next turn" arrow and hint). */
data class Turn(
    /** Index of the vertex in the line where the turn is shown. */
    val index: Int,
    val point: GeoPoint,
    /** Compass bearings (0 = north, clockwise) before and after the turn. */
    val bearingIn: Double,
    val bearingOut: Double,
    /** Signed change of direction in degrees: > 0 right, < 0 left, ±180 = U-turn. */
    val angle: Double,
    val distanceFromStart: Double,
) {
    val kind: TurnKind get() = TurnKind.of(angle)
}

enum class TurnKind(val label: String) {
    SLIGHT_LEFT("Slight left"), LEFT("Left"), SHARP_LEFT("Sharp left"), U_TURN_LEFT("U-turn"),
    SLIGHT_RIGHT("Slight right"), RIGHT("Right"), SHARP_RIGHT("Sharp right"), U_TURN_RIGHT("U-turn");

    companion object {
        fun of(angle: Double): TurnKind {
            val a = abs(angle)
            val right = angle > 0
            return when {
                a < 60 -> if (right) SLIGHT_RIGHT else SLIGHT_LEFT
                a < 120 -> if (right) RIGHT else LEFT
                a < 160 -> if (right) SHARP_RIGHT else SHARP_LEFT
                else -> if (right) U_TURN_RIGHT else U_TURN_LEFT
            }
        }
    }
}

/**
 * Finds turns on a line (planned route or track). Pure, unit-tested.
 *
 * The direction at each vertex is measured over [WINDOW_M] before and after it (interpolated along the
 * line), so dense vertices and GPS-like zigzags of a few metres do not count as turns. Vertices whose
 * change is ≥ [MIN_ANGLE] form runs of the same side; each run is one turn (a long bend = one turn, two
 * quick rights = a U-turn). Turns closer than [MERGE_M] are merged, keeping the stronger one.
 */
object TurnDetector {
    const val WINDOW_M = 25.0
    const val MIN_ANGLE = 35.0
    const val MERGE_M = 30.0
    /** Near the ends a shorter window is used, but not shorter than this. */
    private const val MIN_WINDOW_M = 8.0

    fun turns(points: List<GeoPoint>): List<Turn> {
        if (points.size < 3) return emptyList()
        val cum = Geo.cumulative(points)
        val total = cum.last()

        // 1. direction change at each inner vertex
        class Cand(val i: Int, val bIn: Double, val bOut: Double, val angle: Double)
        val cands = ArrayList<Cand>()
        for (i in 1 until points.size - 1) {
            val back = minOf(WINDOW_M, cum[i])
            val ahead = minOf(WINDOW_M, total - cum[i])
            if (back < MIN_WINDOW_M || ahead < MIN_WINDOW_M) continue
            val before = pointAt(points, cum, cum[i] - back)
            val after = pointAt(points, cum, cum[i] + ahead)
            if (Geo.distance(before, points[i]) < 1.0 || Geo.distance(points[i], after) < 1.0) continue
            val bIn = bearing(before, points[i])
            val bOut = bearing(points[i], after)
            val a = diff(bIn, bOut)
            if (abs(a) >= MIN_ANGLE) cands += Cand(i, bIn, bOut, a)
        }
        if (cands.isEmpty()) return emptyList()

        // 2. runs of consecutive candidates on the same side (and without a long gap) → one turn each
        val turns = ArrayList<Turn>()
        var start = 0
        for (k in 1..cands.size) {
            val breakHere = k == cands.size ||
                (cands[k].angle > 0) != (cands[k - 1].angle > 0) ||
                cum[cands[k].i] - cum[cands[k - 1].i] > MERGE_M
            if (!breakHere) continue
            val run = cands.subList(start, k)
            val peak = run.maxBy { abs(it.angle) }
            val first = run.first(); val last = run.last()
            var net = diff(first.bIn, last.bOut)
            // same side all along: more than 180° in total wraps to the other side – keep the side, cap at 180
            if (run.size > 1 && (net > 0) != (peak.angle > 0)) net = if (peak.angle > 0) 180.0 else -180.0
            if (abs(net) < abs(peak.angle)) net = peak.angle   // a bend that partly unwinds: the peak tells more
            turns += Turn(peak.i, points[peak.i], first.bIn, last.bOut, net, cum[peak.i])
            start = k
        }

        // 3. merge turns closer than MERGE_M (keep the stronger)
        val merged = ArrayList<Turn>()
        for (t in turns) {
            val prev = merged.lastOrNull()
            if (prev != null && t.distanceFromStart - prev.distanceFromStart < MERGE_M) {
                if (abs(t.angle) > abs(prev.angle)) merged[merged.lastIndex] = t
            } else merged += t
        }
        return merged
    }

    /** The next turn ahead of [alongM] (metres from the start), within [maxAheadM]; null if none. */
    fun nextTurn(turns: List<Turn>, alongM: Double, maxAheadM: Double = 500.0, passedM: Double = 5.0): Pair<Turn, Double>? {
        val t = turns.firstOrNull { it.distanceFromStart > alongM + passedM } ?: return null
        val d = t.distanceFromStart - alongM
        return if (d <= maxAheadM) t to d else null
    }

    /** Compass bearing a → b in degrees [0, 360). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed difference to − from in (-180, 180]. */
    fun diff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    private fun pointAt(points: List<GeoPoint>, cum: DoubleArray, d: Double): GeoPoint {
        if (d <= 0) return points.first()
        if (d >= cum.last()) return points.last()
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        val seg = cum[hi] - cum[lo]
        val f = if (seg <= 0) 0.0 else (d - cum[lo]) / seg
        val a = points[lo]; val b = points[hi]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }
}
