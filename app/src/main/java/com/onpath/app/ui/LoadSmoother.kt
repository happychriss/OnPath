package com.onpath.app.ui

import kotlin.math.max
import kotlin.math.min

/**
 * The loading window's % by TIME, not by bytes (owner): byte-weighting makes the quick routing data look like half the
 * job while the server still builds the map for minutes, so the bar races to 50 % and then looks stuck. The bar shows
 * the share of the expected total time that has passed: elapsed / (elapsed + time left),
 * with "time left" from the loading states (server estimate while it prepares, download speed while it loads).
 *
 * - Never backwards; never 100 before the map is there (the caller closes the window then).
 * - When the estimate grows (server slower than said), the bar does not freeze: it keeps creeping by [CREEP_PER_S]
 *   up to [CREEP_CAP] – the user sees that it is still working.
 * - It does not jump either: at most [MAX_STEP_PER_S] per second towards the target.
 *
 * Time is passed in. One instance per loading window (reset when the window closes).
 */
class LoadSmoother {
    private var startAt = -1L
    private var lastAt = -1L
    private var shown = 0.0

    fun reset() { startAt = -1L; lastAt = -1L; shown = 0.0 }

    /** The % to show at [now] (ms) with about [secondsLeft] left (null = unknown → [DEFAULT_LEFT_S]). */
    fun next(now: Long, secondsLeft: Int?): Int {
        if (startAt < 0) { startAt = now; lastAt = now }
        val dt = ((now - lastAt) / 1000.0).coerceAtLeast(0.0)
        lastAt = now
        val elapsed = (now - startAt) / 1000.0
        val left = (secondsLeft ?: DEFAULT_LEFT_S).coerceAtLeast(1).toDouble()
        val target = 100.0 * elapsed / (elapsed + left)
        shown = when {
            target > shown -> min(target, shown + MAX_STEP_PER_S * max(dt, 1.0))
            else -> if (shown < CREEP_CAP) min(CREEP_CAP, shown + CREEP_PER_S * dt) else shown
        }
        return shown.toInt().coerceIn(0, 99)
    }

    companion object {
        /** Assumed time left when nothing is known yet (the server's usual time for a new area). */
        const val DEFAULT_LEFT_S = 180
        /** Slow creep when the estimate grew, so the bar never looks frozen. */
        const val CREEP_PER_S = 0.15
        /** The creep stops here; only real progress goes further. */
        const val CREEP_CAP = 95.0
        /** At most this many % per second (no jumps when an estimate shrinks). */
        const val MAX_STEP_PER_S = 4.0
    }
}
