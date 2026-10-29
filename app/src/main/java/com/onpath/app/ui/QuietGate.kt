package com.onpath.app.ui

/**
 * Turns a burst of changes into one action, e.g. one map style reload for many map store generations, one plan
 * recompute for many arriving routing parts – each costly, together they heat the phone.
 *
 * Due [quietMs] after the last [changed], but at the latest [maxWaitMs] after the first one of the burst. While the
 * user is busy (dragging the map or the sheet, scrubbing the profile) it waits – up to [maxBusyMs] after the first
 * change, so a stuck "busy" never blocks it for good. Not thread-safe (main thread).
 */
class QuietGate(private val quietMs: Long, private val maxWaitMs: Long, private val maxBusyMs: Long = 120_000L) {
    private var first = 0L
    private var last = 0L

    /** A change waits for its action. */
    var pending = false
        private set

    fun changed(now: Long) {
        if (!pending) { pending = true; first = now }
        last = now
    }

    /** Forget a pending change (the action ran another way, or is not wanted any more). */
    fun reset() { pending = false }

    /**
     * Null = nothing pending; 0 = act now (the gate is reset); else ms to wait before asking again.
     */
    fun waitMs(now: Long, busy: Boolean): Long? {
        if (!pending) return null
        if (busy && now < first + maxBusyMs) return BUSY_RECHECK_MS.coerceAtMost(first + maxBusyMs - now)
        val due = minOf(last + quietMs, first + maxWaitMs)
        if (now >= due) { pending = false; return 0L }
        return due - now
    }

    companion object {
        const val BUSY_RECHECK_MS = 300L
    }
}
