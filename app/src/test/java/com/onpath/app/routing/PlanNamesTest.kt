package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name and fit rules the web planner compiles from the app (mirror list): one copy for both. */
class PlanNamesTest {
    @Test fun names() {
        assertEquals("Vellau → Leiteralm", PlanNames.newRouteName("Vellau → Leiteralm", "Route 1 Nov", emptyList()))
        assertEquals("Route 1 Nov", PlanNames.newRouteName(" ", "Route 1 Nov", emptyList()))
        assertEquals("A (2)", PlanNames.newRouteName("A", "d", listOf("A")))
        assertEquals("A (3)", PlanNames.copyName("A (2)", listOf("A", "A (2)")))
    }

    @Test fun fit() {
        assertTrue(RouteFit.firstRoute(1, 2))
        assertFalse(RouteFit.firstRoute(2, 3))
        val a = GeoPoint(46.0, 11.0); val b = GeoPoint(46.1, 11.1)
        assertTrue(RouteFit.fitAfterPlace(listOf(a), listOf(a, b)))
        assertFalse(RouteFit.fitAfterPlace(listOf(a, b), listOf(a, b)))
    }
}
