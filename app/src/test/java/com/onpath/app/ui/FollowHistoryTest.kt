package com.onpath.app.ui

import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Bug 02 (field 2026-10-31): led ("Guide me") 60 km to a 4-point plan, a hold added point 5, Undo – the tour then
 * followed the bare 4-point plan: the way there was gone. Undo during a tour takes the followed route back exactly.
 */
class FollowHistoryTest {
    private val four = listOf(pt(0.0, 0.0), pt(700.0, 0.0), pt(1_400.0, 0.0), pt(2_000.0, 0.0))
    private val five = four + pt(-45_000.0, 0.0)
    private val plan = line(0.0 to 0.0, 2_000.0 to 0.0)
    private val ledThere = NavSession.start(NavKind.PLAN, "punkt 3", Activity.HIKE, 2, plan, four)
        .copy(current = line(-60_000.0 to 0.0, 2_000.0 to 0.0), lead = true, version = 1)
    private val fivePoints = NavSession.start(NavKind.PLAN, "punkt 3", Activity.HIKE, 2, line(0.0 to 0.0, -45_000.0 to 0.0), five)

    @Test fun bug02UndoTakesBackTheLedSessionExactly() {
        val h = FollowHistory()
        // the hold: the led 4-point session is replaced by the 5-point plan
        h.remember(four, ledThere)
        // Undo: the plan is at the 4 points again → the session with its way there, as it was
        assertSame(ledThere, h.find(four))
        // the plan's points may carry heights the session's stops (from nav-session.json) do not have
        assertSame(ledThere, h.find(four.map { it.copy(ele = 412.0) }))
        // … and the 5-point one is kept for Redo
        h.remember(five, fivePoints)
        assertSame(fivePoints, h.find(five))
        assertSame(ledThere, h.find(four))
    }

    @Test fun bug02OtherPointsFindNothingAndTheNewestWins() {
        val h = FollowHistory(max = 2)
        assertNull(h.find(four))
        h.remember(four, fivePoints)
        h.remember(four, ledThere)
        assertSame(ledThere, h.find(four))
        assertNull(h.find(five))
        h.remember(five, fivePoints)
        h.remember(listOf(pt(1.0, 1.0), pt(2.0, 2.0)), fivePoints)
        assertEquals("at most max kept", null, h.find(four))
        h.clear()
        assertNull(h.find(five))
    }
}
