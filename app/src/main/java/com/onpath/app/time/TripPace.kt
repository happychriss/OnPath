package com.onpath.app.time

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Learning on the trip (docs/plan-time-estimate.md §4.2), owned by the recording service and fed with every good fix.
 * Pure and serialisable (JSON), so it survives a service restart and can be replayed on the JVM.
 *
 * - **Moving time vs. breaks:** [BreakDetector] on the recording's active clock (pauses excluded).
 * - **What is learned:** while you are on the followed line and it moves forward, every ~50 m of progress adds
 *   (predicted seconds, real moving seconds) to the group of that piece (down / flat / up). Elevation comes from the
 *   line (DEM), never from GPS. Off-route stretches, jumps and implausible pieces are not learned.
 * - **Factors:** shrunk towards the plan (as if [TAU_S] of "normal" walking were already done), groups not walked yet
 *   take half of the trip factor ([RHO]); clamped to [MIN_F]…[MAX_F].
 * - **Calm display:** a new estimate is shown every 100 m of progress (not more often than every 10 s) and moves the
 *   arrival clock by at most 1 min + 2 % of the remaining time. Standing still, "to go" stays and the arrival only
 *   moves later as time passes (checked every 30 s). A new line or new settings reset that limit once.
 *
 * Predictions are `model (Normal) × prior`, where prior = pace setting × what was learned on earlier tours.
 */
class TripPace(prior: Factors = Factors.ONE, private val allowance: Double = TimeEstimate.BREAK_ALLOWANCE) {

    var prior: Factors = prior
        private set
    private var times: RouteTimes? = null
    private var breaks = BreakDetector()
    /** Observed per group: real moving seconds / predicted seconds (with prior). */
    private val sumA = DoubleArray(PaceModel.GROUPS)
    private val sumP = DoubleArray(PaceModel.GROUPS)
    private var baseAlong = Double.NaN
    private var baseMoving = 0.0
    /** Last known progress on the line (m). */
    private var along = 0.0
    private var activeS = 0.0
    // calm display
    private var shownArrivalMs: Long? = null
    private var shownToGoS: Double? = null
    private var lastShowWall = 0L
    private var lastShowAlong = 0.0
    /** "To go" shown and the raw estimate at the last real update (between updates "to go" follows the progress). */
    private var showToGoS: Double? = null
    private var showRawS = 0.0
    private var resetCalm = true
    /** The last fix was matched to the line ([along] is where the user is). */
    private var matched = true
    private var lastFixS = 0.0
    /** Recent fixes (active s, lat, lon, along or NaN) over [STALE_S]: "moving but the estimate cannot follow". */
    private val history = ArrayDeque<DoubleArray>()

    /**
     * New line to follow (start, re-route, plan edit) or none. Learned sums stay; the display may jump once.
     * [alongM] = where the user is on it; null = not known yet: "not on route" until a fix is matched (never "the whole
     * line from its start").
     */
    fun setLine(t: RouteTimes?, alongM: Double?) {
        times = t?.takeIf { it.lengthM > 0 }
        along = (alongM ?: 0.0).coerceAtLeast(0.0)
        matched = alongM != null
        baseAlong = Double.NaN
        resetCalm = true
        history.clear()
        if (times == null || !matched) { shownArrivalMs = null; shownToGoS = null; showToGoS = null }
    }

    /** Pace setting or stored factors changed. */
    fun setPrior(f: Factors) {
        if (f == prior) return
        // keep what was learned meaningful: observed "predicted" seconds are re-scaled to the new prior
        for (g in 0 until PaceModel.GROUPS) if (prior[g] > 0) sumP[g] *= f[g] / prior[g]
        prior = f
        resetCalm = true
    }

    /** Pause: an ongoing break ends; the piece in progress is not learned. */
    fun onPause() { breaks.reset(); baseAlong = Double.NaN; lastFixS = activeS; history.clear() }

