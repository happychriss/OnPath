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
}
