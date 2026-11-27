package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.strings.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * (A micro-calibration – a tiny fixed route searched without map data – is not offered: BRouter needs rd5 data to search
 * at all, so the factor is learned from real legs only.)
 *
 * How long the router searches one leg on this phone (owner 2026-11-27, „wir brauchen eine robuste Lösung“): an honest
 * estimate instead of the 24-hour question and "Weiter warten?". Pure, unit-tested (RouteCostTest).
 *
 * - Desktop JVM time by the leg's straight km: [A_MS] · km^1.5 up to [KNEE_KM], continuing with exponent 1.8 above it
 *   (continuous at the knee). Measured: hike 60 km 1.5 s, 100 km 3.1 s, 160 km 7.4 s, 487 km 56 s; bike 100 km 2.2 s,
 *   200 km 10 s, 487 km 47 s; T2 = T3.
 * - Phone time = desktop × this phone's factor: start [DEFAULT_FACTOR] (Pixel 8 measured ≈ 5–8), learned after every
 *   leg routed with ≥ [LEARN_FROM_KM] straight km ([learn], EWMA, clamped [MIN_FACTOR]..[MAX_FACTOR]). Pure search time
 *   only, never the map loading.
 * - A leg above [CAPACITY_KM] is beyond a phone's memory (487 km reached 215 MB of a 256 MB heap): no waiting offered.
 */
object RouteCost {
    const val A_MS = 3.5
    const val KNEE_KM = 160.0
    const val EXP_NEAR = 1.5
    const val EXP_FAR = 1.8
    const val DEFAULT_FACTOR = 8.0
    const val MIN_FACTOR = 2.0
    const val MAX_FACTOR = 30.0
    const val LEARN_WEIGHT = 0.3
    const val LEARN_FROM_KM = 10.0
    /** The app asks before a run whose longest leg takes longer than this on the phone. */
    const val ASK_ABOVE_MS = 30_000L
    const val ASK_ABOVE_S = ASK_ABOVE_MS / 1000.0
    /** The time limit allows this many times a leg's estimate ([limitMs]). */
    const val SAFETY = 2.0
    /** A phone's routing heap (MB) and the straight km it holds: 487 km reached 215 MB of a 256 MB heap. */
    const val PHONE_MEMORY_MB = 256
    const val PHONE_CAP_KM = 300.0
    /** A leg longer than this (straight km) is beyond a phone's memory. */
    const val CAPACITY_KM = PHONE_CAP_KM
    /** The range shown around an estimate. */
    const val RANGE_LOW = 0.7
    const val RANGE_HIGH = 1.5
    /** The wait window shows the run's estimate only above this. */
    const val SHOW_IN_WAIT_ABOVE_S = 5.0

    private val KNEE_MS = A_MS * KNEE_KM.pow(EXP_NEAR)

    /** Desktop search ms for a leg of [km] straight kilometres. */
    fun desktopMs(km: Double): Double {
        val k = km.coerceAtLeast(0.0)
        return if (k <= KNEE_KM) A_MS * k.pow(EXP_NEAR) else KNEE_MS * (k / KNEE_KM).pow(EXP_FAR)
    }

    /**
     * The search time in ms of one leg of [straightKm] straight km on a device [factor] times slower than the desktop JVM
     * ([desktopMs]; the factor comes from outside: the phone's learned one, the browser's own). [activity]: measured
     * alike for every profile (hike / bike within the model's spread), kept for a later split.
     */
    @Suppress("UNUSED_PARAMETER")
    fun estimateMs(straightKm: Double, activity: Activity, factor: Double): Long = (desktopMs(straightKm) * bounded(factor)).toLong()

    /** The straight km a device with [memoryCapMb] MB routing heap holds in one leg: [PHONE_CAP_KM] scaled linearly. */
    fun capKm(memoryCapMb: Int): Double = PHONE_CAP_KM * memoryCapMb.coerceAtLeast(1) / PHONE_MEMORY_MB

    /** What to do before a run ([decide]). */
    sealed interface Decision {
        /** Route at once. */
        data object Now : Decision
        /** Ask first: the leg [legIndex] takes about [loMs] … [hiMs]. */
        data class Ask(val loMs: Long, val hiMs: Long, val legIndex: Int) : Decision
        /** The leg [legIndex] is beyond the device's memory: only a stop on the way helps, no waiting offered. */
        data class SplitOnly(val legIndex: Int) : Decision
    }

