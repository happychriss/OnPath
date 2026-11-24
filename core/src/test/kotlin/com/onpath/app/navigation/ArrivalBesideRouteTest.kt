package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.navigation.Trace.walk
import com.onpath.app.routing.Activity
import com.onpath.app.routing.ArrivalGate
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5, arrival: a plan can hold a stop the chosen level cannot reach (the route passes it by, the plan sheet's red line
 * says so – `PlanGaps`). Following the line never comes within the "reached" radius of it, so it stayed open for ever
 * and "Du bist angekommen" never came at the goal. It stops holding the arrival back once the rider got to the place on
 * the line nearest to it – turning round earlier is still not an arrival.
 */
class ArrivalBesideRouteTest {
    private val straight = line(0.0 to 0.0, 2000.0 to 0.0)

    private fun plan(route: List<GeoPoint>, vararg stops: GeoPoint, activity: Activity = Activity.HIKE) =
        NavSession.start(NavKind.PLAN, "Test", activity, 3, route, stops.toList())

    /** The service's arrival check on every fix: progress on the followed line + the session's open stops. */
    private class Ride(session: NavSession) {
        val nav = Navigator(session)
        val gate = ArrivalGate().apply { restoreAway(true) }
        var arrived = false

        fun feed(fixes: List<OffRouteDetector.Fix>) {
            for (f in fixes) {
                nav.onFix(f)
                val st = nav.state
                val remaining = (Geo.length(st.line) - (st.shownAlongM ?: st.progressM)).coerceAtLeast(0.0)
                if (gate.onFix(f.point, st.line.last(), remaining, f.accuracyM, openStops = nav.session.openStops)) arrived = true
            }
        }
    }

