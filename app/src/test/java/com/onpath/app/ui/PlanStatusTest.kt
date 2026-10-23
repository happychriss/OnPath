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
        assertEquals(PlanStatus.OnCancel.KEEP_POINTS, PlanStatus.onCancel(PlanStatus.Cause.Other, canUndo = false, canRedo = false))
        val line = PlanStatus.noRouteLine(plan.size, routing = false, notRouted = NotRouted.LONG)
        assertFalse("not calculating: $line", line.contains("Calculating"))
        assertEquals("Plan anyway", PlanStatus.notRoutedAction(NotRouted.LONG))
    }

    @Test fun cancelAfterAnEditTakesItBack() {
        assertEquals(PlanStatus.OnCancel.UNDO, PlanStatus.onCancel(PlanStatus.Cause.PointEdit, canUndo = true, canRedo = false))
        assertEquals(PlanStatus.OnCancel.KEEP_POINTS, PlanStatus.onCancel(PlanStatus.Cause.PointEdit, canUndo = false, canRedo = false))
        // Cancel of an Undo brings the step back, of a Redo takes it back
        assertEquals(PlanStatus.OnCancel.REDO, PlanStatus.onCancel(PlanStatus.Cause.Undo, canUndo = true, canRedo = true))
        assertEquals(PlanStatus.OnCancel.UNDO, PlanStatus.onCancel(PlanStatus.Cause.Redo, canUndo = true, canRedo = true))
    }

    /**
     * UX review 2026-10-23, problem 1: points edited, then the activity changed → slow route → Cancel undid the last
     * *point* edit and the activity stayed changed. Now Cancel puts the activity back and never touches the points.
     */
    @Test fun cancelAfterAnActivityChangeRestoresTheActivityNotThePoints() {
        val c = PlanStatus.Cause.ActivityChange(Activity.HIKE, 3)
        assertEquals(PlanStatus.OnCancel.RESTORE_ACTIVITY, PlanStatus.onCancel(c, canUndo = true, canRedo = false))
        assertEquals(PlanStatus.OnCancel.RESTORE_ACTIVITY, PlanStatus.onCancel(c, canUndo = false, canRedo = true))
    }

    /** Newer routing data, a plan restored at start or loaded: Cancel just stops, even with undo history. */
    @Test fun cancelOfAnyOtherRunOnlyStops() {
        assertEquals(PlanStatus.OnCancel.KEEP_POINTS, PlanStatus.onCancel(PlanStatus.Cause.Other, canUndo = true, canRedo = true))
    }

    /** The ViewModel sets the cause at each trigger and reads it in the one Cancel path. */
    @Test fun viewModelSetsTheCauseAtEachTrigger() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val change = vm.substringAfter("private fun change(").substringBefore("\n    }")
        assertTrue(change, change.contains("nextCause = PlanStatus.Cause.PointEdit"))
        val step = vm.substringAfter("private fun step(").substringBefore("\n    }")
        assertTrue(step, step.contains("PlanStatus.Cause.Undo") && step.contains("PlanStatus.Cause.Redo"))
        val select = vm.substringAfter("fun selectActivity(").substringBefore("\n    }")
        assertTrue(select, select.contains("activityCause()"))
        val sacSel = vm.substringAfter("fun selectSac(").substringBefore("\n    }")
        assertTrue(sacSel, sacSel.contains("activityCause()"))
        val recalc = vm.substringAfter("private fun recalc() {").substringBefore("routeJob?.cancel()")
        assertTrue(recalc, recalc.contains("runCause = nextCause; nextCause = PlanStatus.Cause.Other"))
        // the routing-data re-route is no user change: its Cancel only stops
        val data = vm.substringAfter("routingDataReroute = true; ").substringBefore("\n")
        assertFalse(data, data.contains("nextCause"))
    }

    /** Problem 9: a tap outside "This route is very long" or Back must not undo – the dialog stays until a button. */
    @Test fun longRouteDialogStaysUntilAnswered() {
        val bar = File("src/main/java/com/onpath/app/ui/TourBar.kt").readText()
        val dlg = bar.substringAfter("fun LongRouteDialog(")
        assertTrue(dlg, dlg.contains("onDismissRequest = {}"))
        assertTrue(dlg, dlg.contains("dismissOnBackPress = false, dismissOnClickOutside = false"))
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
        assertTrue(answer, answer.contains("cancelRun(NotRouted.LONG)"))
        val run = vm.substringAfter("private fun cancelRun(").substringBefore("\n    }")
        assertTrue(run, run.contains("PlanStatus.onCancel(cause, canUndo, canRedo)") && run.contains("leaveUnrouted(why)"))
        val cancel = vm.substringAfter("fun cancelRouting(").substringBefore("\n    private fun recalc(")
        assertTrue(cancel, cancel.contains("cancelRun(NotRouted.STOPPED)"))
        val leave = vm.substringAfter("private fun leaveUnrouted(").substringBefore("\n    }")
        assertTrue(leave, leave.contains("routing = false") && leave.contains("requests.invalidate()"))
    }
}
