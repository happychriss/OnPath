package com.onpath.app.navigation

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteResult
import kotlin.math.max

/**
 * How the rest of the followed route fits one activity (Navigate → tap the activity icon → check-and-switch sheet).
 * [line] is the route the offline router found for [activity] from the position through the remaining stops; it is
 * used as is when the user switches (no second wait). [problems] empty = "fits".
 */
data class ActivityFit(
    val activity: Activity,
    val sac: Int,
    val problems: List<String>,
    /** Length of the new route for [activity] and its time. */
    val distanceM: Double,
    val timeS: Double,
    /** New length minus what is left of the followed route (< 0 = shorter). */
    val extraM: Double,
    val line: List<GeoPoint>,
) {
    val fits: Boolean get() = problems.isEmpty()
}

/** A proposed activity switch, shown as [NavPrompt.Question.switch]: taking it navigates [session] from now on. */
data class ActivitySwitch(val activity: Activity, val sac: Int, val session: NavSession, val timeS: Double)

/**
 * Pure logic of the activity check (owner request 2026-10-18: "a wrong activity is a disaster").
 *
 * Approach: for every other activity the **rest of the tour is routed offline** (BRouter, position → remaining stops;
 * a followed track keeps its shape through vias every [TRACK_VIA_M]). The followed line carries no per-way tags, so
 * "does it fit" is read from what the router does for that activity:
 * - parts of the followed rest the new route does not use ([avoidedM]) = not suited for that activity (no access,
 *   too hard, steps, above the SAC limit – the router's profile avoids them);
 * - the new route's own way types / surfaces: steps for bikes, unpaved for road bikes, rough trail for trekking bikes,
 *   busy roads for hiking.
 * The same result is the proposal when the user switches.
 */
object ActivityCheck {
    /** File the app writes a proposed session to before telling the service (lines are too big for an Intent). */
    const val PROPOSAL_FILE = "nav-proposal.json"
    /** Per activity request; the rows compute one after the other (BRouter runs one request at a time). */
    const val TIMEOUT_MS = 15_000L
    /** Followed tracks (no stops): vias this far apart keep the route on the track where the activity allows it. */
    const val TRACK_VIA_M = 2_000.0
    const val MAX_TRACK_VIAS = 12
    /** A sample of the followed rest this close to the new route counts as used by it. */
    const val ON_LINE_M = 30.0
    const val SAMPLE_M = 25.0
    /** Avoided parts below this (and below [AVOID_MIN_SHARE] of the rest) are noise (snapping, small detours). */
    const val AVOID_MIN_M = 150.0
    const val AVOID_MIN_SHARE = 0.03
    const val STEPS_MIN_M = 10.0
    const val ROUGH_MIN_M = 500.0
    const val BUSY_MIN_M = 300.0
    /** Less than this left of the route: nothing to check. */
    const val MIN_REST_M = 50.0

    /** What is routed for each activity: [waypoints] (position first), the followed [line] left, the plan's [stops] left. */
    data class Rest(val waypoints: List<GeoPoint>, val line: List<GeoPoint>, val stops: List<NavStop>)

    /**
     * The rest of the tour from [position]: [activeProgressM] = progress on the navigated line (service). Stops passed
     * or visited are dropped (like "Continue navigation"); after a new route was taken, stops it skipped stay skipped.
     * Null = nothing left (destination reached / no usable line).
     */
    fun rest(session: NavSession, activeProgressM: Double, position: GeoPoint): Rest? {
        val active = NavLine(session.active)
        if (!active.isUsable) return null
        val progress = activeProgressM.coerceIn(0.0, active.lengthM)
        val line = active.restFrom(progress)
        if (active.lengthM - progress < MIN_REST_M) return null
        if (session.stops.isEmpty()) {
            val restM = active.lengthM - progress
            val step = max(TRACK_VIA_M, restM / (MAX_TRACK_VIAS + 1))
            val vias = ArrayList<GeoPoint>()
            var a = progress + step
            while (a < active.lengthM - step / 2 && vias.size < MAX_TRACK_VIAS) { vias += active.pointAt(a); a += step }
            return Rest(listOf(position) + vias + active.points.last(), line, emptyList())
        }
        val onOriginal = when {
            !session.rerouted -> progress
            progress >= session.spliceAtM -> session.resumeAlongM + (progress - session.spliceAtM)
            // still on the way back: it leads to the rest of the original (stops before it were skipped)
            else -> session.resumeAlongM - RerouteTargets.PASSED_SLACK_M - 5.0
        }
        val base = RerouteTargets.base(NavLine(session.original), position, onOriginal)
        val passed = RerouteTargets.passed(session.stops, base, session.visited)
        val left = session.stops.filterIndexed { i, _ -> i !in passed }
        if (left.isEmpty()) return null
        return Rest(listOf(position) + left.map { it.point }, line, left)
    }

