package com.onpath.app.recording

import com.onpath.app.data.Geo

/**
 * Slower GPS while standing still during a tour (owner: battery, never lose a point). Pure, unit-tested;
 * [RecordingService] feeds it every usable GPS fix of a running (not paused) tour and
 * applies [intervalMs].
 *
 * - Resting: all good fixes (≤ [REST_MAX_ACCURACY_M]) for [REST_AFTER_MS] stayed within [STILL_M] of the first one, none
 *   reported a speed above [STILL_SPEED_MS], and there was no gap longer than [MAX_GAP_MS] between them. So never while
 *   the first fix is still being acquired, and never on a slow walk.
 * - Moving again at once: the first fix more than [WAKE_M] from the rest point or faster than [WAKE_SPEED_MS] – also a
 *   poor one (waking costs a little battery, staying slow while walking would cost track points). That fix itself is
 *   recorded as usual: the rate only decides how often fixes come, never which are stored.
 */
class RestWatch {
    companion object {
        /** Normal tour rate. */
        const val NORMAL_MS = 1_000L
        /** Rate while standing still. */
        const val REST_MS = 10_000L
        const val REST_AFTER_MS = 60_000L
        const val STILL_M = 10.0
        const val STILL_SPEED_MS = 0.5f
        const val REST_MAX_ACCURACY_M = 20f
        const val MAX_GAP_MS = 15_000L
        const val WAKE_M = 12.0
        const val WAKE_SPEED_MS = 0.7f

        /** No fix for this long = "GPS lost" (Searching GPS, no off-route alarms); the slow rate is allowed for. */
        fun lostAfterMs(resting: Boolean): Long = if (resting) REST_MS * 2 + 5_000L else 8_000L
    }

    enum class Change { REST, MOVING }

    var resting = false
        private set
    val intervalMs: Long get() = if (resting) REST_MS else NORMAL_MS

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorMs = 0L
    private var lastMs = Long.MIN_VALUE

    /** Back to the normal rate, the minute starts anew (tour start, pause, resume). */
    fun reset() { resting = false; anchorLat = Double.NaN; lastMs = Long.MIN_VALUE }

    /**
     * A usable GPS fix ([timeMs] = the fix's elapsed-realtime clock, [speedMs] null when the fix has none). Returns the
     * change to apply, or null.
     */
    fun onFix(timeMs: Long, lat: Double, lon: Double, accuracyM: Float, speedMs: Float?): Change? {
        val gap = lastMs != Long.MIN_VALUE && timeMs - lastMs > MAX_GAP_MS
        lastMs = timeMs
        if (resting) {
            val far = !anchorLat.isNaN() && Geo.distance(anchorLat, anchorLon, lat, lon) > WAKE_M
            if (!far && (speedMs == null || speedMs <= WAKE_SPEED_MS)) return null
            resting = false
            anchor(timeMs, lat, lon)
            return Change.MOVING
        }
        if (!accuracyM.isFinite() || accuracyM > REST_MAX_ACCURACY_M) { anchorLat = Double.NaN; return null }
        if (speedMs != null && speedMs > STILL_SPEED_MS) { anchorLat = Double.NaN; return null }
        if (anchorLat.isNaN() || gap || Geo.distance(anchorLat, anchorLon, lat, lon) > STILL_M) {
            anchor(timeMs, lat, lon); return null
        }
        if (timeMs - anchorMs < REST_AFTER_MS) return null
        resting = true
        return Change.REST
    }

    private fun anchor(timeMs: Long, lat: Double, lon: Double) { anchorLat = lat; anchorLon = lon; anchorMs = timeMs }
}
