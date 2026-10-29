package com.onpath.app.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProgressTest {
    // Straight line eastwards, one vertex every ~76 m.
    private val route = (0..100).map { GeoPoint(46.67, 11.10 + it * 0.001) }
    private val start = route[0]
    private val dest = route[100]

    private fun at(lon: Double, latOffset: Double = 0.0) = GeoPoint(46.67 + latOffset, lon)

    @Test fun arrival() {
        assertTrue(RouteProgress.arrived(at(11.2003), dest, remainingAlongM = 20.0))   // ~23 m away
        assertTrue(RouteProgress.arrived(at(11.2003), dest, remainingAlongM = null))   // free line end
        assertFalse(RouteProgress.arrived(at(11.2008), dest, remainingAlongM = 10.0))  // ~61 m away
        assertFalse(RouteProgress.arrived(null, dest, 0.0))
        assertFalse(RouteProgress.arrived(at(11.2), null, 0.0))
        // Round trip just after the start: close to the destination, but the whole line is left.
        assertFalse(RouteProgress.arrived(at(11.1001), start, remainingAlongM = 7_500.0))
    }
}
