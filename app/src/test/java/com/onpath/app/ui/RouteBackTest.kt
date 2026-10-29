package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner rule: during a tour, deleting the GOAL of a 2-point plan (only the start is left) asks a persistent question
 * "Route back to the start?". Yes = the plan becomes position → start and the guidance follows it; No = plain
 * recording (what Plan shows = what Navigate follows). Undo works after either answer. The
 * steps are AppViewModel's (PlanHistory + RouteBack + PlanGuidance).
 */
class RouteBackTest {
    private val start = GeoPoint(46.670, 11.160)
    private val goal = GeoPoint(46.700, 11.200)
    private val here = GeoPoint(46.690, 11.190)

    /** A running tour following the plan, as AppViewModel holds it. */
    private class Tour(var plan: List<GeoPoint>, val recording: Boolean = true) {
        val history = PlanHistory()
        var following = true
        var followed: List<GeoPoint>? = plan
        var endedByEmptyPlan = false
        var asking = false
        val followIsTrack = false

        /** One undoable edit ([AppViewModel.change]). */
        fun edit(to: List<GeoPoint>, position: GeoPoint?) {
            val before = plan
            history.record(plan); plan = to
            asking = RouteBack.asks(before, to, recording, following, followIsTrack, position)
            afterChange()
        }
        fun undo() { history.undo(plan)?.let { plan = it; asking = false; afterChange() } }
        fun redo() { history.redo(plan)?.let { plan = it; asking = false; afterChange() } }

        /** [AppViewModel.answerRouteBack]. */
        fun answer(yes: Boolean, position: GeoPoint?) {
            asking = false
            val back = if (yes) RouteBack.plan(plan, position) else null
            if (back != null) { plan = back; afterChange() } // no new Undo step: Undo goes back to start → goal
            else { following = false; followed = null; endedByEmptyPlan = true } // plain recording
        }

        private fun afterChange() {
            if (PlanGuidance.endsGuidance(plan.size, recording, following, followIsTrack)) {
                following = false; followed = null; endedByEmptyPlan = true
            }
            if (plan.size < 2) return
            // a route was computed for the plan: a resumed or followed plan is followed (followEditedPlan)
            if (PlanGuidance.resumes(endedByEmptyPlan, recording, following, plan.size)) { following = true; endedByEmptyPlan = false }
            if (following) followed = plan
        }
    }

    @Test fun deletingTheGoalAsks() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        assertTrue(t.asking)
        assertEquals("nothing changes while the question is open", listOf(start, goal), t.followed)
    }

    @Test fun yesRoutesFromThePositionBackToTheStart() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        t.answer(yes = true, position = here)
        assertEquals(listOf(here, start), t.plan)
        assertTrue(t.following)
        assertEquals("guidance follows the way back", listOf(here, start), t.followed)
    }

    @Test fun undoAfterYesBringsTheOriginalPlanBack() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        t.answer(yes = true, position = here)
        t.undo()
        assertEquals("one Undo: the plan before the delete", listOf(start, goal), t.plan)
        assertEquals(listOf(start, goal), t.followed)
        t.redo()
        assertEquals("Redo: the way back again", listOf(here, start), t.plan)
        assertEquals(listOf(here, start), t.followed)
    }

    @Test fun noIsPlainRecordingAndUndoBringsTheGuidanceBack() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        t.answer(yes = false, position = here)
        assertEquals("the start stays in the plan", listOf(start), t.plan)
        assertFalse("no route is followed: Plan shows none", t.following)
        assertNull(t.followed)
        t.undo()
        assertEquals(listOf(start, goal), t.plan)
        assertTrue("Undo: the tour follows the plan again", t.following)
        assertEquals(listOf(start, goal), t.followed)
    }

    @Test fun yesWithoutAPositionIsPlainRecording() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        t.answer(yes = true, position = null)
        assertFalse(t.following)
        t.undo()
        assertTrue(t.following)
    }

    @Test fun undoWhileAskingClosesTheQuestion() {
        val t = Tour(listOf(start, goal))
        t.edit(listOf(start), here)
        t.undo()
        assertFalse(t.asking)
        assertTrue(t.following)
    }

    @Test fun onlyTheGoalOfATwoPointPlanDuringAFollowedTour() {
        // the start deleted: the goal is left – no question
        assertFalse(RouteBack.asks(listOf(start, goal), listOf(goal), true, true, false, here))
        // three points → two: still a route
        assertFalse(RouteBack.asks(listOf(start, here, goal), listOf(start, here), true, true, false, here))
        // no tour, nothing followed, a followed GPX track
        assertFalse(RouteBack.asks(listOf(start, goal), listOf(start), false, true, false, here))
        assertFalse(RouteBack.asks(listOf(start, goal), listOf(start), true, false, false, here))
        assertFalse(RouteBack.asks(listOf(start, goal), listOf(start), true, true, true, here))
        // standing at the start: no way back to ask about
        assertFalse(RouteBack.asks(listOf(start, goal), listOf(start), true, true, false, GeoPoint(46.6702, 11.1601)))
        // position unknown: asked (Yes then says so and records plainly)
        assertTrue(RouteBack.asks(listOf(start, goal), listOf(start), true, true, false, null))
    }

    @Test fun planIsNullWhenThePlanChangedMeanwhile() {
        assertNull(RouteBack.plan(listOf(start, goal), here))
        assertNull(RouteBack.plan(emptyList(), here))
        assertEquals(listOf(here, start), RouteBack.plan(listOf(start), here))
    }
}
