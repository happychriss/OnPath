package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import kotlin.math.min

/** What Navigate shows about being off the route. Every state stays until answered or the situation is gone. */
sealed interface NavPrompt {
    /** Off route confirmed, the way back is being computed. */
    data object Searching : NavPrompt

    /** "Off route – new route found (+350 m, rejoins in 600 m)" · Take new route · Keep original. */
    data class Question(
        val id: Int,
        val extraM: Double,
        val rejoinInM: Double,
        /** Distance from the position to the start of the new line (snapped onto a way). */
        val startOffM: Double,
        val line: List<GeoPoint>,
        val legM: Double,
        val resumeAlongM: Double,
        /**
         * Non-null: not off route – the user chose another activity in the check sheet; [line] is the rest of the tour
         * for it. Stays until answered (also when on the route).
         */
        val switch: ActivitySwitch? = null,
        /** The new line starts by going back the way the rider came ([TurnRound]): "Turn round". */
        val turnRound: Boolean = false,
    ) : NavPrompt

    /** No way back (no data, nothing found, timeout): arrow + distance to the route. */
    data class NoWay(val reason: Rerouter.Reason) : NavPrompt

    /** The user chose to keep the original: arrow + distance to the route. */
    data object Kept : NavPrompt
}

/** Straight-line direction to the nearest route point ahead (arrow mode). */
data class RouteHint(val distanceM: Double, val bearingDeg: Double, val target: GeoPoint)

/** Navigation state the service publishes for the UI (RecState.nav). */
data class NavState(
    val line: List<GeoPoint>,
    val version: Int,
    /** The original line while a new route is followed (drawn thin grey); null otherwise. */
    val original: List<GeoPoint>?,
    val prompt: NavPrompt?,
    val toRoute: RouteHint?,
    /** Distance to the navigated line (last good fix). */
    val offM: Double?,
    /** What is navigated (activity, stops) – the activity check reads it. */
    val session: NavSession? = null,
    /** Progress along [line] (metres from its start). */
    val progressM: Double = 0.0,
    /** Progress to show "to go" from; null = not on the route (not matched / confirmed off it). */
    val shownAlongM: Double? = null,
    /** Where to turn round: the U-turn sign on the map (asked turn-round question, or taken until turned). */
    val uTurnAt: GeoPoint? = null,
)

/**
 * The re-routing flow while navigating (pure; owner decisions in docs/plan-auto-reroute.md):
 * off route confirmed → vibrate → way back computed in the background → a question that stays until answered;
 * back on the route by yourself → the question goes away. After "Take new route" the original stays as a grey line
 * and is used again as soon as you are back on it. Passed stops are skipped. Never re-routes silently.
 *
 * The service feeds fixes and routing results and carries out the returned [Step] (vibration, routing job, saving).
 */
class Navigator(session: NavSession, offRouteM: Double = 40.0) {

    data class Job(val id: Int, val request: Rerouter.Request)

    data class Step(
        val vibrate: Boolean = false,
        val job: Job? = null,
        val cancelJob: Boolean = false,
        /** The navigated line changed (save the session + follow line, publish). */
        val lineChanged: Boolean = false,
        /** Something else in the session changed (visited stops): save. */
        val sessionChanged: Boolean = false,
        /** The navigated activity changed (switch taken): store it with the tour. */
        val activityChanged: Boolean = false,
    ) {
        operator fun plus(o: Step) = Step(vibrate || o.vibrate, o.job ?: job, (cancelJob || o.cancelJob) && o.job == null,
            lineChanged || o.lineChanged, sessionChanged || o.sessionChanged, activityChanged || o.activityChanged)
    }

    var session: NavSession = session
        private set
    var prompt: NavPrompt? = null
        private set
    var toRoute: RouteHint? = null
        private set
    /** Settings: off-route warning on. Off = never ask. */
    var enabled = true
    /** False in Plan mode: no new questions (a pending one stays). */
    var navigating = true
        private set

