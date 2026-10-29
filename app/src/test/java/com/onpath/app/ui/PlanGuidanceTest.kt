package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule: what Plan shows = what Navigate follows (never an old route followed invisibly). Deleting all points
 * during a tour ends the route
 * guidance (the recording goes on); Undo brings the points and the guidance back. The steps are AppViewModel's
 * (PlanHistory + PlanGuidance).
 */
class PlanGuidanceTest {
    private val a = GeoPoint(46.670, 11.160)
    private val b = GeoPoint(46.672, 11.165)
    private val c = GeoPoint(46.675, 11.170)

    /** A running tour following the plan; the plan as AppViewModel holds it. */
    private class Tour(var plan: List<GeoPoint>) {
        val history = PlanHistory()
        var following = true
        var endedByEmptyPlan = false
        val recording = true
        val followIsTrack = false

        fun edit(to: List<GeoPoint>) { history.record(plan); plan = to; afterChange() }
        fun undo() { history.undo(plan)?.let { plan = it; afterChange() } }
        fun redo() { history.redo(plan)?.let { plan = it; afterChange() } }

        private fun afterChange() {
            if (PlanGuidance.endsGuidance(plan.size, recording, following, followIsTrack)) {
                following = false; endedByEmptyPlan = true
            }
            // route computed for the plan (two points or more)
            if (plan.size >= 2 && PlanGuidance.resumes(endedByEmptyPlan, recording, following, plan.size)) {
                following = true; endedByEmptyPlan = false
            }
        }
    }

    @Test fun deletingAllPointsEndsTheGuidanceUndoBringsItBack() {
        val t = Tour(listOf(a, b, c))
        t.edit(listOf(a, c)); assertTrue("still 2 points: followed", t.following)
        t.edit(listOf(a)); assertTrue("one point left: nothing changes yet", t.following)
        t.edit(emptyList())
        assertFalse("empty plan: no route followed", t.following)
        t.undo(); assertFalse("one point is no route", t.following)
        t.undo()
        assertTrue("Undo brought two points: the tour follows them again", t.following)
        assertEquals(listOf(a, c), t.plan)
    }

    /** Sheet ⋯ → Clear route is allowed during a tour (owner) and goes through the same plan change. */
    @Test fun clearRouteDuringATourEndsTheGuidanceToo() {
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val clear = vm.substringAfter("fun clearPlan() {").substringBefore("\n    }")
        assertTrue(clear, clear.contains("change { clear() }"))
        val change = vm.substringAfter("private fun change(").substringBefore("\n    }")
        assertTrue(change, change.contains("endGuidanceIfPlanEmpty()"))
        val t = Tour(listOf(a, b))
        t.edit(emptyList())
        assertFalse(t.following)
        t.undo()
        assertTrue(t.following)
    }

    @Test fun aFollowedTrackOrAFreeRecordingIsNotTouched() {
        assertFalse(PlanGuidance.endsGuidance(0, recording = true, following = true, followIsTrack = true))
        assertFalse(PlanGuidance.endsGuidance(0, recording = true, following = false, followIsTrack = false))
        assertFalse(PlanGuidance.endsGuidance(0, recording = false, following = true, followIsTrack = false))
        // a free recording that never followed anything does not start following by itself
        assertFalse(PlanGuidance.resumes(endedByEmptyPlan = false, recording = true, following = false, routePoints = 50))
    }

    /**
     * A track was taken to follow during the tour, then the plan was edited (owner): the edited plan wins (Continue
     * follows it, the track is drawn grey); the plan as it was when the track was taken (also by Undo) keeps the track.
     */
    @Test fun anEditedPlanReplacesAFollowedTrack() {
        val atFollow = listOf(a, b)
        assertFalse("unedited plan: the track stays", PlanGuidance.planReplacesTrack(true, listOf(a, b), atFollow))
        assertTrue("edited plan: Continue follows it", PlanGuidance.planReplacesTrack(true, listOf(a, b, c), atFollow))
        assertFalse("no plan to follow", PlanGuidance.planReplacesTrack(true, listOf(c), atFollow))
        assertFalse("unknown (app restart): the track stays", PlanGuidance.planReplacesTrack(true, listOf(a, c), null))
        assertFalse("no track followed", PlanGuidance.planReplacesTrack(false, listOf(a, b, c), atFollow))
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val cont = vm.substringAfter("fun continueNavigation(").substringBefore("\n    }")
        assertTrue(cont, cont.contains("(!followIsTrack || planReplacesTrack())"))
        val look = vm.substringAfter("fun trackLook(").substringBefore("\n    }")
        assertTrue(look, look.contains("planReplacesTrack()") && look.contains("FOLLOW_BEHIND"))
        val shown = vm.substringAfter("fun followShown(").substringBefore("\n    }")
        assertTrue(shown, shown.contains("planAtTrackFollow = waypoints.toList()"))
    }
}
