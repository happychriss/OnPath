package com.onpath.app.navigation

import com.onpath.app.domain.tour.NextStop
import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import com.onpath.app.routing.ArrivalGate
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5 (2026-11-25): a stop passed by is never "next ahead"; GPS noise (±20–45 m) does not count an arrival / a stop
 * early; a rider who leaves the line past a stop does not keep it open for ever.
 */
class NavStopsNoiseTest {
    private val straight = line(0.0 to 0.0, 2000.0 to 0.0)

    private fun plan(vararg stops: GeoPoint, activity: Activity = Activity.HIKE) =
        NavSession.start(NavKind.PLAN, "Test", activity, 3, straight, stops.toList())

    @Test fun aStopPassedByIsNeverTheNextStopAhead() {
        val s = plan(pt(0.0, 0.0), pt(1000.0, 0.0), pt(2000.0, 0.0)).copy(reached = true, passedBy = setOf(1))
        val next = NextStop.ahead(s, 500.0)
        assertEquals("the goal, not the passed stop 2", 3, next?.number)
        assertEquals(listOf(3), NextStop.aheadList(s, 500.0).map { it.number })
    }

    @Test fun aNoisyFixNearTheGoalIsNoArrival() {
        val g = ArrivalGate().apply { restoreAway(true) }
        val goal = pt(2000.0, 0.0)
        // 45 m short, a poor fix (accuracy 40 m): may be 85 m away really
        assertFalse(g.onFix(pt(1955.0, 0.0), goal, 45.0, 40.0))
        // 40 m short, accuracy 25 m
        assertFalse(g.onFix(pt(1960.0, 0.0), goal, 40.0, 25.0))
    }

    @Test fun aCleanFixStillArrivesAsBefore() {
        val goal = pt(2000.0, 0.0)
        val g = ArrivalGate().apply { restoreAway(true) }
        assertTrue(g.onFix(pt(1955.0, 0.0), goal, 45.0, 5.0)) // clean GPS: 45 m still counts, as before
        val g2 = ArrivalGate().apply { restoreAway(true) }
        assertTrue(g2.onFix(pt(1990.0, 0.0), goal, 10.0, 40.0)) // a poor fix right at the goal still counts
    }

    @Test fun aNoisyFixBesideAStopDoesNotReachIt() {
        val nav = Navigator(plan(pt(0.0, 0.0), pt(1000.0, 0.0), pt(2000.0, 0.0)))
        nav.onFix(fix(0.0, 0.0, 0L)); nav.onFix(fix(10.0, 0.0, 10_000L))
        // before the stop, noisy fixes (accuracy 20) jump to 18-30 m from it
        nav.onFix(fix(982.0, 0.0, 700_000L, acc = 20.0)); nav.onFix(fix(975.0, 0.0, 701_000L, acc = 20.0))
        nav.onFix(fix(970.0, 0.0, 702_000L, acc = 20.0))
        assertFalse("not reached from a noisy fix 18-30 m away", 1 in nav.session.visited)
    }

    @Test fun leavingTheLinePastAStopDoesNotKeepItOpen() {
        val nav = Navigator(plan(pt(0.0, 0.0), pt(1000.0, 0.0), pt(2000.0, 0.0)))
        // walks 32 m beside the line (outside the 25 m radius) past the stop, then leaves for a hut 300 m off the line
        nav.onFix(fix(0.0, 0.0, 0L))
        var t = 1000L
        var x = 5.0
        while (x <= 1100.0) { nav.onFix(fix(x, 32.0, t)); x += 10.0; t += 8_000L }
        var y = 32.0
        while (y <= 300.0) { nav.onFix(fix(1100.0, y, t)); y += 10.0; t += 8_000L }
        assertFalse(1 in nav.session.visited)
        assertTrue("passed by, not open", 1 in nav.session.passedBy)
        assertEquals(0, nav.session.openStops)
    }
}
