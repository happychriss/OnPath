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
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("val askAtLimit = mode == AppMode.PLAN"))
        assertTrue(vm.contains("Planner(eng, cancel, askAtLimit = askAtLimit, rounds = rounds, legs = legCache"))
        val answer = vm.substringAfter("fun answerSlowPlan(").substringBefore("\n    }\n")
        assertTrue(answer, answer.contains("if (next == null) { planRounds = 1; cancelRun(NotRouted.STOPPED); return }"))
        assertTrue(answer, answer.contains("nextCause = runCause") && answer.contains("recalc()"))
        val recalc = vm.substringAfter("private fun recalc() {").substringBefore("val rounds = planRounds")
        assertTrue("a new run starts at one period", recalc.contains("if (!keepRounds) planRounds = 1"))
        val screen = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(screen.contains("vm.slowPlanAsk?.let { slowPlanMessage(onAnswer = vm::answerSlowPlan) }"))
    }
}
