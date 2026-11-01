package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/tour-edit-position.md: the position splits the plan during a tour (owner 2026-10-31). */
class TourSplitTest {
    /** A point [x] m east and [y] m north of a spot in Hilden. */
    private fun pt(x: Double, y: Double) = GeoPoint(51.170 + y / 111_320.0, 6.930 + x / (111_320.0 * Math.cos(Math.toRadians(51.17))))
    private fun line(vararg p: GeoPoint, stepM: Double = 10.0): List<GeoPoint> = p.toList().zipWithNext().flatMap { (a, b) ->
        val n = maxOf(1, (com.onpath.app.data.Geo.distance(a, b) / stepM).toInt())
        (0 until n).map { i -> GeoPoint(a.lat + (b.lat - a.lat) * i / n, a.lon + (b.lon - a.lon) * i / n) }
    } + p.last()

    // Field case (owner, Hilden, 2026-10-31): plan 1 → 2 → 3; ridden from 1 past 2 at ~45 m, then another way south-west
    private val p1 = pt(0.0, 0.0)
    private val p2 = pt(0.0, 300.0)
    private val p3 = pt(300.0, -1100.0)
    private val here = pt(-200.0, -600.0)
    private val ridden = line(p1, pt(45.0, 300.0), pt(-100.0, 0.0), here)

    private fun tour(bike: Boolean, track: List<GeoPoint> = ridden, pos: GeoPoint? = here, reached: Boolean = true) =
        TourSplit.Tour(listOf(track), pos, reached, bike)

    @Test fun fieldCaseOnTheBike_point2IsPassedByNearness_deleting3LeavesNothingAhead() {
        val plan = listOf(p1, p2, p3)
        val passed = TourSplit.passed(plan, tour(bike = true))
        assertEquals(setOf(0, 1), passed)
        assertEquals(TourSplit.Outcome.NothingAhead, TourSplit.outcome(plan, listOf(p1, p2), passed))
    }

    @Test fun fieldCaseOnFoot_45mIsNotNear_butPoint3IsClearlyCloser() {
        val plan = listOf(p1, p2, p3)
        // 40 m on foot: 2 is not near the track; ~920 m to 2, ~710 m to 3 → 2 passed by the closer rule
        assertEquals(setOf(0, 1), TourSplit.passed(plan, tour(bike = false)))
        assertEquals(TourSplit.Outcome.NothingAhead, TourSplit.outcome(plan, listOf(p1, p2), setOf(0, 1)))
    }

    // Plan 1 → 2 → 3 → 4 along a street, the user between 2 and 3
    private val a1 = pt(0.0, 0.0); private val a2 = pt(0.0, 500.0); private val a3 = pt(0.0, 1000.0); private val a4 = pt(0.0, 1500.0)
    private val four = listOf(a1, a2, a3, a4)
    private val mid = pt(0.0, 700.0)
    private val walked = TourSplit.Tour(listOf(line(a1, mid)), mid, reached = true, bike = false)

    @Test fun pointsBehindArePassed_pointsAheadAreNot() {
        assertEquals(setOf(0, 1), TourSplit.passed(four, walked))
        assertEquals(listOf(a3, a4), TourSplit.ahead(four, setOf(0, 1)))
    }

    @Test fun deleteTheNextPoint_followPositionToTheRest() {
        assertEquals(TourSplit.Outcome.Follow(listOf(a4)), TourSplit.outcome(four, listOf(a1, a2, a4), setOf(0, 1)))
    }

    @Test fun deleteTheDestination_thePointBeforeItIsTheDestination() {
        assertEquals(TourSplit.Outcome.Follow(listOf(a3)), TourSplit.outcome(four, listOf(a1, a2, a3), setOf(0, 1)))
    }

    @Test fun deleteAPassedPoint_nothingChangesAhead() {
        assertEquals(TourSplit.Outcome.Unchanged, TourSplit.outcome(four, listOf(a2, a3, a4), setOf(0, 1)))
        assertEquals(TourSplit.Outcome.Unchanged, TourSplit.outcome(four, listOf(a1, a3, a4), setOf(0, 1)))
    }

