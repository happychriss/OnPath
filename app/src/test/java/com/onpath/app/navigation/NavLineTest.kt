package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLineTest {
    @Test fun outAndBackHasTwoParts() {
        val l = NavLine(line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 8.0, 0.0 to 8.0))
        val parts = l.parts(pt(400.0, 3.0), 40.0)
        assertEquals(2, parts.size)
        assertEquals(400.0, parts[0].alongM, 2.0)
        assertEquals(1608.0, parts[1].alongM, 2.0)
    }

    @Test fun spurOnTheSamePathIsSplitByTheRiseInBetween() {
        // out 60 m and back on exactly the same path: two parts although every segment is within reach
        val l = NavLine(line(0.0 to 0.0, 60.0 to 0.0, -200.0 to 0.0))
        val parts = l.parts(pt(20.0, 0.0), 40.0)
        assertEquals(2, parts.size)
        assertEquals(20.0, parts[0].alongM, 2.0)
        assertEquals(100.0, parts[1].alongM, 2.0)
    }

    @Test fun windowLimitsTheParts() {
        val l = NavLine(line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 8.0, 0.0 to 8.0))
        assertEquals(1, l.parts(pt(400.0, 3.0), 40.0, 0.0, 1000.0).size)
        assertEquals(0, l.parts(pt(400.0, 300.0), 40.0).size)
    }

    @Test fun aheadBearingLooksForwardAndStopsAtTheEnd() {
        val l = NavLine(line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0))
        assertEquals(90.0, l.aheadBearing(30.0)!!, 1.0)
        assertEquals(45.0, l.aheadBearing(80.0)!!, 1.0)   // the bend ahead already turns the map halfway
        assertEquals(0.0, l.aheadBearing(150.0)!!, 1.0)
        assertEquals(null, l.aheadBearing(195.0))
    }

    @Test fun bearingFollowsTheLine() {
        val l = NavLine(line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0))
        assertEquals(90.0, l.bearingAt(50.0), 1.0)
        assertEquals(0.0, l.bearingAt(150.0), 1.0)
    }

    @Test fun sameRouteToleratesARecomputedLine() {
        val a = line(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 500.0)
        val b = line(0.0 to 2.0, 1000.0 to 3.0, 1000.0 to 500.0, stepM = 7.0) // other points, a few metres apart
        assertTrue(NavLine.sameRoute(a, b))
        // a round trip is not its way out; another route is not the same
        assertFalse(NavLine.sameRoute(a, a + a.reversed()))
        assertFalse(NavLine.sameRoute(a, line(0.0 to 0.0, 0.0 to 500.0, 1000.0 to 500.0)))
    }
}
