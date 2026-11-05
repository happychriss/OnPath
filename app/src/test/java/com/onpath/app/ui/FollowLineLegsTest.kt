package com.onpath.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan edits during a tour (owner 2026-11-05, docs/design/long-bike-routes.md): the plan run and the tour's follow line
 * (position → points ahead) share one leg cache, so the follow line routes only position → first point ahead; a newer
 * edit stops a follow line still running, so the next plan run never waits behind it (field case 2026-11-04: 15 s plan +
 * 20 s follow line per edit on a 98 km bike plan). Source checks – the flow itself needs the service.
 */
class FollowLineLegsTest {
    private val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()

    @Test fun planAndFollowLineShareTheLegCache() {
        assertTrue(vm.contains("Planner(eng, cancel, askAtLimit = askAtLimit, rounds = rounds, legs = legCache,"))
        val follow = vm.substringAfter("private fun followAhead(").substringBefore("\n    fun followShown()")
        assertTrue(follow, follow.contains("Planner(routingEngine(), cancel, legs = legCache)"))
    }

    @Test fun aNewerEditStopsTheFollowLine() {
        val recalc = vm.substringAfter("private fun recalc() {").substringBefore("val act = activity; val level = sac")
        assertTrue(recalc, recalc.contains("followCancel?.cancel(); followCancel = null"))
        val follow = vm.substringAfter("private fun followAhead(").substringBefore("\n    fun followShown()")
        assertTrue("an older follow line stops", follow.contains("followCancel?.cancel()"))
        assertTrue("a stopped run changes nothing", follow.contains("if (cancel.isCancelled) {") && follow.contains("return@launch"))
    }

    /** The wait bar moves within one long leg (owner 2026-11-05): the router is asked off the main thread, for the plan's thread only. */
    @Test fun theWaitBarAsksTheRouterOffTheMainThread() {
        val run = vm.substringAfter("private fun recalc() {").substringBefore("\n    }\n")
        assertTrue(run, run.contains("val poll = launch(Dispatchers.Default)"))
        assertTrue(run, run.contains("searchProgress(t)") && run.contains("bar.next("))
        assertTrue(run, run.contains("planThread.set(Thread.currentThread())"))
        assertTrue("the poll stops with the run", run.contains("slow.cancel(); poll.cancel()"))
    }

    @Test fun newRoutingDataDropsTheLegs() {
        val engine = vm.substringAfter("private fun routingEngine(): RoutingEngine =").substringBefore("\n    /**")
        assertTrue(engine, engine.contains("legCache.clear()"))
    }
}
