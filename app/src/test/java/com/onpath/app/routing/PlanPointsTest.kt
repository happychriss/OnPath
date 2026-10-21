package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.ui.PlanHistory
import com.onpath.app.ui.RouteRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Points section of the plan sheet (core feature C9): rows, reorder by drag, delete, undo. */
class PlanPointsTest {
    private val a = GeoPoint(46.67, 11.10)
    private val b = GeoPoint(46.67, 11.20)
    private val c = GeoPoint(46.75, 11.20)
    private val d = GeoPoint(46.75, 11.10)

    private fun names(i: Int): String? = if (i == 1) "Leiter Alm" else null

    // ---------------------------------------------------------------- rows

    @Test fun rowsOfAnOpenRoute() {
        val r = PlanPoints.rows(listOf(a, b, c), null, ::names)
        assertEquals(listOf(PointRole.START, PointRole.STOP, PointRole.GOAL), r.map { it.role })
        assertEquals(listOf(0, 1, 2), r.map { it.planIndex })
        assertEquals(listOf("Point 1", "Leiter Alm", "Point 3"), r.map { it.name })
        assertNull("the start has no previous point", r[0].legM)
        assertEquals(Geo.distance(a, b), r[1].legM!!, 1.0)
        assertTrue("no route line: straight-line distances are approximate", r.all { it.approx })
        assertFalse(r.any { it.loop })
    }

    @Test fun oneAndTwoPoints() {
        assertEquals(listOf(PointRole.START), PlanPoints.rows(listOf(a), null) { null }.map { it.role })
        assertEquals(listOf(PointRole.START, PointRole.GOAL), PlanPoints.rows(listOf(a, b), null) { null }.map { it.role })
        assertTrue(PlanPoints.rows(emptyList(), null) { null }.isEmpty())
    }

    /** Round trip: start = goal shown once (↺ mark), its distance is the way back. */
    @Test fun roundTripShowsTheStartOnce() {
        val r = PlanPoints.rows(listOf(a, b, c, a), null) { null }
        assertEquals(3, r.size)
        assertTrue(r[0].loop)
        assertEquals(PointRole.START, r[0].role)
        assertEquals(PointRole.STOP, r[2].role) // the last row is not the goal – the start is
        assertEquals(Geo.distance(c, a), r[0].legM!!, 1.0)
    }

    /** With a route, the leg distance is measured along the line (longer than straight). */
    @Test fun legDistanceAlongTheRoute() {
        val mid = GeoPoint(46.72, 11.15) // the line goes a → mid → b → c
        val line = interp(a, mid, 20) + interp(mid, b, 20).drop(1) + interp(b, c, 20).drop(1)
        val r = PlanPoints.rows(listOf(a, b, c), line) { null }
        assertFalse(r[1].approx)
        assertEquals(Geo.distance(a, mid) + Geo.distance(mid, b), r[1].legM!!, 30.0)
        assertEquals(Geo.distance(b, c), r[2].legM!!, 30.0)
    }

    // ---------------------------------------------------------------- reorder

    @Test fun moveARowDown() = assertEquals(listOf(a, c, b, d), PlanPoints.move(listOf(a, b, c, d), 1, 2))

    @Test fun movingARowToTheTopMakesItTheStart() =
        assertEquals(listOf(c, a, b, d), PlanPoints.move(listOf(a, b, c, d), 2, 0))

    @Test fun movingTheStartDownMakesTheNextOneTheStart() =
        assertEquals(listOf(b, c, a, d), PlanPoints.move(listOf(a, b, c, d), 0, 2))

    @Test fun invalidMoveKeepsThePlan() {
        val w = listOf(a, b, c)
        assertEquals(w, PlanPoints.move(w, 1, 1))
        assertEquals(w, PlanPoints.move(w, 7, 0))
    }

