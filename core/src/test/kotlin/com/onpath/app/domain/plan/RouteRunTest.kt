package com.onpath.app.domain.plan

import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.plan.RouteRun.Effect
import com.onpath.app.domain.plan.RouteRun.Event
import com.onpath.app.domain.plan.RouteRun.Phase
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongRoute
import com.onpath.app.routing.SlowPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan's route run ([RouteRun]) – what `AppViewModel.recalc()` and its callbacks did with their flags before the
 * code audit's point (a), characterised transition by transition (cleanup piece 3, 2026-11-17: nothing changes), with
 * the field cases of its history: the long-plan question, Cancel back to the plan, a new run while one is going, the
 * sheet that rises once per plan (2a54f2e4), "Weiter warten?".
 */
class RouteRunTest {
    private val a = GeoPoint(51.16, 6.93)
    private val b = GeoPoint(51.20, 6.95)
    private val c = GeoPoint(51.25, 7.00)
    private val rome = GeoPoint(41.90, 12.50)
    private val near = listOf(a, b)
    private val far = listOf(a, rome)

    private fun recalc(pts: List<GeoPoint>, plan: Boolean = true, newPlan: Boolean = false) =
        Event.Recalc(pts, Activity.HIKE, planMode = plan, newPlan = newPlan)

    /** Sends [events] one after the other; the last step. */
    private fun RouteRun.after(vararg events: Event): RouteRun.Step =
        events.fold(RouteRun.Step(this)) { step, e -> step.state.on(e) }

    private val routing = RouteRun().on(recalc(near)).state
    private fun ok(gen: Int, plan: Boolean = true, canRise: Boolean = true) = Event.Succeeded(gen, planMode = plan, sheetCanRise = canRise)
    private fun RouteRun.Step.take() = effects.filterIsInstance<Effect.TakeRoute>().single()

    // ---------------- a run starts ----------------

    @Test fun aPlanOfTwoPointsIsRouted() {
        val step = RouteRun().on(recalc(near))
        assertEquals(Phase.Routing(), step.state.phase)
        assertTrue(step.state.routing); assertFalse(step.state.routingSlow)
        assertEquals(listOf(Effect.StartRouting(gen = 1, rounds = 1, askAtLimit = true, viaData = false, compareActivity = false)), step.effects)
        assertTrue(step.state.isCurrent(1))
    }

    @Test fun aRunForNavigateKeepsTheStraightLegsAtTheTimeLimit() {
        val start = RouteRun().on(recalc(near, plan = false)).effects.single() as Effect.StartRouting
        assertFalse("only a run started in Plan asks \"Weiter warten?\"", start.askAtLimit)
    }

    @Test fun fewerThanTwoPointsHaveNoRouteAndDropTheOrdersToFollowAndFit() {
        for (pts in listOf(emptyList(), listOf(a))) {
            val before = routing.after(Event.FitNext, Event.FollowNext, Event.PlanEdited, Event.AdoptBaseline).state
            val step = before.on(recalc(pts))
            assertEquals(Phase.Idle, step.state.phase)
            assertEquals(listOf(Effect.ClearRoute, Effect.NoCorridor), step.effects)
            assertFalse(step.state.fitNext); assertFalse(step.state.followNext)
            assertTrue("an edit stays pending, the baseline order too", step.state.editPending && step.state.adoptBaseline)
            assertFalse("the run that was going is superseded", step.state.isCurrent(before.gen))
        }
    }

