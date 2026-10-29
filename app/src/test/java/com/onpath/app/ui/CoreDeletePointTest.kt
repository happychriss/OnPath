package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CORE FEATURE C1 (docs/core-features.md): point menu → Delete always deletes the held point – never "Delete tapped,
 * nothing happened". Each test is one way the plan can change while a menu is open
 * or a route is still being calculated; the steps are the ones AppViewModel takes (PointMenuTarget, RouteRequests,
 * PlanHistory, WaypointSnap).
 */
class CoreDeletePointTest {
    private val a = GeoPoint(46.670, 11.160)
    private val b = GeoPoint(46.672, 11.165)
    private val c = GeoPoint(46.675, 11.170)
    private val d = GeoPoint(46.678, 11.175)

    /** What "Delete" in the menu does (AppViewModel.deletePointFromMenu). */
    private fun delete(plan: List<GeoPoint>, menu: PointMenuTarget): List<GeoPoint> {
        val i = menu.indexIn(plan) ?: return plan
        return plan.toMutableList().apply { removeAt(i) }
    }

    @Test fun deleteRemovesTheHeldPoint() {
        val plan = listOf(a, b, c, d)
        assertEquals(listOf(a, b, d), delete(plan, PointMenuTarget(2, c)))
    }

    /**
     * The menu stays open while the plan changes underneath (route ⋯ → Points… → Delete of an earlier point, a tour
     * opened): the menu kept only an index, so Delete removed the point that moved into that slot – or nothing, when
     * the index was past the end.
     */
    @Test fun planChangedUnderneathAnOpenMenu() {
        val menu = PointMenuTarget(2, c) // menu of point 3 (c)
        val plan = listOf(b, c, d) // point 1 (a) deleted from the point list meanwhile
        assertEquals("by index the menu would delete d", d, plan[2])
        assertEquals(listOf(b, d), delete(plan, menu))
        // the last point deleted underneath: index 3 is past the end of [a, b, c] → no point, nothing deleted by mistake
        val menu4 = PointMenuTarget(3, d)
        assertNull(menu4.indexIn(listOf(a, b, c)))
    }

    @Test fun menuOfAPointThatIsGoneActsOnNothing() {
        val menu = PointMenuTarget(1, b)
        val plan = listOf(a, c)
        assertNull(menu.indexIn(plan))
        assertEquals(plan, delete(plan, menu))
    }

    /** The route result snaps points onto ways (moves up to 1 km): the open menu follows its point. */
    @Test fun routeResultSnapsThePointWhileTheMenuIsOpen() {
        val plan = listOf(a, b, c)
        val bSnapped = GeoPoint(46.6721, 11.1651)
        val snapped = listOf(a, bSnapped, c)
        val menu = PointMenuTarget(1, b).afterSnap(WaypointSnap.moves(plan, snapped))
        assertEquals(1, menu.indexIn(snapped))
        assertEquals(listOf(a, c), delete(snapped, menu))
    }

    /** Round trip: start and destination on the same spot – the menu deletes the one it was opened for. */
    @Test fun roundTripSameSpot() {
        val plan = listOf(a, b, c, a)
        assertEquals(listOf(a, b, c), delete(plan, PointMenuTarget(3, a)))
        assertEquals(listOf(b, c, a), delete(plan, PointMenuTarget(0, a)))
    }

    /**
     * Delete while the route is still being calculated (a newer request supersedes older ones): the older result must
     * never bring the deleted point back – not as a line (RouteRequests) and not by snapping (the plan differs from
     * the routed points, so nothing is snapped) and not through the undo history.
     */
    @Test fun deleteWhileAnOlderRouteIsCalculated() {
        val requests = RouteRequests()
        val history = PlanHistory()
        var plan = listOf(a, b, c)
        val g1 = requests.next() // route for [a, b, c] running
        history.record(plan)
        plan = delete(plan, PointMenuTarget(1, b)) // Delete → plan [a, c] → recalc
        val g2 = requests.next()
        // the old result arrives first
        assertFalse("older result ignored", requests.isCurrent(g1))
        val routed = listOf(a, b, c)
        assertFalse("its snapping is not applied (AppViewModel.snapToRoute guard)", plan == routed)
        // the old result's snapping is not mapped into the history either (moves only of the routed list)
        assertEquals(listOf(a, c), plan)
        assertTrue(requests.isCurrent(g2))
    }

    /** Delete right after the route finished and snapped: history keeps the snapped points, undo brings back b' . */
    @Test fun deleteRightAfterTheRouteSnapped() {
        val history = PlanHistory()
        var plan = listOf(a, b)
        history.record(listOf(a)) // b was added
        val bSnapped = GeoPoint(46.6721, 11.1651)
        val snapped = listOf(a, bSnapped)
        history.replacePoints(WaypointSnap.moves(plan, snapped))
        plan = snapped
        history.record(plan)
        plan = delete(plan, PointMenuTarget(1, b).afterSnap(WaypointSnap.moves(listOf(a, b), snapped)))
        assertEquals(listOf(a), plan)
        assertEquals(listOf(a, bSnapped), history.undo(plan))
    }
}
