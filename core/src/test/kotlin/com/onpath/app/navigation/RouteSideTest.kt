package com.onpath.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSideTest {
    @Test fun fourSectors() {
        assertEquals(RouteSide.AHEAD, RouteSide.of(90.0, 90.0))
        assertEquals(RouteSide.AHEAD, RouteSide.of(90.0, 130.0))
        assertEquals(RouteSide.RIGHT, RouteSide.of(90.0, 180.0))
        assertEquals(RouteSide.LEFT, RouteSide.of(90.0, 0.0))
        assertEquals(RouteSide.BEHIND, RouteSide.of(90.0, 270.0))
        assertEquals(RouteSide.BEHIND, RouteSide.of(90.0, 230.0))
    }

    @Test fun wrapsAroundNorth() {
        assertEquals(RouteSide.AHEAD, RouteSide.of(350.0, 10.0))
        assertEquals(RouteSide.RIGHT, RouteSide.of(350.0, 60.0))
        assertEquals(RouteSide.LEFT, RouteSide.of(10.0, 300.0))
        assertEquals(RouteSide.BEHIND, RouteSide.of(350.0, 170.0))
    }

    @Test fun edgesGoToTheSide() {
        assertEquals(RouteSide.AHEAD, RouteSide.of(0.0, 45.0))
        assertEquals(RouteSide.RIGHT, RouteSide.of(0.0, 46.0))
        assertEquals(RouteSide.RIGHT, RouteSide.of(0.0, 134.0))
        assertEquals(RouteSide.BEHIND, RouteSide.of(0.0, 135.0))
    }

    @Test fun unknownIsNull() {
        assertNull(RouteSide.of(null, 90.0))
        assertNull(RouteSide.of(90.0, null))
        assertNull(RouteSide.of(Double.NaN, 90.0))
    }
}