    @Test fun everyRunTakesTheNewerDataAndTheActivityComparisonOfItsOwn() {
        val ordered = RouteRun().after(Event.NewerData, Event.CompareActivity(true)).state
        assertTrue(ordered.viaData && ordered.compareActivity)
        val start = ordered.on(recalc(near))
        assertEquals(Effect.StartRouting(1, 1, askAtLimit = true, viaData = true, compareActivity = true), start.effects.single())
        assertEquals(Phase.Routing(viaData = true), start.state.phase)
        assertFalse(start.state.viaData); assertFalse(start.state.compareActivity)
        // the run after it is a plain one again
        assertEquals(Effect.StartRouting(2, 1, askAtLimit = true, viaData = false, compareActivity = false), start.state.on(recalc(near)).effects.single())
        // a run that routes nothing takes them too (they were for that run)
        for (pts in listOf(listOf(a), far)) {
            val s = ordered.on(recalc(pts)).state
            assertFalse(s.viaData); assertFalse(s.compareActivity)
        }
        assertFalse("a plan edit drops the comparison", ordered.on(Event.CompareActivity(false)).state.compareActivity)
    }

    // ---------------- a new run while one is going ----------------

    @Test fun aNewRunSupersedesTheRunningOne() {
        val second = routing.on(recalc(listOf(a, b, c)))
        assertEquals(Effect.StartRouting(2, 1, askAtLimit = true, viaData = false, compareActivity = false), second.effects.single())
        val s = second.state
        // the first run comes back late: nothing of it is taken, the second keeps calculating
        assertEquals(RouteRun.Step(s, listOf(Effect.Superseded)), s.on(Event.Returned(1, active = true)))
        for (late in listOf(ok(1), Event.Failed(1), Event.TimeLimit(1, 1), Event.Done(1), Event.Slow(1)))
            assertEquals("$late", RouteRun.Step(s), s.on(late))
        assertTrue(s.routing)
        // the second one is taken
        assertEquals(listOf(Effect.ClearProgress), s.on(Event.Returned(2, active = true)).effects)
        assertEquals(1, s.on(ok(2)).effects.size)
    }

    @Test fun aRunWhoseJobWasStoppedIsIgnoredEvenAsTheNewest() {
        val back = routing.on(Event.Slow(1)).state.on(Event.Returned(1, active = false))
        assertEquals(listOf(Effect.ClearProgress, Effect.Superseded), back.effects)
        assertFalse(back.state.routingSlow)
    }

    // ---------------- the wait card ----------------

    @Test fun cancelIsOfferedOnceTheRunTakesAWhileAndGoesWhenTheRouterIsBack() {
        val slow = routing.on(Event.Slow(1)).state
        assertTrue(slow.routing && slow.routingSlow)
        val back = slow.on(Event.Returned(1, active = true))
        assertEquals(listOf(Effect.ClearProgress), back.effects)
        assertTrue("still calculating until the result is taken", back.state.routing)
        assertFalse(back.state.routingSlow)
    }

    @Test fun cancelOnTheWaitCardOnlyWhileARunIsGoing() {
        assertEquals(listOf(Effect.Cancel(NotRouted.STOPPED)), routing.on(Event.CancelTapped).effects)
        assertEquals(routing, routing.on(Event.CancelTapped).state)
        for (idle in listOf(RouteRun(), routing.after(Event.Returned(1, true), ok(1), Event.Done(1)).state, RouteRun().on(recalc(far)).state))
            assertEquals(RouteRun.Step(idle), idle.on(Event.CancelTapped))
    }

    @Test fun cancelLeavesThePlanWithoutARouteAndNothingCalculating() {
        // the view model took back what started the run (RunCause, PlanStatus.onCancel) and then leaves the plan unrouted
        val before = routing.after(Event.Slow(1), Event.FollowNext, Event.PlanEdited, Event.FitNext).state
        val left = before.on(Event.Unrouted(NotRouted.STOPPED))
        assertEquals(listOf(Effect.StopRun, Effect.ClearRoute, Effect.NoCorridor), left.effects)
        assertEquals(Phase.Unrouted(NotRouted.STOPPED), left.state.phase)
        assertEquals(NotRouted.STOPPED, left.state.notRouted)
        assertFalse("never \"Calculating…\" with nothing running", left.state.routing || left.state.routingSlow)
        assertFalse(left.state.followNext); assertFalse(left.state.editPending)
        assertTrue("the fit order stays for the next route", left.state.fitNext)
        // the stopped run's late result is ignored
        assertEquals(listOf(Effect.Superseded), left.state.on(Event.Returned(1, active = true)).effects)
        // "Calculate" routes it again, for whatever comes (no cause to take back)
        assertEquals(listOf(Effect.Rerun(sameCause = false)), left.state.on(Event.PlanAnyway(near)).effects)
        assertNull(left.state.on(recalc(near)).state.notRouted)
    }