    private var original = NavLine(session.original)
    val detector = OffRouteDetector(offRouteM)
    private var leftAtM = session.leftAtM
    private var jobId = 0
    private var runningJob = -1
    private var vibrateOnResult = false
    private var refreshing = false
    private var questionId = 0
    private var askAt: GeoPoint? = null
    private var askAtMs = 0L
    private var failures = 0
    private var keeps = 0
    private var origHits = 0
    private var forceCheck = false
    private var lastFix: OffRouteDetector.Fix? = null
    /** A taken way back that starts by turning round: its start, until the rider has turned and moved on. */
    private var turnRoundAt: GeoPoint? = null
    /** The rider's latest positions, oldest first ([TurnRound]). */
    private val recent = ArrayDeque<GeoPoint>()

    init {
        // new session or restart (R-07): NO_GPS, the first fixes match the whole line (earliest part: round trips)
        detector.setLine(NavLine(session.active), null)
    }

    var offRouteM: Double
        get() = detector.offRouteM
        set(v) { detector.offRouteM = v }

    val state: NavState
        get() = NavState(session.active, session.version, if (session.rerouted) session.original else null, prompt, toRoute,
            detector.lastDistanceM, session, detector.progressM, detector.shownAlongM,
            (prompt as? NavPrompt.Question)?.takeIf { it.turnRound && it.switch == null }?.line?.firstOrNull() ?: turnRoundAt)

    /** An activity switch is being asked (check sheet → chosen activity). */
    private val switchAsked: Boolean get() = (prompt as? NavPrompt.Question)?.switch != null

    /** "Continue navigation": check the next fix at once (no 15 s wait), ask when away from the line. */
    fun checkNow() { forceCheck = true }

    fun setNavigating(on: Boolean): Step {
        navigating = on
        // confirmed while in Plan: ask now
        if (on && prompt == null && enabled && detector.state == OffRouteDetector.State.OFF_ROUTE) {
            lastFix?.let { return ask(it, vibrate = true) }
        }
        return Step()
    }

    fun onGpsLost() = detector.gpsLost()

    fun onFix(f: OffRouteDetector.Fix): Step {
        lastFix = f
        val p = f.point
        recent.addLast(p); while (recent.size > RECENT_FIXES) recent.removeFirst()
        var step = markVisited(p)
        if (forceCheck) {
            forceCheck = false
            if (detector.forceCheck(f)) {
                if (!session.rerouted) leftAtM = detector.leftAtM
                if (enabled && prompt == null) return step + ask(f, vibrate = false)
            }
        }
        val st = detector.onFix(f)
        // turned round and on the way: the sign goes (also when the line changed again)
        if (turnRoundAt != null && (!session.rerouted || detector.progressM > TURN_ROUND_DONE_M)) turnRoundAt = null
        if (!session.rerouted && (st.state != OffRouteDetector.State.OFF_ROUTE || st.confirmedNow)) leftAtM = detector.leftAtM

        // on a new route: back on the original (rest reached, or walked back onto it) → use the original again
        if (session.rerouted) {
            val restore = restoreAlong(f, st)
            if (restore != null) return step + restoreOriginal(restore)
        }
        if (!switchAsked && (st.rejoinedNow || (prompt != null && st.state == OffRouteDetector.State.ON_ROUTE))) {
            return step + clearPrompt()
        }
        if (st.confirmedNow && enabled && navigating && prompt == null) return step + ask(f, vibrate = true)
        if (prompt != null && !switchAsked) {
            toRoute = hint(p)
            step += followUp(f)
        }
        return step
    }

    /**
     * The user chose another activity in the check sheet: [proposed] is the rest of the tour for it (computed by the
     * app). Shown as the dashed proposal with the same question as re-routing; replaces an off-route prompt.
     */
    fun proposeSwitch(proposed: NavSession, timeS: Double): Step {
        if (proposed.original.size < 2) return Step()
        val line = proposed.original
        val newM = Geo.length(line)
        val restM = (Geo.length(session.active) - detector.progressM).coerceAtLeast(0.0)
        val startOff = lastFix?.let { Geo.distance(it.point, line.first()) } ?: 0.0
        val had = runningJob >= 0
        runningJob = -1; refreshing = false; vibrateOnResult = false
        prompt = NavPrompt.Question(++questionId, newM - restM, 0.0, startOff, line, newM, 0.0,
            switch = ActivitySwitch(proposed.activity, proposed.sac, proposed, timeS))
        toRoute = null
        return Step(cancelJob = had)
    }

