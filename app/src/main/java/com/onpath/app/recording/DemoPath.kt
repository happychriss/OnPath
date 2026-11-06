package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import com.onpath.app.time.Factors
import com.onpath.app.time.RouteTimes

/** One simulated position ([DemoPath.at]): where, which way, how fast; [ended] = at the end of the line, standing. */
data class DemoFix(
    val lat: Double, val lon: Double, val ele: Double?,
    /** Compass bearing of the line here (degrees); null when standing (end, pause, a fixed place). */
    val bearingDeg: Double?,
    val speedMs: Double,
    /** Metres from the start of the line. */
    val alongM: Double,
    val ended: Boolean,
)

/**
 * A line ridden in simulated time (Developer tools → Demo, [DemoLocation]): position, bearing and speed after a given
 * number of seconds, interpolated between the line's points. [cumS] = seconds from the start to each point
 * (non-decreasing, same size as [line]): from the per-segment time model ([timed] – slow uphill, fast downhill, the
 * user's pace) or a fixed speed ([constant]). Pure.
 */
class DemoPath private constructor(val line: List<GeoPoint>, private val cumM: DoubleArray, private val cumS: DoubleArray) {
    val lengthM: Double get() = cumM.last()
    val durationS: Double get() = cumS.last()

    /** The position after [simS] seconds; before 0 = the start, after [durationS] = the end (standing, [DemoFix.ended]). */
    fun at(simS: Double): DemoFix {
        if (simS >= durationS) return standing(line.size - 1, ended = true)
        if (simS <= 0.0) return moving(firstMovingSegment(0), 0.0)
        // last point reached at or before simS (binary search; equal times = a zero-time span, skipped)
        var lo = 0; var hi = line.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (cumS[mid] <= simS) lo = mid else hi = mid }
        val span = cumS[hi] - cumS[lo]
        val t = if (span > 0) (simS - cumS[lo]) / span else 0.0
        return moving(lo, t)
    }

    /** The ride time at [alongM] metres along the line (clamped to the line). */
    fun timeAtAlong(alongM: Double): Double {
        if (alongM <= 0.0) return 0.0
        if (alongM >= lengthM) return durationS
        var lo = 0; var hi = cumM.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (cumM[mid] <= alongM) lo = mid else hi = mid }
        val span = cumM[hi] - cumM[lo]
        val t = if (span > 0) (alongM - cumM[lo]) / span else 0.0
        return cumS[lo] + t * (cumS[hi] - cumS[lo])
    }

    /**
     * The ride time at the point of the line nearest to [lat] / [lon] (the Navigate "Sim" goes on along a new followed
     * line from where it is, owner 2026-11-01). Flat projection per span – metres around one place.
     */
    fun timeNearest(lat: Double, lon: Double): Double {
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(lat)); val ky = 111_320.0
        var best = Double.MAX_VALUE; var bestS = 0.0
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
            val px = (lon - a.lon) * kx; val py = (lat - a.lat) * ky
            val len2 = bx * bx + by * by
            val t = if (len2 <= 0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * bx; val dy = py - t * by
            val d = dx * dx + dy * dy
            if (d < best) { best = d; bestS = cumS[i] + t * (cumS[i + 1] - cumS[i]) }
        }
        return bestS
    }

    /** The point at [i] + [t] of the span to the next point, moving along it. */
    private fun moving(i: Int, t: Double): DemoFix {
        if (i >= line.size - 1) return standing(line.size - 1, ended = true)
        val a = line[i]; val b = line[i + 1]
        val ele = when {
            a.ele != null && b.ele != null -> a.ele + (b.ele - a.ele) * t
            else -> a.ele ?: b.ele
        }
        val lenM = cumM[i + 1] - cumM[i]
        val s = cumS[i + 1] - cumS[i]
        return DemoFix(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, ele,
            if (lenM > 0) TurnDetector.bearing(a, b) else null, if (s > 0) lenM / s else 0.0, cumM[i] + lenM * t, ended = false)
    }

    private fun standing(i: Int, ended: Boolean) = line[i].let { DemoFix(it.lat, it.lon, it.ele, null, 0.0, cumM[i], ended) }

    /** First span from [i] that has a length (two identical points at the start give no bearing). */
    private fun firstMovingSegment(i: Int): Int {
        var k = i
        while (k < line.size - 2 && cumM[k + 1] - cumM[k] <= 0.0) k++
        return k
    }

    companion object {
        /** Slowest speed a span is ridden at: a line without times still moves. */
        const val MIN_SPEED_MS = 0.3

        /** At a fixed speed (m/s); null = no line (fewer than two distinct points) or no speed. */
        fun constant(line: List<GeoPoint>, speedMs: Double): DemoPath? {
            if (!(speedMs > 0)) return null
            val c = cumulative(line) ?: return null
            return DemoPath(line, c, DoubleArray(line.size) { c[it] / speedMs })
        }

        /**
         * At the time model's speed for [activity] with the user's pace [factors] – the same seconds as the plan sheet
         * shows. A line the model gives no time for falls back to [fallbackMs].
         */
        fun timed(line: List<GeoPoint>, activity: Activity, factors: Factors = Factors.ONE, fallbackMs: Double = 1.2): DemoPath? {
            val c = cumulative(line) ?: return null
            val rt = try { RouteTimes.of(line, activity) } catch (e: Exception) {
                com.onpath.app.debug.Problems.warn("demo", "Demo: no time model for this line; riding it at a fixed speed", e)
                null
            }
            val total = rt?.total(factors) ?: 0.0
            if (!(total > 0) || total.isNaN()) return constant(line, fallbackMs)
            val s = DoubleArray(line.size)
            for (i in 1 until line.size) {
                val model = total - rt!!.remainingFrom(c[i], factors)
                val span = c[i] - c[i - 1]
                // never backwards, never slower than MIN_SPEED_MS (a model glitch must not stop the ride)
                s[i] = maxOf(model, s[i - 1]).coerceAtMost(s[i - 1] + span / MIN_SPEED_MS)
            }
            return DemoPath(line, c, s)
        }

        /**
         * A recorded tour again, at its recorded times (developer tools, owner 2026-11-01: walk a field tour through in
         * debug mode – stops stay stops, gaps stay gaps). [timesMs] per point; a point without a time or going back in
         * time keeps the time before it. Null = no line or no times.
         */
        fun recorded(line: List<GeoPoint>, timesMs: List<Long?>): DemoPath? {
            val c = cumulative(line) ?: return null
            val t0 = timesMs.firstOrNull { it != null } ?: return null
            var last = 0.0
            val s = DoubleArray(line.size) { i -> maxOf(last, ((timesMs.getOrNull(i) ?: t0) - t0) / 1000.0).also { last = it } }
            return if (s.last() > 0) DemoPath(line, c, s) else null
        }

        private fun cumulative(line: List<GeoPoint>): DoubleArray? {
            if (line.size < 2) return null
            val c = Geo.cumulative(line)
            return if (c.last() > 0) c else null
        }
    }
}
