package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import org.junit.Assert.assertEquals
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

    @Test fun bearingFollowsTheLine() {
        val l = NavLine(line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0))
        assertEquals(90.0, l.bearingAt(50.0), 1.0)
        assertEquals(0.0, l.bearingAt(150.0), 1.0)
    }
}