    /** Result of [Job] [id]; stale results are ignored. */
    fun onResult(id: Int, outcome: Rerouter.Outcome): Step {
        if (id != runningJob) return Step()
        runningJob = -1
        val wasRefresh = refreshing; refreshing = false
        val vib = vibrateOnResult; vibrateOnResult = false
        if (prompt == null) return Step() // back on route meanwhile
        return when (outcome) {
            is Rerouter.Found -> {
                prompt = NavPrompt.Question(++questionId, outcome.extraM, outcome.rejoinInM, outcome.startOffM, outcome.line,
                    outcome.legM, outcome.resumeAlongM, turnRound = TurnRound.needed(recent.toList(), outcome.line))
                failures = 0
                Step(vibrate = vib)
            }
            is Rerouter.Failed -> {
                if (outcome.reason == Rerouter.Reason.CANCELLED) return Step()
                when (prompt) {
                    is NavPrompt.Question, NavPrompt.Kept -> Unit // a refresh / re-ask failed: keep what is shown
                    else -> { prompt = NavPrompt.NoWay(outcome.reason); failures++ }
                }
                if (!wasRefresh) lastFix?.let { markAsked(it) }
                Step()
            }
        }
    }

    /** "Take new route" for question [id] (app or notification). */
    fun take(id: Int): Step {
        val q = prompt as? NavPrompt.Question ?: return Step()
        if (q.id != id) return Step()
        q.switch?.let { sw ->
            // a new tour line for the new activity: it becomes the original (the old one was for the wrong activity)
            session = sw.session.copy(version = session.version + 1)
            original = NavLine(session.original)
            leftAtM = 0.0
            detector.setLine(original, 0.0, assumeOnRoute = true)
            prompt = null; toRoute = null; failures = 0; keeps = 0; origHits = 0
            return Step(cancelJob = true, lineChanged = true, activityChanged = true)
        }
        turnRoundAt = if (q.turnRound) q.line.firstOrNull() else null
        session = session.copy(current = q.line, spliceAtM = q.legM, resumeAlongM = q.resumeAlongM, leftAtM = leftAtM,
            version = session.version + 1)
        detector.setLine(NavLine(q.line), 0.0, assumeOnRoute = true)
        prompt = null; toRoute = null; failures = 0; keeps = 0; origHits = 0
        return Step(cancelJob = true, lineChanged = true)
    }