    // ---------------- the result ----------------

    @Test fun theSheetRisesOncePerPlan() {
        val first = routing.after(Event.Returned(1, true), ok(1))
        assertTrue("the plan's first route raises the sheet", first.take().rises)
        assertTrue(first.state.sheetRisen)
        val done = first.state.on(Event.Done(1)).state
        // the same plan routed again (an edit, an activity change, Undo): never again
        val again = done.after(recalc(listOf(a, b, c)), Event.Returned(2, true), ok(2))
        assertFalse(again.take().rises)
        // a first route with the sheet already up there, in Navigate or during a tour (SheetRise.rises says no) still counts
        val up = routing.after(ok(1, canRise = false))
        assertFalse(up.take().rises); assertTrue(up.state.sheetRisen)
        // a plan without points begins anew, and so does a loaded saved route
        val cleared = done.on(recalc(emptyList(), newPlan = true)).state
        assertFalse(cleared.sheetRisen)
        assertTrue(cleared.after(recalc(near), ok(3)).take().rises)
        assertFalse(done.on(Event.NewPlan).state.sheetRisen)
        // a failed or timed-out first run did not show a route: the next good one raises it
        assertTrue(routing.after(Event.Failed(1), Event.Done(1), recalc(near), ok(2)).take().rises)
        assertTrue(routing.after(Event.TimeLimit(1, 1), Event.SlowAnswer(SlowPlan.Answer.KEEP_WAITING), recalc(near), ok(2)).take().rises)
    }

    @Test fun theFirstRouteIsFittedOnce() {
        val step = RouteRun().after(Event.FitNext, recalc(near), ok(1))
        assertTrue(step.take().fit); assertFalse(step.state.fitNext)
        assertFalse(step.state.after(Event.Done(1), recalc(listOf(a, b, c)), ok(2)).take().fit)
        // a failed run drops the order (the points were fitted when they came), a time limit keeps it for the longer run
        assertFalse(RouteRun().after(Event.FitNext, recalc(near), Event.Failed(1)).state.fitNext)
        assertTrue(RouteRun().after(Event.FitNext, recalc(near), Event.TimeLimit(1, 1)).state.fitNext)
    }

    @Test fun aPlainPlanRunFreshensTheMapDataAlongTheRoute() {
        assertEquals(Effect.TakeRoute(rises = true, fit = false, freshen = true, viaData = false, adoptBaseline = false), routing.on(ok(1)).take())
        assertFalse("not in Navigate", routing.on(ok(1, plan = false)).take().freshen)
        assertFalse("not for a route the tour will follow", routing.after(Event.FollowNext, ok(1)).take().freshen)
        assertFalse("not for an edit (a tour may follow it)", routing.after(Event.PlanEdited, ok(1)).take().freshen)
        val data = RouteRun().after(Event.NewerData, recalc(near), ok(1)).take()
        assertFalse("not for the run the newer data itself started", data.freshen)
        assertTrue(data.viaData)
    }

    @Test fun theResultIsTakenBeforeTheRunIsOver() {
        val taken = routing.after(Event.Returned(1, true), ok(1)).state
        assertTrue("still \"calculating\" while the view model applies the result", taken.routing)
        assertEquals(Phase.Idle, taken.on(Event.Done(1)).state.phase)
    }

