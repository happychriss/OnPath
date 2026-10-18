package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos

class NavTurnHintTest {
    private fun p(x: Double, y: Double) = GeoPoint(46.5 + y / 111_195.0, 11.35 + x / (111_195.0 * cos(Math.toRadians(46.5))))
    // north 300 m, then east 300 m: one right turn at 300 m
    private val line = (0..60).map { p(0.0, it * 5.0) } + (1..60).map { p(it * 5.0, 300.0) }
    private val turns = TurnDetector.turns(line)
    private val total = 600.0

    @Test fun showsNextTurnAheadOnRoute() {
        val (t, d) = nextTurnHint(turns, total, remainingM = 500.0, offM = 5.0, offRouteAlert = false)!!
        assertEquals(turns[0], t)
        assertEquals(200.0, d, 8.0)
    }

    @Test fun hiddenWhenOffRouteFarOrPassed() {
        assertNull(nextTurnHint(turns, total, 500.0, offM = 80.0, offRouteAlert = false))
        assertNull(nextTurnHint(turns, total, 500.0, offM = 5.0, offRouteAlert = true))
        assertNull(nextTurnHint(turns, total, remainingM = null, offM = null, offRouteAlert = false))
        assertNull(nextTurnHint(turns, total, remainingM = 250.0, offM = 2.0, offRouteAlert = false)) // passed
        assertNull(nextTurnHint(emptyList(), total, 500.0, 2.0, false))
    }

    @Test fun distanceWording() {
        assertEquals("Now", turnDistance(8.0))
        assertEquals("40 m", turnDistance(43.0))
        assertEquals("100 m", turnDistance(99.0))
        assertEquals("120 m", turnDistance(121.0))
        assertEquals("200 m", turnDistance(196.0))
        assertEquals("250 m", turnDistance(240.0))
        assertEquals("500 m", turnDistance(480.0))
    }
}
