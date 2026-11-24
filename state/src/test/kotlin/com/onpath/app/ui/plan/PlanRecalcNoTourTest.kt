package com.onpath.app.ui.plan

import com.onpath.app.domain.AppMode
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.RouteCancel
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.Background
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The route run as the web planner strings it (laptop's asks 2026-11-20): no tour ([PlanTourPort.None]), no offline map data
 * ([MapDataPort.None]), the stages called one after the other on one thread – [PlanRecalcState.begin] (with or without the
 * long-plan question), [PlanRecalcState.route], [PlanRecalcState.taken]: snap / take / fail → Done.
 */
class PlanRecalcNoTourTest {
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(46.600, 11.100)
    private val b = GeoPoint(46.610, 11.130)
    private val rome = GeoPoint(41.90, 12.50)

    private class MemoryPlanStore : PlanStore {
        override var points: List<GeoPoint> = emptyList()
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }

    private val plan = PlanDocument(MemoryPlanStore())
    private val causes = RunCause()
    private val legs = LegCache()
    private val engine = FakeRoutingEngine()
    private val said = ArrayList<String>()

    private val planRun = PlanRunSlice(causes, object : PlanRunHost {
        override fun points(): List<GeoPoint> = plan.points.toList()
        override fun clearRoute() { host.route = null; host.routeError = null }
        override fun noCorridor() = Unit
        override fun loadDeferredMaps(before: List<GeoPoint>) = Unit
        override fun recalc() = Unit
        override fun cancelRun(why: NotRouted) = Unit
    })

    private val ports = object : PlanRecalcPorts {
        override val runCauses: RunCause get() = causes
        override val activity: Activity get() = Activity.HIKE
        override val sac: Int get() = 2
        override val legCache: LegCache get() = legs
        override fun planFactors(a: Activity) = com.onpath.app.time.Factors()
        override fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = r
        override var activityBefore: PlannedRoute? = null
        override var spotsOpen: Boolean = false
        override var spotLine: List<GeoPoint>? = null
        override var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>? = null
        override var routeChangedNotice: String? = null
        override var planSize: PlanSize = PlanSize.COLLAPSED
        override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = Unit
        override fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long) = Unit
        override fun linkSnapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) = Unit
    }

    private val host = object : PlanRecalcHost {
        override var route: PlannedRoute? = null
        override var routeError: String? = null
        override var highlight: GeoPoint? = null
        override var planReach: com.onpath.app.routing.PlanReach? = null
        override var planResult: com.onpath.app.routing.PlanResult? = null
        override val mode: AppMode get() = AppMode.PLAN
        override fun nameOfStop(p: GeoPoint): String? = null
        override val multiDayOverview: Boolean get() = false
        override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = Unit
        override fun checkRoutingDataFor(line: List<GeoPoint>) = Unit
    }

    private val background = object : Background {
        override fun <T> run(what: String, work: () -> T, always: () -> Unit, then: (T) -> Unit) {
            try { then(work()) } finally { always() }
        }
    }

    private val state = PlanRecalcState(plan, planRun, ports, PlanTourPort.None, MapDataPort.None,
        Say { text, _, _ -> said += text }, host, background, elapsedMs = { 0L })

    /** The planner's run: head, route, taken – on one thread. */
    private fun run(askLong: Boolean = true): PlanRecalcState.Run? {
        val run = state.begin(askLong) ?: return null
        val result = runCatching { PlanRecalcState.route(run, engine, legs, planRun.routeCancel ?: RouteCancel(), { _, _ -> }).result }
        val post = result.getOrNull()?.let { MapDataPort.None.postRoute(it.route, run.pts, run.act) }
        state.taken(run, result, reach = null, tries = null, post = post, active = true)
        return run
    }

    @Test fun aPlanIsRoutedAndTakenWithoutTourOrMapData() {
        plan.replace(listOf(a, b))
        assertNotNull(run())
        assertNotNull("the route is taken", host.route)
        assertNull(host.routeError)
        assertEquals("the run is done", RouteRun.Phase.Idle, planRun.routeRun.phase)
        assertNotNull(host.planResult)
    }

    @Test fun aFailedRunSaysSoAndLeavesNoRoute() {
        plan.replace(listOf(a, b))
        val run = state.begin()!!
        // a failure of the route stage (the engine's own "no way" makes straight legs, not a failure)
        state.taken(run, Result.failure(com.onpath.app.routing.RoutingException("no way")), reach = null, tries = null, post = null, active = true)
        assertNull("no tour records: the line goes", host.route)
        assertNotNull(host.routeError)
        assertEquals(RouteRun.Phase.Idle, planRun.routeRun.phase)
    }

    @Test fun aPlanBeyondADayIsAskedAboutUnlessTheHostSaysNever() {
        plan.replace(listOf(a, rome))
        assertNull("asked first: nothing routed", run())
        assertTrue(planRun.routeRun.phase is RouteRun.Phase.AskLong)
        assertNull(host.route)
        assertTrue(engine.calls.isEmpty())

        assertNotNull("the planner's multi-day tour: never asked", run(askLong = false))
        assertTrue(engine.calls.isNotEmpty())
        assertEquals(RouteRun.Phase.Idle, planRun.routeRun.phase)
    }
}
