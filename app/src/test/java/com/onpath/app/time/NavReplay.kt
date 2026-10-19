package com.onpath.app.time

import com.onpath.app.data.Geo
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.Navigator
import com.onpath.app.navigation.OffRouteDetector
import com.onpath.app.navigation.Rerouter
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import java.util.Locale
import kotlin.math.abs

/**
 * Replays a recorded track through the **same pipeline as the recording service**: [Navigator] (off-route detector,
 * progress matching) → [TripPace] (learning, calm display, estimate state). Unlike [TripReplay] (which matches with
 * its own simple window), this shows what the phone shows: frozen progress, a wrong part of an out-and-back line,
 * "not on route", "estimate paused".
 *
 * Plan edits during the walk ([Edit]) replace the navigated line like "plan changed during a tour" does on the phone
 * (new session, new navigator; the walking direction is kept).
 */
class NavReplay(
    plan: List<GeoPoint>,
    private val activity: Activity = Activity.HIKE,
    prior: Factors = Factors.ONE,
    private val stops: List<GeoPoint> = listOf(plan.first(), plan.last()),
    private val accuracyM: Double = 8.0,
) {
    /** Replace the followed line when the walker has walked [atWalkedM] metres (track distance). */
    data class Edit(val atWalkedM: Double, val line: List<GeoPoint>, val stops: List<GeoPoint> = listOf(line.first(), line.last()))

    /** One fix as the screen would show it afterwards. */
    data class Frame(
        val activeS: Double, val wallMs: Long, val walkedM: Double, val point: GeoPoint,
        val state: OffRouteDetector.State, val alongM: Double?, val time: TripTime, val prompt: Boolean,
        /** Unrounded "to go" shown (s), null = none. */
        val shownToGoS: Double?, val lineLengthM: Double,
        /** Learned estimate before the calm display (s), the line's times and the factors in use then. */
        val rawToGoS: Double?, val times: RouteTimes?, val factors: Factors,
    )

    var navigator = Navigator(session(plan, stops))
        private set
    val trip = TripPace(prior)
    val frames = ArrayList<Frame>()
    /** What the card showed during simulated GPS gaps. */
    val gapStates = ArrayList<TripTime>()
    /** Off-route warnings (question / banner appearing). */
    var confirms = 0
        private set
    var firstPromptWalkedM: Double? = null
        private set

    private fun session(line: List<GeoPoint>, st: List<GeoPoint>) =
        NavSession.start(NavKind.PLAN, "replay", activity, 3, line, st)

    private var times: RouteTimes? = RouteTimes.of(plan, activity)

    init { trip.setLine(times, null) }

    fun run(track: List<List<TrackPoint>>, edits: List<Edit> = emptyList(), gpsGaps: List<Pair<Double, Double>> = emptyList()): NavReplay {
        val pending = edits.sortedBy { it.atWalkedM }.toMutableList()
        var active = 0.0
        var walked = 0.0
        var last: GeoPoint? = null
        var t0: Long? = null
        for (seg in track) {
            var lastT: Long? = null
            last = null
            for (tp in seg) {
                val t = tp.timeMs ?: continue
                if (t0 == null) t0 = t
                lastT?.let { prev ->
                    // clock ticks in between (1 s): breaks, GPS gaps
                    var s = prev + 1000
                    while (s < t) { active += 1.0; trip.tick(active, s); s += 1000 }
                    active += (t - s + 1000).coerceAtLeast(0) / 1000.0
                }
                lastT = t
                val p = GeoPoint(tp.lat, tp.lon)
                last?.let { walked += Geo.distance(it, p) }
                last = p
                while (pending.isNotEmpty() && walked >= pending.first().atWalkedM) {
                    val e = pending.removeAt(0)
                    val old = navigator
                    navigator = Navigator(session(e.line, e.stops))
                    navigator.detector.adoptHeading(old.detector)
                    times = RouteTimes.of(e.line, activity)
                    trip.setLine(times, null)
                }
                if (gpsGaps.any { walked >= it.first && walked < it.second }) {
                    navigator.onGpsLost()
                    trip.tick(active, t)
                    gapStates += trip.snapshot()
                    continue
                }
                fix(OffRouteDetector.Fix(tp.lat, tp.lon, accuracyM, t - t0!!), active, t, walked, p)
            }
        }
        return this
    }

    private fun fix(f: OffRouteDetector.Fix, active: Double, wallMs: Long, walked: Double, p: GeoPoint) {
        val nav = navigator
        val step = nav.onFix(f)
        step.job?.let { nav.onResult(it.id, Rerouter.Failed(Rerouter.Reason.NO_WAY, "replay: no router", 0)) }
        if (nav.prompt != null && frames.lastOrNull()?.prompt != true) { confirms++; if (firstPromptWalkedM == null) firstPromptWalkedM = walked }
        val d = nav.detector
        trip.onFix(active, wallMs, p.lat, p.lon, d.shownAlongM, d.learnable)
        frames += Frame(active, wallMs, walked, p, d.state, d.shownAlongM, trip.snapshot(), nav.prompt != null,
            trip.shownToGoS(), d.line?.lengthM ?: 0.0, trip.rawToGoS(), times, trip.prior * trip.factors())
    }

    // ---- metrics -----------------------------------------------------------------------------------------------

    /** Longest walked distance (m) with an OK estimate whose progress did not move (the "frozen" bug). */
    fun frozenMaxM(): Double {
        var best = 0.0
        var startWalk = Double.NaN; var startAlong = Double.NaN
        for (f in frames) {
            val a = f.alongM
            if (f.time.state != EstimateState.OK || a == null) { startWalk = Double.NaN; continue }
            if (startWalk.isNaN() || abs(a - startAlong) >= 20) { startWalk = f.walkedM; startAlong = a; continue }
            best = maxOf(best, f.walkedM - startWalk)
        }
        return best
    }

    /** Seconds per estimate state. */
    fun stateSeconds(): Map<EstimateState, Double> {
        val out = HashMap<EstimateState, Double>()
        for (k in 1 until frames.size) out.merge(frames[k].time.state, frames[k].activeS - frames[k - 1].activeS, Double::plus)
        return out
    }

    /** "To go" at a checkpoint, only up to the last progress reached (a tour may end before the line's end). */
    data class Checkpoint(val frame: Frame, val shownS: Double, val rawS: Double, val staticS: Double, val realS: Double)

    /**
     * Checkpoints at 25 / 50 / 75 % of the way from the first matched progress to the last one on the last followed
     * line (null = not reached). Real = moving time from there until that last progress was reached.
     */
    fun checkpoints(prior: Factors): List<Checkpoint?> {
        val none = List<Checkpoint?>(3) { null }
        val lastLine = frames.lastOrNull { it.alongM != null && it.time.state == EstimateState.OK }?.times ?: return none
        val ok = frames.filter { it.alongM != null && it.time.state == EstimateState.OK && it.times === lastLine }
        if (ok.size < 10) return none
        val start = ok[ok.size / 20]
        val a0 = start.alongM!!; val a1 = ok.last().alongM!!
        if (a1 - a0 < 500) return none
        val end = frames.first { it.wallMs >= start.wallMs && it.times === lastLine && it.alongM != null && it.alongM >= a1 - 10 }
        return List(3) { i ->
            val target = a0 + (i + 1) * 0.25 * (a1 - a0)
            val f = ok.firstOrNull { it.alongM!! >= target } ?: return@List null
            val shown = f.shownToGoS ?: return@List null
            val raw = f.rawToGoS ?: return@List null
            val beyond = lastLine.remainingFrom(a1, f.factors)
            val real = end.time.movingS - f.time.movingS
            if (real <= 60) return@List null
            Checkpoint(f, shown - beyond * shown / raw.coerceAtLeast(1.0), raw - beyond,
                TimeEstimate.round(lastLine.remainingFrom(f.alongM!!, prior)) - lastLine.remainingFrom(a1, prior), real)
        }
    }

    /** Plan time (prior, no learning) vs. real moving time over the part walked between the checkpoints' ends. */
    fun planError(prior: Factors): Double {
        val lastLine = frames.lastOrNull { it.alongM != null && it.time.state == EstimateState.OK }?.times ?: return Double.NaN
        val ok = frames.filter { it.alongM != null && it.time.state == EstimateState.OK && it.times === lastLine }
        if (ok.size < 10) return Double.NaN
        val a = ok[ok.size / 20]; val b = ok.last()
        val real = b.time.movingS - a.time.movingS
        if (real <= 60) return Double.NaN
        return (lastLine.remainingFrom(a.alongM!!, prior) - lastLine.remainingFrom(b.alongM!!, prior)) / real - 1
    }

    /** |shown to go − real moving time| / real at 25 / 50 / 75 % (NaN = not reached). */
    fun errors(prior: Factors = Factors.ONE): DoubleArray =
        checkpoints(prior).map { c -> c?.let { abs(it.shownS / it.realS - 1) } ?: Double.NaN }.toDoubleArray()

    /** Changes of the shown arrival clock between updates >= 100 m apart (min), breaks excluded (real, not jumps). */
    fun arrivalJumps(): List<Double> {
        val out = ArrayList<Double>()
        var lastArr: Long? = null; var lastAlong = -1e9; var lastBreak = 0.0; var lastTimes: RouteTimes? = null
        var prevA = Double.NaN
        for (f in frames) {
            val a = f.alongM; val arr = f.time.arrivalMs
            if (a == null || arr == null || f.time.state != EstimateState.OK) { lastArr = null; prevA = Double.NaN; continue }
            // a new line, a break, or progress jumping to another part of the line: real changes, not jumps
            if (f.time.breakS != lastBreak || f.times !== lastTimes || !(abs(a - prevA) <= 300)) {
                lastBreak = f.time.breakS; lastTimes = f.times; lastArr = null
            }
            prevA = a
            if (lastArr == null || abs(a - lastAlong) >= 100) {
                lastArr?.let { out += abs(arr - it) / 60_000.0 }
                lastArr = arr; lastAlong = a
            }
        }
        return out
    }

    /** One line per ~[everyM] walked: what the card showed. */
    fun timeline(everyM: Double = 400.0): String {
        val sb = StringBuilder()
        var next = 0.0
        for (f in frames) {
            if (f.walkedM < next && f !== frames.last()) continue
            next = f.walkedM + everyM
            sb.append(String.format(Locale.US, "  walked %5.0f  %-9s along %6s/%5.0f  to go %6s  arr %s  %s%s\n",
                f.walkedM, f.state.name, f.alongM?.let { "%.0f".format(Locale.US, it) } ?: "-", f.lineLengthM,
                f.time.toGoS?.let { TimeEstimate.hm(it) } ?: "-", f.time.arrivalMs?.let { TimeEstimate.clock(it) } ?: "-",
                f.time.state.name, if (f.prompt) " [prompt]" else ""))
        }
        return sb.toString()
    }
}