    fun movingS(): Double = max(0.0, activeS - breaks.breakS())

    /**
     * One good fix. [activeS] = recording time without pauses; [alongM] = position on the followed line (null = not on
     * it / not known), [onRoute] = progress may be learned (on the line, walking it forwards).
     */
    fun onFix(activeS: Double, wallMs: Long, lat: Double, lon: Double, alongM: Double?, onRoute: Boolean) {
        this.activeS = max(this.activeS, activeS)
        lastFixS = this.activeS
        breaks.onFix(this.activeS, lat, lon, alongM.takeIf { onRoute && times != null })
        val t = times
        if (t != null && alongM != null) {
            val a = alongM.coerceIn(0.0, t.lengthM)
            // matched again after "not on route", or a jump (other part of the line, rejoined ahead): a real change
            if (!matched || abs(a - along) > JUMP_RESET_M) resetCalm = true
            along = a
            matched = true
            if (onRoute) observe(t) else baseAlong = Double.NaN
        } else {
            matched = false
            baseAlong = Double.NaN
        }
        history.addLast(doubleArrayOf(this.activeS, lat, lon, if (t != null && alongM != null) along else Double.NaN))
        while (history.size > 2 && this.activeS - history[1][0] >= STALE_S) history.removeFirst()
        refresh(wallMs)
    }

    /** Clock tick without a fix (breaks, GPS lost): keeps the display moving in time. */
    fun tick(activeS: Double, wallMs: Long) {
        this.activeS = max(this.activeS, activeS)
        refresh(wallMs)
    }

    private fun observe(t: RouteTimes) {
        val moving = movingS()
        if (baseAlong.isNaN() || along < baseAlong - BACK_M) { baseAlong = along; baseMoving = moving; return }
        val d = along - baseAlong
        if (d < OBSERVE_M) return
        val a = moving - baseMoving
        val p = t.between(baseAlong, along)
        for (g in 0 until PaceModel.GROUPS) p[g] *= prior[g]
        val sp = p.sum()
        baseAlong = along; baseMoving = moving
        if (d > MAX_JUMP_M || sp <= 0 || a <= 0) return // GPS jump, a lift, nothing to compare
        val ratio = a / sp
        if (ratio < MIN_RATIO || ratio > MAX_RATIO) return
        for (g in 0 until PaceModel.GROUPS) { sumA[g] += a * p[g] / sp; sumP[g] += p[g] }
    }

    /** Trip factor over all groups (relative to the prior). */
    fun tripFactor(): Double = ((TAU_S + sumA.sum()) / (TAU_S + sumP.sum())).coerceIn(MIN_F, MAX_F)

    /** Learned factors per group (relative to the prior); 1 = as planned. */
    fun factors(): Factors {
        val all = tripFactor()
        val start = 1 + RHO * (all - 1)
        return Factors.of(DoubleArray(PaceModel.GROUPS) { g -> ((TAU_G * start + sumA[g]) / (TAU_G + sumP[g])).coerceIn(MIN_F, MAX_F) })
    }

    /** Predicted seconds already compared with real moving time (s). */
    fun observedS(): Double = sumP.sum()

    /** Raw moving time from the current progress to the end (s); null without a line. */
    fun rawToGoS(): Double? = times?.remainingFrom(along, prior * factors())

