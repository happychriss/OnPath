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

    @Test fun resetForgetsEverything() {
        val g = ArrivalGate()
        g.onFix(at(11.21), dest, 3000.0, 8.0)
        g.reset()
        assertFalse(g.onFix(at(11.2003), dest, 20.0, 8.0))
    }
}
