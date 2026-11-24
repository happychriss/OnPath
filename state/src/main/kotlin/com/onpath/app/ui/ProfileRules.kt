package com.onpath.app.ui

/**
 * Indices of the points to draw for [columns] pixel columns: all when there are few, else per column the first, the
 * lowest, the highest and the last point, in order (the line looks the same at that width). Pure, unit-tested.
 */
fun columnIndices(dist: DoubleArray, ele: DoubleArray, total: Double, columns: Int): IntArray {
    val n = dist.size
    if (n <= 4 * columns || total <= 0) return IntArray(n) { it }
    val out = ArrayList<Int>(4 * columns + 2)
    var i = 0
    while (i < n) {
        val col = (dist[i] / total * columns).toInt().coerceIn(0, columns - 1)
        var lo = i; var hi = i; var j = i
        while (j + 1 < n && (dist[j + 1] / total * columns).toInt().coerceIn(0, columns - 1) == col) {
            j++
            if (ele[j] < ele[lo]) lo = j
            if (ele[j] > ele[hi]) hi = j
        }
        for (k in listOf(i, minOf(lo, hi), maxOf(lo, hi), j)) if (out.isEmpty() || out.last() < k) out += k
        i = j + 1
    }
    return out.toIntArray()
}

/** Which heights the profile's left axis writes (pure, `ProfileAxisTest`). */
object ProfileAxis {
    /**
     * [min] and [max] at the drawn heights [yMin] / [yMax] (pixels, y down) with labels [textH] high: both when they
     * keep a label's height and a bit apart and differ as written (whole metres), else only [max].
     */
    fun labels(min: Double, max: Double, yMin: Float, yMax: Float, textH: Float): List<Double> =
        if (min.toInt() != max.toInt() && kotlin.math.abs(yMin - yMax) >= textH * 1.2f) listOf(min, max) else listOf(max)
}

/**
 * The profile's fill (owner 2026-11-06, polish 8): [BASE_ALPHA] of the line's colour at the top fading to nothing, and
 * where the way is steep up to [MAX_EXTRA_ALPHA] more. The slope is taken over [WINDOW_M] around each point (so a DEM
 * bump or a short ramp does not paint a stripe) and turned into [LEVELS] fine steps from 5 % (nothing) to 15 % and more
 * (all) – neighbouring steps differ by a few per cent of colour, the fill shades along the way. Pure, unit-tested.
 */
object ProfileFill {
    const val BASE_ALPHA = 0.30f
    const val MAX_EXTRA_ALPHA = 0.22f
    const val LEVELS = 8
    const val WINDOW_M = 300.0
    private const val FROM_PCT = 5.0
    private const val TO_PCT = 15.0

    fun alpha(level: Int): Float = MAX_EXTRA_ALPHA * level / (LEVELS - 1)

    /** Per point its level 0..[LEVELS]-1: the absolute slope over [WINDOW_M] centred on it, 5 % → 0, ≥ 15 % → top. */
    fun levels(dist: DoubleArray, ele: DoubleArray): IntArray {
        val n = minOf(dist.size, ele.size)
        val out = IntArray(n)
        if (n < 2) return out
        var a = 0; var b = 0
        for (i in 0 until n) {
            // the window [dist[i] - W/2, dist[i] + W/2], clamped to the profile
            while (dist[a] < dist[i] - WINDOW_M / 2 && a < i) a++
            while (b + 1 < n && dist[b + 1] <= dist[i] + WINDOW_M / 2) b++
            if (b < i) b = i
            var lo = a; var hi = b
            // at the ends the window is too short: widen it to the full width where the profile allows
            while (dist[hi] - dist[lo] < WINDOW_M && (lo > 0 || hi < n - 1)) { if (lo > 0) lo--; if (hi < n - 1) hi++ }
            val run = dist[hi] - dist[lo]
            if (run <= 0) continue
            val pct = kotlin.math.abs(ele[hi] - ele[lo]) / run * 100
            val share = ((pct - FROM_PCT) / (TO_PCT - FROM_PCT)).coerceIn(0.0, 1.0)
            out[i] = Math.round(share * (LEVELS - 1)).toInt()
        }
        return out
    }
}