    private fun refresh(wallMs: Long) {
        val raw = rawToGoS() ?: run { shownArrivalMs = null; shownToGoS = null; showToGoS = null; return }
        if (!matched && shownToGoS == null) return // nothing known yet on this line: "not on route", no number
        val target = TimeEstimate.arrivalMs(wallMs, raw, allowance)
        val shown = shownArrivalMs
        val back = along < lastShowAlong - SHOW_EVERY_M
        val moved = along - lastShowAlong >= SHOW_EVERY_M || back
        if (matched && (resetCalm || shown == null || (moved && wallMs - lastShowWall >= MIN_SHOW_MS))) {
            // a real update: the arrival moves at most 1 min + 2 % of the time to go towards the new estimate;
            // walking back along the route is no estimate noise – the time to go grows at once (owner rule 2)
            val next = if (resetCalm || shown == null || back) target else {
                val lim = ((CALM_S + CALM_SHARE * raw) * 1000).roundToLong()
                shown + (target - shown).coerceIn(-lim, lim)
            }
            shownArrivalMs = next
            shownToGoS = max(0.0, (next - wallMs) / 1000.0 / (1 + allowance))
            showToGoS = shownToGoS; showRawS = raw
            lastShowWall = wallMs; lastShowAlong = along; resetCalm = false
        } else if (wallMs - lastShowWall >= SHOW_EVERY_MS) {
            // between updates: "to go" follows the progress made (at the shown pace) and stays while standing; the
            // arrival only moves later once the clock eats into it (a break, slower than shown) – it does not creep
            // later while walking as shown (field test 2026-10-19: 12:45 → 13:15 at a steady pace)
            val base = showToGoS ?: shownToGoS ?: return
            val toGo = if (matched && showRawS > 0) base * (raw / showRawS).coerceIn(0.0, 1.0) else shownToGoS ?: base
            shownToGoS = toGo
            shownArrivalMs = max(shown ?: 0L, TimeEstimate.arrivalMs(wallMs, toGo, allowance))
            lastShowWall = wallMs
        }
    }

    /** Unrounded "to go" on the screen (s); replay metrics. */
    fun shownToGoS(): Double? = shownToGoS

    /** Moving time from [alongM] to the end with the factors learned so far (s); replay metrics. */
    fun remainingAt(alongM: Double): Double = times?.remainingFrom(alongM, prior * factors()) ?: 0.0

    /**
     * Whether the shown estimate can be trusted now (docs/plan-time-estimate.md, owner rules 6 and 8): not on the
     * route; no fix for [STALE_S] (not in a break); or moving for [STALE_S] while the progress along the route stays.
     */
    fun estimateState(): Pair<EstimateState, PauseReason?> {
        if (times == null) return EstimateState.OK to null
        if (activeS - lastFixS >= STALE_S && !breaks.inBreak) return EstimateState.PAUSED to PauseReason.NO_GPS
        if (!matched) return EstimateState.NOT_ON_ROUTE to null
        if (stuck()) return EstimateState.PAUSED to PauseReason.NO_PROGRESS
        return EstimateState.OK to null
    }

    /**
     * Moved at least [STUCK_MOVE_M] during the last [STALE_S] on the line, but the progress hardly changed. "Moved" =
     * the mean position of the fixes in the first [STUCK_AVG_S] of the window vs. that of the last [STUCK_AVG_S] – GPS
     * jitter and single wild fixes while standing are not movement (field 2026-10-22, lift top station).
     */
    private fun stuck(): Boolean {
        if (history.size < 3) return false
        val o = history.first(); val n = history.last()
        if (n[0] - o[0] < STALE_S) return false
        if (history.any { it[3].isNaN() }) return false
        val start = history.filter { it[0] <= o[0] + STUCK_AVG_S }
        val end = history.filter { it[0] >= n[0] - STUCK_AVG_S }
        val moved = com.onpath.app.data.Geo.distance(start.sumOf { it[1] } / start.size, start.sumOf { it[2] } / start.size,
            end.sumOf { it[1] } / end.size, end.sumOf { it[2] } / end.size)
        if (moved < STUCK_MOVE_M) return false
        return abs(n[3] - o[3]) < STUCK_ALONG_M
    }

    /** What the screen shows now. */
    fun snapshot(): TripTime {
        val toGo = shownToGoS
        val (st, why) = estimateState()
        return TripTime(
            movingS = movingS(), breakS = breaks.breakS(),
            toGoS = toGo?.let { TimeEstimate.round(it) }, arrivalMs = shownArrivalMs.takeIf { toGo != null },
            learned = factors().takeIf { observedS() >= SHOW_LEARNED_S },
            state = st, pausedBecause = why,
        )
    }

