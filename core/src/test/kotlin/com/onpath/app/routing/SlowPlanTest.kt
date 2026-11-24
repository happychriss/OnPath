package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-02 / 03 (onpath.private#13, #21 B): a plan run that reaches its time limit asks in Plan "Die Route ist
 * lang, die Berechnung dauert. Weiter warten?" – Weiter warten = one more time-limit period, Abbrechen = like Cancel;
 * runs that do not ask keep the straight lines as before.
 */
class SlowPlanTest {
    private val a = GeoPoint(51.16, 6.93)
    private val b = GeoPoint(51.20, 6.95)
    private val c = GeoPoint(51.25, 7.00)

    /** Always times out; remembers the rounds it was given. */
    private class SlowEngine : RoutingEngine {
        val rounds = ArrayList<Int>()
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
            rounds += 1
            throw RoutingException("took too long", timedOut = true)
        }
        override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult {
            rounds += limits.rounds
            throw RoutingException("took too long", timedOut = true)
        }
    }

    @Test fun theRule() {
        assertEquals(SlowPlan.AtLimit.ASK, SlowPlan.atLimit(ask = true))
        assertEquals(SlowPlan.AtLimit.STRAIGHT, SlowPlan.atLimit(ask = false))
        assertEquals("keep waiting: one more period", 2, SlowPlan.next(1, SlowPlan.Answer.KEEP_WAITING))
        assertEquals(3, SlowPlan.next(2, SlowPlan.Answer.KEEP_WAITING))
        assertNull("cancel stops", SlowPlan.next(2, SlowPlan.Answer.CANCEL))
    }

    @Test fun theLimitGrowsWithTheRounds() {
        val one = RouteTimeLimit.forRoute(listOf(a, c), Activity.BIKE_TOURING)
        assertEquals(one * 2, RouteTimeLimit.forRoute(listOf(a, c), Activity.BIKE_TOURING, rounds = 2))
        assertEquals("never below one period", one, RouteTimeLimit.forRoute(listOf(a, c), Activity.BIKE_TOURING, rounds = 0))
    }

    @Test fun askingPlannerThrowsAtTheLimit() {
        for (pts in listOf(listOf(a, c), listOf(a, b, c))) {
            val engine = SlowEngine()
            try {
                Planner(engine, RouteCancel(), askAtLimit = true, rounds = 2).plan(pts, Activity.BIKE_TOURING, 3)
                fail("a run in Plan asks instead of straight lines")
            } catch (e: SlowPlan.TimeLimit) {
                assertEquals(2, e.rounds)
            }
            assertTrue("the engine got two periods: ${engine.rounds}", engine.rounds.all { it == 2 })
        }
    }

    @Test fun aRunThatDoesNotAskKeepsTheStraightLines() {
        val r = Planner(SlowEngine(), RouteCancel()).plan(listOf(a, b, c), Activity.BIKE_TOURING, 3)
        assertTrue(r.legs.all { it.straight })
        assertTrue(r.error != null)
    }

    @Test fun theAppAsksOnlyInPlanAndCancelIsTheUsualCancel() {
        // `recalc`'s head and result are in PlanRecalcState (rebuild phase 2 piece 9b); the screen calls `vm.planRun.answerSlowPlan` itself (piece 21)
        val vm = File("../state/src/main/kotlin/com/onpath/app/ui/plan/PlanRecalcState.kt").readText()
        // only a run started in Plan asks; Cancel is the usual Cancel; "Weiter warten" runs again for the same cause with
        // one more period and a new run starts at one period: the route run's rules (RouteRunTest
        // aRunForNavigateKeepsTheStraightLegsAtTheTimeLimit, cancelOfWeiterWartenIsTheUsualCancel,
        // weiterWartenRunsAgainWithOneMorePeriodAndANewRunStartsAtOneAgain) – here: the view model asks them and obeys
        val recalc = vm.substringAfter("fun begin(): Run? {").substringBefore("routeCancel = RouteCancel()")
        assertTrue(recalc.contains("RouteRun.Event.Recalc(pts, act, planMode = mode == AppMode.PLAN,"))
        assertTrue(recalc.contains("val gen = start.gen; val rounds = start.rounds; val askAtLimit = start.askAtLimit"))
        assertTrue(vm.contains("Planner(eng, cancel, askAtLimit = run.askAtLimit, rounds = run.rounds, legs = legs"))
        assertTrue(vm.contains("routeEvent(RouteRun.Event.TimeLimit(gen, it.rounds))"))
        // the answer and what the effects do moved to PlanRunSlice (rebuild phase 2 piece 9; PlanRunFlowTest proves them as flows)
        val run = File("../state/src/main/kotlin/com/onpath/app/ui/plan/PlanRunSlice.kt").readText()
        val answer = run.substringAfter("fun answerSlowPlan(").substringBefore("\n    }\n")
        assertTrue(answer, answer.contains("val effects = routeEvent(RouteRun.Event.SlowAnswer(answer))") && answer.contains("perform(effects)"))
        val perform = run.substringAfter("fun perform(").substringBefore("\n    }\n")
        assertTrue(perform, perform.contains("is RouteRun.Effect.Cancel -> host.cancelRun(fx.why)"))
        // RunCause.again (PlanStatusTest.eachTriggerSetsTheCauseOfTheNextRun)
        assertTrue(perform, perform.contains("is RouteRun.Effect.Rerun -> { if (fx.sameCause) runCauses.again(); host.recalc() }"))
        val screen = File("../app/src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("vm.planRun.slowPlanAsk?.let { slowPlanMessage(onAnswer = vm.planRun::answerSlowPlan) }"))
    }
}
