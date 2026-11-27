package com.onpath.app.domain

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PointMarksTest {
    private fun kinds(points: List<GeoPoint>, passed: Set<Int> = emptySet()) =
        PointMarks.kinds(points, passed = passed)

    private val a = GeoPoint(47.0, 8.0)
    private val b = GeoPoint(47.01, 8.0)
    private val c = GeoPoint(47.01, 8.01)
    private val d = GeoPoint(47.02, 8.02)

    @Test fun plainRouteStartNumberedGoalFlag() =
        assertEquals(listOf("s", "v", "e"), kinds(listOf(a, b, c)))

    @Test fun roundTripStartShowsTheGoalFlag() =
        // owner 2026-11-27: start = goal, so the flag, never the „1“
        assertEquals(listOf("e", "v", "v", "e"), kinds(listOf(a, b, c, a)))

    @Test fun roundTripBehindAStopOnTheWayFlagsTheLoopStartOnly() =
        assertEquals(listOf("s", "e", "v", "v", "e"), kinds(listOf(d, a, b, c, a)))

    @Test fun passedAndUnreachedWin() {
        assertEquals(listOf("p", "v", "v", "e"), kinds(listOf(a, b, c, a), passed = setOf(0)))
        assertEquals("g", PointMarks.kind(0, 4, unreached = setOf(0), loopStart = 0))
    }

    @Test fun onePointIsTheStart() = assertEquals(listOf("s"), kinds(listOf(a)))
}
