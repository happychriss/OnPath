package com.onpath.app.domain

import com.onpath.app.cells.CellState
import com.onpath.strings.*
import kotlin.math.exp

/*
 * One progress design, two forms (owner 2026-11-19: „A, aber nie mehr als 3 Häkchen; B: einfach nur Prozent, muss aber
 * passen für die gesamte Aktion“). The pure math of both, Android-free (the browser planner compiles it too):
 *  - A, the wait window: ONE bar 0–100 over all steps ([OneBar]) – each step owns its share of time ([StepShares],
 *    learned on this phone), never back, never standing –, at most 3 step rows ([WaitSteps]), the time left ([TimeLeft]).
 *  - B, the sign beside: ONE percent over the whole action ([ActionPercent]: every load of it together), never back.
 */

/** The steps of a map load, in their order. */
enum class LoadStep { PREPARE, DOWNLOAD, JOIN }

/**
 * The share of time each step of a map load takes (sum 1). Start values prepare 30 / download 50 / join 20; then learned
 * from the loads on this phone ([learn]), kept inside [MIN]..[MAX] so one odd load never takes a step's room away.
 */
data class StepShares(val prepare: Double, val download: Double, val join: Double) {
    fun of(step: LoadStep): Double = when (step) {
        LoadStep.PREPARE -> prepare
        LoadStep.DOWNLOAD -> download
        LoadStep.JOIN -> join
    }

    /** The share of every step before [step] (where its part of the bar begins). */
    fun before(step: LoadStep): Double = LoadStep.entries.takeWhile { it != step }.sumOf { of(it) }

    /**
     * The shares after a load whose steps took [ms] (only the steps that really ran – a step skipped, e.g. the server
     * had the map ready, says nothing about its time): the measured split of the steps that ran, inside the share
     * they had together, blended in by [WEIGHT]. Bounded, sums to 1.
     */
    fun learn(ms: Map<LoadStep, Long>): StepShares {
        val ran = ms.filter { (_, v) -> v > 0 }
        val sum = ran.values.sum()
        if (ran.size < 2 || sum < MIN_LEARN_MS) return this
        val room = ran.keys.sumOf { of(it) }
        fun next(s: LoadStep) = ran[s]?.let { of(s) * (1 - WEIGHT) + room * it / sum * WEIGHT } ?: of(s)
        return bounded(next(LoadStep.PREPARE), next(LoadStep.DOWNLOAD), next(LoadStep.JOIN))
    }

    /** "v1:0.300,0.500,0.200" for storage ([decode]). */
    fun encode(): String = "v1:" + listOf(prepare, download, join).joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }

    companion object {
        val DEFAULT = StepShares(0.30, 0.50, 0.20)
        const val MIN = 0.05
        const val MAX = 0.80
        /** How much one load moves the learned shares. */
        const val WEIGHT = 0.3
        /** A load faster than this teaches nothing (all from caches). */
        const val MIN_LEARN_MS = 2_000L

        /**
         * Made to sum to 1 with each share inside [MIN]..[MAX]: the farthest out is set to its bound, the rest share
         * what is left in their proportion – again until all fit (the defaults when something is not a number).
         */
        fun bounded(p: Double, d: Double, j: Double): StepShares {
            if (listOf(p, d, j).any { it.isNaN() || it.isInfinite() }) return DEFAULT
            val v = doubleArrayOf(p, d, j).map { it.coerceAtLeast(0.0) }.toDoubleArray()
            if (v.sum() <= 0.0) return DEFAULT
            val fixed = BooleanArray(3)
            repeat(3) {
                val free = v.indices.filter { !fixed[it] }
                val room = 1.0 - v.indices.filter { fixed[it] }.sumOf { v[it] }
                val sum = free.sumOf { v[it] }
                for (i in free) v[i] = if (sum > 0) v[i] / sum * room else room / free.size
                val out = free.maxByOrNull { i -> maxOf(MIN - v[i], v[i] - MAX) } ?: return@repeat
                if (v[out] in MIN..MAX) return StepShares(v[0], v[1], v[2])
                v[out] = v[out].coerceIn(MIN, MAX); fixed[out] = true
            }
            return StepShares(v[0], v[1], v[2])
        }

        /** What [encode] wrote; [DEFAULT] for anything else (missing, damaged, another version) – never throws. */
        fun decode(s: String?): StepShares {
            val v = s?.takeIf { it.startsWith("v1:") }?.removePrefix("v1:")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            if (v == null || v.size != 3) return DEFAULT
            return bounded(v[0], v[1], v[2])
        }
    }
}

