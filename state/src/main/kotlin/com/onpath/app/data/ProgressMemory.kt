package com.onpath.app.data

import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.StepShares

/**
 * The wait window's learned shares of time (owner 2026-11-19: each step owns the share it really takes, measured on this
 * phone, learned from the last loads): the map load's steps ([StepShares]) and the route's check ([RouteShares]). Kept in
 * the app's preferences; robust first – a value that cannot be read or written gives the start values (reported), the
 * session goes on with what it learned in memory. Thread-safe.
 */
interface ProgressMemory {
    var shares: StepShares
    var routeCheck: Double
    /**
     * The round-trip search's three steps (read the paths · find loops · rate – ui/progress/SuggestWait) in the three
     * places of a [StepShares]; start [SUGGEST_DEFAULT]. Default accessors: a memory that keeps none uses the start values.
     */
    var suggestShares: StepShares
        get() = SUGGEST_DEFAULT
        set(@Suppress("UNUSED_PARAMETER") v) {}

    /** For tests and when nothing can be stored: in memory only. */
    class InMemory(override var shares: StepShares = StepShares.DEFAULT, override var routeCheck: Double = RouteShares.DEFAULT_CHECK,
                   override var suggestShares: StepShares = SUGGEST_DEFAULT) : ProgressMemory

    companion object {
        /**
         * The round-trip search's start shares: read 65 · find 30 · rate 5 (measured: a 3 h bike wish network + facts
         * 4.5 s, place + loops 1.8 s, judging 0.1 s – docs/design/tour-suggestions.md).
         */
        val SUGGEST_DEFAULT = StepShares(0.65, 0.30, 0.05)
    }
}
