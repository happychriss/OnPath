package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Are we really off the route?" and "how far along are we?" – a pure state machine (docs/plan-auto-reroute.md §4,
 * docs/plan-time-estimate.md "Field test 2026-10-19"), fed with good GPS fixes by the recording service. Unit-tested
 * with simulated traces and replayed owner tours.
 *
 * - **Progress** = the part of the line near the position that continues the progress so far ([BACK_M] behind to
 *   [AHEAD_M] ahead). Where the line uses a path twice (out-and-back, round trip on the same path) the **walking
 *   direction** decides: when the part you are on runs against the way you walk and another part runs with it, progress
 *   moves there – also far ahead (you turned around before the turnaround point: the way back counts down).
 * - **Walking backwards** along the route (no part runs your way): progress goes back with you, so the time to go
 *   grows. After [BACKWARD_M] backwards this counts as leaving the route (the normal off-route question follows).
 * - **Start anywhere** (mid-route start, new line during a tour): the first fixes search the whole line; with a known
 *   walking direction the part running that way, else the earliest one (corrected as soon as the direction is known).
 * - Off-route distance = setting + accuracy margin (min(accuracy, [MAX_MARGIN_M])).
 * - Confirmed only after [CONFIRM_MS] off **and** having moved [MOVE_M] (+ margin) since leaving: a break with GPS
 *   drift or a single jump never triggers.
 * - Back on route (hysteresis): within half the off distance for [REJOIN_FIXES] fixes in a row.
 * - GPS lost ([gpsLost]): timers stop, nothing is confirmed; a confirmed off-route state is kept (the question stays
 *   until the situation is really gone).
 */