/**
 * The route's wait (form A, owner 2026-11-19 „Route berechnet ist auch A“): the legs and „prüfen“ as the steps – each leg
 * the same share, the check its learned share ([check], start [DEFAULT_CHECK], kept inside [MIN_CHECK]..[MAX_CHECK]).
 */
object RouteShares {
    const val DEFAULT_CHECK = 0.10
    const val MIN_CHECK = 0.03
    const val MAX_CHECK = 0.40

    /** The check's share after a run whose legs took [legsMs] and the check [checkMs]. */
    fun learn(check: Double, legsMs: Long, checkMs: Long): Double {
        if (legsMs + checkMs < StepShares.MIN_LEARN_MS || checkMs <= 0) return bounded(check)
        return bounded(check * (1 - StepShares.WEIGHT) + checkMs.toDouble() / (legsMs + checkMs) * StepShares.WEIGHT)
    }

    fun bounded(check: Double): Double = if (check.isNaN()) DEFAULT_CHECK else check.coerceIn(MIN_CHECK, MAX_CHECK)

    /** "r1:0.1000" for storage; [decode] gives [DEFAULT_CHECK] for anything else. */
    fun encode(check: Double): String = "r1:" + "%.4f".format(java.util.Locale.ROOT, bounded(check))
    fun decode(s: String?): Double = s?.takeIf { it.startsWith("r1:") }?.removePrefix("r1:")?.toDoubleOrNull()?.let(::bounded) ?: DEFAULT_CHECK

    /**
     * The route's share 0..1 from [done] legs of [total] (only the legs the router works on), the running leg's search
     * [leg] 0..1 and whether the check runs ([checking]: every leg is routed, the result is worked out).
     */
    fun fraction(done: Int, total: Int, leg: Double?, checking: Boolean, check: Double): Double {
        val legs = 1 - bounded(check)
        if (checking || (total > 0 && done >= total)) return legs
        if (total <= 0) return 0.0
        return legs * (done.coerceIn(0, total) + (leg ?: 0.0).coerceIn(0.0, 1.0)) / total
    }
}

/** Where a step row of the wait window stands. */
enum class WaitStepState { DONE, NOW, LATER }

/**
 * One row under the bar: "✓ vorbereitet", "● lädt Karte + Routendaten", "○ einfügen" – or one leg of a route. [short]:
 * the label at a large font when [label] would not fit one line ("lädt" for "lädt Karte + Routendaten").
 */
data class WaitStep(val label: String, val state: WaitStepState, val short: String? = null)

/** Never more than 3 rows (owner 2026-11-19, „nie mehr als 3 Häkchen“). Pure. */
object WaitSteps {
    const val MAX = 3

    /** With more steps (a route's legs + the check) a window of 3 moves along: the last done, the running one, the next. */
    fun shown(steps: List<WaitStep>): List<WaitStep> {
        if (steps.size <= MAX) return steps
        val now = steps.indexOfFirst { it.state != WaitStepState.DONE }.let { if (it < 0) steps.lastIndex else it }
        val from = (now - 1).coerceIn(0, steps.size - MAX)
        return steps.subList(from, from + MAX)
    }

    /** The rows of [labels] with the one at [now] running (those before done, after later). */
    fun of(labels: List<Pair<String, String?>>, now: Int): List<WaitStep> = labels.mapIndexed { i, (l, s) ->
        WaitStep(l, when { i < now -> WaitStepState.DONE; i == now -> WaitStepState.NOW; else -> WaitStepState.LATER }, s)
    }
}

