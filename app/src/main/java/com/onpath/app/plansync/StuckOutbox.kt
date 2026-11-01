package com.onpath.app.plansync

/**
 * Review sync M8: an item that keeps failing (refused as too large, a mailbox that lost its history) stayed in the outbox
 * for ever while Settings said "synced just now". After [PASSES] passes in a row that ended with items still waiting, the
 * status says so. Pure.
 */
object StuckOutbox {
    const val PASSES = 3

    /** Passes in a row with something left, after a pass that left [left] items. */
    fun next(passes: Int, left: Int): Int = if (left > 0) passes + 1 else 0

    fun stuck(passes: Int): Boolean = passes >= PASSES
}
