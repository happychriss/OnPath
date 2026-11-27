package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.debug.Problems
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RunCause
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.RouteCancel
import com.onpath.app.ui.Cancellable

/** What [PlanRunSlice] asks from the rest of the app (main thread). */
interface PlanRunHost {
    /** The plan's points now. */
    fun points(): List<GeoPoint>
    /** The plan has no route: the line and its error line go. */
    fun clearRoute()
    /** Nothing loads for a plan that is not routed (the map data's corridor follows no plan). */
    fun noCorridor()
    /** "Plan anyway": the maps of the far point load now (the points before the edit that made the plan very long). */
    fun loadDeferredMaps(before: List<GeoPoint>)
    /** Plan the points again (the run itself: `recalc`, still in the view model). */
    fun recalc()
    /** Cancel of the question / of a running calculation: the change that started the run is taken back. */
    fun cancelRun(why: NotRouted)
    /** The long-plan question was put for [ask] (its leg, for the app's dialog); default: nothing. */
    fun longAsked(ask: com.onpath.app.routing.LongAsk) = Unit
}

/** Routing that runs beside the plan's route and gives way to what needs the router now (the app's detour check). */
interface BesideRoute {
    /** A hold's measure takes the router ([running]) or is over. */
    fun holdMeasures(running: Boolean)
    /** A tour starts: this work ends now. */
    fun stop()
}

/**
 * The plan's route run, second half, first part (rebuild phase 2 piece 9): the run's state ([RouteRun], changed only by
 * [routeEvent]), what its effects do ([perform]), the run's handles (job, cancel signal, wait card progress) and the
 * entry points the user reaches while a run is open or asked about (Cancel, "Weiter warten?", "Plan anyway"). The coroutine
 * of the run itself (`recalc`) is still in the view model: it reaches all of this through the view model's forwarders.
 * Constructed with the view model (no work).
 */
class PlanRunSlice(
    /** What started the routing run: Cancel takes back only that ([PlanRouteSlice.runCauses]). */
    private val runCauses: RunCause,
    private val host: PlanRunHost,
    /**
     * The run's start state: its rules ([RouteRun.longRule], [RouteRun.askSlow]) stay with every later state. Default =
     * today's (the web planner); the app passes its phone rule (owner 2026-11-27).
     */
    initial: RouteRun = RouteRun(),
) {
    /** The plan's route run: calculating, its two questions, "not routed" and the orders for the next result. */
    var routeRun by mutableStateOf(initial)
        private set
    val routing: Boolean get() = routeRun.routing
    /** The plan run reached its time limit: the periods of the run that asked; null = none. */
    val slowPlanAsk: Int? get() = routeRun.slowAsk
    /** The route has been calculating for a while: the plan sheet offers Cancel. */
    val routingSlow: Boolean get() = routeRun.routingSlow
    /** Estimated duration (s) of a plan longer than a day: "Plan it anyway?" (null = no question). */
    val longRouteAsk: Double? get() = routeRun.longAsk
    /** The plan is left without a route on purpose; the sheet offers to calculate. */
    val notRouted: NotRouted? get() = routeRun.notRouted

    /** The routing beside this plan's route, set by the plan's recalculation; a hold and a tour start reach it here. */
    var besideRoute: BesideRoute? = null

    /** Cancel signal of the route being calculated (Cancel button; a newer request stops the older one). */
    var routeCancel: RouteCancel? = null
    /** The running calculation (stopped by [cancel]; the app's is a `JobHandle` around its coroutine). */
    var routeJob: Cancellable? = null
    /** The points before the edit that made the plan very long: its maps load only after "Plan anyway". */
    var longRouteDeferred: List<GeoPoint>? = null
    /** The wait card's progress: legs done / legs to route; null = none known yet. */
    var routeProgress by mutableStateOf<Pair<Int, Int>?>(null)
    /** The wait bar's share 0..1; null = not known yet. */
    var routeShare by mutableStateOf<Float?>(null)

    /** The answer to the slow-plan question: wait one more period (the run again, longer) or stop like Cancel. */
    fun answerSlowPlan(answer: com.onpath.app.routing.SlowPlan.Answer) {
        val rounds = slowPlanAsk ?: return
        val effects = routeEvent(RouteRun.Event.SlowAnswer(answer))
        Problems.info("routing", "Slow plan: ${answer.name.lowercase()} after $rounds period(s)")
        // wait on: the run again with one more period (Cancel later still takes back the change that started it);
        // else the usual Cancel
        perform(effects)
    }

    /** "Plan anyway" / "Calculate" of the not-routed notice. */
    fun planAnyway() = perform(routeEvent(RouteRun.Event.PlanAnyway(host.points())))

    /** The one place the route run's state changes: what [e] makes of it ([RouteRun.on]); the effects are the caller's to do. */
    fun routeEvent(e: RouteRun.Event): List<RouteRun.Effect> {
        val step = routeRun.on(e)
        routeRun = step.state
        return step.effects
    }

    /** Does what the route run asks for, in its order; a run's start and its result are done in `recalc`. */
    fun perform(effects: List<RouteRun.Effect>) {
        for (fx in effects) when (fx) {
            RouteRun.Effect.ClearRoute -> host.clearRoute()
            RouteRun.Effect.NoCorridor -> host.noCorridor() // nothing loads for a plan that is not routed
            is RouteRun.Effect.LongAsked -> Problems.trace("routing", when {
                fx.estimateS.isInfinite() -> "A leg beyond the phone's capacity – asking first"
                routeRun.longRule == com.onpath.app.routing.LongRule.DAY -> "Very long plan (${com.onpath.app.routing.LongRoute.describe(fx.estimateS)}) – asking first"
                else -> "Long leg (about ${fx.estimateS.toInt()} s on this phone) – asking first"
            }).also { routeRun.longAskLeg?.let(host::longAsked) }
            RouteRun.Effect.StopRun -> {
                routeCancel?.cancel(); routeCancel = null
                routeJob?.cancel(); routeJob = null
                routeProgress = null; routeShare = null
            }
            RouteRun.Effect.LoadDeferredMaps -> longRouteDeferred?.let { longRouteDeferred = null; host.loadDeferredMaps(it) }
            is RouteRun.Effect.Rerun -> { if (fx.sameCause) runCauses.again(); host.recalc() }
            is RouteRun.Effect.Cancel -> host.cancelRun(fx.why)
            is RouteRun.Effect.StartRouting, RouteRun.Effect.ClearProgress, RouteRun.Effect.Superseded, is RouteRun.Effect.TakeRoute,
            RouteRun.Effect.ShowFailure, RouteRun.Effect.FollowNewRoute, RouteRun.Effect.FollowEditedPlan -> Unit // in recalc
        }
    }

    fun leaveUnrouted(why: NotRouted) {
        perform(routeEvent(RouteRun.Event.Unrouted(why))) // the run stops, the route and its corridor go
        Problems.info("routing", "Plan left without a route (${why.name.lowercase()}): ${host.points().size} points")
    }

    /**
     * Cancel while a route is calculated (owner): the router stops at once and the change that
     * started this calculation is taken back (`cancelRun`). Nothing to take back: the points stay, without a route.
     */
    fun cancelRouting() {
        val effects = routeEvent(RouteRun.Event.CancelTapped)
        if (effects.isEmpty()) return // nothing is calculated
        Problems.info("routing", "Route calculation cancelled by the user")
        perform(effects) // cancelRun
    }
}