    @Test fun continueNavigationFollowsTheNextResult() {
        val good = routing.after(Event.FollowNext, ok(1))
        assertEquals(Effect.FollowNewRoute, good.effects.last())
        assertFalse(good.state.followNext)
        // a failed run answers it too (the view model follows the line that stayed, or says there is no new route)
        val bad = routing.after(Event.FollowNext, Event.Failed(1))
        assertEquals(listOf(Effect.ShowFailure, Effect.FollowNewRoute), bad.effects)
        assertFalse(bad.state.followNext)
        // an edit pending beside it is not followed twice: it waits for the next good run
        val both = routing.after(Event.FollowNext, Event.PlanEdited, ok(1))
        assertEquals(listOf(Effect.FollowNewRoute), both.effects.drop(1))
        assertTrue(both.state.editPending)
    }

    @Test fun anEditDuringATourIsFollowedWithTheNextGoodResult() {
        val good = routing.after(Event.PlanEdited, ok(1))
        assertEquals(Effect.FollowEditedPlan, good.effects.last())
        assertFalse(good.state.editPending)
        // a failed run keeps the edit pending: the next good result is followed
        val bad = routing.after(Event.PlanEdited, Event.Failed(1))
        assertEquals(listOf(Effect.ShowFailure), bad.effects)
        assertEquals(Effect.FollowEditedPlan, bad.state.after(Event.Done(1), recalc(near), ok(2)).effects.last())
        // without an edit nothing is followed
        assertEquals(1, routing.on(ok(1)).effects.size)
    }

    @Test fun anActivitySwitchedWhileNavigatingBecomesTheBaselineAndIsNotFollowedAsAnEdit() {
        val step = routing.after(Event.AdoptBaseline, Event.PlanEdited, ok(1))
        assertTrue(step.take().adoptBaseline)
        assertEquals("no follow of an edit", 1, step.effects.size)
        assertFalse(step.state.adoptBaseline); assertFalse(step.state.editPending)
        // the order waits through a failed run
        assertTrue(routing.after(Event.AdoptBaseline, Event.Failed(1)).state.adoptBaseline)
    }

    @Test fun aFailedRunShowsItsErrorAndEnds() {
        val failed = routing.after(Event.Slow(1), Event.Returned(1, true), Event.Failed(1))
        assertEquals(listOf(Effect.ShowFailure), failed.effects)
        assertEquals(Phase.Idle, failed.state.on(Event.Done(1)).state.phase)
    }

    // ---------------- the long-plan question ----------------

    @Test fun aPlanBeyondADayIsAskedAboutBeforeAnythingIsRoutedOrLoaded() {
        val s = LongRoute.estimateS(far, Activity.HIKE)
        assertTrue(LongRoute.needsAsk(far, Activity.HIKE))
        val asked = routing.after(Event.FitNext, Event.PlanEdited).state.on(recalc(far))
        assertEquals(Phase.AskLong(s), asked.state.phase)
        assertEquals(s, asked.state.longAsk!!, 0.0)
        assertEquals(listOf(Effect.ClearRoute, Effect.LongAsked(s), Effect.NoCorridor), asked.effects)
        assertFalse(asked.state.routing)
        assertFalse("the run that was going is superseded", asked.state.isCurrent(1))
        assertTrue("the orders wait for the answer", asked.state.fitNext && asked.state.editPending)
    }

    @Test fun planAnywayRoutesItAndIsNotAskedAgainForThesePoints() {
        val asked = RouteRun().on(recalc(far)).state
        val yes = asked.on(Event.LongAnswer(planAnyway = true, points = far))
        assertEquals(listOf(Effect.LoadDeferredMaps, Effect.Rerun(sameCause = true)), yes.effects)
        assertNull(yes.state.longAsk)
        assertEquals(far, yes.state.longOk)
        val run = yes.state.on(recalc(far))
        assertTrue(run.state.routing)
        assertEquals(Effect.StartRouting(2, 1, askAtLimit = true, viaData = false, compareActivity = false), run.effects.single())
        // an activity change of the same plan: not asked again; another far point: asked again
        assertTrue(run.state.on(Event.Recalc(far, Activity.BIKE_TOURING, planMode = true, newPlan = false)).state.routing)
        assertTrue(run.state.on(recalc(listOf(a, rome, b))).state.phase is Phase.AskLong)
    }

