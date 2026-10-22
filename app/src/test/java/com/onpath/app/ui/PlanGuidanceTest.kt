package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner decision after field test 2026-10-22 (tour 5: all points deleted in Plan, the tour kept following the old
 * route invisibly): what Plan shows = what Navigate follows. Deleting all points during a tour ends the route
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

    @Test fun clearRouteDuringATourEndsTheGuidanceToo() {
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
}