/**
 * The ONE bar of the wait window (owner 2026-11-18/19): 0–100 over all steps, never back, never standing. Fed about
 * every second with what is measured – [raw] 0..1 over the whole wait (each step its share × its own progress) – and
 * where the running step's part of the bar ends ([stepEnd], with what later steps already did). While the measurement
 * moves, the bar follows it. When the number stood [STALL_MS], it creeps on by time towards the running step's end:
 * [stepLeftMs] (the step's own time left, when known) sets the pace, else [DEFAULT_TAU_MS]; the last step creeps only to
 * [FINAL_MARGIN] before the end, so whole percents are left for an overrun. A step that takes much longer than its
 * share borrows from the rest of the bar, slower the longer the wait is ([late]: "dauert etwas länger…") – never 100
 * before everything is there. A gap between two calls longer than [MAX_GAP_MS] (the screen was off, the poll stopped)
 * moves nothing. One per wait; not thread-safe.
 */
class OneBar {
    private var shown = 0.0
    private var lastMs = -1L
    private var activeMs = 0L
    private var lastPct = -1
    private var pctAt = 0L

    /** The running step took longer than its share: the bar borrows from the rest – the time line says so. */
    var late = false
        private set

    /**
     * The bar 0..100 at [nowMs] (any steady clock). [finished]: everything is there – 100. [paused]: the wait stands
     * (offline, waiting for Wi-Fi, failed) – the bar stands too and says so in grey; no creep.
     */
    fun next(raw: Double, stepEnd: Double, stepLeftMs: Long?, nowMs: Long, finished: Boolean = false, paused: Boolean = false,
             stepStart: Double = stepEnd - 0.1): Int {
        if (finished) { shown = 1.0; measured = 1.0; lastMs = nowMs; late = false; return 100 }
        val gap = if (lastMs < 0) 0L else (nowMs - lastMs).coerceAtLeast(0L)
        val dt = if (paused || gap > MAX_GAP_MS) 0L else gap
        if (lastMs < 0 || dt == 0L && gap > 0) pctAt = nowMs // a pause or a gap: the stand is counted afresh
        lastMs = nowMs
        activeMs += dt
        val end = stepEnd.coerceIn(0.0, 1.0)
        val cap = (end - MARGIN).coerceIn(0.0, END)
        // the last step creeps by time only half its part: the other half is left in whole percents for an overrun
        val creepCap = (if (end >= 1.0 - 1e-9) minOf(end - FINAL_MARGIN, stepStart + (end - stepStart) / 2) else end - MARGIN).coerceIn(0.0, END)
        // the measurement, inside the running step's part
        measured = maxOf(measured, raw.coerceIn(0.0, 1.0).coerceAtMost(cap))
        var next = maxOf(shown, measured)
        if ((next * 100).toInt() > lastPct) late = false
        // the number stood for a while: it creeps on towards the step's end; the step overran its share (no room left
        // there): one whole percent from the rest of the bar now and then – the longer the wait, the slower
        if (dt > 0 && (next * 100).toInt() <= lastPct && nowMs - pctAt >= STALL_MS) {
            val tau = (stepLeftMs ?: DEFAULT_TAU_MS).coerceIn(MIN_TAU_MS, MAX_TAU_MS).toDouble()
            if (next < creepCap) next += (creepCap - next) * (1 - exp(-dt / tau))
            if ((next * 100).toInt() <= lastPct && next >= creepCap - 0.005) {
                late = true
                if (nowMs - pctAt >= maxOf(BORROW_MIN_MS, activeMs / 5)) next = maxOf(next, (lastPct + 1) / 100.0 + 1e-6)
            }
        }
        shown = next.coerceAtMost(END)
        val pct = (shown * 100).toInt().coerceIn(0, 99)
        if (pct != lastPct) { lastPct = pct; pctAt = nowMs }
        return pct
    }

    /** The bar's share now, 0..1. */
    val share: Double get() = shown

    /**
     * What of [share] was measured (the creep by time and the borrowed percents not counted). A wait whose creep knows
     * nothing of the step (no step time, the route's legs) estimates its time left from this, not from [share]: a bar that
     * only creeps promises no time (owner 2026-11-19, „noch etwa 5 Sekunden“ and then minutes). A wait whose steps have
     * learned times (the round-trip search, the map's steps) keeps [share]: its creep runs at the step's own pace.
     */
    var measured = 0.0
        private set

