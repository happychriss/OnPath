package com.onpath.app.recording

/**
 * Quiet while standing still (owner 2026-11-01: in a restaurant, phone in the pocket, talking – "No GPS signal" came
 * again and again): moving = a track point was accepted ([PointFilter] drops standing-still fixes) within [WINDOW_MS].
 * While standing still the tour says nothing – no "No GPS signal", no off-route question, no reminder; only a quiet state
 * may change. A GPS loss counts as "while moving" only when the user moved within the window before the loss. Pure.
 */
class MoveWatch {
    companion object {
        const val WINDOW_MS = 120_000L
    }

    private var lastMovedMs: Long? = null

    /** A tour starts / continues / resumes: counts as moving for the first [WINDOW_MS] (a far start is still asked). */
    fun start(nowMs: Long) { lastMovedMs = nowMs }

    /** A track point was accepted (the user moved). */
    fun moved(nowMs: Long) { lastMovedMs = nowMs }

    /** Moved within [WINDOW_MS] before [atMs] (for a GPS loss: the time of the last good fix). */
    fun moving(atMs: Long): Boolean = lastMovedMs?.let { atMs - it in 0..WINDOW_MS || it > atMs } ?: false
}
