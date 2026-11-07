package com.onpath.app.ui

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.LongRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App start with a saved ≈ 8-day plan (Bozen → Germany) → "Very long plan – asking first" → Cancel: the sheet must not
 * show "Calculating the route…" forever with "Start navigation" disabled. Cancel = undo the last change, but a plan
 * restored at start has nothing to undo, and "Calculating…" belongs only to a running request.
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
     * Points edited, then the activity changed → slow route → Cancel puts the activity back and never touches the
     * points (not the last *point* edit undone with the activity left changed).
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

    /** Each trigger sets the cause, a run takes it, the one Cancel path reads it once ([com.onpath.app.domain.plan.RunCause]). */
    @Test fun eachTriggerSetsTheCauseOfTheNextRun() {
        val c = com.onpath.app.domain.plan.RunCause()
        c.pointEdit(); c.start()
        assertEquals(PlanStatus.Cause.PointEdit, c.takeForCancel())
        assertEquals("Cancel reads it once", PlanStatus.Cause.Other, c.takeForCancel())
        c.step(undo = true); c.start(); assertEquals(PlanStatus.Cause.Undo, c.run)
        c.step(undo = false); c.start(); assertEquals(PlanStatus.Cause.Redo, c.run)
        // a run without a trigger (newer routing data, a restored plan): Cancel only stops
        c.start(); assertEquals(PlanStatus.Cause.Other, c.takeForCancel())
        // "Plan anyway" / "Weiter warten": the same run again – a later Cancel still takes back what started it
        c.pointEdit(); c.start(); c.again(); c.start()
        assertEquals(PlanStatus.Cause.PointEdit, c.takeForCancel())
    }

    /** A second activity change while the first still calculates keeps the activity from BEFORE the first. */
    @Test fun anActivityChangeWhileTheLastOneCalculatesKeepsTheOldActivity() {
        val c = com.onpath.app.domain.plan.RunCause()
        val first = PlanStatus.Cause.ActivityChange(Activity.HIKE, 3)
        c.activity(c.activityChange(first, previousStillOpen = false)); c.start()
        val second = PlanStatus.Cause.ActivityChange(Activity.MTB, 2)
        assertEquals(first, c.activityChange(second, previousStillOpen = true))
        assertEquals("the first run is over: the change from now", second, c.activityChange(second, previousStillOpen = false))
        c.other(); assertEquals(PlanStatus.Cause.Other, c.next)
    }

    /** Problem 9: a tap outside "This route is very long" or Back must not undo – the dialog stays until a button. */
    @Test fun longRouteDialogStaysUntilAnswered() {
        assertFalse(LongRouteDialogRules.properties.dismissOnBackPress)
        assertFalse(LongRouteDialogRules.properties.dismissOnClickOutside)
    }

    /** Invariant: "Calculating the route…" only while a routing request runs. */
    @Test fun calculatingOnlyWhileRouting() {
        for (n in 0..5) for (why in listOf(null, NotRouted.LONG, NotRouted.STOPPED)) {
            assertFalse("n=$n why=$why", PlanStatus.noRouteLine(n, routing = false, notRouted = why).contains("Calculating"))
            if (n >= 2) assertEquals(PlanStatus.CALCULATING, PlanStatus.noRouteLine(n, routing = true, notRouted = why))
        }
    }

    /** The wait card counts the legs routed (owner 2026-11-05): "Calculating section 2 of 4…", a quarter of the bar; one leg = running bar. */
    @Test fun theWaitCardCountsTheLegsRouted() {
        assertEquals(PlanStatus.CALCULATING, PlanStatus.calculating(null))
        assertEquals(PlanStatus.CALCULATING, PlanStatus.calculating(0 to 1))
        assertFalse(PlanStatus.hasLegs(0 to 1))
        assertTrue(PlanStatus.hasLegs(0 to 2))
        assertEquals("Calculating section 2 of 4…", PlanStatus.calculating(1 to 4))
        assertEquals("the last leg done: still 4 of 4", "Calculating section 4 of 4…", PlanStatus.calculating(4 to 4))
        assertEquals(0.25f, PlanStatus.share(1 to 4), 0.0001f)
        assertEquals(1f, PlanStatus.share(5 to 4), 0.0001f)
    }

    /** The bar moves within one long leg and never goes back (owner 2026-11-05). */
    @Test fun theWaitBarMovesWithinALegAndNeverBack() {
        val one = WaitBar()
        assertEquals(0f, one.next(0, 1, null), 0.0001f)
        assertEquals(0.4f, one.next(0, 1, 0.4), 0.0001f)
        assertEquals("a retry inside the leg starts its search again: the bar stays", 0.4f, one.next(0, 1, 0.1), 0.0001f)
        assertEquals(1f, one.next(1, 1, null), 0.0001f)
        val four = WaitBar()
        assertEquals("leg 2 of 4 half done", 0.375f, four.next(1, 4, 0.5), 0.0001f)
        assertEquals("the next leg starts: no step back", 0.5f, four.next(2, 4, 0.0), 0.0001f)
        assertEquals(0.5f, four.next(1, 4, null), 0.0001f)
        assertEquals(1f, four.next(4, 4, 0.7), 0.0001f)
        assertEquals("no legs to route", 0f, WaitBar().next(0, 0, 0.5), 0.0001f)
    }
}
