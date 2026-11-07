package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.ui.PlanGap
import com.onpath.app.ui.PlanGaps

/**
 * What reaches a plan point the route does not reach (owner 2026-11-07, Pixel "Meran → Mutkopf": at Hike T1 the line
 * stopped 552 m short of ② and the sheet only said "kein Weg für Wandern"). The plan sheet's red line then says
 * "② erst ab T2 erreichbar" ([PlanGaps.reachText]); a tap shows [way] on the map. No switch button (owner: "Ohne
 * Knopf" – the level is changed with the activity button).
 *
 * [fix]: [Fix.Level] a higher T level (hike), [Fix.Bike] a rougher bike activity (Gravelbike, MTB / E-MTB), [Fix.OnFoot]
 * only on foot (a bike plan; the lowest T level that does), [Fix.None] nothing. [way]: the piece that reaches it, from
 * where the route stopped; with [Fix.None] the missing piece itself (the grey dashed line).
 *
 * Pure (SharedCoreRuleTest); blocking – the caller runs it off the main thread, after the plan route, and only when a
 * stop is not reached.
 */
data class PlanReach(val index: Int, val fix: Fix, val way: List<GeoPoint>) {
    sealed interface Fix {
        data class Level(val sac: Int) : Fix
        data class Bike(val activity: Activity) : Fix
        data class OnFoot(val sac: Int) : Fix
        data object None : Fix
    }

    companion object {
        /** One try may take this long (a few hundred metres: normally ~40 ms on the phone). */
        const val TRY_TIMEOUT_MS = 5_000L

        /** The bike activities worth trying after [a], gentle to rough (same motor: E-bike → E-MTB). */
        fun rougher(a: Activity): List<Activity> = when (a) {
            Activity.BIKE_TOURING -> listOf(Activity.BIKE_TREKKING, Activity.MTB)
            Activity.EBIKE -> listOf(Activity.BIKE_TREKKING, Activity.EMTB)
            Activity.BIKE_TREKKING -> listOf(Activity.MTB)
            Activity.MTB, Activity.EMTB, Activity.HIKE -> emptyList()
        }

        /**
         * The easiest change that reaches [gap]'s point, for a plan routed with [activity] at T [sac]. Routes only the
         * missing piece – from where the route stopped ([PlanGap.routeEnd], on a way the plan's level allows, so every
         * rougher level allows it too) to the point: a hike tries T(sac+1)..T5; a bike its [rougher] activities, then
         * on foot T1..T5. Reached = the router matched the point within [PlanGaps.FAR_M]. Null: nothing to search (a
         * skipped stop – it lies on a way) or [cancelled] (the plan changed).
         */
        fun find(eng: RoutingEngine, gap: PlanGap, activity: Activity, sac: Int, cancel: RouteCancel? = null,
                 cancelled: () -> Boolean = { cancel?.isCancelled == true }): PlanReach? {
            if (gap.skipped || cancelled()) return null
            val ask = listOf(gap.routeEnd, gap.point)
            var stopped = false
            fun reaching(a: Activity, level: Int): List<GeoPoint>? {
                if (stopped || cancelled()) { stopped = true; return null }
                val r = try {
                    eng.route(ask, a, level, RouteLimits(TRY_TIMEOUT_MS, cancel))
                } catch (e: RoutingException) {
                    // expected: this level / activity has no way to the point (or the try was cancelled)
                    Problems.trace("routing", "Reach: ${a.name} T$level – no way to point ${gap.index + 1} (${e.kind})")
                    return null
                } catch (e: Exception) {
                    Problems.warn("routing", "Reach: ${a.name} T$level to point ${gap.index + 1} failed; counted as not reached", e)
                    return null
                }
                val at = r.matched.getOrNull(1) ?: r.points.lastOrNull() ?: return null
                return r.points.takeIf { Geo.distance(at, gap.point) < PlanGaps.FAR_M && it.size >= 2 }
            }
            val walk = activity == Activity.HIKE
            var fix: Fix = Fix.None
            var way: List<GeoPoint>? = null
            if (walk) {
                for (t in (sac + 1)..5) { way = reaching(Activity.HIKE, t); if (way != null) { fix = Fix.Level(t); break } }
            } else {
                for (b in rougher(activity)) { way = reaching(b, sac); if (way != null) { fix = Fix.Bike(b); break } }
                if (way == null) for (t in 1..5) { way = reaching(Activity.HIKE, t); if (way != null) { fix = Fix.OnFoot(t); break } }
            }
            if (stopped) return null
            return PlanReach(gap.index, fix, way ?: ask)
        }
    }
}