    @Test fun movingAPointAhead_followsTheNewPoints() {
        val moved = pt(50.0, 1000.0)
        assertEquals(TourSplit.Outcome.Follow(listOf(moved, a4)), TourSplit.outcome(four, listOf(a1, a2, moved, a4), setOf(0, 1)))
    }

    @Test fun aNewPointGoesOnlyAhead_neverBehindTheUser() {
        // index among [position] + ahead → index in the whole plan
        assertEquals(2, TourSplit.insertIndex(setOf(0, 1), 0)) // "before the position" → right after it
        assertEquals(2, TourSplit.insertIndex(setOf(0, 1), 1))
        assertEquals(3, TourSplit.insertIndex(setOf(0, 1), 2))
        assertEquals(4, TourSplit.insertIndex(setOf(0, 1), 3)) // after the destination
        val plan = four.toMutableList().apply { add(TourSplit.insertIndex(setOf(0, 1), 1), pt(20.0, 850.0)) }
        assertEquals(TourSplit.Outcome.Follow(listOf(pt(20.0, 850.0), a3, a4)), TourSplit.outcome(four, plan, setOf(0, 1)))
    }

    @Test fun passedPointsAreCarriedOverByCoordinate() {
        // a point inserted between two passed ones is behind as well (plan order)
        val plan = listOf(a1, pt(10.0, 250.0), a2, a3, a4)
        assertEquals(setOf(0, 1, 2), TourSplit.carry(four, setOf(0, 1), plan))
    }

    @Test fun justStarted_deletingTheStart_followsTheRest() {
        val atStart = TourSplit.Tour(listOf(listOf(pt(0.0, -20.0), pt(0.0, -5.0))), pt(0.0, -5.0), reached = true, bike = false)
        val passed = TourSplit.passed(four, atStart)
        assertEquals(setOf(0), passed)
        assertEquals(TourSplit.Outcome.Unchanged, TourSplit.outcome(four, listOf(a2, a3, a4), passed))
    }

    @Test fun nothingPassed_thePlanIsFollowedAsAWhole() {
        val far = TourSplit.Tour(listOf(listOf(pt(5000.0, 5000.0))), pt(5000.0, 5000.0), reached = false, bike = true)
        assertEquals(emptySet<Int>(), TourSplit.passed(four, far))
        assertEquals(TourSplit.Outcome.WholePlan, TourSplit.outcome(four, listOf(a1, a2, a3), emptySet()))
    }

    @Test fun startedFarAway_theCloserRuleWaitsUntilTheRouteIsReached() {
        // 3 km south, closer to 2 than to 1 – but never on the route: everything stays ahead (C5 far start)
        val far = TourSplit.Tour(listOf(listOf(pt(0.0, -3000.0))), pt(0.0, -3000.0), reached = false, bike = false)
        assertEquals(emptySet<Int>(), TourSplit.passed(four, far))
    }

    @Test fun hairpin_aPointJustAboveIsNotClearlyCloser() {
        // switchback: 2 at the bottom, 3 just 150 m above in a straight line; the user below 2 on the way up
        val b1 = pt(0.0, 0.0); val b2 = pt(0.0, 600.0); val b3 = pt(150.0, 600.0)
        val below = pt(0.0, 380.0)
        val t = TourSplit.Tour(listOf(line(b1, below)), below, reached = true, bike = false)
        assertEquals(setOf(0), TourSplit.passed(listOf(b1, b2, b3), t))
    }

    @Test fun roundTrip_theGoalIsNeverPassed() {
        val loop = listOf(a1, a2, pt(400.0, 500.0), pt(0.0, 5.0))
        val home = TourSplit.Tour(listOf(line(a1, a2, pt(400.0, 500.0), pt(0.0, 5.0))), pt(0.0, 5.0), reached = true, bike = false)
        assertEquals(setOf(0, 1, 2), TourSplit.passed(loop, home))
    }