    @Test fun cancelOfTheLongPlanQuestionGoesBackToThePlan() {
        val asked = RouteRun().on(recalc(far)).state
        val no = asked.on(Event.LongAnswer(planAnyway = false, points = far))
        assertEquals(listOf(Effect.Cancel(NotRouted.LONG)), no.effects)
        assertNull(no.state.longAsk); assertNull(no.state.longOk)
        // the view model took the far point back (Undo) and routes the plan from before …
        assertTrue(no.state.after(Event.Unrouted(NotRouted.LONG), recalc(near)).state.routing)
        // … or had nothing to take back (a plan restored at start): the points stay unrouted, "Plan anyway" is offered
        val left = no.state.on(Event.Unrouted(NotRouted.LONG)).state
        assertEquals(NotRouted.LONG, left.notRouted)
        val anyway = left.on(Event.PlanAnyway(far))
        assertEquals(listOf(Effect.LoadDeferredMaps, Effect.Rerun(sameCause = false)), anyway.effects)
        assertTrue(anyway.state.on(recalc(far)).state.routing)
        // routed again without the yes (newer routing data, the app restarted): asked again
        assertTrue(left.on(recalc(far)).state.phase is Phase.AskLong)
    }

    @Test fun aPlanBeyondADayIsRoutedWithoutTheQuestionWhenTheHostSaysNever() {
        val step = RouteRun().on(Event.Recalc(far, Activity.HIKE, planMode = true, newPlan = false, askLong = false))
        assertTrue(step.state.routing)
        assertTrue(step.effects.none { it is Effect.LongAsked })
        assertEquals(1, step.effects.filterIsInstance<Effect.StartRouting>().size)
    }

    @Test fun aYesToTheLongPlanSurvivesAFreshRunWhenItsPointsAreGivenBack() {
        // the planner drops its worker (and its RouteRun) on a superseded run: it gives longOk back with RoutedBefore
        val yes = RouteRun().on(recalc(far)).state.on(Event.LongAnswer(planAnyway = true, points = far)).state
        val fresh = RouteRun().on(Event.RoutedBefore(yes.longOk!!)).state
        assertEquals(yes.longOk, fresh.longOk)
        assertTrue(fresh.on(recalc(far)).state.routing)
    }

    @Test fun aSavedRouteRoutedBeforeIsNotAskedAbout() {
        assertTrue(RouteRun().after(Event.RoutedBefore(far), recalc(far)).state.routing)
    }

    @Test fun planAnywayNeedsAPlan() {
        val left = RouteRun().after(recalc(far), Event.Unrouted(NotRouted.LONG)).state
        assertEquals(RouteRun.Step(left), left.on(Event.PlanAnyway(listOf(a))))
    }

    @Test fun anOpenLongPlanQuestionStaysWhenThePlanShrinksBelowTwoPoints() {
        // as before the rebuild (the dialog is modal, so only a change from outside the screen gets here)
        val asked = RouteRun().on(recalc(far)).state
        val shrunk = asked.on(recalc(listOf(a)))
        assertEquals(asked.phase, shrunk.state.phase)
        assertEquals(listOf(Effect.ClearRoute, Effect.NoCorridor), shrunk.effects)
    }

    // ---------------- "Weiter warten?" ----------------

    @Test fun atTheTimeLimitThePlanAsksAndKeepsEverythingElse() {
        val before = routing.after(Event.FitNext, Event.FollowNext, Event.PlanEdited, Event.Slow(1), Event.Returned(1, true)).state
        val asked = before.on(Event.TimeLimit(1, rounds = 1))
        assertEquals(emptyList<Effect>(), asked.effects)
        assertEquals(before.copy(phase = Phase.AskSlow(1)), asked.state)
        assertEquals(1, asked.state.slowAsk)
        assertFalse(asked.state.routing)
    }

