package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.domain.PlanGaps
import kotlin.math.min

/**
 * A plan point the hike's T level sends on a long detour (owner 2026-11-21, „Hinweis, du entscheidest“): the route stays as
 * it is, the plan sheet's red line says "② macht 4,2 km Umweg" ([PlanGaps.detourText]) and he changes the level or deletes
 * the point himself. [extraM] = what the level costs at this point, from real routes – never a distance to a straight line.
 * [way] = the drawn route around the point (a tap shows it).
 *
 * A long way via a point alone proves nothing: a summit planned as a spur is long on purpose. So:
 *  1. [toCheck] (no routing): the way via the point is ≥ [LOOK_RATIO] × and ≥ [LOOK_OVER_M] over the straight line of its
 *     neighbours – only then it is worth a look, at most [MAX_POINTS] points, the worst first.
 *  2. The same points one level harder (T1 → T2 …; hike only, nothing at T5): the route must be clearly shorter.
 *  3. The two neighbours without the point, at both levels: the part that is the stretch's own (long at this level with or
 *     without the point) does not count, or "② macht … Umweg" would promise a saving that deleting ② does not bring ([extra]).
 *
 * A point the route does not reach but a harder level does ([PlanReach.Fix.Level], his Mutkopf plan: T1 30.3 km and ② stays
 * 552 m off, T2 10.8 km): the same three steps against the level that reaches it; the line then says both
 * ([PlanGaps.reachText]: "Umweg 17,4 km notwendig, ② erst ab Wandern T2 erreichbar").
 *
 * Pure (shared with the planner); blocking – the caller runs it off the main thread, after the route is shown. Never changes
 * the route. A failed or cancelled check gives no hint.
 */