    @Test fun roundTrip_deleteTheStart_theLoopStaysClosedAtTheNextPoint() {
        val c = pt(400.0, 500.0)
        val loop = listOf(a1, a2, c, a1)
        val after = PlanPoints.deleteAt(loop, 0)
        val o = TourSplit.outcome(loop, after, setOf(0, 1))
        assertTrue("$o", o is TourSplit.Outcome.Follow)
        val ahead = (o as TourSplit.Outcome.Follow).ahead
        assertEquals(c, ahead.first())
        assertEquals(after.first(), ahead.last()) // the new start is the goal again
    }

    @Test fun fieldCase_backToTheStart_closesTheLoopAndFollowsPositionToTheStart() {
        // "Zurück zum Start" (AppViewModel.answerRouteBack): the start is appended as the goal; 1 and 2 stay passed
        val plan = listOf(p1, p2)
        val back = plan + plan.first()
        assertEquals(TourSplit.Outcome.Follow(listOf(p1)), TourSplit.outcome(plan, back, setOf(0, 1)))
    }

    @Test fun allPointsDeleted_isEmpty() {
        assertEquals(TourSplit.Outcome.Empty, TourSplit.outcome(four, emptyList(), setOf(0, 1)))
    }

    @Test fun twoPointPlan_goalDeletedAwayFromTheStart_nothingAhead() {
        // the old RouteBack case is the same rule
        val plan = listOf(a1, a4)
        val passed = TourSplit.passed(plan, walked)
        assertEquals(setOf(0), passed)
        assertEquals(TourSplit.Outcome.NothingAhead, TourSplit.outcome(plan, listOf(a1), passed))
    }

    /**
     * Field 2026-11-01 (fix-tour-edit-line.md cause 1): after an edit the tour follows position → ahead; its new session
     * no longer knows the passed points, so they were worked out again from the position alone and dropped – the pale
     * plan and the ✓ vanished and the next edit followed the whole plan from point 1 again. Passed points are kept per
     * tour ([known]).
     */
    @Test fun passedPointsStayPassedAfterTheTourFollowsTheEditedPlan() {
        val b1 = pt(0.0, 0.0); val b2 = pt(0.0, 500.0); val b3 = pt(0.0, 1000.0); val b4 = pt(0.0, 3000.0)
        val plan = listOf(b1, b2, b3, b4)
        val pos = pt(100.0, 600.0)
        val track = line(pt(60.0, 0.0), pt(60.0, 600.0), pos)
        val stops = listOf(NavStop(b1, 0.0), NavStop(b2, 500.0), NavStop(b3, 1000.0), NavStop(b4, 3000.0))
        val before = TourSplit.Tour(listOf(track), pos, reached = true, bike = false, stops = stops, progressM = 600.0)
        assertEquals(setOf(0, 1), TourSplit.passed(plan, before))
        // delete b4 → follow position → b3
        val after = listOf(b1, b2, b3)
        assertEquals(TourSplit.Outcome.Follow(listOf(b3)), TourSplit.outcome(plan, after, setOf(0, 1)))
        // the session after followAhead: position → b3, only b3 as a stop, nothing visited, no progress yet
        val fresh = TourSplit.Tour(listOf(track), pos, reached = true, bike = false,
            stops = listOf(NavStop(pos, 0.0), NavStop(b3, 420.0)), progressM = 0.0)
        assertEquals("without the tour's memory b2 is lost", setOf(0), TourSplit.passed(after, fresh))
        assertEquals(setOf(0, 1), TourSplit.passed(after, fresh, known = setOf(b1, b2)))
        // a deleted point no longer matches; a known point never makes the goal of a round trip passed
        assertEquals(setOf(0), TourSplit.passed(listOf(b1, b3), fresh, known = setOf(b1, b2)))
        assertEquals(emptySet<Int>(), TourSplit.passed(listOf(b1, b3, b1), fresh.copy(position = null, track = emptyList()),
            known = setOf(b1)).filter { it == 2 }.toSet())
    }
}