    @Test fun aStopTheRouteCannotReachDoesNotHoldBackTheArrival() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 2000.0 to 0.0))
        assertTrue("arrives at the goal", r.arrived)
        assertFalse("never counted as reached", 1 in r.nav.session.visited)
    }

    @Test fun onABikeToo() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 200.0), pt(2000.0, 0.0), activity = Activity.BIKE_TOURING))
        r.feed(walk(0.0 to 0.0, 2000.0 to 0.0, speed = 5.0))
        assertTrue(r.arrived)
    }

    /** Today's rule stays: a stop the line does reach, passed without a good fix near it, still holds the arrival back. */
    @Test fun aReachableStopNotVisitedStillHoldsTheArrivalBack() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 0.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 900.0 to 0.0))
        r.feed(walk(900.0 to 0.0, 1100.0 to 0.0, acc = 35.0, t0Ms = 700_000L)) // too poor to count as reached
        r.feed(walk(1100.0 to 0.0, 2000.0 to 0.0, t0Ms = 900_000L))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }

    /**
     * An out-and-back whose far stop lies 200 m beyond the end of the way. Turned round early, the detector counts the way
     * back as progress ("you turned around before the turnaround point") – the open stop is what says "not arrived".
     */
    @Test fun turningRoundBeforeThePlaceNearestToItIsNoArrival() {
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val r = Ride(plan(outAndBack, pt(0.0, 0.0), pt(1200.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 500.0 to 0.0, 0.0 to 0.0))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }

    @Test fun theSameOutAndBackWalkedToItsEndArrives() {
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val r = Ride(plan(outAndBack, pt(0.0, 0.0), pt(1200.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0))
        assertEquals(0, r.nav.session.openStops)
        assertTrue(r.arrived)
    }

    /** A restart of the service after passing it: the session file keeps it. */
    @Test fun passingItSurvivesARestart() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 1500.0 to 0.0))
        val back = NavSessionStore.fromJson(NavSessionStore.toJson(r.nav.session))!!
        assertEquals(setOf(1), back.passedBy)
        assertEquals(0, back.openStops)
    }

    /** On a new line (way back taken) a later stop counts only in plan order: the stop passed by must not block it. */
    @Test fun aStopPassedByDoesNotBlockTheNextOneOnANewLine() {
        val s = plan(straight, pt(0.0, 0.0), pt(500.0, 250.0), pt(1500.0, 0.0), pt(2000.0, 0.0))
            .copy(current = straight.toList(), reached = true)
        val r = Ride(s)
        r.feed(walk(0.0 to 0.0, 2000.0 to 0.0))
        assertTrue("the stop on the line is reached", 2 in r.nav.session.visited)
        assertTrue(r.arrived)
    }

    // ---- review 2026-11-21: a false "you have arrived" is worse than a missing one ----

    private val outAndBackFar = line(0.0 to 0.0, 3000.0 to 0.0, 3000.0 to 1100.0, 1500.0 to 1100.0,
        3000.0 to 1100.0, 3000.0 to 0.0, 0.0 to 0.0)

    /** Finding 1: the far stop's own place is the real turnaround, not an early pass the next stop's along cuts it off at. */
    @Test fun aFarStopIsNotPassedOnAnEarlyPartOfTheLine() {
        val r = Ride(plan(outAndBackFar, pt(0.0, 0.0), pt(1500.0, 560.0), pt(1700.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 1800.0 to 0.0, 0.0 to 0.0))
        assertFalse("turned round after 1.8 km of 11 km", r.arrived)
        assertEquals(1, r.nav.session.openStops)
    }

    @Test fun theSameFarStopWalkedToTheTurnaroundArrives() {
        val r = Ride(plan(outAndBackFar, pt(0.0, 0.0), pt(1500.0, 560.0), pt(1700.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 3000.0 to 0.0, 3000.0 to 1100.0, 1500.0 to 1100.0, 3000.0 to 1100.0, 3000.0 to 0.0, 0.0 to 0.0))
        assertEquals(setOf(1), r.nav.session.passedBy)
        assertTrue(r.arrived)
    }

    /** Finding 2: a plan edit does not lead the rider back to a stop passed by – and the map does not call it reached. */
    @Test fun aPlanEditKeepsAStopPassedByBehindTheRider() {
        val pts = listOf(pt(0.0, 0.0), pt(1000.0, 250.0), pt(1500.0, 0.0), pt(2000.0, 0.0))
        val track = listOf(line(0.0 to 0.0, 1700.0 to 0.0))
        val t = TourSplit.Tour(track, pt(1700.0, 0.0), reached = true, bike = false,
            visited = setOf(pts[0], pts[2]), passedBy = setOf(pts[1]))
        val passed = TourSplit.passed(pts, t)
        assertEquals(setOf(0, 1, 2), passed)
        assertEquals(listOf(pts[3]), TourSplit.ahead(pts, passed))
        assertEquals("only reached points are grey on the map", setOf(0, 2), TourSplit.reachedOf(pts, passed, t))
    }

    /** A stop passed by and then really walked to counts as reached on the map. */
    @Test fun aStopPassedByAndThenWalkedToIsGreyOnTheMap() {
        val pts = listOf(pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0))
        val track = listOf(line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 250.0))
        val t = TourSplit.Tour(track, pt(1000.0, 250.0), reached = true, bike = false, passedBy = setOf(pts[1]))
        assertEquals(setOf(0, 1), TourSplit.reachedOf(pts, TourSplit.passed(pts, t), t))
    }

    /** Finding 3: on a taken way back, a stop passed by is no target any more (it could mean "turn round"). */
    @Test fun aStopPassedByIsNoTargetOfTheNextWayBack() {
        val s = plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)).copy(
            current = line(600.0 to -120.0, 1700.0 to -120.0, 2000.0 to 0.0), leftAtM = 600.0, reached = true,
            visited = setOf(0), passedBy = setOf(1))
        val nav = Navigator(s)
        var job: Navigator.Job? = null
        for (f in walk(1200.0 to -120.0, 1300.0 to -120.0, 1300.0 to -500.0)) nav.onFix(f).job?.let { job = it }
        val req = job!!.request
        val base = RerouteTargets.base(req.original, req.position, req.progressM, req.stops, req.visited)
        assertTrue(RerouteTargets.candidates(req.original, base, req.stops, req.visited).none { it.stopIndex == 1 })
    }

    @Test fun anActivityCheckDoesNotRouteBackToAStopPassedBy() {
        val s = plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0))
            .copy(reached = true, visited = setOf(0), passedBy = setOf(1))
        val rest = ActivityCheck.rest(s, 600.0, pt(1300.0, 0.0))!!
        assertEquals(listOf(s.stops[2]), rest.stops)
    }

    /** Finding 4: a taken line that keeps away from the original's place has a nearest place of its own. */
    @Test fun aStopCountsAtTheNearestPlaceOfATakenLine() {
        // left the plan at 400 m; the way back runs 60 m beside it, past the stop's place, and rejoins it at 1300 m
        val detour = line(400.0 to -60.0, 1300.0 to -60.0, 1300.0 to 0.0, 2000.0 to 0.0)
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(1700.0, 0.0), pt(2000.0, 0.0))
            .copy(current = detour, leftAtM = 400.0, spliceAtM = Geo.length(detour) - 700.0, resumeAlongM = 1300.0,
                reached = true, visited = setOf(0)))
        r.feed(walk(400.0 to -60.0, 1300.0 to -60.0, 1300.0 to 0.0, 2000.0 to 0.0))
        assertEquals(setOf(1), r.nav.session.passedBy)
        assertTrue("the stop after it counts again", 2 in r.nav.session.visited)
        assertTrue(r.arrived)
    }

    /** A way back from an early turnaround is no line "past" the stop: its nearest place is nowhere near the plan's. */
    @Test fun aWayBackFromAnEarlyTurnaroundDoesNotPassTheStop() {
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val back = line(300.0 to -100.0, 350.0 to 0.0, 0.0 to 0.0)
        val r = Ride(plan(outAndBack, pt(0.0, 0.0), pt(1200.0, 0.0), pt(0.0, 0.0))
            .copy(current = back, leftAtM = 1700.0, spliceAtM = Geo.length(back), resumeAlongM = 2000.0,
                reached = true, visited = setOf(0)))
        r.feed(walk(300.0 to -100.0, 350.0 to 0.0, 0.0 to 0.0))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }

    @Test fun aParallelWayPastThePlaceCountsOnTheStopsSide() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 100.0 to 30.0, 1900.0 to 30.0, 2000.0 to 0.0))
        assertEquals(setOf(1), r.nav.session.passedBy)
        assertTrue(r.arrived)
    }

    @Test fun aParallelWayPastThePlaceCountsOnTheOtherSide() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 100.0 to -30.0, 1900.0 to -30.0, 2000.0 to 0.0))
        assertEquals(setOf(1), r.nav.session.passedBy)
        assertTrue(r.arrived)
    }

    @Test fun turningRoundThirtyMetresShortOfThePlaceIsNoArrival() {
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val r = Ride(plan(outAndBack, pt(0.0, 0.0), pt(1200.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 970.0 to 0.0, 0.0 to 0.0))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }

    /**
     * A hairpin whose way back runs 20 m beside the way out, the stop's place on the way back. Turned round early on the way
     * out, the rider was within the radius of that place, but 800 m before it along the line: not there.
     */
    @Test fun theOtherBranchOfAHairpinIsNotThePlace() {
        val hairpin = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 20.0, 0.0 to 20.0)
        val r = Ride(plan(hairpin, pt(0.0, 0.0), pt(600.0, 300.0), pt(0.0, 20.0)))
        r.feed(walk(0.0 to 0.0, 640.0 to 0.0, 0.0 to 0.0))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }

    /** Finding 5: poor GPS (still recorded, too poor for "reached") at the place does not hold the arrival back for good. */
    @Test fun poorGpsAtThePlaceStillCountsItPassed() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 900.0 to 0.0))
        r.feed(walk(900.0 to 0.0, 1100.0 to 0.0, acc = 35.0, t0Ms = 700_000L))
        r.feed(walk(1100.0 to 0.0, 2000.0 to 0.0, t0Ms = 900_000L))
        assertEquals(setOf(1), r.nav.session.passedBy)
        assertFalse("never reached", 1 in r.nav.session.visited)
        assertTrue(r.arrived)
    }

    /** A few poor fixes thrown across the place by the noise, the rider 40 m short of it: no walk past it. */
    @Test fun poorFixesThrownAcrossThePlaceAreNoWalkPastIt() {
        val r = Ride(plan(straight, pt(0.0, 0.0), pt(1000.0, 250.0), pt(2000.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 960.0 to 0.0))
        val t = 800_000L
        r.feed(listOf(960.0, 1005.0, 1010.0, 1035.0, 1040.0, 960.0).mapIndexed { i, x -> Trace.fix(x, 0.0, t + i * 1000L, acc = 35.0) })
        assertEquals(1, r.nav.session.openStops)
    }

    @Test fun poorGpsTurningRoundShortOfThePlaceIsNoArrival() {
        val outAndBack = line(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        val r = Ride(plan(outAndBack, pt(0.0, 0.0), pt(1200.0, 0.0), pt(0.0, 0.0)))
        r.feed(walk(0.0 to 0.0, 900.0 to 0.0))
        r.feed(walk(900.0 to 0.0, 960.0 to 0.0, 0.0 to 0.0, acc = 35.0, t0Ms = 700_000L))
        assertEquals(1, r.nav.session.openStops)
        assertFalse(r.arrived)
    }
}
