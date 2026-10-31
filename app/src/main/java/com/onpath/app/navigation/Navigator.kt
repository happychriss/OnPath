package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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
        /** The new line leaves out this stop (index in the session's stops, [StopSkip]); null = none. */
        val skipStop: Int? = null,
        /** Name of [skipStop] when it has one (else "Stop N"). */
        val skipName: String? = null,
        /**
         * Non-null: "Off route – re-plan?" with [skipStop] in question – "Yes" takes [via] (the way via it), "Yes, skip
         * stop N" takes this line (without it); Keep original stays possible.
         */
        val via: Question? = null,
        /**
         * The rider kept moving while the question is open: the way from here is this much longer (> 0, moving away)
         * or shorter than when [line] was computed. Only the shown numbers change ([NavText.question]); [line],
         * [legM] and [id] stay, so "Take new route" takes exactly what was computed (A-015: the card said "+80 m"
         * while the rider walked from 77 to 183 m away).
         */
        val movedM: Double = 0.0,
        /** Distance to the route now ("You're 120 m off the route"); follows the rider in steps. Null = not known. */
        val offM: Double? = null,
    ) : NavPrompt

    /** No way back (no data, nothing found, timeout): arrow + distance to the route. */
    data class NoWay(val reason: Rerouter.Reason) : NavPrompt

    /** The user chose to keep the original: arrow + distance to the route. */
    data object Kept : NavPrompt

    /**
     * Never on the route yet and away from it (owner 2026-10-31): "Route starts 31 km away" · "south-west (direct) ·
     * recording" · Guide me / Just record. No vibration, no sound; nothing happens until the user decides.
     * [distanceM] / [bearingDeg] = straight line to the route's first point (follow the rider in steps).
     * [searching]: "Guide me" was tapped, the way there is being computed.
     */
    data class FarStart(val id: Int, val distanceM: Double, val bearingDeg: Double, val searching: Boolean = false) : NavPrompt

    /** "Guide me" found no way: "No way to the route found – recording continues" · OK (the red note). */
    data class LeadFailed(val id: Int) : NavPrompt
}

