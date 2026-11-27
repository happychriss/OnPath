package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Step 1c (owner 2026-11-27, Hilden – Venedig): a point set ON the known line must not ask the long-leg question – also
 * when its maps are weighed ([AppViewModel] `cellsForNewPoints`, T1), not only when the run starts. Both ask through
 * PlanRecalcState.longAsk → longLegsOf, which cuts the known leg at the new point from memory first
 * (LegSplit.seedBeforeRun, HP's 1b), so the halves count as routed. Fails when the maps' question weighs the visible points
 * on its own again (routeRun.asks without the seed) or the seed leaves longLegsOf.
 */
class LongAskSeedWiringTest {
    private fun src(path: String) = File(path).readText()

    @Test fun theMapsQuestionForNewPointsWeighsTheLegsLikeTheRun() {
        val vm = src("src/main/java/com/onpath/app/ui/AppViewModel.kt")
        val cells = vm.substringAfter("private fun cellsForNewPoints(").substringBefore("\n    }\n")
        assertTrue("the maps' question goes through longAsk", cells.contains("planRecalc.longAsk(after.toList())"))
        assertFalse("never the visible points alone", cells.contains("routeRun.asks("))
        val recalc = src("../state/src/main/kotlin/com/onpath/app/ui/plan/PlanRecalcState.kt")
        val longAsk = recalc.substringAfter("fun longAsk(").substringBefore("\n    }\n")
        assertTrue("longAsk weighs the legs as routed", longAsk.contains("longLegsOf("))
        val legsOf = recalc.substringAfter("private fun longLegsOf(").substringBefore("\n    }\n")
        assertTrue("a point put in on the line is cut first (1b)", legsOf.contains("LegSplit.seedBeforeRun(legCache, expanded, act, level)"))
        // the run asks with the same legs
        val begin = recalc.substringAfter("fun begin(askLong: Boolean = true): Run? {").substringBefore("\n    }\n")
        assertTrue(begin.contains("longLegsOf(pts, act, level, pins)"))
    }
}