    /**
     * The per-plan decision from the legs' estimates [legEstimatesMs] ([estimateMs]) and straight km [legKm] (same order;
     * a leg already routed is passed as 0 ms and 0.0 km): the longest leg beyond [capKm] of [memoryCapMb] → [Decision.SplitOnly];
     * else the slowest leg above [ASK_ABOVE_MS] → [Decision.Ask] with its range ([RANGE_LOW] … [RANGE_HIGH]); else [Decision.Now].
     */
    fun decide(legEstimatesMs: List<Long>, legKm: List<Double>, memoryCapMb: Int = PHONE_MEMORY_MB): Decision {
        val cap = capKm(memoryCapMb)
        legKm.indices.filter { legKm[it] > cap }.maxByOrNull { legKm[it] }?.let { return Decision.SplitOnly(it) }
        val slow = legEstimatesMs.indices.maxByOrNull { legEstimatesMs[it] } ?: return Decision.Now
        val ms = legEstimatesMs[slow]
        return if (ms > ASK_ABOVE_MS) Decision.Ask((ms * RANGE_LOW).toLong(), (ms * RANGE_HIGH).toLong(), slow) else Decision.Now
    }

    /**
     * The device factor after a leg of [straightKm] was searched in [searchMs] (successfully, pure search time): legs of
     * ≥ [LEARN_FROM_KM] only (else [oldFactor] as it is, bounded); EWMA with [LEARN_WEIGHT], clamped [MIN_FACTOR]..[MAX_FACTOR].
     */
    fun factorAfter(oldFactor: Double, straightKm: Double, searchMs: Long): Double = learn(oldFactor, straightKm, searchMs)

    /** This phone's search seconds for a leg of [km] with the phone [factor]. */
    fun phoneS(km: Double, factor: Double): Double = desktopMs(km) * bounded(factor) / 1000.0

    fun bounded(factor: Double): Double = if (factor.isNaN()) DEFAULT_FACTOR else factor.coerceIn(MIN_FACTOR, MAX_FACTOR)

    /** A leg of [km] straight kilometres is beyond a phone's memory. */
    fun beyond(km: Double): Boolean = km > CAPACITY_KM

    /**
     * The factor after a leg of [km] straight km was searched in [searchMs] (successfully): EWMA with [LEARN_WEIGHT],
     * clamped. Short legs (< [LEARN_FROM_KM]) and odd times teach nothing.
     */
    fun learn(factor: Double, km: Double, searchMs: Long): Double {
        if (km < LEARN_FROM_KM || searchMs <= 0) return bounded(factor)
        val observed = searchMs / desktopMs(km)
        if (observed.isNaN() || observed.isInfinite()) return bounded(factor)
        return bounded(bounded(factor) * (1 - LEARN_WEIGHT) + observed * LEARN_WEIGHT)
    }

    /** The app's time limit for a request of [km] straight km: today's [todayMs], at least twice the phone's estimate. */
    fun limitMs(km: Double, factor: Double, todayMs: Long): Long = maxOf(todayMs, (SAFETY * phoneS(km, factor) * 1000).toLong())

    /** Straight km of the leg [i] → [i] + 1 of [points]. */
    fun legKm(points: List<GeoPoint>, i: Int): Double = Geo.distance(points[i], points[i + 1]) / 1000.0

    /** "about 40–90 seconds" / "about 3–6 minutes" / "about 2–4 hours" for [estimateS] ([RANGE_LOW] … [RANGE_HIGH]); [short]: "about 1–2 min". */
    fun words(estimateS: Double, short: Boolean = false): String {
        val lo = estimateS * RANGE_LOW
        val hi = estimateS * RANGE_HIGH
        return when {
            hi <= 90 -> {
                val step = if (hi <= 30) 5 else 10
                val l = (floor(lo / step).toInt() * step).coerceAtLeast(step)
                val h = (ceil(hi / step).toInt() * step).coerceAtLeast(l + step)
                Txt.s(if (short) Res.string.plan_est_short_s else Res.string.plan_est_seconds, l, h)
            }
            hi < 120 * 60 -> {
                val l = (lo / 60).roundToInt().coerceAtLeast(1)
                val h = (hi / 60).roundToInt().coerceAtLeast(l + 1)
                Txt.s(if (short) Res.string.plan_est_short_min else Res.string.plan_est_minutes, l, h)
            }
            else -> {
                val l = (lo / 3600).roundToInt().coerceAtLeast(1)
                val h = (hi / 3600).roundToInt().coerceAtLeast(l + 1)
                Txt.s(if (short) Res.string.plan_est_short_h else Res.string.plan_est_hours, l, h)
            }
        }
    }

    /** The question's title: "Long route: about 3–6 minutes", or "Too long for the phone" ([LongAsk.beyond]). */
    fun askTitle(ask: LongAsk): String =
        if (ask.beyond) Txt.s(Res.string.nav_too_long_title) else Txt.s(Res.string.nav_long_leg_title, words(ask.estimateS))

