package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanStateTest {
    private val a = GeoPoint(46.6, 11.1)
    private val b = GeoPoint(46.7, 11.2)
    private val c = GeoPoint(46.8, 11.3)

    @Test fun clearIsOneUndoableStep() {
        val h = PlanHistory()
        var w = listOf<GeoPoint>()
        h.record(w); w = listOf(a)
        h.record(w); w = listOf(a, b)
        h.record(w); w = listOf(a, b, c)
        h.record(w); w = emptyList() // Clear
        assertTrue(h.canUndo)
        w = h.undo(w)!!
        assertEquals(listOf(a, b, c), w)
        assertTrue(h.canRedo)
        w = h.redo(w)!!
        assertEquals(emptyList<GeoPoint>(), w)
    }

    @Test fun clearedPointsStayReachableForPinnedMarks() {
        val h = PlanHistory()
        h.record(listOf(a, b)) // then cleared
        assertEquals(setOf(a, b), h.allPoints())
    }

    @Test fun newChangeDropsRedo() {
        val h = PlanHistory()
        h.record(listOf(a))
        val back = h.undo(listOf(a, b))
        assertEquals(listOf(a), back)
        assertTrue(h.canRedo)
        h.record(back!!)
        assertFalse(h.canRedo)
        assertNull(h.redo(listOf(a, c)))
    }

    @Test fun historyIsBounded() {
        val h = PlanHistory(limit = 3)
        repeat(5) { h.record(List(it) { a }) }
        var w = listOf<GeoPoint>()
        var n = 0
        while (true) { w = h.undo(w) ?: break; n++ }
        assertEquals(3, n)
    }

    @Test fun clearMakesRunningRouteRequestStale() {
        val r = RouteRequests()
        val running = r.next()
        assertTrue(r.isCurrent(running))
        r.invalidate() // Clear / fewer than two points
        assertFalse(r.isCurrent(running))
        val newer = r.next()
        assertFalse(r.isCurrent(running))
        assertTrue(r.isCurrent(newer))
    }

    /** fix-tour-edit-line.md cause 3: edit 1's slow position → ahead route must not land after edit 2 / Undo / clear. */
    @Test fun aLaterEditMakesARunningSplitRouteStale() {
        val a = GeoPoint(51.0, 7.0); val b = GeoPoint(51.01, 7.0); val c = GeoPoint(51.02, 7.0)
        val r = SplitRequests()
        val first = r.next(listOf(a, b, c))
        assertTrue(r.isCurrent(first, listOf(a, b, c)))
        r.invalidate() // edit 2 (splitOf), Undo, clear, end of the guidance
        assertFalse(r.isCurrent(first, listOf(a, b, c)))
        // and the plan must still be the one it was routed for
        val second = r.next(listOf(a, c))
        assertFalse(r.isCurrent(second, listOf(a, b, c)))
        assertTrue(r.isCurrent(second, listOf(a, c)))
    }
}
