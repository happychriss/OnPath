package com.onpath.app.routing

import kotlin.math.abs

/** Pure helpers for the Komoot-style route summary (breakdown, difficulty, gradient). */
object RouteSummary {

    /** Sums several breakdowns (e.g. one per leg) per key, largest first; drops empty keys. */
    fun mergeShares(parts: List<List<Share>>): List<Share> {
        val sum = LinkedHashMap<String, Double>()
        parts.flatten().forEach { if (it.meters > 0) sum[it.key] = (sum[it.key] ?: 0.0) + it.meters }
        return sum.map { Share(it.key, it.value) }.sortedByDescending { it.meters }
    }

    enum class Difficulty(val label: String) { EASY("Easy"), MODERATE("Moderate"), DIFFICULT("Difficult") }

    /**
     * Simple difficulty from distance and ascent ("effort kilometres"):
     *   effort = km + ascentM / climbFactor
     * Hike uses the Swiss/DIN "Leistungskilometer" (100 m up = 1 km). Bikes count climbing
     * more cheaply (100 m up = 4 km touring/e-bike, 5 km MTB). Thresholds per activity:
     *   HIKE            easy ≤ 12, moderate ≤ 22
     *   BIKE_TOURING /
     *   BIKE_TREKKING   easy ≤ 40, moderate ≤ 80
     *   EBIKE           easy ≤ 60, moderate ≤ 120
     *   MTB             easy ≤ 25, moderate ≤ 50
     *   EMTB            easy ≤ 40, moderate ≤ 80
     * Above "moderate" = difficult. Trail technique (SAC/MTB scale) is not part of this label.
     */
    fun difficulty(activity: Activity, distanceM: Double, ascentM: Double): Difficulty {
        val (climbFactor, easy, moderate) = when (activity) {
            Activity.HIKE -> Triple(100.0, 12.0, 22.0)
            Activity.BIKE_TOURING, Activity.BIKE_TREKKING -> Triple(25.0, 40.0, 80.0)
            Activity.EBIKE -> Triple(25.0, 60.0, 120.0)
            Activity.MTB -> Triple(20.0, 25.0, 50.0)
            Activity.EMTB -> Triple(20.0, 40.0, 80.0)
        }
        val effort = distanceM / 1000.0 + ascentM / climbFactor
        return when {
            effort <= easy -> Difficulty.EASY
            effort <= moderate -> Difficulty.MODERATE
            else -> Difficulty.DIFFICULT
        }
    }

    /** Gradient classes for colouring the elevation profile (absolute slope, up or down). */
    enum class Steepness { FLAT, MODERATE, STEEP, VERY_STEEP }

    fun steepnessOf(gradePercent: Double): Steepness {
        val g = abs(gradePercent)
        return when {
            g < 5 -> Steepness.FLAT
            g < 10 -> Steepness.MODERATE
            g < 15 -> Steepness.STEEP
            else -> Steepness.VERY_STEEP
        }
    }

    /**
     * Steepness per profile segment i (between sample i and i+1). Slope is measured over a
     * window of at least [windowM] around the segment so DEM noise does not paint red stripes.
     * [dist] cumulative metres (ascending), [ele] elevation, same size.
     */
    fun steepness(dist: DoubleArray, ele: DoubleArray, windowM: Double = 100.0): List<Steepness> {
        val n = dist.size
        if (n < 2) return emptyList()
        return (0 until n - 1).map { i ->
            val mid = (dist[i] + dist[i + 1]) / 2
            var a = i; var b = i + 1
            while (dist[b] - dist[a] < windowM && (a > 0 || b < n - 1)) {
                // widen towards the side that keeps the window centred on the segment
                if (a > 0 && (b == n - 1 || mid - dist[a - 1] <= dist[b + 1] - mid)) a-- else b++
            }
            val run = dist[b] - dist[a]
            steepnessOf(if (run <= 0) 0.0 else (ele[b] - ele[a]) / run * 100)
        }
    }
}
