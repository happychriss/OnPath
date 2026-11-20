package com.onpath.web

import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanHistory
import com.onpath.app.routing.Activity
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.navigation.TourSplit
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanEditPorts
import com.onpath.app.ui.plan.PlanEditState
import com.onpath.app.ui.plan.PlanRunHost
import com.onpath.app.ui.plan.PlanRunSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.MapDataPort
import com.onpath.app.ui.plan.PlanRecalcHost
import com.onpath.app.ui.plan.PlanRecalcPorts
import com.onpath.app.ui.plan.PlanRecalcState
import com.onpath.app.ui.plan.PlanTourPort
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.routing.LegCache
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.SlowPlan
import com.onpath.app.ui.Background
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.Say

/**
 * The app's plan-editing chain as the planner's page would hold it (umbau's `XState` cut, docs/design/web-ready-status.md
 * §6): [PlanDocument] → [PlanRunSlice] → [PlanEditState], over ports with the planner's facts (always Plan, no tour, no
 * GPS track). The ports record what the slice asks the host to do ([did]) – the page would apply those.
 *
 * It lives in the routing worker (WebApi's chain) around the engine's one [PlanHistory], so the undo history still survives
 * a fresh worker (historyExport / historyImport, web-planner.md §26): the page's commit, Undo / Redo and Cancel go through
 * [record], [step] and [cancel] – the shared PlanEditState decides (Cancel takes back the change that started the run).
 */
class WebPlanChain(points: List<GeoPoint> = emptyList(), history: PlanHistory = PlanHistory()) {
    /** What the slice asked the host, in order (one word each, e.g. "recalc", "restoreActivity"). */
    val did = ArrayList<String>()
    var line: List<GeoPoint>? = null

    private val store = object : PlanStore {
        override var points: List<GeoPoint> = points
        override var pinned: Set<GeoPoint> = emptySet()
        override var savedName: String? = null
        override var savedId: Long = -1L
        override var savedPoints: List<GeoPoint>? = null
    }
    val plan = PlanDocument(store, history)
    val runCauses = RunCause()
    val planRun = PlanRunSlice(runCauses, object : PlanRunHost {
        override fun points() = plan.points.toList()
        override fun clearRoute() { did += "clearRoute" }
        override fun noCorridor() { did += "noCorridor" }
        override fun loadDeferredMaps(before: List<GeoPoint>) { did += "loadDeferredMaps" }
        override fun recalc() { did += "recalc" }
        // the page does every Cancel itself (in edit order, through [cancel]): here it is only recorded
        override fun cancelRun(why: NotRouted) { did += "cancel:" + why.name }
    })

    private var mark: Place? = null

