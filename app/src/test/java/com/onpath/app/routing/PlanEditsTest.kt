package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    // --- owner concept (2026-10-18): start / end here drop points, round trip rotates ---
    private val r = listOf(GeoPoint(46.0, 11.0), GeoPoint(46.01, 11.0), GeoPoint(46.02, 11.0), GeoPoint(46.03, 11.0), GeoPoint(46.04, 11.0))
    private val loop = listOf(GeoPoint(46.0, 11.0), GeoPoint(46.01, 11.0), GeoPoint(46.01, 11.01), GeoPoint(46.0, 11.01), GeoPoint(46.0, 11.0))

    @Test fun startAtDropsEarlierPoints() = assertEquals(r.subList(2, 5), PlanEdits.startAt(r, 2))
    @Test fun endAtDropsLaterPoints() = assertEquals(r.subList(0, 3), PlanEdits.endAt(r, 2))
    @Test fun startAndEndIgnoreStartAndDestination() {
        assertEquals(r, PlanEdits.startAt(r, 0)); assertEquals(r, PlanEdits.startAt(r, 4))
        assertEquals(r, PlanEdits.endAt(r, 0)); assertEquals(r, PlanEdits.endAt(r, 4))
    }
    @Test fun roundTripDetected() {
        assertTrue(PlanEdits.isRoundTrip(loop)); assertFalse(PlanEdits.isRoundTrip(r))
        assertFalse(PlanEdits.isRoundTrip(listOf(loop[0], loop[0]))) // 2 points is not a loop
    }
    @Test fun rotateLoopKeepsOrderAndCloses() {
        val out = PlanEdits.rotateLoop(loop, 2)
        assertEquals(listOf(loop[2], loop[3], loop[0], loop[1], loop[2]), out)
        assertEquals(loop.size, out.size)
    }
    @Test fun rotateLoopNoOpOnNormalRouteOrStart() {
        assertEquals(r, PlanEdits.rotateLoop(r, 2)); assertEquals(loop, PlanEdits.rotateLoop(loop, 0))
    }
}
