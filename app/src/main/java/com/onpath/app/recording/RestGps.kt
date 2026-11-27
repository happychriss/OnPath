package com.onpath.app.recording

import android.location.Location
import android.os.SystemClock
import com.onpath.app.debug.Problems

/**
 * [RestWatch] on the recording's [tracker] (owner: battery, never lose a point): every usable fix of a running tour and
 * the service's one-second tick with the motion sensor's word ([still], elapsed-realtime ms → true still / false moving /
 * null none) – slower GPS while standing still, GPS off after an hour lying still (owner 2026-11-24), back to every second
 * at the first move. A failure never leaves GPS slow or off: back to every second, reported.
 */
internal class RestGps(private val tracker: LocationTracker, private val still: (Long) -> Boolean?) {
    private val watch = RestWatch()

    val resting: Boolean get() = watch.resting

    /** Back to the normal rate (tour start, pause, resume, end); the caller sets the tracker's rate. */
    fun reset() = watch.reset()

    fun onFix(loc: Location) {
        try {
            watch.onFix(loc.elapsedRealtimeNanos / 1_000_000L, loc.latitude, loc.longitude, loc.accuracy,
                if (loc.hasSpeed()) loc.speed else null, still(SystemClock.elapsedRealtime()))?.let { apply(it, "GPS") }
        } catch (e: Exception) { failed(e) }
    }

    /** The tick: rest without good fixes, wake without fixes, GPS off after an hour ([RestWatch.onTick]). */
    fun onTick(nowMs: Long) {
        try { watch.onTick(nowMs, still(nowMs))?.let { apply(it, "motion sensor") } } catch (e: Exception) { failed(e) }
    }

    private fun apply(change: RestWatch.Change, by: String) {
        when (change) {
            RestWatch.Change.OFF -> tracker.stop()
            else -> { tracker.setInterval(watch.intervalMs); tracker.start() } // start: GPS back on after OFF, no-op while running
        }
        Problems.trace("gps", when (change) {
            RestWatch.Change.REST -> "Standing still for ${(if (by == "motion sensor") RestWatch.SENSOR_REST_AFTER_MS else RestWatch.REST_AFTER_MS) / 1000} s ($by): GPS every ${RestWatch.REST_MS / 1000} s"
            RestWatch.Change.MOVING -> "Moving again ($by): GPS every second"
            RestWatch.Change.OFF -> "Lying still for ${RestWatch.OFF_AFTER_MS / 60_000} min (motion sensor): GPS off until the phone moves"
        })
    }

    private fun failed(e: Exception) {
        Problems.warn("gps", "GPS rate for standing still not changed; back to every second", e)
        watch.reset()
        try { tracker.setInterval(RestWatch.NORMAL_MS); tracker.start() } catch (e2: Exception) {
            Problems.error("gps", "GPS could not be started again after a rate failure", e2)
        }
    }
}
