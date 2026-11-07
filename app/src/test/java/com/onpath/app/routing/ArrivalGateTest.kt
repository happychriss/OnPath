package com.onpath.app.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalGateTest {
    private val dest = GeoPoint(46.67, 11.20)
    private fun at(lon: Double) = GeoPoint(46.67, lon) // 0.001° ≈ 76 m

    @Test fun roundTripDoesNotArriveAtTheStart() {
        val g = ArrivalGate()
        assertFalse(g.onFix(at(11.2001), dest, remainingAlongM = 5.0, accuracyM = 8.0)) // standing at the start = end
        assertFalse(g.onFix(at(11.2003), dest, remainingAlongM = 5.0, accuracyM = 8.0))
    }

    @Test fun asksOnceAfterHavingBeenAway() {
        val g = ArrivalGate()
        assertFalse(g.onFix(at(11.21), dest, remainingAlongM = 3000.0, accuracyM = 8.0)) // 760 m away
        assertTrue(g.onFix(at(11.2003), dest, remainingAlongM = 20.0, accuracyM = 8.0))
        assertFalse(g.onFix(at(11.2002), dest, remainingAlongM = 10.0, accuracyM = 8.0)) // not again while here
    }

    @Test fun asksAgainAfterLeavingAndReturning() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        assertTrue(g.onFix(at(11.2003), dest, 20.0, 8.0))
        assertFalse(g.onFix(at(11.21), dest, 3000.0, 8.0)) // declined, walked on
        assertTrue(g.onFix(at(11.2003), dest, 20.0, 8.0))
    }

    @Test fun poorFixesNeverTrigger() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        assertFalse(g.onFix(at(11.2003), dest, 20.0, accuracyM = 120.0))
        assertTrue(g.onFix(at(11.2003), dest, 20.0, accuracyM = 12.0))
    }

    @Test fun tooMuchRouteLeftIsNotArrival() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        assertFalse(g.onFix(at(11.2003), dest, remainingAlongM = 400.0, accuracyM = 8.0)) // path passes the hut
    }

    /** Owner 2026-11-01: never by being near the goal alone. */
    @Test fun notWhilePointsAreOpenOrTheProgressIsNotAtTheEnd() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        assertFalse("tour 62: 54 m and a turn left", g.onFix(at(11.2003), dest, remainingAlongM = 54.0, accuracyM = 8.0))
        assertFalse("a point in the loop not reached", g.onFix(at(11.2003), dest, remainingAlongM = 20.0, accuracyM = 8.0, openStops = 1))
        assertFalse("no progress known", g.onFix(at(11.2003), dest, remainingAlongM = null, accuracyM = 8.0))
        assertTrue(g.onFix(at(11.2003), dest, remainingAlongM = 20.0, accuracyM = 8.0, openStops = 0))
    }

    @Test fun resetForgetsEverything() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        g.reset()
        assertFalse(g.onFix(at(11.2003), dest, 20.0, 8.0))
    }

    /**
     * Field S10 2026-11-07 20:37 (0.17.3, bike, ▶ Sim): the plan edited ~75 m before the goal gave a new followed line;
     * the gate started over and forgot that the rider had been away – and as he never got 150 m from the goal again,
     * no "Arrived", no "Ziel erreicht", no end sheet. A new line keeps "been away"; it is asked again for it.
     */
    @Test fun aNewLineCloseToTheGoalStillArrives() {
        val g = ArrivalGate()
        assertFalse(g.onFix(at(11.21), dest, remainingAlongM = 3000.0, accuracyM = 5.0)) // riding, 760 m away
        assertFalse(g.onFix(at(11.2026), dest, remainingAlongM = 200.0, accuracyM = 5.0)) // 200 m to go
        g.newLine() // the plan edited 75 m before the goal: position → the points ahead
        assertFalse(g.onFix(at(11.2010), dest, remainingAlongM = 75.0, accuracyM = 5.0))
        assertTrue("arrives on the new line", g.onFix(at(11.2004), dest, remainingAlongM = 30.0, accuracyM = 5.0))
        assertTrue(g.away)
    }

    /** A new line after an arrival asks again for it (its own goal); never before the rider was away at all. */
    @Test fun aNewLineAsksAgainButNeverBeforeHavingBeenAway() {
        val g = ArrivalGate()
        assertFalse(g.onFix(at(11.2003), dest, 20.0, 5.0))
        g.newLine()
        assertFalse("the tour began here (a round trip, a stale fix)", g.onFix(at(11.2003), dest, 20.0, 5.0))
        g.onFix(at(11.21), dest, 3000.0, 5.0)
        assertTrue(g.onFix(at(11.2003), dest, 20.0, 5.0))
        g.newLine()
        assertTrue("the new line's own question", g.onFix(at(11.2002), dest, 10.0, 5.0))
    }

    /** Fixes stopping 20–40 m before the goal (a GPS gap, the phone put away): the last one in the zone arrives. */
    @Test fun fixesStoppingShortOfTheGoalArriveOnTheLastOne() {
        for (m in listOf(40.0, 30.0, 20.0)) {
            val g = ArrivalGate()
            g.onFix(at(11.21), dest, 3000.0, 5.0)
            assertFalse(g.onFix(at(11.2000 + 90 / 76_000.0), dest, remainingAlongM = 90.0, accuracyM = 5.0))
            assertTrue("$m m before the goal", g.onFix(at(11.2000 + m / 76_000.0), dest, remainingAlongM = m, accuracyM = 5.0))
        }
    }

    /** A service restart during the tour: "been away" comes back from the trip file. */
    @Test fun beenAwayIsRestoredAfterARestart() {
        val g = ArrivalGate()
        g.restoreAway(true)
        assertTrue(g.onFix(at(11.2003), dest, 20.0, 5.0))
        val h = ArrivalGate()
        h.restoreAway(false)
        assertFalse(h.onFix(at(11.2003), dest, 20.0, 5.0))
    }
}
