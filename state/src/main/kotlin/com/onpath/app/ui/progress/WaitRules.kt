package com.onpath.app.ui.progress

import com.onpath.app.data.ProgressMemory
import com.onpath.app.debug.Problems
import com.onpath.app.domain.OneBar
import com.onpath.app.domain.RouteShares
import com.onpath.app.domain.TimeLeft
import com.onpath.app.domain.WaitStep
import com.onpath.app.domain.WaitSteps
import com.onpath.app.routing.PlanPointRow
import com.onpath.strings.*

/** The route's wait window: the ONE bar, the legs + „prüfen“ as steps (at most 3 shown), the time left. */
data class RouteWaitUi(val percent: Int, val steps: List<WaitStep>, val secondsLeft: Int?, val late: Boolean = false) {
    val window: WaitWindowUi get() = WaitWindowUi(WaitSign.ROUTE, Txt.s(Res.string.wait_title_route), percent, steps,
        if (late) TimeLeft.LATE else secondsLeft?.let(TimeLeft::text))
}

/**
 * Route calculation is form A too (owner 2026-11-19 „besser eher zu A als den Benutzer zu verunsichern“): the legs the
 * router works on are the steps – each the same share, its own progress the router's search ([com.onpath.app.domain.WaitBar])
 * –, „prüfen“ (times, warnings, what reaches a stop) the last step with its learned share ([RouteShares]). The ONE bar
 * never goes back and never stands ([OneBar]). Fed about every second while the plan screen is up, also when the run is
 * quick and no window shows: each run's measured times teach the check's share. Pure (time passed in), unit-tested.
 */
class RouteWait(private val memory: ProgressMemory) {
    private var bar = OneBar()
    private var startMs = -1L
    private var checkFromMs = -1L
    private var sawShare = false
    private var gen: Int? = null

    /**
     * [routing]: a run is on; [progress] = legs done / legs to route (null = not known yet); [share] = the legs' share
     * 0..1 ([com.onpath.app.domain.WaitBar], null = not known, or every leg routed); [labels] = the legs' names when known.
     * [gen] = the run's generation (RouteRun.gen): a new run while one is on (an edit, an activity change, routing data
     * arriving – routing stays on) starts afresh, the old one teaches nothing. Null = no run.
     */
    fun update(routing: Boolean, progress: Pair<Int, Int>?, share: Float?, labels: List<String>?, now: Long, gen: Int): RouteWaitUi? {
        if (!routing) { finish(now); return null }
        if (this.gen != gen) { reset(); this.gen = gen }
        if (startMs < 0) startMs = now
        if (share != null) sawShare = true
        val total = (progress?.second ?: 0).coerceAtLeast(1)
        val done = (progress?.first ?: 0).coerceIn(0, total)
        val checking = (progress != null && progress.second > 0 && progress.first >= progress.second) || (share == null && sawShare)
        if (checking && checkFromMs < 0) checkFromMs = now
        val check = memory.routeCheck
        val legs = 1 - check
        val legsFrac = if (checking) 1.0 else (share?.toDouble() ?: (done.toDouble() / total)).coerceIn(0.0, 1.0)
        val raw = legs * legsFrac
        val stepEnd = if (checking) 1.0 else legs * (done + 1) / total
        // the step's own time left: the legs' pace so far for the rest of this leg; the check by its share of the legs' time
        val gone = now - startMs
        val stepLeftMs = if (checking) {
            val legsMs = (checkFromMs - startMs).coerceAtLeast(0)
            val expected = legsMs * check / legs
            maxOf(expected - (now - checkFromMs), expected / 2).toLong()
        } else if (legsFrac > 0.02) ((gone / legsFrac) * ((done + 1.0) / total - legsFrac)).toLong().coerceAtLeast(0) else null
        val pct = bar.next(raw, stepEnd, stepLeftMs, now, stepStart = if (checking) legs else legs * done / total)
        val names = labels?.takeIf { it.size == total } ?: (1..total).map { Txt.s(Res.string.wait_leg, it) }
        val rows = names.map { it to null } + ((if (checking) Txt.s(Res.string.wait_step_checking) else Txt.s(Res.string.wait_step_check)) to null)
        // a time only while the router measures (its share within the legs, or the check with its learned share): a leg it
        // reports nothing of promises none – the bar creeps on, the time is not read off the creep (owner 2026-11-19,
        // „noch etwa 5 Sekunden“ and then minutes); the planner's WebWait has the same rule
        val left = if (checking || share != null) TimeLeft.seconds(bar.measured, gone, null) else null
        return RouteWaitUi(pct, WaitSteps.of(rows, if (checking) total else done), left, bar.late)
    }

    /** The run ended: its legs' and check's times teach the check's share; the next run starts afresh. */
    private fun finish(now: Long) {
        if (startMs < 0) return
        if (checkFromMs > startMs) try {
            memory.routeCheck = RouteShares.learn(memory.routeCheck, checkFromMs - startMs, now - checkFromMs)
        } catch (e: Exception) { Problems.warn("routing", "Route wait: the check's share not learned", e) }
        reset()
    }

    private fun reset() { bar = OneBar(); startMs = -1L; checkFromMs = -1L; sawShare = false }

    companion object {
        /**
         * The legs' names "Algund → Leiter Alm" from the Points list [rows] when the router works on every leg of the
         * [points] (none from the cache: [total] = points − 1); else null (they are counted: "Abschnitt 2").
         */
        fun legLabels(rows: List<PlanPointRow>, points: Int, total: Int?): List<String>? {
            if (total == null || total != points - 1 || points < 2) return null
            val byIndex = rows.associateBy { it.planIndex }
            // a round trip's closing point is no row: its name is the start's
            fun name(i: Int) = (byIndex[i] ?: if (i == points - 1) byIndex[0] else null)?.name?.takeIf { it.isNotBlank() }
            return (0 until total).map { i -> "${name(i) ?: return null} → ${name(i + 1) ?: return null}" }
        }
    }
}

/** What the wait window waits for – its sign: the map (cloud), a route (route sign), offline (cloud crossed), failed (⚠). */
enum class WaitSign { MAP, ROUTE, OFFLINE, FAILED }

/**
 * Form A ("ich muss warten", owner 2026-11-19): one window over the map, always the same frame – sign + title, ONE bar
 * 0–100 over all steps with its number beside it (never back, never standing – [com.onpath.app.domain.OneBar]), at most
 * 3 step rows with ticks ([WaitSteps]), the time left ("noch etwa 20 Sekunden") or why it waits ([line]), the buttons
 * (Cancel). [paused]: the bar stands grey (offline, waiting for Wi-Fi); [bar] false: no bar (failed).
 */
data class WaitWindowUi(
    val sign: WaitSign,
    val title: String,
    /** 0..100 over all steps. */
    val percent: Int,
    val steps: List<WaitStep>,
    /** "noch etwa 20 Sekunden", or why it waits / what failed; null = nothing (not estimable yet). */
    val line: String?,
    val paused: Boolean = false,
    val bar: Boolean = true,
)

/** A button of the window: a verb ("Abbrechen", "Erneut versuchen"). */
class WaitButton(val label: String, val onClick: () -> Unit)

/** One load in the details: its name and where it stands ("fertig", "lädt", "wird vorbereitet", "fast fertig"). */
data class BesideRow(val name: String, val state: String)
