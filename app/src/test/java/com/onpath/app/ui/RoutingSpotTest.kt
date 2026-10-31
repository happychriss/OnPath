package com.onpath.app.ui

import com.onpath.app.navigation.Trace.pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bug 02: "nothing happened" for 13 s after a hold – the spinner turns at the point that was added / moved. */
class RoutingSpotTest {
    private val a = pt(0.0, 0.0); private val b = pt(500.0, 0.0); private val c = pt(900.0, 0.0)
    private val held = pt(-45_000.0, 0.0)

    @Test fun bug02SpinnerAtTheHeldPointWhileItsRouteIsComputed() {
        val spot = RoutingSpot.of(listOf(a, b, c), listOf(a, b, c, held))
        assertEquals(held, spot)
        assertEquals(held, RoutingSpot.shown(routing = true, spot, listOf(a, b, c, held)))
        assertNull("route ready", RoutingSpot.shown(routing = false, spot, listOf(a, b, c, held)))
        // Undo took the point away: no spinner at a point that is gone
        assertNull(RoutingSpot.shown(routing = true, spot, listOf(a, b, c)))
    }

    @Test fun bug02MovedPointIsTheSpotDeletesAndReordersHaveNone() {
        val moved = pt(520.0, 30.0)
        assertEquals(moved, RoutingSpot.of(listOf(a, b, c), listOf(a, moved, c)))
        assertNull(RoutingSpot.of(listOf(a, b, c), listOf(a, c)))
        assertNull(RoutingSpot.of(listOf(a, b, c), listOf(c, b, a)))
    }
}
