package com.onpath.app.ui

import com.onpath.app.routing.PlanPointRow
import com.onpath.app.routing.PointRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two ✕ taps 1 s apart: after the first the rows must not move up under the finger, so the second tap cannot delete
 * the next point (owner).
 */
class PointsDeleteGuardTest {
    private fun row(i: Int, role: PointRole = PointRole.STOP) = PlanPointRow(i, role, "P${i + 1}", 100.0, false)
    private val three = listOf(row(0, PointRole.START), row(1), row(2, PointRole.GOAL))

    @Test fun deletedRowStaysAsAPlaceholderSoNothingShifts() {
        val g = PointsDeleteGuard()
        assertTrue(g.onDelete(1, three[1], rowsBefore = 3, now = 0))
        val after = listOf(three[0], row(1, PointRole.GOAL).copy(name = "P3")) // the plan without point 2
        val shown = g.entries(after, now = 300)
        assertEquals(3, shown.size)
        assertEquals("P1", shown[0].row.name); assertEquals(0, shown[0].rowIndex)
        assertTrue(shown[1].deleted); assertNull(shown[1].rowIndex)
        assertEquals("P3", shown[2].row.name); assertEquals(1, shown[2].rowIndex) // still in its old place
    }

    @Test fun secondTapOnTheSameSpotIsIgnoredWhileQuiet() {
        val g = PointsDeleteGuard()
        assertTrue(g.onDelete(1, three[1], rowsBefore = 3, now = 0))
        assertFalse("1 s later at the same spot (the field case)", g.onDelete(1, three[2], rowsBefore = 3, now = 950))
        assertFalse(g.allowsTap(now = 950))
        assertTrue(g.allowsTap(now = PointsDeleteGuard.QUIET_MS + 1))
        assertTrue(g.onDelete(1, three[2], rowsBefore = 3, now = PointsDeleteGuard.QUIET_MS + 1))
    }

    @Test fun afterTheQuietTimeTheListIsThePlanAgain() {
        val g = PointsDeleteGuard()
        g.onDelete(0, three[0], rowsBefore = 3, now = 0)
        val after = three.drop(1)
        assertEquals(2, g.entries(after, now = PointsDeleteGuard.QUIET_MS + 1).size)
        assertTrue(g.entries(after, now = PointsDeleteGuard.QUIET_MS + 1).none { it.deleted })
    }

    @Test fun planChangedElsewhereDropsThePlaceholder() {
        val g = PointsDeleteGuard()
        g.onDelete(1, three[1], rowsBefore = 3, now = 0)
        // Undo on the map brought the point back: the plan has 3 rows again – no placeholder on top of it
        val shown = g.entries(three, now = 200)
        assertEquals(3, shown.size)
        assertTrue(shown.none { it.deleted })
    }
}
