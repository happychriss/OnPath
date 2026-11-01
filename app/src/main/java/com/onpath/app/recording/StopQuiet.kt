package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * "Navigation Stopp" = the phone is quiet (owner 2026-11-01, a restaurant on a hike: "Kein GPS-Signal" twice and a
 * junction vibration while the tour stood in Plan, screen off). Pure, unit-tested (`StopQuietTest`).
 *
 * - While the app is in Plan during a tour (the slider's "Stopp"): no voice, no junction vibration, no off-route
 *   question – also with the screen off (replaces review H1's "Plan off screen = warnings on"). Recording goes on.
 * - Walked off without "Weiter" (the phone pocketed, the app off screen): after [RESUME_M] from where the quiet began
 *   the guidance comes back by itself (H1's worry: a silent rest of the tour) – fixes worse than [MAX_ACCURACY_M] do not
 *   count (indoor drift in a restaurant).
 */
class StopQuiet {
    /** The app is in Plan during the tour. */
    var stopped = false
        private set
    /** The guidance came back by walking off ([onFix]). */
    var resumed = false
        private set
    private var from: GeoPoint? = null

    /** Quiet now: stopped and not walked off. */
    val quiet: Boolean get() = stopped && !resumed

    /** The app's mode: Plan during the tour = [stopped]. A new state starts afresh. */
    fun set(stopped: Boolean) {
        if (stopped == this.stopped) return
        this.stopped = stopped; resumed = false; from = null
    }

    /** A recorded fix; true = the guidance comes back now (walked [RESUME_M] away with the app off screen). */
    fun onFix(p: GeoPoint, accuracyM: Float?, appVisible: Boolean): Boolean {
        if (!stopped || resumed) return false
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) return false
        val start = from ?: run { from = p; return false }
        if (appVisible || Geo.distance(start, p) < RESUME_M) return false
        resumed = true
        return true
    }

    companion object {
        const val RESUME_M = 150.0
        const val MAX_ACCURACY_M = 30f
    }
}