    /**
     * The question's text: "Hilden → München, 475 km in one go. …" – [from] / [to] the names of the leg's ends (both
     * known, else "Point 1 → 2"); beyond capacity: "475 km in one go. Add a stop or plan in the browser."
     */
    fun askText(ask: LongAsk, from: String?, to: String?): String {
        val km = ask.km.roundToInt().toString()
        if (ask.beyond) return Txt.s(Res.string.nav_too_long_text, km)
        val leg = if (!from.isNullOrBlank() && !to.isNullOrBlank()) "$from → $to"
            else Txt.s(Res.string.nav_long_leg_points, ask.leg + 1, ask.leg + 2)
        return Txt.s(Res.string.nav_long_leg_text, leg, km)
    }
}

/**
 * The question before a run ([LongRule.ask]): [estimateS] seconds (the day rule: the plan's time; the phone rule: the
 * longest leg's search, [Double.POSITIVE_INFINITY] = beyond the phone's capacity), [leg] its index (-1 = the whole plan),
 * [km] its straight km.
 */
data class LongAsk(val estimateS: Double, val leg: Int = -1, val km: Double = 0.0) {
    val beyond: Boolean get() = estimateS.isInfinite()
}

/** Whether a plan is asked about before it is routed (injected into [com.onpath.app.domain.plan.RouteRun]). */
fun interface LongRule {
    /** The question for [points], null = route at once. */
    fun ask(points: List<GeoPoint>, activity: Activity): LongAsk?

    /** The run's search estimate in seconds (the wait window); null = none known. */
    fun runS(points: List<GeoPoint>, activity: Activity): Double? = null

    companion object {
        /** Today's rule (the web planner): a plan longer than a day ([LongRoute]). */
        val DAY: LongRule = LongRule { points, activity ->
            if (LongRoute.needsAsk(points, activity)) LongAsk(LongRoute.estimateS(points, activity)) else null
        }
    }
}

/**
 * The app's rule ([RouteCost]): asks only when the longest leg still to route takes longer than [RouteCost.ASK_ABOVE_S]
 * on this phone ([factor]) or is beyond its capacity. [routed] (from, to, quick): the leg is routed already (the leg
 * cache; quick = memory only, no file read) – asked only for the legs that would make it ask, and quick for [runS].
 */
class PhoneLongRule(
    private val factor: () -> Double,
    private val routed: (GeoPoint, GeoPoint, Boolean) -> Boolean = { _, _, _ -> false },
) : LongRule {
    override fun ask(points: List<GeoPoint>, activity: Activity): LongAsk? {
        if (points.size < 2) return null
        val f = factor()
        val km = MutableList(points.size - 1) { RouteCost.legKm(points, it) }
        val ms = MutableList(km.size) { RouteCost.estimateMs(km[it], activity, f) }
        // the leg the decision is about, unless it is routed already (only those legs are looked up): then without it
        while (true) {
            val i = when (val d = RouteCost.decide(ms, km)) {
                RouteCost.Decision.Now -> return null
                is RouteCost.Decision.Ask -> d.legIndex
                is RouteCost.Decision.SplitOnly -> d.legIndex
            }
            if (!routed(points[i], points[i + 1], false))
                return LongAsk(if (RouteCost.beyond(km[i])) Double.POSITIVE_INFINITY else ms[i] / 1000.0, i, km[i])
            km[i] = 0.0; ms[i] = 0L
        }
    }

    override fun runS(points: List<GeoPoint>, activity: Activity): Double? {
        if (points.size < 2) return null
        val f = factor()
        var s = 0.0
        for (i in 0 until points.size - 1) {
            val km = RouteCost.legKm(points, i)
            if (km < RouteCost.LEARN_FROM_KM || routed(points[i], points[i + 1], true)) continue
            s += RouteCost.estimateMs(km, activity, f) / 1000.0
        }
        return s
    }
}

/**
 * The phone's search cost for the engine (BRouterEngine): the time limit of a request ([limitMs]) and what a finished
 * search teaches ([searched]). [read] / [write] the stored factor (ProgressMemory).
 */
class SearchCost(private val read: () -> Double, private val write: (Double) -> Unit) {
    fun limitMs(waypoints: List<GeoPoint>, todayMs: Long): Long =
        RouteCost.limitMs(RouteTimeLimit.straightKm(waypoints), read(), todayMs)

    /** A request of two points was searched in [searchMs]: the factor learns (legs only – one request per leg). */
    fun searched(waypoints: List<GeoPoint>, searchMs: Long) {
        if (waypoints.size != 2) return
        val before = read()
        val after = RouteCost.learn(before, RouteTimeLimit.straightKm(waypoints), searchMs)
        if (after != before) write(after)
    }
}
