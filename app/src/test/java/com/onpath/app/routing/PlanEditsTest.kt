package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanEditsTest {
    private val a = GeoPoint(46.67, 11.10)
    private val b = GeoPoint(46.67, 11.20)
    private val c = GeoPoint(46.75, 11.20)

    @Test fun reverse() = assertEquals(listOf(c, b, a), PlanEdits.reverse(listOf(a, b, c)))

    @Test fun roundTripAddsStartOnce() {
        val r = PlanEdits.roundTrip(listOf(a, b, c))
        assertEquals(listOf(a, b, c, a), r)
        assertEquals(r, PlanEdits.roundTrip(r))            // already a loop
        assertEquals(listOf(a), PlanEdits.roundTrip(listOf(a)))
    }

    @Test fun moveKeepsOthersInOrder() {
        assertEquals(listOf(b, c, a), PlanEdits.move(listOf(a, b, c), 0, 2))
        assertEquals(listOf(c, a, b), PlanEdits.move(listOf(a, b, c), 2, 0))
        assertEquals(listOf(a, b, c), PlanEdits.move(listOf(a, b, c), 5, 0)) // invalid index: unchanged
    }

    @Test fun replace() {
        val p = GeoPoint(1.0, 2.0)
        assertEquals(listOf(a, p, c), PlanEdits.replace(listOf(a, b, c), 1, p))
        assertEquals(listOf(a, b), PlanEdits.replace(listOf(a, b), 9, p))
    }

    @Test fun bestViaIsNeverTheEnd() {
        // a point far beyond c still goes between existing points
        val far = GeoPoint(46.85, 11.20)
        assertEquals(2, PlanEdits.bestViaIndex(listOf(a, b, c), far))
        // beside the a→b leg → index 1
        assertEquals(1, PlanEdits.bestViaIndex(listOf(a, b, c), GeoPoint(46.68, 11.15)))
    }
}