    companion object {
        /** The bar stops this far before the running step's end until the step is really done. */
        const val MARGIN = 0.01
        /** The last step creeps by time at most to this far before the end (whole percents left for an overrun). */
        const val FINAL_MARGIN = 0.05
        /** Never further before everything is there. */
        const val END = 0.995
        /** The pace of the creep when the step's time left is unknown: 63 % of the way left in this time. */
        const val DEFAULT_TAU_MS = 30_000L
        const val MIN_TAU_MS = 3_000L
        const val MAX_TAU_MS = 30 * 60_000L
        /** An overrun borrows a whole percent at most this often (later: every fifth of the wait so far). */
        const val BORROW_MIN_MS = 8_000L
        /** The number stood this long: it creeps / borrows. */
        const val STALL_MS = 2_000L
        /** A longer gap between two calls (screen off, not polled) moves nothing and counts as no time. */
        const val MAX_GAP_MS = 5_000L
    }
}

/** "noch etwa 20 Sekunden" (owner 2026-11-19) – as soon as it can be estimated. Pure. */
object TimeLeft {
    /** Not before this much of the wait is behind (a guess from 1 % would jump about). */
    const val MIN_SHARE = 0.03
    const val MIN_ELAPSED_MS = 3_000L

    /**
     * Seconds left for a wait at [share] 0..1 after [elapsedMs]: the pace so far for the rest; never less than the
     * running step's own measured time left [stepLeftS] (the server's estimate, the bytes at the measured rate). Null =
     * not estimable yet.
     */
    fun seconds(share: Double, elapsedMs: Long, stepLeftS: Int? = null): Int? {
        if (share >= 1.0) return 0
        val pace = if (share >= MIN_SHARE && elapsedMs >= MIN_ELAPSED_MS) (elapsedMs / 1000.0 * (1 - share) / share).toInt() else null
        return listOfNotNull(pace, stepLeftS?.coerceAtLeast(0)).maxOrNull()
    }

    /** "dauert etwas länger…": the running step is past its own estimate ([OneBar.late]) – no frozen number. */
    val LATE: String get() = Txt.s(Res.string.wait_late)

    /** "noch etwa 20 Sekunden" / "noch etwa 1 Minute" / "noch etwa 3 Minuten": seconds in fives, minutes rounded. */
    fun text(s: Int): String = when {
        s < 55 -> Txt.s(Res.string.wait_left_seconds, ((s + 4) / 5 * 5).coerceAtLeast(5))
        s < 90 -> Txt.s(Res.string.wait_left_minute)
        else -> Txt.s(Res.string.wait_left_minutes, (s + 30) / 60)
    }
}

/**
 * The time left as the window says it (owner 2026-11-27: „etwa 5 s, dann länger, dann wieder 5 s – lieber 10 s drauf und
 * früher fertig als Hin und Her“): the first estimate plus [BUFFER_S], then it only counts down – a higher estimate later
 * never raises it, a lower one lowers it. Paused (offline, Wi-Fi) it stands. Run out while the wait goes on: [expired] –
 * the window says „dauert etwas länger…“. One per wait; pure (time passed in).
 */
class Countdown {
    companion object { const val BUFFER_S = 10.0 }
    private var shownS = -1.0
    private var atMs = -1L
    /** The countdown ran out and the wait goes on. */
    var expired = false
        private set

    /** [estimateS]: the step's / wait's own time left now (null = none yet); returns the seconds to show, or null. */
    fun next(estimateS: Int?, nowMs: Long, paused: Boolean = false): Int? {
        if (shownS < 0) {
            if (estimateS == null || paused) return null
            shownS = estimateS + BUFFER_S; atMs = nowMs
            return shownS.toInt()
        }
        if (!paused) shownS -= (nowMs - atMs).coerceAtLeast(0) / 1000.0
        atMs = nowMs
        if (estimateS != null && !paused) shownS = minOf(shownS, estimateS + BUFFER_S)
        if (shownS <= 0) { shownS = 0.0; expired = true }
        return shownS.toInt()
    }
}

/**
 * Where one map area stands, 0..1 over its own steps ([StepShares]): preparing = the server's own progress inside the
 * prepare share, downloading = its bytes inside the download share, joining = [joined] (the pieces that are in, 0..1)
 * inside the join share. On the phone = 1. Pure.
 */
