package com.onpath.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTextTest {
    @Test fun positionForEmergencyCall() {
        assertEquals("46.54120 N 10.90320 E · ±8 m · 1234 m", NavText.position(46.5412, 10.9032, 8.2f, 1234.4, 5))
        assertEquals("33.86000 S 151.21000 E", NavText.position(-33.86, 151.21, null, null, 0))
        assertEquals("46.54120 N 10.90320 E · position 4 min old", NavText.position(46.5412, 10.9032, null, null, 250))
    }

    /** One off-route card – "Off route", "120 m north-east (direct)", the state. */
    @Test fun offRouteCardTexts() {
        assertEquals("120 m north-east (direct)", NavText.toRoute(RouteHint(118.0, 44.0, com.onpath.app.routing.GeoPoint(46.0, 11.0))))
        assertEquals("Looking for a way back…", NavText.state(NavPrompt.Searching))
        assertEquals("No way back found", NavText.state(NavPrompt.NoWay(Rerouter.Reason.NO_WAY)))
        assertEquals("No map here to find a way back", NavText.state(NavPrompt.NoWay(Rerouter.Reason.NO_DATA)))
        assertEquals("Following the original route", NavText.state(NavPrompt.Kept))
    }

    @Test fun positionCopyIsPlainLatLon() {
        assertEquals("46.54120, 10.90320", NavText.positionCopy(46.5412, 10.9032))
    }
}
