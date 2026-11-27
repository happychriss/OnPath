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
 * - The motion sensor (owner 2026-11-24, his Pixel lying indoors woke 9 times in 48 min from fixes of ±49 m): while it
 *   says the phone lies still, a poor fix (over [REST_MAX_ACCURACY_M]) does not wake – a good one still does (a phone
 *   lying in a moving car or train); [SENSOR_REST_AFTER_MS] still by the sensor rests without good fixes ([onTick]); the sensor saying
 *   "moving" wakes at once, also without any fix. No sensor word (none, not sure) = the GPS rules above.
 * - GPS off (owner 2026-11-24: „wenn mehr als 1h Ruhe, dann kann GPS auch ganz aus, dann ist das Handy wenigstens nicht
 *   leer“): resting and still by the sensor for [OFF_AFTER_MS] without a break → [Change.OFF]; the sensor saying moving
 *   or losing its word (not sure, gone) turns GPS back on at the normal rate. Never without the sensor.
 */
class RestWatch {
    companion object {
        /** Normal tour rate. */
        const val NORMAL_MS = 1_000L
        /** Rate while standing still. */
        const val REST_MS = 10_000L
        const val REST_AFTER_MS = 60_000L
        /**
         * Still by the motion sensor this long: rest (owner 2026-11-27, „kürzer“). The sensor says „moving“ at the first step,
         * so a stop at a junction (10–30 s) costs nothing; shorter would slow GPS at every traffic light.
         */
        const val SENSOR_REST_AFTER_MS = 30_000L
        const val STILL_M = 10.0
        const val STILL_SPEED_MS = 0.5f
        const val REST_MAX_ACCURACY_M = 20f
        const val MAX_GAP_MS = 15_000L
        const val WAKE_M = 12.0
        const val WAKE_SPEED_MS = 0.7f
        /** Still by the motion sensor this long without a break: GPS off until the phone moves. */
        const val OFF_AFTER_MS = 60 * 60_000L

        /** No fix for this long = "GPS lost" (Searching GPS, no off-route alarms); the slow rate is allowed for. */
        fun lostAfterMs(resting: Boolean): Long = if (resting) REST_MS * 2 + 5_000L else 8_000L
    }

    enum class Change { REST, MOVING, OFF }

    var resting = false
        private set
    /** GPS switched off: still by the sensor for [OFF_AFTER_MS] ([resting] stays true). */
    var off = false
        private set
    val intervalMs: Long get() = if (resting) REST_MS else NORMAL_MS

    /** Since when the motion sensor says "still" without a break (elapsed-realtime ms), null = not now. */
    private var sensorStillSince: Long? = null

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorMs = 0L
    private var lastMs = Long.MIN_VALUE

    /** Back to the normal rate, the minute starts anew (tour start, pause, resume). */
    fun reset() { resting = false; off = false; anchorLat = Double.NaN; lastMs = Long.MIN_VALUE; sensorStillSince = null }

    /**
     * The service's tick (about every second) with the motion sensor's word [still] (true still, false moving, null none /
     * not sure). Returns the change to apply, or null.
     */
    fun onTick(nowMs: Long, still: Boolean?): Change? {
        sensorStillSince = if (still == true) sensorStillSince ?: nowMs else null
        if (resting && (still == false || (off && still == null))) {
            resting = false; off = false; anchorLat = Double.NaN
            return Change.MOVING
        }
        val since = sensorStillSince ?: return null
        if (!resting && nowMs - since >= SENSOR_REST_AFTER_MS) { resting = true; anchorLat = Double.NaN; return Change.REST }
        if (resting && !off && nowMs - since >= OFF_AFTER_MS) { off = true; return Change.OFF }
        return null
    }

    /**
     * A usable GPS fix ([timeMs] = the fix's elapsed-realtime clock, [speedMs] null when the fix has none). Returns the
     * change to apply, or null.
     */
    fun onFix(timeMs: Long, lat: Double, lon: Double, accuracyM: Float, speedMs: Float?, still: Boolean? = null): Change? {
        val gap = lastMs != Long.MIN_VALUE && timeMs - lastMs > MAX_GAP_MS
        lastMs = timeMs
        if (resting) {
            val good = accuracyM.isFinite() && accuracyM <= REST_MAX_ACCURACY_M
            // the sensor says the phone lies still: a poor fix is GPS noise indoors, not a step
            if (still == true && !good) return null
            // a rest started by the sensor has no rest point yet: the first good fix is it
            if (anchorLat.isNaN() && good && (speedMs == null || speedMs <= WAKE_SPEED_MS)) { anchor(timeMs, lat, lon); return null }
            val far = !anchorLat.isNaN() && Geo.distance(anchorLat, anchorLon, lat, lon) > WAKE_M
            if (!far && (speedMs == null || speedMs <= WAKE_SPEED_MS)) return null
            resting = false; off = false
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
