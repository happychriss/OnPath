package com.onpath.app.time

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Predicted moving time along one line (plan, followed route, re-route), worked out per ~50 m segment with its DEM
 * gradient ([PaceModel]) and kept as cumulative sums per learning group, so "time from here to the end" is O(1)
 * (docs/plan-time-estimate.md §7). The same object answers the plan sheet, the route list and Navigate "to go",
 * so the time never jumps when navigation starts.
 *
 * Along positions are metres from the start of the line as [Geo.cumulative] counts them (same as NavLine).
 * Elevation: the line's own (BRouter DEM) values; missing values are carried over from the neighbours (flat).
 */
class RouteTimes private constructor(
    val activity: Activity,
    /** Line length (m). */
    val lengthM: Double,
    /** Segment length (m); all segments are equally long. */
    private val step: Double,
    private val groups: IntArray,
    private val seconds: DoubleArray,
    /** Smoothed gradient per segment (−0.2 = 20 % down) – for the Time details screen. */
    private val grades: DoubleArray = DoubleArray(seconds.size),
    /** Height change per segment (m). */
    private val dzs: DoubleArray = DoubleArray(seconds.size),
) {
    /** Per segment: (gradient, height change m, seconds at Normal pace) – for [TimeBreakdown]. */
    fun segments(): List<Triple<Double, Double, Double>> = seconds.indices.map { Triple(grades[it], dzs[it], seconds[it]) }
    val segmentLengthM: Double get() = step

    /** cum[g][k] = seconds of group g in segments 0 until k (Normal pace, no factors). */
    private val cum: Array<DoubleArray> = Array(PaceModel.GROUPS) { g ->
        val c = DoubleArray(seconds.size + 1)
        for (k in seconds.indices) c[k + 1] = c[k] + if (groups[k] == g) seconds[k] else 0.0
        c
    }

    val segmentCount: Int get() = seconds.size

    /** Seconds per group for the whole line (Normal pace). */
    fun groupTotals(): DoubleArray = DoubleArray(PaceModel.GROUPS) { cum[it].last() }

    /** Whole line, with [f] (pace × learned factors). */
    fun total(f: Factors = Factors.ONE): Double = (0 until PaceModel.GROUPS).sumOf { f[it] * cum[it].last() }

    /** From [alongM] to the end, with [f]. */
    fun remainingFrom(alongM: Double, f: Factors = Factors.ONE): Double {
        val at = upTo(alongM)
        return (0 until PaceModel.GROUPS).sumOf { f[it] * (cum[it].last() - at[it]) }
    }

    /** Seconds per group between two along positions (Normal pace); empty range = zeros. */
    fun between(fromM: Double, toM: Double): DoubleArray {
        val a = upTo(fromM); val b = upTo(max(fromM, toM))
        return DoubleArray(PaceModel.GROUPS) { max(0.0, b[it] - a[it]) }
    }

    /** Seconds per group from the start to [alongM] (partial segment interpolated). */
    private fun upTo(alongM: Double): DoubleArray {
        val out = DoubleArray(PaceModel.GROUPS)
        if (seconds.isEmpty() || step <= 0) return out
        val x = alongM.coerceIn(0.0, lengthM) / step
        val k = min(seconds.size, floor(x).toInt())
        for (g in 0 until PaceModel.GROUPS) out[g] = cum[g][k]
        if (k < seconds.size) out[groups[k]] += seconds[k] * (x - k).coerceIn(0.0, 1.0)
        return out
    }

    /** Gradient group of the segment at [alongM] (for tests and debugging). */
    fun groupAt(alongM: Double): Int =
        if (seconds.isEmpty()) PaceModel.FLAT else groups[min(seconds.size - 1, floor(alongM.coerceAtLeast(0.0) / step).toInt())]

    companion object {
        /** Empty line (fewer than two points): zero time. */
        fun of(points: List<GeoPoint>, activity: Activity): RouteTimes {
            if (points.size < 2) return RouteTimes(activity, 0.0, 0.0, IntArray(0), DoubleArray(0))
            val c = Geo.cumulative(points)
            val total = c.last()
            if (total <= 0 || total.isNaN()) return RouteTimes(activity, 0.0, 0.0, IntArray(0), DoubleArray(0))
            var last = points.firstOrNull { it.ele != null }?.ele ?: 0.0
            val e = DoubleArray(points.size) { i -> (points[i].ele ?: last).also { last = it } }
            val n = max(1, (total / PaceModel.SEGMENT_M).roundToInt())
            val step = total / n
            val ele = DoubleArray(n + 1)
            var k = 0
            for (i in 0..n) {
                val d = i * step
                while (k < c.size - 2 && c[k + 1] < d) k++
                val span = c[k + 1] - c[k]
                val t = if (span > 0) ((d - c[k]) / span).coerceIn(0.0, 1.0) else 0.0
                ele[i] = e[k] + (e[k + 1] - e[k]) * t
            }
            // light smoothing over 3 samples (~150 m): DEM steps are not real ups and downs
            val sm = DoubleArray(n + 1) { i -> (ele[max(0, i - 1)] + ele[i] + ele[min(n, i + 1)]) / 3 }
            val groups = IntArray(n)
            val secs = DoubleArray(n)
            val grades = DoubleArray(n)
            val dzs = DoubleArray(n)
            for (i in 0 until n) {
                val dz = sm[i + 1] - sm[i]
                groups[i] = PaceModel.group(dz / step)
                secs[i] = PaceModel.seconds(step, dz, activity)
                grades[i] = dz / step; dzs[i] = dz
            }
            return RouteTimes(activity, total, step, groups, secs, grades, dzs)
        }
    }
}
