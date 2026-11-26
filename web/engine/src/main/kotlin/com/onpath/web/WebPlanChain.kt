package com.onpath.web

import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.PlanHistory
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
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
import com.onpath.app.routing.RouteCancel
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.debug.Problems
import com.onpath.app.ui.plan.PlanSettle
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.SlowPlan
import com.onpath.app.routing.PlanResult
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
class WebPlanChain(points: List<GeoPoint> = emptyList(), history: PlanHistory = PlanHistory(),
                   /** The engine's own leg cache: the snap aliases legs in it, the settle reads and fills it. */
                   private val legCache: LegCache = LegCache()) {
    /** What the slice asked the host, in order (one word each, e.g. "recalc", "restoreActivity"). */
    val did = ArrayList<String>()
    var line: List<GeoPoint>? = null

    private val store = WebPlanStore(points)
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
    /**
     * The page's named stops by value, as of the run last ended ([end]): the app's `nameOfStop` / `stopName` answer – a named
     * stop keeps its place when the run's points are snapped (PlanResult.withNamed), as on the page.
     */
    private var stopNames: Map<GeoPoint, String> = emptyMap()
    /** The run [head] began last: [end] takes its result. */
    private var lastRun: PlanRecalcState.Run? = null

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
        override fun stopName(p: GeoPoint): String? = stopNames[p]
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

    /** Like [record], for a plan from elsewhere: [after] brings no anchors, even where it shares points with [before]; Undo brings them back. */
    fun recordNew(before: List<GeoPoint>, after: List<GeoPoint>) {
        plan.replace(before)
        did.clear()
        plan.arriving(after, AnchorSet.NONE)
        edit.set(after)
    }

    /** A loaded plan [list] with its anchors [set] (detour-stitch.md §8.16): from here they follow its edits, Undo brings them back. */
    fun arrive(list: List<GeoPoint>, set: AnchorSet) {
        plan.arriving(list, set)
        plan.replace(list)
    }

    /** The router moved the points [routed] onto their ways ([snapped], same size): by index, so each keeps its anchors. */
    fun snapped(routed: List<GeoPoint>, snapped: List<GeoPoint>) {
        plan.replace(routed)
        plan.moveSnapped(routed, snapped)
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
        override val legCache: LegCache get() = this@WebPlanChain.legCache
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
        override fun nameOfStop(p: GeoPoint): String? = stopNames[p]
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

    /**
     * The idle settling (detour-stitch.md §8.15, piece W2): the app's pure [PlanSettle] (cap, what to settle, the blocking work).
     * The page steps it once the taken run's line is shown – [settleStep], one leg a call, like the detour check's detourAt –
     * so a menu, a hold or Save waits one leg at most, and the legs routed so far are kept (the browser's store) after each
     * step. An edit stops it (a fresh worker, client.ts); here every [PlanRecalcState.begin] drops the work. No tour runs in
     * the planner (a multi-day tour is a plan; the app's PlanRecalcSlice.tourRuns = touring, recording, navigating).
     */
    val settle = PlanSettle()
    private var pending: PlanSettle.Work? = null

    /** The settling under way: its [work], its cancel signal, the legs routed so far and those tried in vain this pass. */
    private class Settling(val work: PlanSettle.Work, val cancel: RouteCancel) {
        var routed = 0
        val tried = HashSet<Pair<GeoPoint, GeoPoint>>()
    }
    private var settling: Settling? = null

    val recalc: PlanRecalcState = object : PlanRecalcState(plan, planRun, recalcPorts, PlanTourPort.None, MapDataPort.None,
        Say { _, _, _ -> }, recalcHost, background, elapsedMs = { System.currentTimeMillis() }) {
        /** Every edit (every begin) stops the settling first – it is the lowest priority. */
        override fun beginning() { stopSettle() }
        /** A run was taken: its settling is asked for, once (null = nothing to settle). */
        override fun routeTaken(run: PlanRecalcState.Run, res: PlanResult) {
            pending = settle.prepare(run, res, planPointsNow(), legCache) { false }
        }
    }

    /** The settling stops: the work not yet started goes, the cancel signal fires (new routing data, a cache clear, an edit). */
    fun stopSettle() { settling?.cancel?.cancel(); settling = null; pending = null }

    /** What one [settleStep] did. */
    sealed interface SettleStep {
        /** A leg was routed or tried: call again. */
        data object More : SettleStep
        /** The settling ended: the settled line taken quietly ([res]), or null – nothing to show (the line stays). */
        data class Done(val res: PlanResult?) : SettleStep
    }

    /**
     * One step of the settling of the run last taken (blocking, in the routing worker): the next leg of the plan routed on its
     * own ([PlanSettle.settle], stopped after one leg that routed), and once no leg is left the line shown again from the
     * cache alone and taken quietly ([PlanRecalcState.takeQuiet]: only while the plan is still exactly the run's). A leg that
     * fails is counted by the settle's cap and not asked again in this pass. Exceptions go to the caller.
     */
    fun settleStep(engine: RoutingEngine): SettleStep {
        val s = settling ?: pending?.let { Settling(it, RouteCancel()) }?.also { settling = it; pending = null }
            ?: return SettleStep.Done(null)
        val w = s.work
        if (s.cancel.isCancelled || engine is com.onpath.app.routing.StraightLineEngine) { settling = null; return SettleStep.Done(null) }
        val left = legsLeft(s)
        if (left.isEmpty()) { settling = null; return SettleStep.Done(finish(s)) }
        val counts = left.map { (a, b) -> settle.failuresOf(a, b, w.act, w.level) }
        var routedOne = false
        val oneLeg = object : RoutingEngine {
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int) =
                engine.route(waypoints, activity, sacLimit).also { routedOne = true }
            override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: com.onpath.app.routing.RouteLimits) =
                engine.route(waypoints, activity, sacLimit, limits).also { routedOne = true }
        }
        // the settle's own "stop before the next leg" (its tour check) ends the step once a leg routed; the legs tried in
        // vain this pass are left out like the no-route legs. Its answer is null then: the quiet line comes in [finish].
        settle.settle(PlanSettle.Work(w.expanded, w.act, w.level, w.noRoute + s.tried, w.again), oneLeg, legCache, s.cancel) { routedOne }
        if (s.cancel.isCancelled) { settling = null; return SettleStep.Done(null) }
        if (routedOne) s.routed++
        left.forEachIndexed { k, (a, b) ->
            // a leg whose count rose failed; with nothing routed, the settle went over every leg left (or stopped at a time
            // limit, as the app's pass does): none of them is asked again in this pass
            if (!legCache.isExact(a, b, w.act, w.level) && (!routedOne || settle.failuresOf(a, b, w.act, w.level) > counts[k])) s.tried += a to b
        }
        return SettleStep.More
    }

    /** The settling's legs not routed yet: not exact in the cache, not drawn straight by the run, not capped, not tried in vain. */
    private fun legsLeft(s: Settling): List<Pair<GeoPoint, GeoPoint>> {
        val w = s.work
        val pts = w.expanded.points
        return (0 until pts.size - 1).map { com.onpath.app.routing.PlanAnchors.exact(pts[it]) to com.onpath.app.routing.PlanAnchors.exact(pts[it + 1]) }
            .filter { (a, b) ->
                !legCache.isExact(a, b, w.act, w.level) && (a to b) !in w.noRoute && (a to b) !in s.tried &&
                    settle.failuresOf(a, b, w.act, w.level) < PlanSettle.FAIL_CAP
            }
    }

    /**
     * The end of the settling: with a leg routed, the plan again from the cache ALONE ([PlanSettle.settle]'s quiet run – a leg
     * the cache lacks would be straight, a line worse than the taken one, so then nothing is shown), taken quietly.
     */
    private fun finish(s: Settling): PlanResult? {
        val w = s.work
        if (s.routed < 1) return null
        Problems.trace("routing", "Settling: the plan is shown again from the settled legs")
        val quietCancel = RouteCancel()
        var missed = false
        val cacheOnly = com.onpath.app.routing.CacheOnlyEngine(known = { x, y -> (x to y) in w.noRoute },
            onMiss = { _, _ -> missed = true; quietCancel.cancel() })
        val quiet = try { PlanRecalcState.route(w.again, cacheOnly, legCache, quietCancel, { _, _ -> }) } catch (e: Exception) {
            if (!missed) throw e
            null
        }
        if (missed || quiet == null) {
            Problems.trace("routing", "Settling: a leg is missing from the cache – the settled line is not shown, the line stays as it is")
            return null
        }
        if (!recalc.takeQuiet(w.again, quiet.result)) {
            Problems.trace("routing", "Settling: a newer run began – the settled line is not shown")
            return null
        }
        return quiet.result
    }

    /** The whole settling at once ([settleStep] until it ends) – for the tests. */
    fun settleNow(engine: RoutingEngine): PlanResult? {
        while (true) when (val st = settleStep(engine)) {
            SettleStep.More -> Unit
            is SettleStep.Done -> return st.res
        }
    }

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
    fun head(now: List<GeoPoint>, activity: Activity, sac: Int, askLong: Boolean, pinned: Set<Int> = emptySet()): Head {
        plan.replace(now)
        // the page's POI / place points (routed exactly): by value in the plan, so the run's pins - and the settle's, and every
        // edit's expansion - are those the page plans with (WebApi.plan gets the same indices)
        plan.pin(pinned.mapNotNull { now.getOrNull(it) })
        act = activity; level = sac
        did.clear()
        val run = recalc.begin(askLong)
        lastRun = run
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
     * The result itself is the page's; here the run's state follows ([RouteRun]: the question, or Idle again). [names] = the
     * page's named stops of the run's points by index (they keep their place when the points are snapped).
     */
    fun end(gen: Int, outcome: String, rounds: Int, names: Map<Int, String> = emptyMap(), resultFor: (List<GeoPoint>) -> PlanResult? = { null }) {
        val run = lastRun?.takeIf { it.gen == gen }
        if (run == null) { // not a run this chain began (a plain state change): only the RouteRun follows
            val back = planRun.routeEvent(RouteRun.Event.Returned(gen, active = true))
            if (RouteRun.Effect.Superseded in back) return
            when (outcome) {
                "slow" -> { planRun.routeEvent(RouteRun.Event.TimeLimit(gen, rounds)); return }
                "ok" -> planRun.routeEvent(RouteRun.Event.Succeeded(gen, planMode = true, sheetCanRise = false))
                else -> planRun.routeEvent(RouteRun.Event.Failed(gen))
            }
            planRun.routeEvent(RouteRun.Event.Done(gen))
            return
        }
        stopNames = names.filterValues { it.isNotBlank() }.mapNotNull { (i, n) -> run.pts.getOrNull(i)?.let { it to n } }.toMap()
        // the app's stage 4 (PlanRecalcState.taken): the RouteRun events, the snapped plan points and - for the next edit's
        // stitch - the lines of this route. The page's Cancel is a fresh worker (the run never comes back as cancelled);
        // the next head supersedes an older run (taken then ignores it, as in the app).
        val res = if (outcome == "ok") resultFor(run.pts)?.takeIf { it.activity == run.act && it.sac == run.level } else null
        val result: Result<PlanResult> = when {
            res != null -> Result.success(asAdopted(res, run.pts))
            outcome == "ok" -> {
                Problems.warn("plan", "Run #$gen came back ok but the engine has no result for its points – the next edit is not stitched")
                Result.failure(IllegalStateException("no result for the run's points"))
            }
            outcome == "slow" -> Result.failure(SlowPlan.TimeLimit(rounds))
            else -> Result.failure(IllegalStateException("Route calculation failed"))
        }
        // a failed run is reported once, by the page (main.ts planRun) – taken's own error line would say it again
        if (result.isFailure) Problems.quiet("routing", "Route calculation failed") {
            recalc.taken(run, result, reach = null, tries = null, post = null, active = true)
        } else recalc.taken(run, result, reach = null, tries = null, post = null, active = true)
    }

    /**
     * The result as the page adopts it (review 2026-11-26 #1): its named stops in place ([PlanResult.withNamed], the names
     * [PlanRecalcHost.nameOfStop] answers) and the snapped points at the page's 6 decimals – the plan's points here are then
     * bit for bit those the page sends back with its next call, so the anchors, the saved mark and the stitch lines stay
     * with them.
     */
    private fun asAdopted(res: PlanResult, pts: List<GeoPoint>): PlanResult {
        val named = res.withNamed(pts.indices.filter { stopNames[pts[it]] != null }.toSet())
        if (!named.moved) return named
        val shown = named.points.map { round6(it) }
        return named.copy(points = shown, moved = shown != named.routed)
    }

    /** The answer to "Weiter warten?" ([PlanRunSlice.answerSlowPlan]): wait on = the next head routes with one period more. */
    fun slowAnswer(wait: Boolean) {
        did.clear()
        planRun.answerSlowPlan(if (wait) SlowPlan.Answer.KEEP_WAITING else SlowPlan.Answer.CANCEL)
    }

    /**
     * The plan menu's row "Ganz neu berechnen" (PlanEditState.recalcRow, owner 2026-11-25, picture B): shown only when the plan
     * [now] has detour or cut points on [activity] / [sac] and no tour runs. Reads only.
     */
    fun recalcRowShown(activity: Activity, sac: Int): Boolean = edit.recalcRow(activity, sac) != null

    /**
     * The row's tap on the plan [now]: PlanEditState.recalcFromScratch (the detour and cut points go in one undoable step, the
     * plan's run starts as an edit's). True = done – the page then routes the plan again as after any edit; false = no row.
     */
    fun recalcFromScratch(now: List<GeoPoint>, activity: Activity, sac: Int): Boolean {
        plan.replace(now)
        did.clear()
        val row = edit.recalcRow(activity, sac) ?: return false
        row()
        return true
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
        /** A point at the page's 6 decimals: the value the page parses from the plan JSON (WebApi.planJson) and sends back. */
        fun round6(p: GeoPoint): GeoPoint = p.copy(lat = Engine.num(p.lat, 6).toDouble(), lon = Engine.num(p.lon, 6).toDouble())

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

/**
 * The planner's [PlanStore]: the plan's points, pins and saved link as the worker holds them. [savedAnchorsJson] keeps the
 * detour points the saved route had, so a cleared saved route stays "unsaved" when a [PlanDocument] is made on the same store
 * again (the page keeps the store's text beside the plan, as the app keeps `Prefs.planSavedAnchors`).
 */
class WebPlanStore(override var points: List<GeoPoint> = emptyList()) : PlanStore {
    override var pinned: Set<GeoPoint> = emptySet()
    override var savedName: String? = null
    override var savedId: Long = -1L
    override var savedPoints: List<GeoPoint>? = null
    override var anchorsJson: String? = null
    override var savedAnchorsJson: String? = null
}