object CellProgress {
    /** [prepared]: the prepare step's own progress 0..1 by the server's time left ([PrepareProgress]); null = its progress only. */
    fun fraction(s: CellState, shares: StepShares, joined: Double = 0.0, prepared: Double? = null): Double = when (s) {
        CellState.None, CellState.Requested, is CellState.Queued -> shares.prepare * (prepared ?: 0.0).coerceIn(0.0, 1.0)
        is CellState.Offline, is CellState.Failed -> shares.prepare * (prepared ?: 0.0).coerceIn(0.0, 1.0)
        is CellState.Building -> shares.prepare * maxOf(prepared ?: 0.0, s.progress).coerceIn(0.0, 1.0)
        is CellState.WaitingForWifi -> shares.prepare
        is CellState.Downloading -> shares.prepare + shares.download *
            (if (s.totalBytes > 0) (s.doneBytes.toDouble() / s.totalBytes).coerceIn(0.0, 1.0) else 0.0)
        CellState.Installing -> shares.prepare + shares.download + shares.join * joined.coerceIn(0.0, 1.0)
        CellState.Downloaded, is CellState.Ready -> 1.0
    }

    /** The step [s] is in (null = on the phone, or not loading). */
    fun step(s: CellState): LoadStep? = when (s) {
        CellState.None, CellState.Requested, is CellState.Queued, is CellState.Building, is CellState.Offline,
        is CellState.Failed -> LoadStep.PREPARE
        is CellState.WaitingForWifi, is CellState.Downloading -> LoadStep.DOWNLOAD
        CellState.Installing -> LoadStep.JOIN
        CellState.Downloaded, is CellState.Ready -> null
    }
}

/**
 * The prepare step's own progress, per load (owner 2026-11-19 „prepare = the server's own progress / time left“): the
 * time gone over the time gone + the server's time left (re-anchored with each new estimate, [defaultS] without one),
 * never back, never quite 1 before the step ends. The wait window and the sign beside use it alike. Thread-safe.
 */
class PrepareProgress(private val defaultS: Int = DEFAULT_S) {
    /** key → [anchored at, seconds left then, first seen, last seen] */
    private val anchors = HashMap<String, LongArray>()
    private val last = HashMap<String, CellState>()
    private val shown = HashMap<String, Double>()

    /** [key]'s prepare progress 0..1 at [now] in state [s]; null when [s] is no prepare state (offline / failed: held). */
    @Synchronized
    fun fraction(key: String, s: CellState, now: Long): Double? {
        val eta = when (s) {
            CellState.None, CellState.Requested -> null
            is CellState.Queued -> s.etaS
            is CellState.Building -> s.etaS
            is CellState.Offline, is CellState.Failed -> return shown[key]
            else -> return null
        }
        val a = anchors.getOrPut(key) { longArrayOf(now, (eta ?: defaultS).toLong(), now, now) }
        // a new estimate from the server re-anchors; without one the first runs down
        if (eta != null && s != last[key]) { a[0] = now; a[1] = eta.toLong() }
        a[3] = now // the server prepares on while the screen is off: its time is wall time
        last[key] = s
        val gone = (now - a[2]) / 1000.0
        val left = (a[1] - (now - a[0]) / 1000.0).coerceAtLeast(1.0)
        val f = maxOf(shown[key] ?: 0.0, (gone / (gone + left)).coerceIn(0.0, 0.99))
        shown[key] = f
        return f
    }

    /** The server's time left for [key] (seconds), null when it is not prepared. */
    @Synchronized
    fun leftS(key: String, s: CellState, now: Long): Int? {
        if (!(s == CellState.None || s == CellState.Requested || s is CellState.Queued || s is CellState.Building)) return null
        val a = anchors[key] ?: return null
        return (a[1] - (now - a[0]) / 1000.0).coerceAtLeast(0.0).toInt()
    }

    /** Only [keys] are still on their way: the others are forgotten. */
    @Synchronized
    fun keep(keys: Set<String>) {
        anchors.keys.retainAll(keys); last.keys.retainAll(keys); shown.keys.retainAll(keys)
    }

    companion object {
        /** Assumed preparing time when the server gives no estimate. */
        const val DEFAULT_S = 180
    }
}