    /** Fit of the followed rest [restLine] for [activity], from the route [r] the router found for it. */
    fun evaluate(restLine: List<GeoPoint>, activity: Activity, sac: Int, r: RouteResult,
                 factors: com.onpath.app.time.Factors = com.onpath.app.time.Factors.ONE): ActivityFit {
        val restM = Geo.length(restLine)
        val newM = if (r.distanceM > 0) r.distanceM else Geo.length(r.points)
        val problems = ArrayList<String>()
        val avoided = avoidedM(restLine, r.points)
        if (avoided >= max(AVOID_MIN_M, AVOID_MIN_SHARE * restM)) {
            problems += "${Format.distance(round10(avoided))} not suited for ${who(activity, sac)}"
        }
        fun way(vararg keys: String) = r.wayTypes.filter { it.key in keys }.sumOf { it.meters }
        fun surf(vararg keys: String) = r.surfaces.filter { it.key in keys }.sumOf { it.meters }
        if (activity != Activity.HIKE) {
            val steps = way("steps")
            if (steps >= STEPS_MIN_M) problems += "steps ${Format.distance(round10(steps))}"
        }
        when (activity) {
            Activity.BIKE_TOURING, Activity.EBIKE -> {
                val rough = surf("gravel", "ground", "unpaved", "natural_trail", "alpine")
                if (rough >= ROUGH_MIN_M) problems += "${Format.distance(round10(rough))} unpaved"
            }
            Activity.BIKE_TREKKING -> {
                val rough = surf("ground", "natural_trail", "alpine")
                if (rough >= ROUGH_MIN_M) problems += "${Format.distance(round10(rough))} rough trail"
            }
            Activity.HIKE -> {
                val busy = way("busy_road")
                if (busy >= BUSY_MIN_M) problems += "${Format.distance(round10(busy))} busy road"
            }
            else -> Unit
        }
        return ActivityFit(activity, sac, problems, newM, timeS(r, activity, factors), newM - restM, r.points)
    }

    /** Time of a route for [activity] (same model as the plan sheet and the to-go card: time/RouteTimes). */
    fun timeS(r: RouteResult, activity: Activity, factors: com.onpath.app.time.Factors = com.onpath.app.time.Factors.ONE): Double =
        com.onpath.app.time.TimeEstimate.planSeconds(r.points, activity, factors)

    /** Metres of [rest] farther than [ON_LINE_M] from [route] (sampled every [SAMPLE_M]). */
    fun avoidedM(rest: List<GeoPoint>, route: List<GeoPoint>): Double {
        val r = NavLine(rest)
        if (!r.isUsable) return 0.0
        val other = NavLine(route)
        if (!other.isUsable) return r.lengthM
        var off = 0.0
        var a = SAMPLE_M / 2
        while (a < r.lengthM) {
            val d = other.match(r.pointAt(a))?.distanceM ?: Double.MAX_VALUE
            if (d > ON_LINE_M) off += SAMPLE_M
            a += SAMPLE_M
        }
        return off.coerceAtMost(r.lengthM)
    }

    /** "trekking bikes" / "hiking (T3)". */
    fun who(activity: Activity, sac: Int): String = when (activity) {
        Activity.HIKE -> "hiking (T${sac.coerceIn(1, 5)})"
        Activity.BIKE_TOURING -> "touring bikes"
        Activity.BIKE_TREKKING -> "trekking bikes"
        Activity.EBIKE -> "e-bikes"
        Activity.MTB -> "mountain bikes"
        Activity.EMTB -> "e-MTBs"
    }

    private fun round10(m: Double) = Math.round(m / 10) * 10.0
}
