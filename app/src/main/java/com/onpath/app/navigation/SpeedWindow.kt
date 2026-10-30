package com.onpath.app.navigation

/**
 * The speed navigation plans with: the highest GPS speed of the last [windowMs] (speeding up counts at once, a red light
 * does not shrink it at once), [fallbackMps] while no fix had a speed. Pure; the smart screen (ui/SmartScreen) and the
 * bike's junction cues ([JunctionCues]) each keep one. Clock = any monotonic milliseconds.
 */
class SpeedWindow(private val windowMs: Long = WINDOW_MS, private val fallbackMps: Double = FALLBACK_MPS) {
    private val speeds = ArrayDeque<Pair<Long, Double>>()

    /** Every fix: its speed (m/s; null = the fix has none). */
    fun note(mps: Double?, nowMs: Long) {
        if (mps != null && mps.isFinite() && mps >= 0) speeds.addLast(nowMs to mps)
        while (speeds.isNotEmpty() && speeds.first().first < nowMs - windowMs) speeds.removeFirst()
    }

    fun speed(nowMs: Long): Double =
        speeds.filter { it.first >= nowMs - windowMs }.maxOfOrNull { it.second } ?: fallbackMps

    companion object {
        const val WINDOW_MS = 10_000L
        /** Speed unknown (no fix with a speed yet): about 22 km/h. */
        const val FALLBACK_MPS = 6.0
    }
}
