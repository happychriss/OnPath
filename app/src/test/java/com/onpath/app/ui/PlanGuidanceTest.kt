package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.onpath.app.domain.plan.RouteBack

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
        val shown = vm.substringAfter("fun followShown(").substringBefore("\n    }")
        assertTrue(shown, shown.contains("planAtTrackFollow = waypoints.toList()"))
    }

    /** Owner 2026-11-06 (onpath.private#39): one point left during a tour = "Hierhin" (position → that point). */
    /**
     * Field bug 2026-11-06 21:18 ("es gibt keinen Punkt 1 mehr"): the point left right where I stand → the plan empties
     * (no "position → point" that comes back after every delete); the rule's own position start left alone → empty
     * too, never "Route back to the start?"; far enough → position → point as before.
     */
    @Test fun onePointLeftNearOrTheRulesOwnStartEmptiesThePlan() {
        fun of(d: Double?, auto: Boolean = false, back: Boolean = false) =
            PlanGuidance.oneLeft(2, 1, recording = true, following = true, followIsTrack = false, routeBackAsk = back,
                leftIsAutoStart = auto, distanceToLeftM = d)
        assertEquals(PlanGuidance.OneLeft.FROM_HERE, of(640.0))
        assertEquals(PlanGuidance.OneLeft.FROM_HERE, of(PlanGuidance.ONE_LEFT_NEAR_M))
        assertEquals(PlanGuidance.OneLeft.EMPTY, of(11.0))
        assertEquals(PlanGuidance.OneLeft.EMPTY, of(0.0))
        // the start the rule put in (my position then) is no point of mine: left alone it goes, wherever I am now
        assertEquals(PlanGuidance.OneLeft.EMPTY, of(300.0, auto = true))
        assertEquals(PlanGuidance.OneLeft.EMPTY, of(300.0, auto = true, back = true))
        // the goal deleted far from the start: "Route back to the start?" as before
        assertEquals(PlanGuidance.OneLeft.NONE, of(300.0, back = true))
        // no position: the view model's "no position" path (guidance ends with a line)
        assertEquals(PlanGuidance.OneLeft.FROM_HERE, of(null))
        // not during a tour on the plan: untouched
        assertEquals(PlanGuidance.OneLeft.NONE, PlanGuidance.oneLeft(2, 1, false, false, false, false, false, 11.0))
        assertEquals(PlanGuidance.OneLeft.NONE, PlanGuidance.oneLeft(2, 1, true, true, true, false, false, 11.0))
        assertEquals(PlanGuidance.OneLeft.NONE, PlanGuidance.oneLeft(3, 2, true, true, false, false, false, 11.0))
        // one rule with "Route back to the start?": closer than this there is nothing to go to
        assertEquals(RouteBack.AT_START_M, PlanGuidance.ONE_LEFT_NEAR_M, 0.0)
    }

    /** Standing on point 1 counts it as passed (split NothingAhead): no question for a point left where I stand. */
    @Test fun aPointLeftWhereIStandIsHere() {
        assertTrue(PlanGuidance.leftIsHere(leftIsAutoStart = false, distanceToLeftM = 0.0))
        assertTrue(PlanGuidance.leftIsHere(false, 49.0))
        assertTrue(PlanGuidance.leftIsHere(leftIsAutoStart = true, distanceToLeftM = 600.0))
        assertTrue(PlanGuidance.leftIsHere(true, null))
        assertFalse(PlanGuidance.leftIsHere(false, 50.0))
        assertFalse(PlanGuidance.leftIsHere(false, 640.0))
        assertFalse("no position: ask as before", PlanGuidance.leftIsHere(false, null))
    }

    /** A leg of a few metres (the goal right beside the start) is no "no path found" (field bug 2026-11-06 21:18). */
    @Test fun aMiniLegIsNoError() {
        assertTrue(com.onpath.app.routing.Planner.isMiniLeg(11.0))
        assertFalse(com.onpath.app.routing.Planner.isMiniLeg(com.onpath.app.routing.Planner.MINI_LEG_M + 1.0))
    }

    @Test fun onePointLeftDuringATourGoesFromHere() {
        // the field case: start of start → goal deleted while the tour follows the plan
        assertTrue(PlanGuidance.oneLeftGoesFromHere(2, 1, recording = true, following = true, followIsTrack = false, routeBackAsk = false))
        assertTrue(PlanGuidance.oneLeftGoesFromHere(3, 1, true, true, false, false))
        // the goal deleted: "Route back to the start?" is asked instead
        assertFalse(PlanGuidance.oneLeftGoesFromHere(2, 1, true, true, false, routeBackAsk = true))
        // no tour, no followed plan, a followed track, a first point set on an empty plan, two points left: untouched
        assertFalse(PlanGuidance.oneLeftGoesFromHere(2, 1, recording = false, following = false, followIsTrack = false, routeBackAsk = false))
        assertFalse(PlanGuidance.oneLeftGoesFromHere(2, 1, true, following = false, followIsTrack = false, routeBackAsk = false))
        assertFalse(PlanGuidance.oneLeftGoesFromHere(2, 1, true, true, followIsTrack = true, routeBackAsk = false))
        assertFalse(PlanGuidance.oneLeftGoesFromHere(0, 1, true, true, false, false))
        assertFalse(PlanGuidance.oneLeftGoesFromHere(3, 2, true, true, false, false))
        // the view model asks after every edit and every Undo / Redo
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        // an edit may empty the plan (field bug 2026-11-06), Undo / Redo never does
        assertTrue(vm.substringAfter("private fun change(").substringBefore("\n    }\n").contains("oneLeftGoesFromHere(before, mayEmpty = true)"))
        assertTrue(vm.substringAfter("private fun step(").substringBefore("\n    }\n").contains("oneLeftGoesFromHere(before)"))
    }
}