/** The prompt is about being off the route (amber card, "Off route" status) – not the far start before the route. */
val NavPrompt.offRoute: Boolean get() = this !is NavPrompt.FarStart && this !is NavPrompt.LeadFailed

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
 * Never on the route yet (owner 2026-10-31): no off-route question at all – a calm [NavPrompt.FarStart] ("Route starts
 * 31 km away" · Guide me / Just record), no vibration; "reached" is kept in the session ([NavSession.reached]).
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
    /** Refreshes of the open question in this off-route episode (owner 2026-10-30: only the first one vibrates). */
    private var refreshes = 0
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
    /** Where the running routing job was asked from. */
    private var jobFrom: GeoPoint? = null
    /** The open question's way back ([live]) and its cost from where it was computed. */
    private var liveLine: NavLine? = null
    private var liveWindowM = 0.0
    private var liveBase = 0.0
    /** Never on the route yet: "Just record" (or OK after no way was found) – no far-start question until reached. */
    private var quiet = false
    /** Fixes in a row away from the line while the route was never reached. */
    private var farFixes = 0

    init {
        // new session or restart (R-07): NO_GPS, the first fixes match the whole line (earliest part: round trips);
        // never reached yet: a position away from it is not progress (see OffRouteDetector.reached)
        detector.setLine(NavLine(session.active), null, reached = session.reached)
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
        // never on the route and away from it: the calm far-start question (no vibration)
        if (on && prompt == null && !session.reached && !quiet && farFixes >= OffRouteDetector.REJOIN_FIXES) {
            lastFix?.let { showFarStart(it.point) }
        }
        return Step()
    }

    fun onGpsLost() = detector.gpsLost()

    fun onFix(f: OffRouteDetector.Fix): Step {
        lastFix = f
        val p = f.point
        recent.addLast(p); while (recent.size > RECENT_FIXES) recent.removeFirst()
        var step = markVisited(p)
        var farNow = false
        if (forceCheck) {
            forceCheck = false
            if (detector.forceCheck(f)) {
                if (!session.rerouted) leftAtM = detector.leftAtM
                if (enabled && prompt == null) return step + ask(f, vibrate = false)
            } else if (!detector.reached) farNow = true // "Continue navigation" far from a route never reached
        }
        val st = detector.onFix(f)
        if (!session.reached) {
            // owner 2026-10-31: never on the route yet = always from the START – no off-route question, no vibration
            if (!detector.reached) return step + farStart(f, st, farNow)
            // reached for the first time: saved (a restart keeps it); a far-start question has nothing left to ask
            session = session.copy(reached = true)
            quiet = false; farFixes = 0
            step += Step(sessionChanged = true)
            if (prompt is NavPrompt.FarStart || prompt is NavPrompt.LeadFailed) step += clearPrompt()
        }
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
            live(p)
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
        if (prompt is NavPrompt.FarStart) return when (outcome) {
            // "Guide me": the user asked – taken at once
            is Rerouter.Found -> takeLine(outcome.line, outcome.legM, outcome.resumeAlongM, skipStop = null, turnRound = false)
            is Rerouter.Failed -> {
                if (outcome.reason != Rerouter.Reason.CANCELLED) prompt = NavPrompt.LeadFailed(++questionId)
                Step()
            }
        }
        return when (outcome) {
            is Rerouter.Found -> {
                val q = question(++questionId, outcome)
                prompt = q
                startLive(q)
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
            session = sw.session.copy(version = session.version + 1, reached = true)
            original = NavLine(session.original)
            leftAtM = 0.0
            detector.setLine(original, 0.0, assumeOnRoute = true)
            prompt = null; toRoute = null; failures = 0; keeps = 0; origHits = 0; refreshes = 0
            return Step(cancelJob = true, lineChanged = true, activityChanged = true)
        }
        return takeLine(q.line, q.legM, q.resumeAlongM, q.skipStop, q.turnRound)
    }

    /** "Yes" to "Off route – re-plan?" with a stop in question [id]: the new route via that stop. */
    fun takeVia(id: Int): Step {
        val q = prompt as? NavPrompt.Question ?: return Step()
        if (q.id != id) return Step()
        val v = q.via ?: return Step()
        return takeLine(v.line, v.legM, v.resumeAlongM, v.skipStop, v.turnRound)
    }

    private fun takeLine(line: List<GeoPoint>, legM: Double, resumeAlongM: Double, skipStop: Int?, turnRound: Boolean): Step {
        turnRoundAt = if (turnRound) line.firstOrNull() else null
        // a stop left out on purpose stays out: later re-routes never send back to it (like a passed one)
        val visited = skipStop?.let { session.visited + it } ?: session.visited
        session = session.copy(current = line, spliceAtM = legM, resumeAlongM = resumeAlongM, leftAtM = leftAtM,
            visited = visited, version = session.version + 1)
        detector.setLine(NavLine(line), 0.0, assumeOnRoute = true)
        prompt = null; toRoute = null; failures = 0; keeps = 0; origHits = 0; refreshes = 0
        return Step(cancelJob = true, lineChanged = true)
    }

    // ---- never on the route yet (owner 2026-10-31) --------------------------------------------------------------

    /** "Guide me" for far-start question [id]: here → the start → all points → the destination, taken when found. */
    fun leadThere(id: Int): Step {
        val pr = prompt as? NavPrompt.FarStart ?: return Step()
        if (pr.id != id || pr.searching) return Step()
        val f = lastFix ?: return Step()
        prompt = pr.copy(searching = true)
        val jid = ++jobId
        runningJob = jid; refreshing = false; vibrateOnResult = false; jobFrom = f.point
        return Step(job = Job(jid, Rerouter.Request(f.point, original, 0.0, session.stops, session.visited, session.activity,
            session.sac, perRequestMs = LEAD_REQUEST_MS, totalMs = LEAD_TOTAL_MS, toStart = true)))
    }

    /** "Just record" for far-start question [id]: quiet until the route is first reached, then the usual flow. */
    fun justRecord(id: Int): Step {
        val pr = prompt as? NavPrompt.FarStart ?: return Step()
        if (pr.id != id) return Step()
        quiet = true
        return clearPrompt()
    }

    /** OK on "No way to the route found – recording continues" ([id]): quiet like "Just record". */
    fun leadFailedOk(id: Int): Step {
        val pr = prompt as? NavPrompt.LeadFailed ?: return Step()
        if (pr.id != id) return Step()
        quiet = true
        return clearPrompt()
    }

    /** A fix while the route was never reached: the far-start question (after a few fixes away, or at once). */
    private fun farStart(f: OffRouteDetector.Fix, st: OffRouteDetector.Status, now: Boolean): Step {
        val away = st.distanceM?.let { it > detector.offDistance(f.accuracyM) } == true
        farFixes = if (away) farFixes + 1 else 0
        val start = original.points.first()
        val d = Geo.distance(f.point, start)
        val b = TurnDetector.bearing(f.point, start)
        when (val pr = prompt) {
            is NavPrompt.FarStart -> {
                toRoute = RouteHint(d, b, start)
                // the numbers follow the rider in steps (no flicker from GPS noise); same question, nothing said again
                if (abs(d - pr.distanceM) >= LIVE_STEP_M || sector(b) != sector(pr.bearingDeg)) prompt = pr.copy(distanceM = d, bearingDeg = b)
            }
            is NavPrompt.LeadFailed -> toRoute = RouteHint(d, b, start)
            null -> if (away && !quiet && navigating && (now || farFixes >= OffRouteDetector.REJOIN_FIXES)) showFarStart(f.point)
            else -> Unit // an activity switch question is open
        }
        return Step()
    }

    private fun showFarStart(p: GeoPoint) {
        val start = original.points.first()
        val d = Geo.distance(p, start)
        val b = TurnDetector.bearing(p, start)
        prompt = NavPrompt.FarStart(++questionId, d, b)
        toRoute = RouteHint(d, b, start)
    }

    /** One of the eight compass words ([NavText.compass]). */
    private fun sector(deg: Double) = ((((deg % 360) + 360) % 360) / 45.0).roundToInt() % 8

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
        keeps++; refreshes = 0
        lastFix?.let { markAsked(it); toRoute = hint(it.point) }
        return Step(cancelJob = true)
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private fun question(id: Int, f: Rerouter.Found): NavPrompt.Question = NavPrompt.Question(id, f.extraM, f.rejoinInM,
        f.startOffM, f.line, f.legM, f.resumeAlongM, turnRound = TurnRound.needed(recent.toList(), f.line),
        skipStop = f.skipStop, skipName = f.skipStop?.let { session.stops.getOrNull(it)?.name?.takeIf { n -> n.isNotBlank() } },
        via = f.ask?.let { question(id, it) }, offM = detector.lastDistanceM)

    private fun ask(f: OffRouteDetector.Fix, vibrate: Boolean): Step {
        prompt = NavPrompt.Searching
        failures = 0; refreshes = 0
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
                // the first refresh of an episode is a second reminder (vibrates); later ones update the card silently
                if (moved >= REFRESH_MOVE_M && since >= REFRESH_MIN_MS) {
                    markAsked(f); Step(job = newJob(f, true, vibrateOnResult = refreshes++ == 0))
                } else Step()
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
        jobFrom = f.point
        return Job(id, Rerouter.Request(f.point, original, leftAtM, session.stops, session.visited, session.activity, session.sac))
    }

    /** A new question: remember its way back and the cost from where it was asked; then catch up with the rider. */
    private fun startLive(q: NavPrompt.Question) {
        val from = jobFrom
        val nl = NavLine(q.line)
        liveWindowM = q.legM.coerceIn(0.0, nl.lengthM)
        val base = from?.let { cost(nl, it) }
        if (base == null) { liveLine = null; return }
        liveLine = nl; liveBase = base
        lastFix?.let { live(it.point) }
    }

    /**
     * Keeps the open question's numbers true while the rider moves on (every fix, no routing): the change in
     * "distance to the way back + what is left of it" since it was computed. Updated in steps of [LIVE_STEP_M], so GPS
     * noise does not make the numbers flicker; same question (id, line), no vibration, no new notification sound.
     */
    private fun live(p: GeoPoint) {
        val q0 = prompt as? NavPrompt.Question ?: return
        if (q0.switch != null) return
        var q = q0
        // "You're 120 m off the route": the distance follows too
        val off = detector.lastDistanceM
        if (off != null && (q.offM == null || abs(off - q.offM!!) >= LIVE_STEP_M)) q = q.copy(offM = off)
        val now = liveLine?.let { cost(it, p) }
        if (now != null) {
            val moved = now - liveBase
            if (abs(moved - q.movedM) >= LIVE_STEP_M) q = q.copy(movedM = moved, via = q.via?.copy(movedM = moved))
        }
        if (q !== q0) prompt = q
    }

    /** Distance to the way back plus what is left of it from the nearest point (relative: only differences count). */
    private fun cost(nl: NavLine, p: GeoPoint): Double? {
        val m = nl.match(p, 0.0, liveWindowM) ?: return null
        return m.distanceM - m.alongM
    }

    private fun markAsked(f: OffRouteDetector.Fix) { askAt = f.point; askAtMs = f.timeMs }

    private fun clearPrompt(): Step {
        val had = prompt != null || runningJob >= 0
        prompt = null; toRoute = null; failures = 0; keeps = 0; runningJob = -1; refreshing = false; refreshes = 0
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
        /**
         * A question is recomputed after moving this far from where it was computed (a new question id; only the first
         * refresh of an off-route episode vibrates – a second reminder – later ones are silent) …
         */
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
        /** An open question's numbers follow the rider in steps of this much ([live]). */
        const val LIVE_STEP_M = 20.0
        /**
         * "Guide me" may route far (30 km to the start and the whole plan): the user asked and waits with a spinner,
         * so more time than a way back ([Rerouter.PER_REQUEST_MS] / [Rerouter.TOTAL_MS]).
         */
        const val LEAD_REQUEST_MS = 20_000L
        const val LEAD_TOTAL_MS = 30_000L
    }
}