data class PlanDetour(val index: Int, val extraM: Double, val way: List<GeoPoint>, /** The T level that is clearly shorter (owner 2026-11-24: the line names it). */ val level: Int) {
    companion object {
        /** Worth a look: the way via the point is this many times the straight line of its neighbours … */
        const val LOOK_RATIO = 1.5
        /** … and at least this much longer than it (metres). */
        const val LOOK_OVER_M = 2_000.0
        /** "Clearly shorter" at the next level: the point's detour shrinks by at least this much (metres) … */
        const val MIN_EXTRA_M = 1_000.0
        /** … and the way via the point is at least this share shorter (a kilometre on a 30 km day is no reason for a red line). */
        const val MIN_SHORTER_SHARE = 0.2
        /** At most this many points are routed again per plan. */
        const val MAX_POINTS = 3
        /** One request of the check. */
        const val TRY_TIMEOUT_MS = 6_000L
        /** The whole check: no new request after this long (what was found by then stands). */
        const val BUDGET_MS = 20_000L
        /** Neighbours this close are one place (a there-and-back to the point): no way between them to route. */
        const val SAME_PLACE_M = 30.0
        private const val HARDEST_LEVEL = 5

        /** Only a hike has a next harder level; T5 has none. */
        fun applies(activity: Activity, sac: Int): Boolean = activity == Activity.HIKE && sac in 1 until HARDEST_LEVEL

        /** The interior points worth a look (step 1), the worst first, at most [MAX_POINTS]. No routing. */
        fun toCheck(points: List<GeoPoint>, route: PlannedRoute, only: Int? = null): List<Int> {
            val legs = route.legs
            if (points.size < 3 || legs.size != points.size - 1) return emptyList()
            return (1 until points.size - 1).mapNotNull { i ->
                if ((only != null && i != only) || legs[i - 1].straight || legs[i].straight) return@mapNotNull null
                val via = Geo.length(legs[i - 1].points) + Geo.length(legs[i].points)
                val straight = Geo.distance(points[i - 1], points[i + 1])
                if (via >= LOOK_RATIO * straight && via - straight >= LOOK_OVER_M) i to via - straight else null
            }.sortedByDescending { it.second }.take(MAX_POINTS).map { it.first }
        }

        /**
         * The metres the level costs at this point, or null when it is not clearly the level's doing. [viaHere] / [viaHarder] =
         * the way through the point at the plan's level / one harder, [directHere] / [directHarder] = its neighbours without
         * the point. What counts is how much less the point adds one level up – and never more than that level really saves.
         */
        fun extra(viaHere: Double, viaHarder: Double, directHere: Double, directHarder: Double): Double? {
            val saved = viaHere - viaHarder
            if (saved < MIN_EXTRA_M || viaHarder > viaHere * (1 - MIN_SHORTER_SHARE)) return null
            val ownPart = (viaHere - directHere) - (viaHarder - directHarder)
            return min(saved, ownPart).takeIf { it >= MIN_EXTRA_M }
        }

        /**
         * The detours of the plan [res], in plan order; empty when there is nothing to say: not a hike below T5, a failed leg,
         * the check failed or [cancel] fired. With a point the route does not reach, only that first gap is looked at, and only
         * when [reach] says a harder level gets there; any other gap keeps its own line.
         */
        fun find(eng: RoutingEngine, res: PlanResult, cancel: RouteCancel? = null, now: () -> Long = { System.currentTimeMillis() },
                 reach: PlanReach? = null): List<PlanDetour> {
            if (!applies(res.activity, res.sac) || res.route.error != null) return emptyList()
            if (eng is StraightLineEngine) return emptyList() // no routing data: its "ways" are no evidence for a detour
            val gap = res.gaps.firstOrNull()
            val reachLevel = (reach?.fix as? PlanReach.Fix.Level)?.sac?.takeIf { gap != null && !gap.skipped && reach.index == gap.index }
            if (gap != null && reachLevel == null) return emptyList()
            val points = res.points
            val legs = res.route.legs
            val started = now()
            val harder = reachLevel ?: (res.sac + 1)
            var stopped = false

            /** The length of the way [from] → [to] at T [sac]; null = no usable answer (it ends away from a point, failed). */
            fun way(from: GeoPoint, to: GeoPoint, sac: Int): Double? {
                if (stopped || cancel?.isCancelled == true || now() - started > BUDGET_MS) { stopped = true; return null }
                val r = try {
                    eng.route(listOf(from, to), Activity.HIKE, sac, RouteLimits(TRY_TIMEOUT_MS, cancel))
                } catch (e: RoutingException) {
                    if (e.kind == RoutingException.Kind.CANCELLED || cancel?.isCancelled == true) {
                        stopped = true // expected: the plan changed, its newer run goes on
                        Problems.trace("routing", "Detour check cancelled")
                    } else if (e.kind == RoutingException.Kind.OTHER) {
                        // expected: no way at this level; no evidence for a detour
                        Problems.trace("routing", "Detour check: no way at T$sac")
                    } else {
                        Problems.warn("routing", "Detour check at T$sac failed (${e.kind}); no hint for this point", e)
                    }
                    return null
                } catch (e: Exception) {
                    Problems.warn("routing", "Detour check at T$sac failed; no hint for this point", e)
                    return null
                }
                val start = r.matched.getOrNull(0) ?: r.points.firstOrNull() ?: return null
                val end = r.matched.getOrNull(1) ?: r.points.lastOrNull() ?: return null
                // a leg that ends away from the point is shorter because it does not get there: no evidence
                if (r.points.size < 2 || Geo.distance(start, from) >= PlanGaps.FAR_M || Geo.distance(end, to) >= PlanGaps.FAR_M) return null
                return Geo.length(r.points)
            }

            fun direct(a: GeoPoint, b: GeoPoint, sac: Int): Double? = if (Geo.distance(a, b) < SAME_PLACE_M) 0.0 else way(a, b, sac)

            val found = ArrayList<PlanDetour>()
            for (i in toCheck(points, res.route, gap?.index)) {
                val before = points[i - 1]; val point = points[i]; val after = points[i + 1]
                val viaHere = Geo.length(legs[i - 1].points) + Geo.length(legs[i].points)
                val toPoint = way(before, point, harder) ?: if (stopped) break else continue
                val fromPoint = way(point, after, harder) ?: if (stopped) break else continue
                val viaHarder = toPoint + fromPoint
                // the same at both levels (a summit spur): settled without routing the neighbours
                if (extra(viaHere, viaHarder, 0.0, 0.0) == null) continue
                val directHere = direct(before, after, res.sac) ?: if (stopped) break else continue
                val directHarder = direct(before, after, harder) ?: if (stopped) break else continue
                val extraM = extra(viaHere, viaHarder, directHere, directHarder) ?: continue
                val onward = legs[i].points.let { if (it.firstOrNull() == legs[i - 1].points.lastOrNull()) it.drop(1) else it }
                found += PlanDetour(i, extraM, legs[i - 1].points + onward, harder)
            }
            if (cancel?.isCancelled == true) return emptyList()
            return found.sortedBy { it.index }
        }
    }
}
