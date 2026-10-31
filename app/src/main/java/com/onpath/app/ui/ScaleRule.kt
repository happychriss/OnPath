package com.onpath.app.ui

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The map's scale bar: a round length (1 / 2 / 5 × 10ⁿ) that fits in [MAX_DP].
 *
 * MapLibre's `Projection.getMetersPerPixelAtLatitude` gives metres per **dp** (a logical pixel – the native map works in
 * them; MapLibre's own scale bar divides by the pixel ratio for screen pixels). The bar used to multiply it by the
 * density once more, so it read 2–3 × too long (field A-019 add-on: Navigate's hike zoom 17 read "50 m" for ~22 m and
 * looked like a wrong, far too close zoom).
 */
internal object ScaleRule {
    const val MAX_DP = 100.0

    /** Label ("200 m", "1 km" – only 1 / 2 / 5 steps) and bar width in dp; null for a broken scale. */
    fun of(metersPerDp: Double): Pair<String, Double>? {
        if (!(metersPerDp > 0.0) || metersPerDp.isInfinite()) return null
        val maxM = metersPerDp * MAX_DP
        val pow10 = 10.0.pow(floor(log10(maxM)))
        val nice = listOf(5.0, 2.0, 1.0).map { it * pow10 }.first { it <= maxM }
        val label = if (nice >= 1000) "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km"
            else "${nice.toInt()} m"
        return label to nice / metersPerDp
    }

    /** Metres per dp at [lat] and [zoom] (MapLibre: 512 dp tiles) – what the projection returns; for tests. */
    fun metersPerDp(lat: Double, zoom: Double): Double =
        kotlin.math.cos(Math.toRadians(lat)) * 2 * Math.PI * 6378137.0 / (512.0 * 2.0.pow(zoom))
}