    val edit: PlanEditState = PlanEditState(plan, planRun, runCauses, object : PlanEditPorts {
        override fun cancelHoldMeasure(why: String) { did += if (why == "Undo" || why == "Redo") why.lowercase() else "cancelHoldMeasure" }
        override fun stopFollowingWhenMapTurns() = Unit // the planner's map never turns with a heading
        override fun closePointMenu(why: String) { did += "closePointMenu" }
        override fun clearFirstPointMenu() { did += "clearFirstPointMenu" }
        override fun clearHoldAddMenu() { did += "clearHoldAddMenu" }
        override fun clearHighlight() { did += "clearHighlight" }
        override fun removePin(why: String) { mark = null; did += "removePin" }
        override fun menuAction(what: String): Int? = null
        override fun setRoutingSpot(spot: GeoPoint?) = Unit
        override fun loadMapsForNewPoints(before: List<GeoPoint>) { did += "loadMaps" }
        override fun keepAsDraft(why: String) { did += "keepAsDraft" }
        override fun routeLine(): List<GeoPoint>? = line
        override fun recalc() { did += "recalc" }
        override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) { did += "restoreActivity" }
        override fun planChanged() { did += "planChanged" }
        // the planner has no tour, no GPS track to follow
        override fun myPosition(): GeoPoint? = null
        override fun touring() = false
        override fun following() = false
        override fun followIsTrack() = false
        override fun followsLine(line: List<GeoPoint>) = false
        override fun followFromPlan() = false
        override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = null
        override fun sayNoDestination() = Unit
        override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = Unit
        override fun endGuidanceIfPlanEmpty() = Unit
        override fun clearFollow() = Unit
        override fun guidanceEndedByEmptyPlan() = Unit
        override fun stopName(p: GeoPoint): String? = null
        override var searchMark: Place?
            get() = mark
            set(v) { mark = v }
    })

    // ---- the page's plan edits (WebApi: the plan and its undo history live here, in the routing worker; the page holds
    // the shown copy and sends what it is now with each call – the worker never routes on these ports, it only records)

    /** One undoable change [before] → [after] (the page's commit; PlanEditState.change). */
    fun record(before: List<GeoPoint>, after: List<GeoPoint>) {
        plan.replace(before)
        did.clear()
        edit.set(after)
    }

    /**
     * Undo / Redo on the plan [now] (PlanEditState.undoStep / redoStep): the points to show, or null when there is
     * nothing to take back.
     */
    fun step(undo: Boolean, now: List<GeoPoint>): List<GeoPoint>? {
        plan.replace(now)
        did.clear()
        if (undo) edit.undoStep() else edit.redoStep()
        return if ((if (undo) "undo" else "redo") in did) plan.points.toList() else null
    }

    /**
     * Cancel of the long-route question ([why] LONG) or of the wait (STOPPED) – PlanEditState.cancelRun – for the run the page started for
     * [cause] ("edit", "undo", "redo", "activity" with the activity / level before it, else other) on the plan [now]:
     * "UNDO" / "REDO" with the points to show, "RESTORE_ACTIVITY", or "KEEP_POINTS".
     */
    fun cancel(cause: String, now: List<GeoPoint>, activityBefore: Activity?, sacBefore: Int,
               why: NotRouted = NotRouted.STOPPED): Pair<String, List<GeoPoint>?> {
        plan.replace(now)
        when (cause) {
            "edit" -> runCauses.pointEdit()
            "undo" -> runCauses.step(undo = true)
            "redo" -> runCauses.step(undo = false)
            "activity" -> if (activityBefore != null) runCauses.activity(PlanStatus.Cause.ActivityChange(activityBefore, sacBefore))
                else runCauses.other()
            else -> runCauses.other()
        }
        runCauses.start()
        did.clear()
        edit.cancelRun(why)
        return when {
            "undo" in did -> "UNDO" to plan.points.toList()
            "redo" in did -> "REDO" to plan.points.toList()
            "restoreActivity" in did -> "RESTORE_ACTIVITY" to null
            else -> "KEEP_POINTS" to null
        }
    }

    // ---- the run's head: the app's PlanRecalcState (umbau's cut for the planner, 2026-11-20) – begin decides whether a plan
    // is routed or asked about first ("This route is very long"), the RouteRun keeps "Plan anyway" and "Weiter warten";
    // the routing itself is Engine.plan (PlanRecalcState.route), the result the page's (no tour, no offline map data here)

    /** The activity and T level of the page's plan (each head call sets them). */
    private var act = Activity.HIKE
    private var level = 2

    private val recalcPorts = object : PlanRecalcPorts {
        override val runCauses: RunCause get() = this@WebPlanChain.runCauses
        override val activity: Activity get() = act
        override val sac: Int get() = level
        override val legCache: LegCache = LegCache() // the head reads none; the engine's own is Engine's legCache
        override fun planFactors(a: Activity): com.onpath.app.time.Factors = WebPace.factors(a)
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

    private val recalcHost = object : PlanRecalcHost {
        override var route: PlannedRoute? = null
        override var routeError: String? = null
        override var highlight: GeoPoint? = null
        override var planReach: com.onpath.app.routing.PlanReach? = null
        override var planResult: com.onpath.app.routing.PlanResult? = null
        override val mode: AppMode get() = AppMode.PLAN // the planner is always Plan
        override fun nameOfStop(p: GeoPoint): String? = null
        override val multiDayOverview: Boolean get() = false
        override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = Unit
        override fun checkRoutingDataFor(line: List<GeoPoint>) = Unit
    }

    /** One thread in the worker: the work at once. */
    private val background = object : Background {
        override fun <T> run(what: String, work: () -> T, always: () -> Unit, then: (T) -> Unit) {
            try { then(work()) } finally { always() }
        }
    }

    val recalc = PlanRecalcState(plan, planRun, recalcPorts, PlanTourPort.None, MapDataPort.None,
        Say { _, _, _ -> }, recalcHost, background, elapsedMs = { System.currentTimeMillis() })

    /** What [head] decided. */
    sealed interface Head {
        /** Route the run [gen] with [rounds] time-limit periods. */
        data class Route(val gen: Int, val rounds: Int) : Head
        /** "This route is very long" first ([estimateS] seconds); nothing is routed until the answer. */
        data class Ask(val estimateS: Double) : Head
        /** Nothing to route (fewer than two points). */
        data object None : Head
    }

    /**
     * The head of a run of the page's plan [now] ([PlanRecalcState.begin]): the run to route, the long-plan question first, or
     * nothing to route. [askLong] false: never asked (the planner's multi-day tour, owner 2026-11-18) – on every head of it.
     */
    fun head(now: List<GeoPoint>, activity: Activity, sac: Int, askLong: Boolean): Head {
        plan.replace(now)
        act = activity; level = sac
        did.clear()
        val run = recalc.begin(askLong)
        val ask = planRun.longRouteAsk
        return when {
            run != null -> Head.Route(run.gen, run.rounds)
            ask != null -> Head.Ask(ask)
            else -> Head.None
        }
    }

    /** The answer to "This route is very long" for the plan [now]: yes = these points are not asked again ([RouteRun.longOk]). */
    fun longAnswer(yes: Boolean, now: List<GeoPoint>) {
        plan.replace(now)
        did.clear()
        planRun.perform(planRun.routeEvent(RouteRun.Event.LongAnswer(yes, now)))
    }

    /**
     * The run [gen] came back: "ok", "failed" or "slow" (its time limit after [rounds] period(s) – "Weiter warten?" is open).
     * The result itself is the page's; here the run's state follows ([RouteRun]: the question, or Idle again).
     */
    fun end(gen: Int, outcome: String, rounds: Int) {
        val back = planRun.routeEvent(RouteRun.Event.Returned(gen, active = true))
        if (RouteRun.Effect.Superseded in back) return
        when (outcome) {
            "slow" -> { planRun.routeEvent(RouteRun.Event.TimeLimit(gen, rounds)); return }
            "ok" -> planRun.routeEvent(RouteRun.Event.Succeeded(gen, planMode = true, sheetCanRise = false))
            else -> planRun.routeEvent(RouteRun.Event.Failed(gen))
        }
        planRun.routeEvent(RouteRun.Event.Done(gen))
    }

    /** The answer to "Weiter warten?" ([PlanRunSlice.answerSlowPlan]): wait on = the next head routes with one period more. */
    fun slowAnswer(wait: Boolean) {
        did.clear()
        planRun.answerSlowPlan(if (wait) SlowPlan.Answer.KEEP_WAITING else SlowPlan.Answer.CANCEL)
    }

    /** "Plan anyway" / "Calculate" of the not-routed line ([PlanRunSlice.planAnyway]) on the plan [now]. */
    fun planAnyway(now: List<GeoPoint>) {
        plan.replace(now)
        did.clear()
        planRun.planAnyway()
    }

    /**
     * The plan [now] was said yes to earlier on this page ([RouteRun.Event.RoutedBefore] = [RouteRun.longOk]): the page keeps
     * its yeses for the session (as before the shared run), so a fresh chain – an opened plan, a fresh worker – asks no
     * more than the old page did.
     */
    fun routedBefore(now: List<GeoPoint>) {
        planRun.routeEvent(RouteRun.Event.RoutedBefore(now))
    }

    /**
     * What a fresh worker must know of the run (umbau's (b), 2026-11-20): the plan said yes to ([RouteRun.longOk]), why it is
     * left without a route, the open "Weiter warten?" – "points|WHY|rounds", each part may be empty.
     */
    fun runExport(): String {
        val r = planRun.routeRun
        return (r.longOk?.joinToString(";") { Engine.num(it.lat, 6) + "," + Engine.num(it.lon, 6) } ?: "") + "|" +
            (r.notRouted?.name ?: "") + "|" + (r.slowAsk?.toString() ?: "")
    }

    /** [runExport]'s text into this (fresh) chain, by the run's own events. */
    fun runImport(text: String) {
        val parts = text.split("|")
        parts.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { planRun.routeEvent(RouteRun.Event.RoutedBefore(Engine.parsePoints(it))) }
        parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { w ->
            NotRouted.entries.firstOrNull { it.name == w }?.let { planRun.routeEvent(RouteRun.Event.Unrouted(it)) }
        }
        parts.getOrNull(2)?.toIntOrNull()?.let { rounds ->
            // the open question comes back on a run of its own (two points, never asked): Routing → TimeLimit = AskSlow
            val p = GeoPoint(0.0, 0.0)
            val start = planRun.routeEvent(RouteRun.Event.Recalc(listOf(p, p), act, planMode = true, newPlan = false, askLong = false))
                .filterIsInstance<RouteRun.Effect.StartRouting>().firstOrNull() ?: return@let
            planRun.routeEvent(RouteRun.Event.TimeLimit(start.gen, rounds))
        }
    }

    companion object {
        /**
         * Builds the chain and runs it once through the spot umbau asked about: a point edit whose run is cancelled – the
         * edit is undone, and cancelRun logs the cause's `simpleName`. `{"afterEdit":3,"points":2,"canRedo":true,"did":[…]}`
         * or `{"error":"…"}`.
         */
        fun probe(): String = try {
            val c = WebPlanChain(listOf(GeoPoint(50.0, 7.0), GeoPoint(50.1, 7.1)))
            c.edit.change { add(GeoPoint(50.05, 7.2)) } // a point edit: its run starts
            val afterEdit = c.plan.points.size
            c.runCauses.start()
            c.edit.cancelRun(NotRouted.STOPPED)
            "{\"afterEdit\":$afterEdit,\"points\":${c.plan.points.size},\"canRedo\":${c.plan.canRedo}," +
                "\"did\":${c.did.joinToString(",", "[", "]") { Engine.str(it) }}}"
        } catch (e: Throwable) {
            "{\"error\":${Engine.str(e::class.simpleName + ": " + e.message)}}"
        }
    }
}