    /** "Keep original" for question [id]: no new route; arrow to the route; asked again only much later. */
    fun keep(id: Int): Step {
        val q = prompt as? NavPrompt.Question ?: return Step()
        if (q.id != id) return Step()
        if (q.switch != null) {
            // keep the activity: nothing changes; still off the route → the normal question flow again
            prompt = null; toRoute = null
            if (enabled && navigating && detector.state == OffRouteDetector.State.OFF_ROUTE) lastFix?.let { return ask(it, vibrate = false) }
            return Step()
        }
        prompt = NavPrompt.Kept
        keeps++
        lastFix?.let { markAsked(it); toRoute = hint(it.point) }
        return Step(cancelJob = true)
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private fun ask(f: OffRouteDetector.Fix, vibrate: Boolean): Step {
        prompt = NavPrompt.Searching
        failures = 0
        toRoute = hint(f.point)
        markAsked(f)
        // the vibration comes with the question: at once (the way back is usually found in < 1 s)
        return Step(vibrate = vibrate, job = newJob(f, refresh = false, vibrateOnResult = false))
    }

    /** While the prompt is shown: refresh a question after moving on, retry after a failure, re-ask after "keep". */
    private fun followUp(f: OffRouteDetector.Fix): Step {
        if (runningJob >= 0) return Step()
        val at = askAt ?: return Step()
        val moved = Geo.distance(at, f.point)
        val since = f.timeMs - askAtMs
        return when (val pr = prompt) {
            is NavPrompt.Question ->
                if (moved >= REFRESH_MOVE_M && since >= REFRESH_MIN_MS) { markAsked(f); Step(job = newJob(f, true, vibrateOnResult = true)) } else Step()
            is NavPrompt.NoWay ->
                if (failures < MAX_FAILURES && pr.reason != Rerouter.Reason.UNAVAILABLE && (moved >= RETRY_MOVE_M || since >= RETRY_MS)) {
                    markAsked(f); Step(job = newJob(f, false, vibrateOnResult = true))
                } else Step()
            NavPrompt.Kept -> {
                val wait = min(KEEP_WAIT_MS shl (keeps - 1).coerceIn(0, 2), KEEP_WAIT_MAX_MS)
                if (moved >= RETRY_MOVE_M && since >= wait) { markAsked(f); Step(job = newJob(f, false, vibrateOnResult = true)) } else Step()
            }
            else -> Step()
        }
    }

    private fun newJob(f: OffRouteDetector.Fix, refresh: Boolean, vibrateOnResult: Boolean): Job {
        val id = ++jobId
        runningJob = id; refreshing = refresh; this.vibrateOnResult = vibrateOnResult
        return Job(id, Rerouter.Request(f.point, original, leftAtM, session.stops, session.visited, session.activity, session.sac))
    }

    private fun markAsked(f: OffRouteDetector.Fix) { askAt = f.point; askAtMs = f.timeMs }

    private fun clearPrompt(): Step {
        val had = prompt != null || runningJob >= 0
        prompt = null; toRoute = null; failures = 0; keeps = 0; runningJob = -1; refreshing = false
        return Step(cancelJob = had)
    }

    /** Along position on the original to switch back to, or null. */
    private fun restoreAlong(f: OffRouteDetector.Fix, st: OffRouteDetector.Status): Double? {
        // the rest of the original reached on the new line
        if (st.state == OffRouteDetector.State.ON_ROUTE && detector.progressM >= session.spliceAtM + REST_REACHED_M) {
            return session.resumeAlongM + (detector.progressM - session.spliceAtM)
        }
        // walked (back) onto the original near where it was left
        val m = original.match(f.point, leftAtM - OffRouteDetector.BACK_M, leftAtM + OffRouteDetector.AHEAD_M, preferAlongM = leftAtM)
        if (m != null && m.distanceM <= detector.offDistance(f.accuracyM) / 2) {
            if (++origHits >= OffRouteDetector.REJOIN_FIXES) return m.alongM
        } else origHits = 0
        return null
    }

    private fun restoreOriginal(alongM: Double): Step {
        session = session.copy(current = null, spliceAtM = 0.0, resumeAlongM = 0.0, leftAtM = alongM, version = session.version + 1)
        leftAtM = alongM
        detector.setLine(original, alongM, assumeOnRoute = true)
        origHits = 0
        if (switchAsked) return Step(lineChanged = true) // the activity question stays until answered
        return clearPrompt().copy(lineChanged = true)
    }

    private fun markVisited(p: GeoPoint): Step {
        val s = session
        if (s.stops.isEmpty()) return Step()
        var v: MutableSet<Int>? = null
        for (i in s.stops.indices) {
            if (i in s.visited) continue
            if (Geo.distance(p, s.stops[i].point) <= NavSession.VISITED_M) (v ?: s.visited.toMutableSet().also { v = it }).add(i)
        }
        val nv = v ?: return Step()
        session = s.copy(visited = nv)
        return Step(sessionChanged = true)
    }

    /** Nearest point of the original ahead of where the user left it (never back to a passed part). */
    private fun hint(p: GeoPoint): RouteHint? {
        val m = original.match(p, leftAtM, leftAtM + HINT_AHEAD_M) ?: return null
        return RouteHint(m.distanceM, TurnDetector.bearing(p, m.point), m.point)
    }

    companion object {
        /** Positions kept for the rider's direction (1 fix / s: the last half minute). */
        const val RECENT_FIXES = 30
        /** The U-turn sign stays until this far along the new line. */
        const val TURN_ROUND_DONE_M = 40.0
        /** A question is recomputed (no vibration) after moving this far from where it was computed … */
        const val REFRESH_MOVE_M = 150.0
        /** … but not more often than this. */
        const val REFRESH_MIN_MS = 30_000L
        /** After "no way found": retry after moving this far or after [RETRY_MS]; at most [MAX_FAILURES] times. */
        const val RETRY_MOVE_M = 200.0
        const val RETRY_MS = 120_000L
        const val MAX_FAILURES = 3
        /** After "Keep original": ask again only after moving [RETRY_MOVE_M] and 2, 4, 8 min. */
        const val KEEP_WAIT_MS = 120_000L
        const val KEEP_WAIT_MAX_MS = 480_000L
        /** On a new route: this far into the untouched rest = back on the original. */
        const val REST_REACHED_M = 30.0
        const val HINT_AHEAD_M = 3_000.0
    }
}
