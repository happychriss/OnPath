package com.onpath.app.recording

/**
 * "Navigation Stopp" = the phone is quiet (owner 2026-11-01, a restaurant on a hike: "Kein GPS-Signal" twice and a
 * junction vibration while the tour stood in Plan, screen off). Pure, unit-tested (`StopQuietTest`).
 *
 * While the app is in Plan during a tour: no voice, no junction vibration, no navigation message ([ModeQuiet]) – also
 * with the screen off. Recording goes on. Only "Navigation fortsetzen" ends it: walking or riding on never brings the
 * guidance back by itself (owner 2026-11-03: "Plan bleibt still" – the 150 m walk-off of 2026-11-01 is gone).
 */
class StopQuiet {
    /** The app is in Plan during the tour. */
    var stopped = false
        private set

    /** Quiet now: in Plan during the tour. */
    val quiet: Boolean get() = stopped

    /** The app's mode: Plan during the tour = [stopped]. */
    fun set(stopped: Boolean) { this.stopped = stopped }
}
