package com.onpath.app.live

/**
 * When the phone uploads and when the viewer page asks (docs/design/live-tracking.md §5, §5.1). Pure rules: every
 * request wakes the phone's radio and counts against the server's daily budget, so both sides ask as rarely as the
 * feature allows.
 */
object LivePolicy {
    /** Moving: an upload every two minutes. */
    const val MOVING_S = 120
    /** Paused, standing still or low battery: every ten minutes (the head only – "still here"). */
    const val STILL_S = 600
    /** An event (start, pause, resume, off-route alarm, arrived, end) goes at once – but at most one upload per 30 s. */
    const val EVENT_FLOOR_MS = 30_000L
    /** After a failed upload: 30 s, doubling per failure in a row … */
    const val RETRY_MS = 30_000L
    /** … up to ten minutes; the server's `retry-after` is capped there too. */
    const val MAX_RETRY_MS = 600_000L
    /** A busy server (429 / 5xx) is left alone for at least a minute. */
    const val BUSY_FLOOR_MS = 60_000L
    /** Android reports the network back: try now – but not more often than this while it flaps. */
    const val NETWORK_BACK_FLOOR_MS = 10_000L

    /**
     * The viewer asks this long after the phone's next upload was due – just the upload's own way to the server, so the
     * new point shows when the countdown reaches 0 (owner 2026-11-26: „wenn … auf 0, dann erwartet man, dass es springt“).
     */
    const val VIEWER_SLACK_MS = 3_000L
    /** The viewer never asks faster than this. */
    const val VIEWER_FLOOR_MS = 5_000L
    /** An upload not there at 0: asked every [VIEWER_FLOOR_MS] for this long past the planned moment (a phone a little late). */
    const val VIEWER_EAGER_MS = 15_000L
    /** Nothing new: the viewer asks again after 1, 2, 5, then every 10 minutes. */
    val VIEWER_QUIET_MS = listOf(60_000L, 120_000L, 300_000L, 600_000L)
    /** … picked by how late the phone is past its planned upload: below 1, 3, 8 minutes, then more. */
    private val VIEWER_LATE_MS = listOf(60_000L, 180_000L, 480_000L)

    /** Seconds between two planned uploads in this situation – also what the head tells the viewer ([LiveHead.nextS]). */
    fun intervalS(moving: Boolean, paused: Boolean, lowBattery: Boolean): Int =
        if (paused || !moving || lowBattery) STILL_S else MOVING_S

    /**
     * The time (ms, the caller's clock) the next upload is due; the caller uploads when its clock reaches it.
     *
     * @param lastUploadMs the last upload that went through; null = none yet (the first one is due at once)
     * @param lastTryMs the last attempt, good or failed; null = none yet
     * @param event something the viewer should see at once happened since the last upload
     * @param failures failed attempts in a row since the last good upload
     * @param retryAfterMs the server's `retry-after` of the last failed attempt (429 / 5xx), else null
     * @param networkBack Android reported the network back since the last failed attempt
     */
    fun nextUpload(
        lastUploadMs: Long?, lastTryMs: Long?, moving: Boolean, paused: Boolean, lowBattery: Boolean,
        event: Boolean, failures: Int, retryAfterMs: Long? = null, networkBack: Boolean = false,
    ): Long {
        if (failures > 0 && lastTryMs != null) {
            if (retryAfterMs != null) return lastTryMs + retryAfterMs.coerceIn(BUSY_FLOOR_MS, MAX_RETRY_MS)
            if (networkBack) return lastTryMs + NETWORK_BACK_FLOOR_MS
            return lastTryMs + backOff(failures)
        }
        if (lastUploadMs == null) return lastTryMs?.plus(EVENT_FLOOR_MS) ?: 0L
        val planned = lastUploadMs + intervalS(moving, paused, lowBattery) * 1000L
        return if (event) minOf(planned, lastUploadMs + EVENT_FLOOR_MS) else planned
    }

    /** The wait after [failures] failed attempts in a row: 30 s, 1, 2, 4, 8, then 10 minutes. */
    fun backOff(failures: Int): Long {
        var w = RETRY_MS
        repeat((failures - 1).coerceIn(0, 10)) { w = minOf(w * 2, MAX_RETRY_MS) }
        return w
    }

    /**
     * How long (ms) the viewer page waits before it asks the server again; null = never again (the tour ended or the
     * sharing was stopped). The page also asks at once when its tab comes back, and not at all while hidden.
     *
     * @param sinceLastWriteMs the server's clock minus the time of the phone's last upload, from the last answer
     * @param nextS the head's [LiveHead.nextS]; null when no head could be read yet
     * @param emptyAsks questions in a row that brought nothing new (0 = the last one did) – paces only a page without a head
     * @param over the tour ended or the box is gone
     * @param retryAfterMs the server said busy (429 / 5xx) with this `retry-after`; 0 = busy without one; null = not busy
     */
    fun viewerNext(sinceLastWriteMs: Long, nextS: Int?, emptyAsks: Int, over: Boolean, retryAfterMs: Long? = null): Long? {
        if (over) return null
        if (retryAfterMs != null) return retryAfterMs.coerceIn(BUSY_FLOOR_MS, MAX_RETRY_MS)
        if (nextS == null) return VIEWER_QUIET_MS[(emptyAsks - 1).coerceIn(0, VIEWER_QUIET_MS.size - 1)]
        // just after the phone's next planned upload; a little late → every few seconds for a moment; later still (a
        // dead zone, or the page opened long after the last upload) → the quiet steps, by how late the phone is
        val late = sinceLastWriteMs - nextS.coerceIn(10, 3600) * 1000L
        if (late < VIEWER_SLACK_MS) return maxOf(VIEWER_SLACK_MS - late, VIEWER_FLOOR_MS)
        if (late < VIEWER_SLACK_MS + VIEWER_EAGER_MS) return VIEWER_FLOOR_MS
        return VIEWER_QUIET_MS[VIEWER_LATE_MS.indexOfFirst { late < it }.let { if (it < 0) VIEWER_QUIET_MS.size - 1 else it }]
    }
}