    /** Round trip: the closing point follows the new start (still a loop, start = goal). */
    @Test fun reorderInARoundTrip() {
        val loop = listOf(a, b, c, a)
        assertEquals(listOf(a, c, b, a), PlanPoints.move(loop, 1, 2))
        assertEquals(listOf(c, a, b, c), PlanPoints.move(loop, 2, 0))
        assertTrue(PlanEdits.isRoundTrip(PlanPoints.move(loop, 2, 0)))
    }

    /** Where a dragged row lands: rows passed (rounded), clamped to the list. */
    @Test fun dropIndexFromTheDragOffset() {
        assertEquals(1, PlanPoints.dropIndex(1, 20f, 100f, 4))
        assertEquals(2, PlanPoints.dropIndex(1, 60f, 100f, 4))
        assertEquals(0, PlanPoints.dropIndex(1, -160f, 100f, 4))
        assertEquals(3, PlanPoints.dropIndex(1, 900f, 100f, 4))
        assertEquals(0, PlanPoints.dropIndex(0, -50f, 0f, 4)) // not measured yet: stays
    }

    /** The other rows make room while a row is dragged. */
    @Test fun rowsShiftWhileDragging() {
        // row 1 dragged to 3: rows 2 and 3 move up one place
        assertEquals(listOf(0, 0, -1, -1), (0..3).map { PlanPoints.shift(it, 1, 3) })
        // row 3 dragged to 0: rows 0..2 move down
        assertEquals(listOf(1, 1, 1, 0), (0..3).map { PlanPoints.shift(it, 3, 0) })
        assertEquals(listOf(0, 0, 0), (0..2).map { PlanPoints.shift(it, 1, 1) })
    }

    // ---------------------------------------------------------------- delete

    @Test fun deleteAMiddlePoint() = assertEquals(listOf(a, c), PlanPoints.delete(listOf(a, b, c), 1))

    @Test fun deleteTheStart() = assertEquals(listOf(b, c), PlanPoints.delete(listOf(a, b, c), 0))

    @Test fun deleteInvalidRowKeepsThePlan() = assertEquals(listOf(a, b), PlanPoints.delete(listOf(a, b), 5))

    @Test fun deleteInARoundTripKeepsTheLoop() {
        assertEquals(listOf(a, c, a), PlanPoints.delete(listOf(a, b, c, a), 1))
        // deleting the start: the next point starts and closes the loop
        assertEquals(listOf(b, c, b), PlanPoints.delete(listOf(a, b, c, a), 0))
        // a loop of two: one point is left, no loop
        assertEquals(listOf(a), PlanPoints.delete(listOf(a, b, a), 1))
    }

    // ---------------------------------------------------------------- with undo and routing

    /** Undo after a reorder / delete brings the old order back (one step each). */
    @Test fun undoRestoresTheOrder() {
        val h = PlanHistory()
        var plan = listOf(a, b, c, d)
        h.record(plan); plan = PlanPoints.move(plan, 3, 0)
        h.record(plan); plan = PlanPoints.delete(plan, 2)
        assertEquals(listOf(d, a, c), plan)
        plan = h.undo(plan)!!
        assertEquals(listOf(d, a, b, c), plan)
        plan = h.undo(plan)!!
        assertEquals(listOf(a, b, c, d), plan)
    }

    /** Reorder while a route is still calculated, then delete: only the newest request's route counts. */
    @Test fun newerEditSupersedesARunningRoute() {
        val req = RouteRequests()
        var plan = listOf(a, b, c, d)
        plan = PlanPoints.move(plan, 1, 3); val g1 = req.next()
        plan = PlanPoints.delete(plan, 0); val g2 = req.next()
        assertFalse("the reorder's route is stale", req.isCurrent(g1))
        assertTrue(req.isCurrent(g2))
        assertEquals(listOf(c, d, b), plan)
    }

    private fun interp(p: GeoPoint, q: GeoPoint, n: Int) =
        (0..n).map { GeoPoint(p.lat + (q.lat - p.lat) * it / n, p.lon + (q.lon - p.lon) * it / n) }
}
