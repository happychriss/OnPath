package com.onpath.app.plansync

import com.onpath.app.sync.SyncRound

/**
 * Sync while the app stays open (owner 2026-10-30: "fully automatic, like WhatsApp Web"): a route saved in the browser
 * arrives without leaving the app. Pure rule, the clock comes from outside ([com.onpath.app.ui.AppViewModel] drives it).
 *
 * - **When:** the app is on screen, paired, no tour is recording, and a screen where routes matter is shown – Plan
 *   mode, My routes or Settings → Browser sync ([wanted]). The app in the background polls nothing.
 * - **How often:** every [BASE_MS] after the last pass (any pass counts: after a change, back in the app …).
 * - **Back-off:** a pass that did not reach the server (offline, refused, files not written) doubles the interval up to
 *   [MAX_MS]; a pass that reached it resets it to [BASE_MS] ([passEnded]).
 * - One pass at a time: the driver skips while a pass runs or waits ([com.onpath.app.plansync.PlanSync.busy]).
 */
class SyncPoll(private val baseMs: Long = BASE_MS, private val maxMs: Long = MAX_MS) {
    /** The current interval (ms): [baseMs], doubled after each failed pass up to [maxMs]. */
    var intervalMs: Long = baseMs
        private set

    /** When the last pass was asked for or ended (ms; 0 = none yet – the first poll is due at once). */
    private var lastMs = 0L

    /** A pass was asked for at [nowMs] (by the poll or another trigger): the next poll counts from here. */
    fun requested(nowMs: Long) { lastMs = nowMs }

    /** A pass ended at [nowMs]; [ok] = reached the server (true), did not (false), nothing ran (null: no change). */
    fun passEnded(nowMs: Long, ok: Boolean?) {
        if (ok == null) return
        lastMs = nowMs
        intervalMs = if (ok) baseMs else (intervalMs * 2).coerceAtMost(maxMs)
    }

    /** How long until the next poll (0 = due now). */
    fun waitMs(nowMs: Long): Long = (lastMs + intervalMs - nowMs).coerceIn(0, intervalMs)

    companion object {
        const val BASE_MS = 60_000L
        const val MAX_MS = 10 * 60_000L
        /** A pass runs or waits when the poll is due: look again after this. */
        const val BUSY_RECHECK_MS = 5_000L

        /** Poll at all: app on screen, paired, no tour recording, and Plan mode / My routes / Settings → Browser sync shown. */
        fun wanted(visible: Boolean, paired: Boolean, recording: Boolean, routesScreen: Boolean): Boolean =
            visible && paired && !recording && routesScreen

        /**
         * The screen shown is one where routes matter: with Settings open only its Browser sync page; else My routes or
         * Plan mode (Navigate without My routes is not).
         */
        fun routesScreen(planMode: Boolean, routesOpen: Boolean, settingsOpen: Boolean, syncPage: Boolean): Boolean =
            if (settingsOpen) syncPage else routesOpen || planMode

        /** What a pass's report means for the back-off: reached the server (true), did not (false), nothing ran (null). */
        fun ok(r: PlanSync.Report): Boolean? = when {
            r.error -> false
            r.result == null -> null
            else -> when (r.result.outcome) {
                is SyncRound.Outcome.Done, SyncRound.Outcome.Ended -> true
                SyncRound.Outcome.Offline, is SyncRound.Outcome.Refused -> false
                null -> null
            }
        }
    }
}
