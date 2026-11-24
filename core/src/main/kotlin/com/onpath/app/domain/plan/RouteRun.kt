package com.onpath.app.domain.plan

import com.onpath.app.domain.NotRouted
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongRoute
import com.onpath.app.routing.SlowPlan

/**
 * The plan's route run as ONE state and a pure reducer ([on]): `(state, event) -> (state, effects)`. The view model
 * (`AppViewModel.recalc` and its callbacks) holds one [RouteRun], sends it what happened and does what the effects say –
 * every side effect (router, coroutines, map data, the tour, messages) stays there. Android-free, `RouteRunTest`.
 *
 * The phases exclude each other; the phase says so by its type.
 * Stays in the view model, because it is data or a handle and not a decision: the route and its error line, the wait
 * card's progress (`routeProgress`, `routeShare`), the job and the router's cancel signal, the stand-in line of a loaded
 * route, the route before an activity change (`activityBefore`), the points whose maps wait for the long-plan answer
 * (`longRouteDeferred`) and what started the run ([RunCause] – what Cancel takes back, [com.onpath.app.domain.PlanStatus.onCancel]).
 *
 * Transitions (each one a test):
 * - [Event.Recalc] (every plan edit, activity change, Undo, load, newer routing data, "Plan anyway", "Weiter warten"):
 *   a new generation – a run still going is superseded, its result ignored. Fewer than two points → [Phase.Idle], no
 *   route, the orders to follow / fit dropped (a long-plan question still open stays – kept as it was). A plan beyond a day that was not said yes to ([longOk]) and may be asked about ([Event.Recalc.askLong]) →
 *   [Phase.AskLong], nothing routed or loaded. Else → [Phase.Routing] + [Effect.StartRouting]. Always: the time-limit
 *   periods start at 1 again unless "Weiter warten" asked for this run, an open [Phase.AskSlow] / [Phase.Unrouted]
 *   goes, [viaData] and [compareActivity] are taken by this run, a plan without points may raise the sheet again.
 * - [Event.Slow]: the newest run takes a while → the sheet offers Cancel.
 * - [Event.Returned]: the router came back. Not the newest run (or its job stopped): [Effect.Superseded], nothing else.
 * - [Event.Succeeded] → [Effect.TakeRoute] with its decisions: the sheet rises once per plan (owner 2026-11-08,
 *   2a54f2e4), the first route is fitted, map data along it is freshened only for a plain plan run, the plan baseline
 *   is adopted; then the tour follows it ([Effect.FollowNewRoute] for "Continue navigation", else
 *   [Effect.FollowEditedPlan] for an edit during a tour – not for an adopted baseline).
 * - [Event.TimeLimit] (Plan only, [Effect.StartRouting.askAtLimit]; other runs keep the straight legs) →
 *   [Phase.AskSlow]; line, points and every order stay. [Event.SlowAnswer]: wait on → the run again with one more
 *   period, cancel → [Effect.Cancel].
 * - [Event.Failed] → [Effect.ShowFailure]; an order to follow is still answered ([Effect.FollowNewRoute]: the view
 *   model says "no new route" without a line), an edit stays pending for the next good run.
 * - [Event.Done]: the view model has taken the result → [Phase.Idle]. Until then the phase is still [Phase.Routing],
 *   as `routing` stayed true while the result was applied.
 * - [Event.CancelTapped] (only while routing) and the cancel answers → [Effect.Cancel]: the view model takes back what
 *   started the run and sends [Event.Unrouted] → [Phase.Unrouted], a new generation, the orders to follow dropped.
 * - [Event.LongAnswer]: yes → [longOk] = these points, their maps load, the run again; no → [Effect.Cancel].
 *   [Event.PlanAnyway] (the not-routed notice's button): the same yes for a plan left as too long, else just the run.
 */
