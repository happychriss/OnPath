package com.onpath.app.ui

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Field bug (owner phone, 0.9.3, 2026-10-22 20:23): app start with a saved ≈ 8-day plan (Bozen → Germany) → "Very long
 * plan – asking first" → Cancel → the sheet showed "Calculating the route…" forever, "Start navigation" disabled, no way
 * out. Cancel = undo the last change, but a plan restored at start has nothing to undo, and the sheet said
 * "Calculating…" for every plan without a route.
 */
class PlanStatusTest {
    private val bozen = GeoPoint(46.4983, 11.3548)
    private val munich = GeoPoint(48.1374, 11.5755)
    private val hamburg = GeoPoint(53.5511, 9.9937)

    @Test fun restoredEightDayPlanCancelKeepsThePointsWithoutCalculating() {
        val plan = listOf(bozen, munich, hamburg)
        assertTrue("the plan is asked about", LongRoute.needsAsk(plan, Activity.HIKE))
        // restored at start: no undo history
        assertEquals(PlanStatus.OnCancel.KEEP_POINTS, PlanStatus.onCancel(canUndo = false))
        val line = PlanStatus.noRouteLine(plan.size, routing = false, notRouted = NotRouted.LONG)
        assertFalse("not calculating: $line", line.contains("Calculating"))
        assertEquals("Plan anyway", PlanStatus.notRoutedAction(NotRouted.LONG))
    }

    @Test fun cancelAfterAnEditTakesItBack() {
        assertEquals(PlanStatus.OnCancel.UNDO, PlanStatus.onCancel(canUndo = true))
    }

    /** Invariant: "Calculating the route…" only while a routing request runs. */
    @Test fun calculatingOnlyWhileRouting() {
        for (n in 0..5) for (why in listOf(null, NotRouted.LONG, NotRouted.STOPPED)) {
            assertFalse("n=$n why=$why", PlanStatus.noRouteLine(n, routing = false, notRouted = why).contains("Calculating"))
            if (n >= 2) assertEquals(PlanStatus.CALCULATING, PlanStatus.noRouteLine(n, routing = true, notRouted = why))
        }
    }

    /** The ViewModel and the sheet use these rules (no own "Calculating" text, no Cancel that leaves nothing running). */
    @Test fun viewModelAndSheetUseTheRules() {
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        val code = panel.lines().filterNot { it.trim().startsWith("/") || it.trim().startsWith("*") }.joinToString("\n")
        assertFalse("PlanPanel has no own Calculating text", code.contains("\"Calculating"))
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val answer = vm.substringAfter("fun answerLongRoute(").substringBefore("\n    fun ")
        assertTrue(answer, answer.contains("PlanStatus.onCancel(canUndo)") && answer.contains("leaveUnrouted(NotRouted.LONG)"))
        val cancel = vm.substringAfter("fun cancelRouting(").substringBefore("\n    private fun recalc(")
        assertTrue(cancel, cancel.contains("leaveUnrouted(NotRouted.STOPPED)"))
        val leave = vm.substringAfter("private fun leaveUnrouted(").substringBefore("\n    }")
        assertTrue(leave, leave.contains("routing = false") && leave.contains("requests.invalidate()"))
    }
}
