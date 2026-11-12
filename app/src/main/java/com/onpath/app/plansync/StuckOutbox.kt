package com.onpath.app.plansync

/**
 * Review sync M8: an item that keeps failing (refused as too large, a mailbox that lost its history) stayed in the outbox
 * for ever while Settings said "synced just now". After [PASSES] passes in a row that ended with items still waiting, the
 * status says so. Pure.
 */
object StuckOutbox {
    const val PASSES = 3

    /**
     * Passes in a row with something left, after a pass that left [left] items and sent [pushed]. A pass that sent
     * something is progress (pacing, SyncRound.WRITES_PER_ROUND: 110 plans take four passes) – it starts the count again.
     */
    fun next(passes: Int, left: Int, pushed: Int = 0): Int = if (left > 0 && pushed == 0) passes + 1 else 0

    fun stuck(passes: Int): Boolean = passes >= PASSES
}