data class RouteRun(
    val phase: Phase = Phase.Idle,
    /** Generation of the newest run: a result counts only while its generation is this one. */
    val gen: Int = 0,
    /** Time-limit periods of the next run: 1, or more after "Weiter warten". */
    val rounds: Int = 1,
    /** The next run keeps [rounds] (a "Weiter warten" run), else it starts at one period again. */
    val keepRounds: Boolean = false,
    /** The very long plan the user said yes to, or a saved route routed before: not asked (again) for these points. */
    val longOk: List<GeoPoint>? = null,
    /** This plan's first route came already – the sheet rises by itself only once per plan. */
    val sheetRisen: Boolean = false,
    /** The next route is fitted on the map (the plan's first route, a plan link, a place added). */
    val fitNext: Boolean = false,
    /** "Continue navigation" re-plans: the tour follows the next result. */
    val followNext: Boolean = false,
    /** The plan's points were edited: a running tour follows the next good result. */
    val editPending: Boolean = false,
    /** The next result is the plan's baseline (an activity switched while navigating is not an edit). */
    val adoptBaseline: Boolean = false,
    /** The next run is for newer routing data. */
    val viaData: Boolean = false,
    /** The next run is for an activity / T level change: its result is compared with the route before. */
    val compareActivity: Boolean = false,
) {
    sealed interface Phase {
        /** No run and no question (with or without a route). */
        data object Idle : Phase
        /** "This route is very long" is open ([estimateS] seconds): nothing is routed or loaded until the answer. */
        data class AskLong(val estimateS: Double) : Phase
        /** The newest run is calculated; [slow]: for a while already (Cancel shows); [viaData]: for newer routing data. */
        data class Routing(val slow: Boolean = false, val viaData: Boolean = false) : Phase
        /** The run reached its time limit after [rounds] period(s): "Weiter warten?" is open. */
        data class AskSlow(val rounds: Int) : Phase
        /** Left without a route on purpose (Cancel with nothing to take back); the sheet offers to calculate. */
        data class Unrouted(val why: NotRouted) : Phase
    }

    val routing: Boolean get() = phase is Phase.Routing
    val routingSlow: Boolean get() = (phase as? Phase.Routing)?.slow == true
    val longAsk: Double? get() = (phase as? Phase.AskLong)?.estimateS
    val slowAsk: Int? get() = (phase as? Phase.AskSlow)?.rounds
    val notRouted: NotRouted? get() = (phase as? Phase.Unrouted)?.why

    /** A result of the run [g] still counts. */
    fun isCurrent(g: Int): Boolean = g == gen

    sealed interface Event {
        /**
         * The plan is to be routed (again) for [points]; [planMode]: the Plan screen shows; [newPlan]: a plan begins (`SheetRise.resets`);
         * [askLong] false: never the long-plan question (the planner's multi-day tour, owner 2026-11-18).
         */
        data class Recalc(val points: List<GeoPoint>, val activity: Activity, val planMode: Boolean, val newPlan: Boolean,
                          val askLong: Boolean = true) : Event
        /** The run [gen] has been calculating for a while. */
        data class Slow(val gen: Int) : Event
        /** The router came back for the run [gen]; [active]: its job was not stopped meanwhile. */
        data class Returned(val gen: Int, val active: Boolean) : Event
        /** … with a route. [sheetCanRise]: the sheet would rise for a first route now (`SheetRise.rises` for a plan not risen). */
        data class Succeeded(val gen: Int, val planMode: Boolean, val sheetCanRise: Boolean) : Event
        /** … at its time limit after [rounds] period(s), asking. */
        data class TimeLimit(val gen: Int, val rounds: Int) : Event
        /** … with any other failure. */
        data class Failed(val gen: Int) : Event
        /** The view model has taken the result of the run [gen]. */
        data class Done(val gen: Int) : Event
        /** Cancel on the wait card. */
        data object CancelTapped : Event
        /** The plan is left without a route ([why]) – the end of every Cancel. */
        data class Unrouted(val why: NotRouted) : Event
        /** The answer to "This route is very long" for the plan [points]. */
        data class LongAnswer(val planAnyway: Boolean, val points: List<GeoPoint>) : Event
        /** The answer to "Weiter warten?". */
        data class SlowAnswer(val answer: SlowPlan.Answer) : Event
        /** "Plan anyway" / "Calculate" of the not-routed notice for the plan [points]. */
        data class PlanAnyway(val points: List<GeoPoint>) : Event
        /**
         * [points] were routed before (a saved route loaded with its line): no long-plan question for them. Also the way back
         * for a host that loses its [RouteRun] (the planner's fresh worker): [longOk] given again, nothing else changes.
         */
        data class RoutedBefore(val points: List<GeoPoint>) : Event
        /** A new plan (a saved route replaces it): its first route may raise the sheet again. */
        data object NewPlan : Event
        /** Orders for the next result / run. */
        data object FitNext : Event
        data object FollowNext : Event
        data object PlanEdited : Event
        data object AdoptBaseline : Event
        data object NewerData : Event
        data class CompareActivity(val on: Boolean) : Event
    }

    sealed interface Effect {
        /** Route the plan as run [gen] with [rounds] time-limit periods; [askAtLimit]: ask at the limit instead of straight legs. */
        data class StartRouting(val gen: Int, val rounds: Int, val askAtLimit: Boolean, val viaData: Boolean, val compareActivity: Boolean) : Effect
        /** The route and its error line go. */
        data object ClearRoute : Effect
        /** No corridor follows the plan (nothing loads for a plan that is not routed). */
        data object NoCorridor : Effect
        /** The long-plan question was put ([estimateS] seconds). */
        data class LongAsked(val estimateS: Double) : Effect
        /** The wait card's progress goes. */
        data object ClearProgress : Effect
        /** The result is not the newest run's: ignore it. */
        data object Superseded : Effect
        /**
         * Take the route. [rises]: the sheet rises to its middle step; [fit]: fit the map to it (after the sheet when it
         * [rises]); [freshen]: newer map areas and routing data along it; [viaData]: it was computed for newer routing
         * data (say so when the way changed); [adoptBaseline]: it is the plan baseline.
         */
        data class TakeRoute(val rises: Boolean, val fit: Boolean, val freshen: Boolean, val viaData: Boolean, val adoptBaseline: Boolean) : Effect
        /** Show the failure (during a tour the old line stays). */
        data object ShowFailure : Effect
        /** "Continue navigation" waited for this result: follow the route, or say there is none. */
        data object FollowNewRoute : Effect
        /** The plan was edited during a tour: the tour follows the new plan. */
        data object FollowEditedPlan : Effect
        /** Stop the run in work (job, router). */
        data object StopRun : Effect
        /** Cancel: take back what started the run, leave the plan without a route ([why]). */
        data class Cancel(val why: NotRouted) : Effect
        /** The maps of the points that waited for the long-plan answer load now. */
        data object LoadDeferredMaps : Effect
        /** Route again; [sameCause]: for the same cause as before (a later Cancel still takes that back). */
        data class Rerun(val sameCause: Boolean) : Effect
    }

    data class Step(val state: RouteRun, val effects: List<Effect> = emptyList())

    private fun stay() = Step(this)
    private fun to(state: RouteRun, vararg effects: Effect) = Step(state, effects.toList())
    private fun runs(g: Int): Phase.Routing? = (phase as? Phase.Routing)?.takeIf { isCurrent(g) }

    /** What [e] makes of this state, and what the view model does then (in this order). */
    fun on(e: Event): Step = when (e) {
        is Event.Recalc -> {
            val next = copy(gen = gen + 1, rounds = if (keepRounds) rounds else 1, keepRounds = false, viaData = false,
                compareActivity = false, sheetRisen = sheetRisen && !e.newPlan)
            when {
                // as before: a long-plan question still open stays open (its dialog is modal, so only a change from
                // outside the screen gets here)
                e.points.size < 2 -> to(next.copy(phase = phase as? Phase.AskLong ?: Phase.Idle, followNext = false, fitNext = false),
                    Effect.ClearRoute, Effect.NoCorridor)
                e.askLong && e.points != longOk && LongRoute.needsAsk(e.points, e.activity) -> {
                    val s = LongRoute.estimateS(e.points, e.activity)
                    to(next.copy(phase = Phase.AskLong(s)), Effect.ClearRoute, Effect.LongAsked(s), Effect.NoCorridor)
                }
                else -> to(next.copy(phase = Phase.Routing(viaData = viaData)),
                    Effect.StartRouting(next.gen, next.rounds, askAtLimit = e.planMode, viaData = viaData, compareActivity = compareActivity))
            }
        }
        is Event.Slow -> runs(e.gen)?.let { to(copy(phase = it.copy(slow = true))) } ?: stay()
        is Event.Returned -> when {
            !isCurrent(e.gen) -> to(this, Effect.Superseded)
            else -> Step(copy(phase = (phase as? Phase.Routing)?.copy(slow = false) ?: phase),
                listOfNotNull(Effect.ClearProgress, Effect.Superseded.takeIf { !e.active }))
        }
        is Event.Succeeded -> runs(e.gen)?.let { run ->
            val take = Effect.TakeRoute(rises = !sheetRisen && e.sheetCanRise, fit = fitNext,
                freshen = e.planMode && !followNext && !editPending && !run.viaData, viaData = run.viaData, adoptBaseline = adoptBaseline)
            val follow = when {
                followNext -> Effect.FollowNewRoute
                editPending && !adoptBaseline -> Effect.FollowEditedPlan
                else -> null
            }
            // "Continue navigation" was answered: an edit still pending waits for the next good run
            Step(copy(sheetRisen = true, fitNext = false, adoptBaseline = false, followNext = false, editPending = editPending && followNext),
                listOfNotNull(take, follow))
        } ?: stay()
        is Event.TimeLimit -> runs(e.gen)?.let { to(copy(phase = Phase.AskSlow(e.rounds))) } ?: stay()
        is Event.Failed -> runs(e.gen)?.let {
            Step(copy(fitNext = false, followNext = false), listOfNotNull(Effect.ShowFailure, Effect.FollowNewRoute.takeIf { followNext }))
        } ?: stay()
        is Event.Done -> runs(e.gen)?.let { to(copy(phase = Phase.Idle)) } ?: stay()
        Event.CancelTapped -> if (routing) to(this, Effect.Cancel(NotRouted.STOPPED)) else stay()
        is Event.Unrouted -> to(copy(phase = Phase.Unrouted(e.why), gen = gen + 1, followNext = false, editPending = false),
            Effect.StopRun, Effect.ClearRoute, Effect.NoCorridor)
        is Event.LongAnswer -> {
            val answered = if (phase is Phase.AskLong) copy(phase = Phase.Idle) else this
            if (e.planAnyway) to(answered.copy(longOk = e.points), Effect.LoadDeferredMaps, Effect.Rerun(sameCause = true))
            else to(answered, Effect.Cancel(NotRouted.LONG))
        }
        is Event.SlowAnswer -> (phase as? Phase.AskSlow)?.let { ask ->
            val next = SlowPlan.next(ask.rounds, e.answer)
            if (next == null) to(copy(phase = Phase.Idle, rounds = 1), Effect.Cancel(NotRouted.STOPPED))
            else to(copy(phase = Phase.Idle, rounds = next, keepRounds = true), Effect.Rerun(sameCause = true))
        } ?: stay()
        is Event.PlanAnyway -> when {
            e.points.size < 2 -> stay()
            notRouted == NotRouted.LONG -> to(copy(longOk = e.points), Effect.LoadDeferredMaps, Effect.Rerun(sameCause = false))
            else -> to(this, Effect.Rerun(sameCause = false))
        }
        is Event.RoutedBefore -> to(copy(longOk = e.points))
        Event.NewPlan -> to(copy(sheetRisen = false))
        Event.FitNext -> to(copy(fitNext = true))
        Event.FollowNext -> to(copy(followNext = true))
        Event.PlanEdited -> to(copy(editPending = true))
        Event.AdoptBaseline -> to(copy(adoptBaseline = true))
        Event.NewerData -> to(copy(viaData = true))
        is Event.CompareActivity -> to(copy(compareActivity = e.on))
    }
}
