package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The dot on the route while close to it, the real GPS position off it. */
class SnapToRouteTest {
    private val l = NavLine(line(0.0 to 0.0, 1000.0 to 0.0))

    @Test fun closeToTheRouteTheDotIsOnIt() {
        val p = SnapToRoute.position(true, l, 300.0, 12.0, null)!!
        assertEquals(pt(300.0, 0.0).lat, p.lat, 1e-5); assertEquals(pt(300.0, 0.0).lon, p.lon, 2e-5) // ±1 m
    }
    @Test fun offTheRouteTheRealFix() = assertNull(SnapToRoute.position(true, l, 300.0, 40.0, null))
    @Test fun switchedOff() = assertNull(SnapToRoute.position(false, l, 300.0, 5.0, null))
    @Test fun notMatched() = assertNull(SnapToRoute.position(true, l, null, 5.0, null))
    @Test fun whileAskingAboutANewRoute() = assertNull(SnapToRoute.position(true, l, 300.0, 5.0, NavPrompt.Searching))
}
