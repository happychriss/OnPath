package com.onpath.app.recording

/**
 * Review H3: after the process died, START_STICKY brings the service back from the background; on some Android versions
 * it then gets no location at all and says "Searching GPS…" for hours while the user believes it records. Armed only
 * for such a restart (never for a tour started indoors – owner: quiet while standing still): no GPS fix within [AFTER_MS]
 * while not paused → one loud notification. Pure.
 */
class GpsWatchdog {
    companion object { const val AFTER_MS = 5 * 60_000L }

    private var armedAt: Long? = null
    private var fired = false

    /** The service came back by itself (sticky restart). */
    fun arm(nowMs: Long) { armedAt = nowMs; fired = false }

    /** A GPS fix came, or the tour ended: nothing to watch. */
    fun disarm() { armedAt = null }

    /** Resume after a pause: the 5 minutes start again (only while still armed). */
    fun resumed(nowMs: Long) { if (armedAt != null) armedAt = nowMs }

    /** True once, when the alarm is due. */
    fun due(nowMs: Long, paused: Boolean): Boolean {
        val at = armedAt ?: return false
        if (paused || fired || nowMs - at < AFTER_MS) return false
        fired = true
        return true
    }
}