    /** End of the tour: moving time, breaks and "planned vs. needed" on the followed route. */
    fun tourTimes(elapsedS: Double): TourTimes {
        val b = breaks.breakS().coerceIn(0.0, max(0.0, elapsedS))
        val p = sumP.sum(); val a = sumA.sum()
        return TourTimes(max(0.0, elapsedS) - b, b, p.takeIf { it > 0 }, a.takeIf { p > 0 })
    }

    /** For learning across tours: observed seconds per group (predicted with prior) and real moving seconds. */
    fun observed(): Pair<DoubleArray, DoubleArray> = sumP.copyOf() to sumA.copyOf()

    fun toJson(): JSONObject = JSONObject()
        .put("prior", JSONArray(prior.toArray().toList()))
        .put("a", JSONArray(sumA.toList())).put("p", JSONArray(sumP.toList()))
        .put("active", activeS).put("along", along)
        .put("breaks", breaks.toJson())

    companion object {
        /** Prior weight of the trip factor: 20 min of "normal" walking. */
        const val TAU_S = 1200.0
        /** Prior weight per group: 15 min. */
        const val TAU_G = 900.0
        /** Share of the trip factor a group starts with (being fast uphill says something, not all, about downhill). */
        const val RHO = 0.5
        const val MIN_F = 0.5
        const val MAX_F = 2.0
        /** Learn per this much progress (m). */
        const val OBSERVE_M = 50.0
        /** Progress jumps longer than this are not learned (GPS jump, lift, shortcut). */
        const val MAX_JUMP_M = 400.0
        /** Going back on the line more than this restarts the piece. */
        const val BACK_M = 30.0
        /** Pieces much faster or slower than predicted are not learned (car, lift, a stop just under 2 min). */
        const val MIN_RATIO = 0.33
        const val MAX_RATIO = 3.0
        const val SHOW_EVERY_MS = 30_000L
        /** Fast riders: not more often than this, even every 100 m. */
        const val MIN_SHOW_MS = 10_000L
        const val SHOW_EVERY_M = 100.0
        const val CALM_S = 60.0
        const val CALM_SHARE = 0.02
        /** Progress changing by more than this between two fixes is a jump (other part of the line): shown at once. */
        const val JUMP_RESET_M = 300.0
        /** No fix / no progress while moving for this long (active s): "estimate paused" (owner rule 8). */
        const val STALE_S = 120.0
        const val STUCK_MOVE_M = 60.0
        const val STUCK_ALONG_M = 20.0
        /** Fixes averaged at each end of the window for "moved" (s). */
        const val STUCK_AVG_S = 20.0
        /** The learned pace is shown after this much comparison (s). */
        const val SHOW_LEARNED_S = 1200.0

        fun fromJson(o: JSONObject?, fallbackPrior: Factors = Factors.ONE): TripPace {
            if (o == null) return TripPace(fallbackPrior)
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> DoubleArray(PaceModel.GROUPS) { a.optDouble(it, 0.0).takeIf { v -> !v.isNaN() && v >= 0 } ?: 0.0 } }
            val prior = arr("prior")?.takeIf { p -> p.all { it > 0 } }?.let { Factors.of(it) } ?: fallbackPrior
            val t = TripPace(prior)
            arr("a")?.copyInto(t.sumA); arr("p")?.copyInto(t.sumP)
            t.activeS = o.optDouble("active", 0.0).takeIf { !it.isNaN() } ?: 0.0
            t.along = o.optDouble("along", 0.0).takeIf { !it.isNaN() } ?: 0.0
            t.lastFixS = t.activeS
            t.breaks = BreakDetector.fromJson(o.optJSONObject("breaks"))
            return t
        }
    }
}
