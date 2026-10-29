package com.onpath.app.map

/** Position dot rules (owner). Pure, unit-tested. */
object PositionDot {
    /** No position for longer than this (screen locked, app in the background): the next one jumps, no glide. */
    const val JUMP_AFTER_MS = 3_000L
    /** Dot size against MapLibre's default (1.0 zoomed in, 0.6 zoomed out): a little larger. */
    const val MAX_SCALE = 1.3f
    const val MIN_SCALE = 0.9f

    /** Whether the dot is placed at once: the first position, or the first after a pause. */
    fun jumps(lastFeedElapsed: Long, nowElapsed: Long): Boolean =
        lastFeedElapsed == 0L || nowElapsed - lastFeedElapsed > JUMP_AFTER_MS
}
