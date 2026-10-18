package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.max
import kotlin.math.min

/**
 * "Are we really off the route?" – a pure state machine (docs/plan-auto-reroute.md §4), fed with good GPS fixes by
 * the recording service. Unit-tested with simulated traces.
 *
 * - The position is matched to the line only in a window around the progress ([BACK_M] behind to [AHEAD_M] ahead),
 *   so on an out-and-back route the way back does not count as "on route" while you are on the way out.
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
        /** This fix confirmed "off route" (LEAVING → OFF_ROUTE). */
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

    private var started = false          // a first match was made on this line
    private var gap = false               // GPS was lost: allow a wider window ahead once
    private var noGpsGood = 0             // good fixes in a row while NO_GPS
    private var leaveStartMs = 0L
    private var leaveStart: GeoPoint? = null
    private var leaveMoved = 0.0
    private var rejoinCount = 0

    /**
     * New line to follow. [progressM] = where the user is known to be on it (e.g. a new route starts at the position);
     * null = unknown: the first fixes search the whole line from [minProgressM] (earliest match, round trips).
     * [assumeOnRoute] starts in ON_ROUTE (a route just taken) instead of waiting for 3 fixes.
     */
    fun setLine(newLine: NavLine?, progressM: Double? = null, minProgressM: Double = 0.0, assumeOnRoute: Boolean = false) {
        line = newLine
        started = progressM != null
        this.progressM = progressM ?: minProgressM
        state = if (assumeOnRoute && newLine != null) State.ON_ROUTE else State.NO_GPS
        noGpsGood = 0; rejoinCount = 0; gap = false
        leaveStart = null; leaveMoved = 0.0; lastDistanceM = null
    }

    /** Off distance for a fix with this accuracy. */
    fun offDistance(accuracyM: Double) = offRouteM + min(max(accuracyM, 0.0), MAX_MARGIN_M)

    /** No good fix for a while (R-04): no timers, no alarms; a confirmed state stays until the user is back. */
    fun gpsLost() {
        if (state == State.NO_GPS) return
        if (state != State.OFF_ROUTE) state = State.NO_GPS
        noGpsGood = 0; rejoinCount = 0; gap = true
        leaveStart = null
    }

    fun onFix(f: Fix): Status {
        val l = line
        if (l == null || !l.isUsable) return Status(state, null, progressM)
        val p = f.point
        val dOff = offDistance(f.accuracyM)
        val dOn = dOff / 2
        val m = matchFor(l, p) ?: return Status(state, null, progressM)
        lastDistanceM = m.distanceM
        if (!started) { started = true; progressM = m.alongM }
        when (state) {
            State.NO_GPS -> {
                noGpsGood++
                if (m.distanceM <= dOff) progressM = m.alongM
                if (noGpsGood >= REJOIN_FIXES) {
                    gap = false
                    if (m.distanceM <= dOff) state = State.ON_ROUTE
                    else startLeaving(f)
                }
            }
            State.ON_ROUTE -> {
                if (m.distanceM <= dOff) progressM = m.alongM
                else startLeaving(f)
            }
            State.LEAVING -> {
                if (m.distanceM <= dOff) {
                    progressM = m.alongM; state = State.ON_ROUTE; leaveStart = null
                } else {
                    leaveStart?.let { leaveMoved = max(leaveMoved, Geo.distance(it, p)) }
                    val needMove = MOVE_M + min(f.accuracyM, MAX_MARGIN_M)
                    if (f.timeMs - leaveStartMs >= CONFIRM_MS && leaveMoved >= needMove) {
                        state = State.OFF_ROUTE; rejoinCount = 0
                        return Status(state, m.distanceM, progressM, confirmedNow = true)
                    }
                }
            }
            State.OFF_ROUTE -> {
                if (m.distanceM <= dOn) {
                    rejoinCount++
                    if (rejoinCount >= REJOIN_FIXES) {
                        progressM = m.alongM; state = State.ON_ROUTE; rejoinCount = 0; gap = false
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
        val l = line ?: return false
        val m = matchFor(l, f.point) ?: return false
        lastDistanceM = m.distanceM
        if (!started) { started = true; progressM = m.alongM }
        if (m.distanceM <= offDistance(f.accuracyM)) {
            progressM = m.alongM; state = State.ON_ROUTE; noGpsGood = 0
            return false
        }
        state = State.OFF_ROUTE; rejoinCount = 0; noGpsGood = 0
        return true
    }

    private fun startLeaving(f: Fix) {
        state = State.LEAVING; leaveStartMs = f.timeMs; leaveStart = f.point; leaveMoved = 0.0
    }

    private fun matchFor(l: NavLine, p: GeoPoint): NavLine.Match? {
        if (!started) return l.matchEarliest(p, progressM)
        val off = state == State.OFF_ROUTE || state == State.LEAVING
        val ahead = if (off || gap) AHEAD_OFF_M else AHEAD_M
        return l.match(p, progressM - BACK_M, progressM + ahead, preferAlongM = progressM)
    }

    companion object {
        const val CONFIRM_MS = 15_000L
        const val MOVE_M = 25.0
        const val MAX_MARGIN_M = 20.0
        const val REJOIN_FIXES = 3
        const val BACK_M = 300.0
        const val AHEAD_M = 2_000.0
        /** While off the route (or after a GPS gap) a rejoin further ahead also counts (shortcut). */
        const val AHEAD_OFF_M = 5_000.0
    }
}
