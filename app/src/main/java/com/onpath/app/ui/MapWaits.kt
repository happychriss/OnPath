package com.onpath.app.ui

import com.onpath.app.debug.Problems
import com.onpath.app.domain.ActionPercent
import com.onpath.app.domain.LoadStep
import com.onpath.app.domain.StepShares

/**
 * The wait window's waits, one per job (owner 2026-11-19: never back, never standing): the same wait goes on while the
 * window is away for a moment (an offline blip, all areas waiting for Wi-Fi, the view showing the detailed map) and
 * comes back for the same job – its bar where it was. When the window goes while the loads go on, the sign beside takes
 * over from the window's percent ([ActionPercent.seed] – A turns into B, no step back). A failing update shows the last
 * picture (reported) – the window never vanishes while the map still loads. A wait that ends with everything there
 * teaches the learned shares ([learn], only without a pause). Pure; main thread.
 */
class MapWaits(
    private val shares: () -> StepShares,
    private val learn: (Map<LoadStep, Long>) -> Unit,
    private val action: ActionPercent = BackgroundAction.shared,
) {
    private var wait: MapWait? = null
    private var key: Any? = null
    private var last: MapLoadUi? = null
    private var hiddenAt = -1L

    /**
     * The window for [key] (what is waited for: the modal's regions, the first map's job; null = no window now – the wait
     * is kept for its key) at [now], [compute] updating its wait. Null = no window (everything there, or none).
     */
    fun tick(key: Any?, now: Long, compute: (MapWait) -> MapLoadUi?): MapLoadUi? {
        if (key == null) { hide(now); return null }
        // another job, or the same one after a long time away: a new wait
        if (key != this.key || (hiddenAt >= 0 && now - hiddenAt > FORGET_MS)) drop()
        this.key = key; hiddenAt = -1L
        val w = wait ?: MapWait(shares()).also { wait = it }
        val ui = try { compute(w) } catch (e: Exception) {
            Problems.warn("cells", "Wait window not updated; it shows its last picture", e)
            return last
        }
        if (ui == null) { end(w, now); return null }
        last = ui
        return ui
    }

    /** The window goes for now (its key may come back): the sign beside goes on from its percent. */
    fun hide(now: Long) {
        last?.takeIf { it.percent < 100 }?.let { action.seed(it.percent) }
        last = null
        if (wait != null && hiddenAt < 0) hiddenAt = now
    }

    /** Cancel / Close: the wait is over without teaching anything. */
    fun drop() { wait = null; key = null; last = null; hiddenAt = -1L }

    private fun end(w: MapWait, now: Long) {
        com.onpath.app.ui.progress.WaitChain.mapDoneAt = now // the route's window may follow („Karte geladen ✓“)
        if (w.learnable) try { learn(w.durations()) } catch (e: Exception) {
            Problems.warn("cells", "Wait window step times not learned", e)
        }
        drop()
    }

    companion object {
        /** A wait away this long is not resumed (its bar would show an old state). */
        const val FORGET_MS = 10 * 60_000L
    }
}
