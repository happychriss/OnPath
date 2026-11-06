package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Navigate's "▶ Sim" begins where the tour is on the followed line (field 2026-11-06 23:35, Hilden round trip). */
class NavSimStartTest {
    @Test fun duringATourOnTheLineTheSimBeginsWhereTheTourIs() {
        // the field case: 2.7 km along a 4.7 km loop – not at ① (which is also the loop's end)
        assertEquals(2_700.0, NavSimStart.alongM(sameLine = true, reached = true, shownAlongM = 2_700.0, progressM = 2_690.0), 1e-9)
        assertEquals("shown progress missing: the matched progress", 2_690.0,
            NavSimStart.alongM(sameLine = true, reached = true, shownAlongM = null, progressM = 2_690.0), 1e-9)
    }

    @Test fun otherwiseFromTheStart() {
        assertEquals("route not reached yet", 0.0, NavSimStart.alongM(true, reached = false, shownAlongM = null, progressM = 120.0), 1e-9)
        assertEquals("another line (plan, track)", 0.0, NavSimStart.alongM(false, reached = true, shownAlongM = 2_700.0, progressM = 2_700.0), 1e-9)
        assertEquals("no tour", 0.0, NavSimStart.alongM(true, reached = false, shownAlongM = null, progressM = null), 1e-9)
    }
}