    @Test fun weiterWartenRunsAgainWithOneMorePeriodAndANewRunStartsAtOneAgain() {
        val asked = routing.on(Event.TimeLimit(1, rounds = 1)).state
        val wait = asked.on(Event.SlowAnswer(SlowPlan.Answer.KEEP_WAITING))
        assertEquals(listOf(Effect.Rerun(sameCause = true)), wait.effects)
        assertNull(wait.state.slowAsk)
        val second = wait.state.on(recalc(near))
        assertEquals(Effect.StartRouting(2, rounds = 2, askAtLimit = true, viaData = false, compareActivity = false), second.effects.single())
        // at that limit it asks again: one more
        val third = second.state.after(Event.TimeLimit(2, rounds = 2), Event.SlowAnswer(SlowPlan.Answer.KEEP_WAITING), recalc(near))
        assertEquals(3, (third.effects.single() as Effect.StartRouting).rounds)
        // any other run (an edit, an activity change …) starts at one period again
        assertEquals(1, (third.state.on(recalc(listOf(a, b, c))).effects.single() as Effect.StartRouting).rounds)
        // … and so does an edit that comes instead of the answer: the question goes with it
        val edit = second.state.on(Event.TimeLimit(2, rounds = 2)).state.on(recalc(listOf(a, c)))
        assertEquals(1, (edit.effects.single() as Effect.StartRouting).rounds)
        assertNull(edit.state.slowAsk)
    }

    @Test fun cancelOfWeiterWartenIsTheUsualCancel() {
        val asked = routing.after(Event.TimeLimit(1, 1), Event.SlowAnswer(SlowPlan.Answer.KEEP_WAITING), recalc(near), Event.TimeLimit(2, 2)).state
        val no = asked.on(Event.SlowAnswer(SlowPlan.Answer.CANCEL))
        assertEquals(listOf(Effect.Cancel(NotRouted.STOPPED)), no.effects)
        assertNull(no.state.slowAsk)
        assertEquals("the next run starts at one period", 1, no.state.rounds)
        assertEquals(1, (no.state.after(Event.Unrouted(NotRouted.STOPPED), recalc(near)).effects.single() as Effect.StartRouting).rounds)
    }

    @Test fun anAnswerWithoutAQuestionDoesNothing() {
        for (s in listOf(RouteRun(), routing))
            for (answer in SlowPlan.Answer.entries) assertEquals(RouteRun.Step(s), s.on(Event.SlowAnswer(answer)))
    }

    // ---------------- always ----------------

    @Test fun oneThingAtATime() {
        // "calculating", the two questions and "not routed" exclude each other by type; whatever happens, a result of an
        // older run never changes anything
        val events = listOf(recalc(near), recalc(far), recalc(listOf(a)), Event.CancelTapped, Event.Unrouted(NotRouted.STOPPED),
            Event.LongAnswer(true, far), Event.LongAnswer(false, far), Event.SlowAnswer(SlowPlan.Answer.KEEP_WAITING),
            Event.PlanAnyway(near), Event.FitNext, Event.FollowNext, Event.PlanEdited, Event.NewerData)
        val rnd = kotlin.random.Random(7)
        repeat(200) {
            var s = RouteRun()
            repeat(30) {
                val gen = s.gen
                s = when (rnd.nextInt(6)) {
                    0 -> s.after(Event.Returned(gen, true), ok(gen), Event.Done(gen)).state
                    1 -> s.after(Event.Returned(gen, true), Event.TimeLimit(gen, s.rounds)).state
                    2 -> s.after(Event.Returned(gen, true), Event.Failed(gen), Event.Done(gen)).state
                    else -> s.on(events[rnd.nextInt(events.size)]).state
                }
                assertTrue("generations only grow", s.gen >= gen)
                if (s.gen > gen) for (late in listOf(ok(gen), Event.Failed(gen), Event.TimeLimit(gen, 1), Event.Done(gen), Event.Slow(gen)))
                    assertEquals(RouteRun.Step(s), s.on(late))
                assertEquals(1, listOf(s.routing, s.longAsk != null, s.slowAsk != null, s.notRouted != null, s.phase == Phase.Idle).count { it })
            }
        }
    }
}