/**
 * Form B's ONE number (owner 2026-11-19, „einfach nur Prozent, muss aber passen für die gesamte Aktion“): every load of
 * the action together – each item weighted by its size, an item that leaves counts as done – 0..100, never back. The
 * action ends when nothing loads ([update] with no items). Thread-safe (the sign and the notification share one).
 */
class ActionPercent {
    /** One load: [key] (stable), its weight (bytes; 0 = unknown, [UNKNOWN_WEIGHT]) and where it stands 0..1. */
    data class Item(val key: String, val weight: Long, val fraction: Double)

    private val items = LinkedHashMap<String, DoubleArray>() // key → [weight, fraction]
    private var held = 0
    /** The wait window handed over at this percent ([seed]); the rest of the action moves on from there. */
    private var floor = 0
    private var floorRaw = -1.0
    private var lastRaw = -1.0

    /**
     * The wait window (form A) closed while its loads go on (owner 2026-11-19 „A turns into B by itself“): the sign
     * goes on from the window's [percent] – never below it, and moving with the rest of the action from there.
     */
    @Synchronized
    fun seed(percent: Int) {
        if (percent <= held) return
        floor = percent.coerceIn(0, 99); floorRaw = lastRaw; held = floor
    }

    /** The action's percent now; null when nothing loads (the action is over and forgotten). */
    @Synchronized
    fun update(active: List<Item>): Int? {
        if (active.isEmpty()) { items.clear(); held = 0; floor = 0; floorRaw = -1.0; lastRaw = -1.0; return null }
        val now = active.associateBy { it.key }
        for ((k, v) in items) if (k !in now) v[1] = 1.0
        for (i in active) {
            val v = items.getOrPut(i.key) { DoubleArray(2) }
            v[0] = (if (i.weight > 0) i.weight else UNKNOWN_WEIGHT).toDouble()
            v[1] = maxOf(v[1], i.fraction.coerceIn(0.0, 1.0))
        }
        val total = items.values.sumOf { it[0] }
        val raw = if (total <= 0) 0.0 else items.values.sumOf { it[0] * it[1] } / total
        lastRaw = raw
        if (floor > 0 && floorRaw < 0) floorRaw = raw
        val pct = if (floor > 0) (floor + (raw - floorRaw).coerceAtLeast(0.0) / (1 - floorRaw).coerceAtLeast(1e-6) * (99 - floor)).toInt()
            else (raw * 100).toInt()
        held = maxOf(held, pct.coerceIn(0, 99))
        return held
    }

    companion object {
        /** An item of unknown size (the server has not said it yet). */
        const val UNKNOWN_WEIGHT = 10L * 1_048_576
    }
}

/**
 * How long each step of one wait really ran – what [StepShares.learn] learns from. Ticked about every second: the time
 * between two ticks counts into the step that ran, unless the wait was paused (offline, Wi-Fi) or the gap is longer
 * than [OneBar.MAX_GAP_MS] (the screen was off, not polled) – such a wait teaches nothing ([hadPause]). Pure.
 */
class StepClock {
    private val active = LinkedHashMap<LoadStep, Long>()
    private var lastMs = -1L
    private var step: LoadStep? = null

    /** The wait paused or was not watched for a while: its times are no measure of the steps. */
    var hadPause = false
        private set

    /** [step] runs at [nowMs] ([running] false: the wait is paused). Steps never go back. */
    fun at(step: LoadStep, nowMs: Long, running: Boolean = true) {
        val prev = this.step
        if (lastMs >= 0 && prev != null) {
            val gap = nowMs - lastMs
            if (running && gap in 0..OneBar.MAX_GAP_MS) active[prev] = (active[prev] ?: 0L) + gap else hadPause = true
        }
        if (!running) hadPause = true
        else if (prev == null || step.ordinal >= prev.ordinal) { this.step = step; active.getOrPut(step) { 0L } }
        lastMs = nowMs
    }

    /** A step ran (the wait was real). */
    val started: Boolean get() = active.isNotEmpty()

    /** The time the steps ran, ms. */
    val elapsed: Long get() = active.values.sum()

    /** How long each step ran (a step that never ran is missing). */
    fun durations(): Map<LoadStep, Long> = active.toMap()
}
