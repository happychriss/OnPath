package com.onpath.app.plansync

import com.onpath.app.sync.SyncRound

/**
 * Sync while the app stays open (owner 2026-10-30: "fully automatic, like WhatsApp Web"): a route saved in the browser
 * arrives without leaving the app. Pure rule, the clock comes from outside ([com.onpath.app.ui.AppViewModel] drives it).
 *
 * - **When:** the app is on screen, paired, no tour is recording, and My routes or Settings → Browser sync is shown
 *   ([wanted]; owner 2026-10-30: not on the Plan map – the pass on open covers that). The app in the background polls
 *   nothing.
 * - **How often:** every [BASE_MS] after the last pass (any pass counts: after a change, back in the app …).
 * - **Back-off:** a pass that did not reach the server (offline, refused, files not written) doubles the interval up to
 *   [MAX_MS]; a pass that reached it resets it to [BASE_MS] ([passEnded]).
 * - One pass at a time: the driver skips while a pass runs or waits ([com.onpath.app.plansync.PlanSync.busy]).
 * - **Right away on open** (owner 2026-10-30, "can it pull when My routes is opened?"): My routes opens, its Planned tab
 *   is chosen or Settings → Browser sync opens ([opened]) → a pass at once ([openDue]) – not if one ran, runs or waits
 *   within [OPEN_DEBOUNCE_MS]; never during a tour. The poll then counts from there.
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

    /**
     * A screen where routes matter was just opened ([opened]): pass at once? Paired, no tour, no pass running or
     * waiting ([busy]) and none asked for / ended within [OPEN_DEBOUNCE_MS].
     */
    fun openDue(nowMs: Long, paired: Boolean, recording: Boolean, busy: Boolean): Boolean =
        paired && !recording && !busy && (lastMs == 0L || nowMs - lastMs >= OPEN_DEBOUNCE_MS)

    /** What is shown, for the pass on open ([opened]). */
    enum class Shown { OTHER, ROUTES_PLANNED, ROUTES_DONE, SYNC_PAGE }

    companion object {
        const val BASE_MS = 60_000L
        const val MAX_MS = 10 * 60_000L
        /** A pass runs or waits when the poll is due: look again after this. */
        const val BUSY_RECHECK_MS = 5_000L
        /** No pass on open when one ran or was asked for this recently. */
        const val OPEN_DEBOUNCE_MS = 10_000L

        /** What is shown: Settings over My routes; app not on screen = [Shown.OTHER]. */
        fun shown(visible: Boolean, routesOpen: Boolean, plannedTab: Boolean, settingsOpen: Boolean, syncPage: Boolean): Shown = when {
            !visible -> Shown.OTHER
            settingsOpen -> if (syncPage) Shown.SYNC_PAGE else Shown.OTHER
            routesOpen -> if (plannedTab) Shown.ROUTES_PLANNED else Shown.ROUTES_DONE
            else -> Shown.OTHER
        }

        /**
         * [before] → [now]: the reason for a pass right away, or null. My routes opened (either tab), its Planned tab
         * chosen, Settings → Browser sync opened; not for Planned → Done or leaving.
         */
        fun opened(before: Shown, now: Shown): String? = when {
            now == before -> null
            now == Shown.SYNC_PAGE -> "browser sync opened"
            now == Shown.ROUTES_PLANNED && before == Shown.ROUTES_DONE -> "planned tab"
            (now == Shown.ROUTES_PLANNED || now == Shown.ROUTES_DONE) && before != Shown.ROUTES_PLANNED -> "my routes opened"
            else -> null
        }

        /** Poll at all: app on screen, paired, no tour recording, and My routes / Settings → Browser sync shown. */
        fun wanted(visible: Boolean, paired: Boolean, recording: Boolean, routesScreen: Boolean): Boolean =
            visible && paired && !recording && routesScreen

        /**
         * The screen shown is one where routes matter: with Settings open only its Browser sync page; else My routes
         * (owner 2026-10-30: not the Plan map, not Navigate).
         */
        fun routesScreen(routesOpen: Boolean, settingsOpen: Boolean, syncPage: Boolean): Boolean =
            if (settingsOpen) syncPage else routesOpen

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
