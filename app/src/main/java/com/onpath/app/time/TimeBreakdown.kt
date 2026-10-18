package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/**
 * "Time details" (owner: a debug view to understand the estimate): the route's 50 m model segments grouped into
 * slope bands with distance, height, base time (Normal pace), the factor applied and the final time. Pure, tested.
 */
object TimeBreakdown {
    data class Band(val label: String, val lo: Double, val hi: Double)

    val BANDS = listOf(
        Band("↓↓ < −20 %", Double.NEGATIVE_INFINITY, -0.20),
        Band("↓ −20…−5 %", -0.20, -0.05),
        Band("→ ±5 %", -0.05, 0.05),
        Band("↑ 5…20 %", 0.05, 0.20),
        Band("↑↑ > 20 %", 0.20, Double.POSITIVE_INFINITY),
    )

    data class Row(val label: String, val distanceM: Double, val upM: Double, val downM: Double,
                   val baseS: Double, val factor: Double, val finalS: Double) {
        /** Average horizontal speed (km/h) at the final time. */
        val kmh: Double get() = if (finalS > 0) distanceM / finalS * 3.6 else 0.0
    }

    data class Result(val activity: Activity, val rows: List<Row>, val baseS: Double, val finalS: Double,
                      val factors: Factors, val segmentM: Double, val segments: Int)

    fun of(points: List<GeoPoint>, activity: Activity, factors: Factors): Result {
        val rt = RouteTimes.of(points, activity)
        val segs = rt.segments()
        val step = rt.segmentLengthM
        val rows = BANDS.map { b ->
            var d = 0.0; var up = 0.0; var down = 0.0; var base = 0.0; var fin = 0.0
            for ((g, dz, s) in segs) if (g >= b.lo && g < b.hi) {
                d += step; base += s; fin += s * factors[PaceModel.group(g)]
                if (dz > 0) up += dz else down -= dz
            }
            val f = if (base > 0) fin / base else factors[PaceModel.group((b.lo.coerceAtLeast(-1.0) + b.hi.coerceAtMost(1.0)) / 2)]
            Row(b.label, d, up, down, base, f, fin)
        }
        return Result(activity, rows, rows.sumOf { it.baseS }, rows.sumOf { it.finalS }, factors, step, segs.size)
    }
}
