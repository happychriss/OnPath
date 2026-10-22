package com.onpath.app.debug

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Frame times while something moves (owner field report 0.9.3: the plan sheet "ruckelt" on a 120 Hz Pixel 8). [start]
 * listens to the window's FrameMetrics (API 24+, no extra library), [stop] returns the summary of the frames since.
 * The plan sheet writes it into the Problems events trace ("Sheet drag: N frames, J janky (p95 X ms)"), so the owner's
 * next swipe gives numbers in the diagnostics export. Never throws.
 */
class FrameJankMeter(private val window: Window) {
    private val durationsNs = ArrayList<Long>()
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    val running: Boolean get() = listener != null

    fun start() {
        if (listener != null) return
        synchronized(durationsNs) { durationsNs.clear() }
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(durationsNs) { durationsNs.add(ns) }
        }
        try {
            window.addOnFrameMetricsAvailableListener(l, handler)
            listener = l
        } catch (e: Exception) {
            Problems.warn("ui", "Frame timing not available; no sheet drag numbers", e)
        }
    }

    /** Stops listening; null when it was not running or no frame was drawn. */
    fun stop(): FrameStats? {
        val l = listener ?: return null
        listener = null
        try {
            window.removeOnFrameMetricsAvailableListener(l)
        } catch (e: Exception) {
            Problems.warn("ui", "Frame timing listener could not be removed", e)
        }
        val ns = synchronized(durationsNs) { durationsNs.toList().also { durationsNs.clear() } }
        return FrameStats.of(ns.map { it / 1_000_000.0 }, refreshHz())
    }

    private fun refreshHz(): Float = try {
        window.decorView.display?.refreshRate ?: 60f
    } catch (e: Exception) {
        Problems.info("ui", "Display refresh rate unknown; 60 Hz assumed", e); 60f
    }

    private companion object {
        val handler: Handler by lazy { Handler(HandlerThread("onpath-frames").apply { start() }.looper) }
    }
}

/** Summary of frame times in ms (pure, unit tested: FrameStatsTest). */
data class FrameStats(
    val frames: Int, val over16: Int, val over33: Int, val overBudget: Int, val budgetMs: Double,
    val p95Ms: Double, val maxMs: Double, val hz: Int,
) {
    /** "Sheet drag: 74 frames, 3 janky > 16 ms, 0 > 33 ms (p95 9.1 ms, max 18.2 ms; 120 Hz: 5 over 8.3 ms)". */
    fun line(what: String): String =
        "$what: $frames frames, $over16 janky > 16 ms, $over33 > 33 ms (p95 ${fmt(p95Ms)} ms, max ${fmt(maxMs)} ms; " +
            "$hz Hz: $overBudget over ${fmt(budgetMs)} ms)"

    companion object {
        fun of(ms: List<Double>, refreshHz: Float = 60f): FrameStats? {
            if (ms.isEmpty()) return null
            val sorted = ms.sorted()
            val hz = refreshHz.coerceIn(30f, 240f)
            val budget = 1000.0 / hz
            // nearest-rank 95th percentile
            val p95 = sorted[(kotlin.math.ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            return FrameStats(ms.size, ms.count { it > 16.0 }, ms.count { it > 33.0 }, ms.count { it > budget + 0.5 },
                budget, p95, sorted.last(), kotlin.math.round(hz).toInt())
        }

        private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
