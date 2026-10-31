package com.onpath.app.routing

import com.onpath.app.debug.Problems
import com.onpath.app.time.Factors
import com.onpath.app.time.TimeEstimate
import com.onpath.app.ui.PlanGap
import com.onpath.app.ui.PlanGaps
import com.onpath.app.ui.RouteSpot
import com.onpath.app.ui.RouteWarnings

/**
 * Everything the plan sheet shows for one set of plan points, worked out in ONE place (owner rule: routes, stops,
 * numbers and warnings identical in the app and the browser planner – both call [PlanResult.plan]; docs/handoff-web-planresult.md).
 * Pure Kotlin, no Android (SharedCoreRuleTest; the web engine compiles it with TeaVM). Blocking: call it off the main
 * thread. Threading, cancelling and "a newer request supersedes" stay with the caller (AppViewModel / the web page).
 *
 * @property routed the plan points as they were routed (the request); [pins] = POI-pinned indices (passed exactly),
 *   [named] = indices of the named stops (both keep their coordinates).
 * @property route the planned route, its time from OnPath's own model ([TimeEstimate.planSeconds]) – not the router's.
 * @property points the plan points after snapping (same size and order as [routed]): a free point moves onto the way the
 *   router used ([WaypointSnap.apply]); POI-pinned and named stops and every point the route does not reach or skips
 *   ([far], `PlannedRoute.skipped`, Bug 01) keep their place – their gap is shown, never hidden by a silent move.
 * @property moved some point moved ([points] differs from [routed]); false = [points] is [routed].
 * @property far indices kept because the route does not reach them (≥ [PlanGaps.FAR_M]) or skips them.
 * @property gaps the plan points the route does not reach ([PlanGaps.of] on [points]): grey dashed piece + red line.
 * @property spots the route warnings ([RouteWarnings.detect]): the amber line and its "On this route" list.
 */
data class PlanResult(
    val routed: List<GeoPoint>,
    val route: PlannedRoute,
    val activity: Activity,
    val sac: Int,
    val pins: Set<Int>,
    val named: Set<Int>,
    val points: List<GeoPoint>,
    val moved: Boolean,
    val far: Set<Int>,
    val gaps: List<PlanGap>,
    val spots: List<RouteSpot>,
) {
    /** The difficulty badge ([RouteSummary.rating]: distance + ascent, raised by trail technique). Worked out on first use. */
    val rating: RouteSummary.Rating by lazy { RouteSummary.rating(activity, route.distanceM, route.ascentM, route.points) }

    /** The elevation profile ([ProfileData.of] of the route line); null = not enough elevation. Worked out on first use. */
    val profile: ProfileData? by lazy { ProfileData.of(route.points) }

    /** The profile's gradient classes ([ProfileData.steepness]); empty without a profile. Worked out on first use. */
    val steepness: List<RouteSummary.Steepness> by lazy { profile?.steepness() ?: emptyList() }

    /**
     * The same result with [named] as the named stops (the names changed while the route was calculated): only the
     * snapping and the gaps are worked out again, the route stays.
     */
    fun withNamed(named: Set<Int>): PlanResult {
        if (named == this.named) return this
        val s = snap(routed, route, pins, named)
        return copy(named = named, points = s.points, moved = s.moved, far = s.far,
            gaps = PlanGaps.of(s.points, route.snapped, route.skipped))
    }

    /** Where the plan points end up ([snap]). */
    data class Snap(val points: List<GeoPoint>, val moved: Boolean, val far: Set<Int>)

    companion object {
        /**
         * Routes [points] for [activity] / T level [sac] with [planner] (leg-by-leg fallback, the [pins] passed
         * exactly) and works out the rest ([of]). [named] = indices of the named stops (they keep their place),
         * [factors] = the pace setting ([TimeEstimate.planFactors]). Throws what the planner throws (e.g. cancelled).
         */
        fun plan(planner: Planner, points: List<GeoPoint>, activity: Activity, sac: Int, pins: Set<Int>, named: Set<Int>,
                 factors: Factors): PlanResult =
            of(points, planner.plan(points, activity, sac, pins), activity, sac, pins, named, factors)

        /** The same with a fresh [Planner] on [engine] ([cancel] = the Cancel signal, null = none). */
        fun plan(engine: RoutingEngine, points: List<GeoPoint>, activity: Activity, sac: Int, pins: Set<Int>, named: Set<Int>,
                 factors: Factors, cancel: RouteCancel? = null): PlanResult =
            plan(Planner(engine, cancel), points, activity, sac, pins, named, factors)

        /** The result for a route [planned] (as the planner returned it) of exactly [points]. */
        fun of(points: List<GeoPoint>, planned: PlannedRoute, activity: Activity, sac: Int, pins: Set<Int>, named: Set<Int>,
               factors: Factors): PlanResult {
            val route = withModelTime(planned, activity, factors)
            val s = snap(points, route, pins, named)
            val spots = try { RouteWarnings.detect(route.points, activity, sac) } catch (e: Exception) {
                Problems.warn("routing", "Route warnings could not be worked out; none shown", e); emptyList()
            }
            return PlanResult(points, route, activity, sac, pins, named, s.points, s.moved, s.far,
                PlanGaps.of(s.points, route.snapped, route.skipped), spots)
        }

        /**
         * The plan's time from OnPath's own model (time/RouteTimes), not the router's: the same number as the route list
         * and Navigate "to go" at the start. Moving time, rounded for display.
         */
        fun withModelTime(r: PlannedRoute, a: Activity, f: Factors): PlannedRoute =
            r.copy(durationS = TimeEstimate.planSeconds(r.points, a, f))

        /**
         * Moves free points of [routed] onto the way the router used for [route] (owner: no off-road guidance), so
         * marker, stored plan and route line agree. [pins], [named] and every point the route does not reach or skips
         * although it lies on a way ([PlannedRoute.skipped]: never moved silently – its gap is shown) keep their place.
         */
        fun snap(routed: List<GeoPoint>, route: PlannedRoute, pins: Set<Int>, named: Set<Int>): Snap {
            // a point the route does not reach – or skips although it lies on a way – keeps its place: the gap is shown
            // (PlanGaps), not hidden by moving the point (field bug 2026-10-30: a stop moved 47 m onto the line, no word)
            val far = PlanGaps.farIndices(routed, route.snapped, route.skipped)
            val snapped = WaypointSnap.apply(routed, route.snapped, pins + far + named)
            return Snap(snapped ?: routed, snapped != null, far)
        }
    }
}
