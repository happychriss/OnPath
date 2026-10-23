package com.onpath.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavTextTest {
    @Test fun positionForEmergencyCall() {
        assertEquals("46.54120 N 10.90320 E · ±8 m · 1234 m", NavText.position(46.5412, 10.9032, 8.2f, 1234.4, 5))
        assertEquals("33.86000 S 151.21000 E", NavText.position(-33.86, 151.21, null, null, 0))
        assertEquals("46.54120 N 10.90320 E · position 4 min old", NavText.position(46.5412, 10.9032, null, null, 250))
    }

    @Test fun positionCopyIsPlainLatLon() {
        assertEquals("46.54120, 10.90320", NavText.positionCopy(46.5412, 10.9032))
    }
}
