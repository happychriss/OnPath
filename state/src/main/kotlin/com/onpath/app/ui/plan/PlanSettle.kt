package com.onpath.app.ui.plan

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.CacheOnlyEngine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlanAnchors
import com.onpath.app.routing.PlanResult
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.ui.plan.PlanRecalcState.Run
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The idle settling's routing-thread body (detour-stitch.md §8.15, piece W2), without coroutines: the leg cap
 * ([failures] per leg id, [capEpoch], [FAIL_CAP]), what a taken run asks to settle ([prepare]) and the blocking work
 * ([settle]: the settle itself, the cap's count, the quiet re-run from the cache alone). The app's [PlanRecalcSlice] adds
 * the threads and the cancel handles, the planner page calls the same two methods on its own thread.
 *
 * The cap map is an immutable map in an atomic reference (no concurrent map in the page): [prepare] runs on the main thread,
 * [settle] on the routing thread.
 */
class PlanSettle {
    /** One leg of the settling by its exact ends and profile: what the cap counts. */
    data class LegId(val a: GeoPoint, val b: GeoPoint, val act: Activity, val sac: Int)

    /** What [prepare] asks for: the plan expanded with its detour points, the profile, the legs that never route, the quiet run's [again]. */
    class Work(
        val expanded: com.onpath.app.routing.ExpandedPlan, val act: Activity, val level: Int,
        val noRoute: Set<Pair<GeoPoint, GeoPoint>>, val again: Run,
    )

    private val failures = AtomicReference<Map<LegId, Int>>(emptyMap())
    private var capEpoch = 0

    /** Failures counted for a leg (2 = [FAIL_CAP], skipped); for tests and the page's trace. */
    fun failuresOf(a: GeoPoint, b: GeoPoint, act: Activity, sac: Int): Int = failures.get()[LegId(a, b, act, sac)] ?: 0

    /** Number of legs with a count. */
    fun failureCount(): Int = failures.get().size

    private fun clearFailures() { failures.set(emptyMap()) }

    private fun bump(id: LegId): Int {
        while (true) {
            val old = failures.get()
            val n = (old[id] ?: 0) + 1
            if (failures.compareAndSet(old, old + (id to n))) return n
        }
    }

    /**
     * A run was taken ([run] gave [res]): null = nothing to settle (no anchors: the cap starts anew; new routing data
     * ([legCache]'s epoch): the cap starts anew and it goes on; a tour runs: [tourRuns]). Otherwise the work. [planPointsNow]: the plan's points as they stand.
     */
    fun prepare(run: Run, res: PlanResult, planPointsNow: List<GeoPoint>, legCache: LegCache, tourRuns: () -> Boolean = { false }): Work? {
        if (run.anchors.isEmpty()) { clearFailures(); return null } // anchors cleared: the cap starts anew; no anchors, no settling
        // not during a tour: the tour follows its line and must not see it shift under the position (the plan's re-run is
        // never told to the tour either); a tour's own edits re-plan and settle after it
        if (tourRuns()) { Problems.trace("routing", "Settling skipped: a tour runs"); return null }
        if (legCache.epoch != capEpoch) { clearFailures(); capEpoch = legCache.epoch } // new routing data: every leg may try again
        val expanded = PlanAnchors.expand(planPointsNow, run.anchors, run.pins)
        val act = run.act; val level = run.level
        // legs the taken run drew straight (no route): a cold device gets the same, nothing to align – never asked again
        val noRoute = Planner.skipNoRoute(expanded, res.route)
        // the settled line is a re-run of the plan as it stands (its points: those of the run, or snapped onto its line)
        val again = Run(run.gen, planPointsNow, run.pins, act, level, 1, false, run.f, run.started, null, null, run.anchors)
        return Work(expanded, act, level, noRoute, again)
    }

    /**
     * Blocking, on the routing thread: settles [work] with [engine], counts the failed legs, then shows the plan again from
     * the cache alone. null = nothing to show (nothing routed, cancelled, a tour began, or a leg missing from the cache).
     * [tourRuns]: read between legs. Exceptions go to the caller (it reports them).
     */
    fun settle(work: Work, engine: RoutingEngine, legCache: LegCache, cancel: RouteCancel, tourRuns: () -> Boolean): PlanResult? {
        val expanded = work.expanded; val act = work.act; val level = work.level; val noRoute = work.noRoute
        Problems.trace("routing", "Settling started: ${expanded.points.size - 1} legs of the plan with detour points")
        val s = Planner(engine, cancel, legs = legCache).settle(expanded, act, level) { a, b ->
            tourRuns() || (a to b) in noRoute || (failures.get()[LegId(a, b, act, level)] ?: 0) >= FAIL_CAP // a tour started: no further leg
        }
        // a sub-leg that always falls back to the full leg counts like a failing one (it is asked again every time)
        for ((a, b) in s.failedLegs + s.fellBackLegs) {
            val n = bump(LegId(a, b, act, level))
            if (n == FAIL_CAP) Problems.warn("routing", "A leg of the plan could not be settled twice – skipped until new routing data or a plan without detour points")
        }
        Problems.trace("routing", "Settling: routed ${s.routed} / failed ${s.failed}" + if (s.cancelled) " / cancelled" else "")
        if (s.routed < 1 || s.cancelled || cancel.isCancelled) return null
        if (tourRuns()) { Problems.trace("routing", "Settling stopped: a tour began"); return null }
        // the quiet re-run comes from the cache ALONE (an engine that refuses every request): nothing is routed again, and
        // a leg the cache lacks would be straight – a line worse than the taken one – so then nothing is shown
        Problems.trace("routing", "Settling: the plan is shown again from the settled legs")
        // a cache miss for any leg that is not a known no-route leg cancels the quiet run's OWN signal (never the settle's or a newer run's):
        // no fallback without anchors, no worse line – the taken line stays
        val quietCancel = RouteCancel()
        val missed = AtomicBoolean(false) // atomic: the planner may ask legs on more than one thread
        val cacheOnly = CacheOnlyEngine(known = { x, y -> (x to y) in noRoute }, onMiss = { _, _ -> missed.set(true); quietCancel.cancel() })
        val quiet = try { PlanRecalcState.route(work.again, cacheOnly, legCache, quietCancel, { _, _ -> }) } catch (e: Exception) {
            if (!missed.get()) throw e
            Problems.trace("routing", "Settling: a leg is missing from the cache – the settled line is not shown, the line stays as it is")
            return null
        }
        if (missed.get()) { Problems.trace("routing", "Settling: a leg is missing from the cache – the settled line is not shown, the line stays as it is"); return null }
        return quiet.result
    }

    companion object {
        /** Failures after which a leg is skipped. */
        const val FAIL_CAP = 2
    }
}