class OffRouteDetector(
    /** Off-route distance from Settings (25 / 40 / 75 m). */
    var offRouteM: Double = 40.0,
) {
    enum class State { NO_GPS, ON_ROUTE, LEAVING, OFF_ROUTE }

    /** One good fix (accuracy already filtered by the service); [timeMs] = monotonic clock. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long) {
        val point: GeoPoint get() = GeoPoint(lat, lon)
    }

    data class Status(
        val state: State,
        /** Distance to the line in the search window; null before the first match. */
        val distanceM: Double?,
        /** Metres along the line where the user is (frozen while off the route). */
        val progressM: Double,
        /** This fix confirmed "off route" (LEAVING → OFF_ROUTE, or walked [BACKWARD_M] backwards). */
        val confirmedNow: Boolean = false,
        /** This fix ended a confirmed off-route state (back on the line). */
        val rejoinedNow: Boolean = false,
    )

    var line: NavLine? = null
        private set
    var state = State.NO_GPS
        private set
    var progressM = 0.0
        private set
    var lastDistanceM: Double? = null
        private set
    /** The off-route state was caused by walking backwards along the line (the position is still on it). */
    var backward = false
        private set

    private var started = false          // a first match was made on this line
    private var provisional = false       // matched before the walking direction was known (may be corrected)
    private var gap = false               // GPS was lost: allow a wider window ahead once
    private var noGpsGood = 0             // good fixes in a row while NO_GPS
    private var leaveStartMs = 0L
    private var leaveStart: GeoPoint? = null
    private var leaveMoved = 0.0
    private var rejoinCount = 0
    /** Furthest progress of the current forward run (walking backwards is measured from here). */
    private var highM = 0.0
    /** Lowest progress while off route walking backwards (turning round is measured from here). */
    private var lowM = 0.0
    /** Where the walking direction first spoke for another part of the line (a switch needs [JUMP_WALK_M]). */
    private var jumpFrom: GeoPoint? = null
    /** The last fix was within the off distance of the line. */
    private var onLine = false
    /** Recent fixes for the walking direction (kept across lines: the direction is about walking, not the line). */
    private val recent = ArrayDeque<Fix>()

    /**
     * New line to follow. [progressM] = where the user is known to be on it (e.g. a new route starts at the position);
     * null = unknown: the first fixes search the whole line from [minProgressM] (see the class comment).
     * [assumeOnRoute] starts in ON_ROUTE (a route just taken) instead of waiting for 3 fixes.
     */
    fun setLine(newLine: NavLine?, progressM: Double? = null, minProgressM: Double = 0.0, assumeOnRoute: Boolean = false) {
        line = newLine
        started = progressM != null
        this.progressM = progressM ?: minProgressM
        highM = this.progressM
        state = if (assumeOnRoute && newLine != null) State.ON_ROUTE else State.NO_GPS
        noGpsGood = 0; rejoinCount = 0; gap = false; jumpFrom = null; provisional = false; backward = false
        onLine = assumeOnRoute && newLine != null
        leaveStart = null; leaveMoved = 0.0; lastDistanceM = null
    }

    /** Keeps the walking direction of [other] (the navigated line was replaced during the tour). */
    fun adoptHeading(other: OffRouteDetector) { recent.clear(); recent.addAll(other.recent) }

    /** Off distance for a fix with this accuracy. */
    fun offDistance(accuracyM: Double) = offRouteM + min(max(accuracyM, 0.0), MAX_MARGIN_M)

    /** Where the user left the line for re-routing: the progress, or where walking backwards started. */
    val leftAtM: Double get() = if (started) max(progressM, highM) else progressM

    /**
     * Progress to compute "to go" from: known while on the line (also walking backwards and during the first seconds of
     * leaving), null when not on the route (confirmed off route, or not matched yet).
     */
    val shownAlongM: Double?
        get() = when (state) {
            State.ON_ROUTE, State.LEAVING -> progressM
            State.OFF_ROUTE -> progressM.takeIf { backward && onLine }
            State.NO_GPS -> progressM.takeIf { started && onLine }
        }

    /** Progress may be used to learn the pace (on the line, walking it forwards). */
    val learnable: Boolean get() = state == State.ON_ROUTE && highM - progressM < LEARN_BACK_M

    /** No good fix for a while (R-04): no timers, no alarms; a confirmed state stays until the user is back. */
    fun gpsLost() {
        if (state == State.NO_GPS) return
        if (state != State.OFF_ROUTE) state = State.NO_GPS
        noGpsGood = 0; rejoinCount = 0; gap = true; jumpFrom = null
        leaveStart = null
    }

    fun onFix(f: Fix): Status {
        remember(f)
        val l = line
        if (l == null || !l.isUsable) return Status(state, null, progressM)
        val p = f.point
        val dOff = offDistance(f.accuracyM)
        val dOn = dOff / 2
        val h = move()
        val pick = pick(l, p, dOff, h) ?: return Status(state, null, progressM)
        val m = pick.match
        lastDistanceM = m.distanceM
        val within = m.distanceM <= dOff
        onLine = within
        if (!started) {
            progressM = m.alongM; highM = progressM
            if (within) { started = true; provisional = h == null }
        }
        if (pick.jumped) { progressM = m.alongM; highM = progressM }
        when (state) {
            State.NO_GPS -> {
                noGpsGood++
                if (within) progressM = m.alongM
                if (noGpsGood >= REJOIN_FIXES) {
                    gap = false
                    if (within) state = State.ON_ROUTE
                    else startLeaving(f)
                }
            }
            State.ON_ROUTE -> {
                if (within) {
                    progressM = m.alongM
                    if (walkedBackwards(l, h)) {
                        state = State.OFF_ROUTE; backward = true; rejoinCount = 0; lowM = progressM
                        return Status(state, m.distanceM, progressM, confirmedNow = true)
                    }
                } else startLeaving(f)
            }
            State.LEAVING -> {
                if (within) {
                    progressM = m.alongM; state = State.ON_ROUTE; leaveStart = null
                } else {
                    leaveStart?.let { leaveMoved = max(leaveMoved, Geo.distance(it, p)) }
                    val needMove = MOVE_M + min(f.accuracyM, MAX_MARGIN_M)
                    if (f.timeMs - leaveStartMs >= CONFIRM_MS && leaveMoved >= needMove) {
                        state = State.OFF_ROUTE; rejoinCount = 0; backward = false
                        return Status(state, m.distanceM, progressM, confirmedNow = true)
                    }
                }
            }
            State.OFF_ROUTE -> {
                if (backward) {
                    if (!within) { backward = false; rejoinCount = 0 } // now really away from the line: progress stays
                    else {
                        progressM = m.alongM
                        lowM = min(lowM, progressM)
                        // back on the route = walking it forwards again (really gaining ground, not a bend)
                        if (progressM >= lowM + REJOIN_FORWARD_M && h != null && dir(l, m.alongM, h) > DIR_MIN) {
                            if (++rejoinCount >= REJOIN_FIXES) {
                                state = State.ON_ROUTE; rejoinCount = 0; backward = false; highM = progressM
                                return Status(state, m.distanceM, progressM, rejoinedNow = true)
                            }
                        } else rejoinCount = 0
                    }
                } else if (m.distanceM <= dOn) {
                    rejoinCount++
                    if (rejoinCount >= REJOIN_FIXES) {
                        progressM = m.alongM; state = State.ON_ROUTE; rejoinCount = 0; gap = false; highM = progressM
                        return Status(state, m.distanceM, progressM, rejoinedNow = true)
                    }
                } else rejoinCount = 0
            }
        }
        return Status(state, m.distanceM, progressM)
    }

    /**
     * Confirms "off route" at once (user asked for navigation while away from the line, e.g. "Continue navigation"):
     * true when the fix is beyond the off distance.
     */
    fun forceCheck(f: Fix): Boolean {
        remember(f)
        val l = line ?: return false
        val m = pick(l, f.point, offDistance(f.accuracyM), move())?.match ?: return false
        lastDistanceM = m.distanceM
        if (!started) { progressM = m.alongM; highM = progressM }
        if (m.distanceM <= offDistance(f.accuracyM)) {
            if (!started) { started = true; provisional = move() == null }
            progressM = m.alongM; state = State.ON_ROUTE; noGpsGood = 0; onLine = true
            return false
        }
        onLine = false
        state = State.OFF_ROUTE; rejoinCount = 0; noGpsGood = 0; backward = false
        return true
    }

    private fun startLeaving(f: Fix) {
        state = State.LEAVING; leaveStartMs = f.timeMs; leaveStart = f.point; leaveMoved = 0.0
    }

    /** Updates the forward high-water mark; true when the user has now walked [BACKWARD_M] backwards along the line. */
    private fun walkedBackwards(l: NavLine, h: Move?): Boolean {
        if (provisional) {
            // matched before the direction was known: the first direction-checked fix sets the start of the run
            highM = progressM
            if (h != null) provisional = false
            return false
        }
        if (progressM > highM) highM = progressM
        return highM - progressM >= BACKWARD_M && h != null && dir(l, progressM, h) < -DIR_MIN
    }

    private class Pick(val match: NavLine.Match, val jumped: Boolean = false)

    private fun pick(l: NavLine, p: GeoPoint, dOff: Double, h: Move?): Pick? {
        if (!started) {
            val parts = l.parts(p, dOff)
            if (parts.isEmpty()) return l.match(p)?.let { Pick(it) } // nowhere near yet: distance only
            if (h != null) parts.firstOrNull { dir(l, it.alongM, h) > DIR_MIN }?.let { return Pick(it) }
            return Pick(parts.first())
        }
        val off = (state == State.OFF_ROUTE && !backward) || state == State.LEAVING
        val lo = progressM - BACK_M
        val hi = progressM + if (off || gap) AHEAD_OFF_M else AHEAD_M
        val near = l.parts(p, dOff, lo, hi)
        if (near.isEmpty()) {
            jumpFrom = null
            return l.match(p, lo, hi, preferAlongM = progressM)?.let { Pick(it) } // off the line: distance for the state machine
        }
        val cont = near.minBy { abs(it.alongM - progressM) }
        if (h == null || dir(l, cont.alongM, h) > -DIR_MIN) { jumpFrom = null; return Pick(cont) }
        // the part you are on runs against your walking direction: a part that runs clearly with it (way back of an
        // out-and-back, rest of a round trip) wins – also far ahead; none = walking backwards along the route.
        // A switch needs clear evidence over [JUMP_WALK_M] of walking (a short spur walked out and back is no reason).
        val fwd = l.parts(p, dOff, lo, l.lengthM).filter { dir(l, it.alongM, h) > JUMP_DIR }.minByOrNull { abs(it.alongM - progressM) }
        if (fwd == null) { jumpFrom = null; return Pick(cont) }
        val from = jumpFrom ?: p.also { jumpFrom = it }
        if (Geo.distance(from, p) < JUMP_WALK_M) return Pick(cont)
        jumpFrom = null
        provisional = false
        return Pick(fwd, jumped = true)
    }

    /** The last stretch walked: from the newest fix back to the last one at least [HEADING_M] away. */
    private class Move(val from: GeoPoint, val distM: Double)

    /**
     * Does the line at [alongM] run the way the user walks? Where was the start of the last stretch walked: on the line
     * just before [alongM] (walking it forwards) or just after it (backwards)? +1 = with the line, −1 = against it,
     * near 0 = unknown (e.g. a spur whose two legs are the same path). Path-based, so a hairpin bend (the walking
     * direction is still the old one) does not fool it, and the other leg of an out-and-back is told apart.
     */
    private fun dir(l: NavLine, alongM: Double, mv: Move): Double {
        val w = 3 * mv.distM + 50
        val far = 2 * mv.distM + 50
        val before = l.match(mv.from, alongM - w, alongM - 1)?.distanceM ?: far
        val after = l.match(mv.from, alongM + 1, alongM + w)?.distanceM ?: far
        return (after - before) / (after + before + 1)
    }

    private fun remember(f: Fix) {
        val last = recent.lastOrNull()
        if (last != null && f.timeMs < last.timeMs) recent.clear() // clock restarted
        recent.addLast(f)
        while (recent.size > 2 && f.timeMs - recent.first().timeMs > HEADING_MAX_MS) recent.removeFirst()
    }

    private fun move(): Move? {
        val now = recent.lastOrNull() ?: return null
        for (k in recent.size - 2 downTo 0) {
            val o = recent[k]
            if (now.timeMs - o.timeMs > HEADING_MAX_MS) break
            val d = Geo.distance(o.point, now.point)
            if (d >= HEADING_M) return Move(o.point, d)
        }
        return null
    }

    /** Walking direction (degrees from north) over the last [HEADING_M]; null = not moving. */
    fun heading(): Double? = move()?.let { m -> recent.lastOrNull()?.let { TurnDetector.bearing(m.from, it.point) } }

    companion object {
        const val CONFIRM_MS = 15_000L
        const val MOVE_M = 25.0
        const val MAX_MARGIN_M = 20.0
        const val REJOIN_FIXES = 3
        const val BACK_M = 300.0
        const val AHEAD_M = 2_000.0
        /** While off the route (or after a GPS gap) a rejoin further ahead also counts (shortcut). */
        const val AHEAD_OFF_M = 5_000.0
        /** Walking backwards along the line this far = off route (owner rule 2026-10-19). */
        const val BACKWARD_M = 200.0
        /** Off route walking backwards: back on it after walking forwards this far again. */
        const val REJOIN_FORWARD_M = 30.0
        /** Walked backwards more than this: not learned (pace is learned walking forwards). */
        const val LEARN_BACK_M = 30.0
        /** Walking direction from fixes at least this far apart … */
        const val HEADING_M = 30.0
        /** … and at most this old. */
        const val HEADING_MAX_MS = 180_000L
        /** |cos| above this: the line runs with / against the walking direction (~72°). */
        const val DIR_MIN = 0.3
        /** A switch to another part of the line needs that part clearly running your way (score above this) … */
        const val JUMP_DIR = 0.6
        /** … over this much walking. */
        const val JUMP_WALK_M = 40.0
    }
}
