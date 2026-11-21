package com.onpath.app.navigation

import com.onpath.app.navigation.Trace.fix
import com.onpath.app.navigation.Trace.line
import com.onpath.app.navigation.Trace.pt
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Navigate on a plan whose route goes past a stop the level cannot reach (owner 2026-11-21, `Planner.passUnreachable`):
 * the line runs straight from ① to ③ and the stop ② lies 2 km off it, beside the line point the plan was cut at. The stop
 * is never within the visit radius – as before the change, when the line went to the nearest T1 way under it. Walking
 * the line must stay quiet: no question, no off-route, no new line, no job sending the walker to ②.
 */
class NavPastUnreachableStopTest {
    private val plan = line(0.0 to 0.0, 3_000.0 to 0.0)
    private val s1 = pt(0.0, 0.0)
    private val s2 = pt(1_500.0, 2_000.0) // unreachable: 2 km north of the line, abeam of its 1.5 km point
    private val s3 = pt(3_000.0, 0.0)

    private fun nav() = Navigator(NavSession.start(NavKind.PLAN, "Route", Activity.HIKE, 1, plan, listOf(s1, s2, s3)))

    @Test fun theStopIsPlacedAtTheCutPointOfTheLine() {
        val n = nav()
        assertEquals(1_500.0, n.session.stops[1].alongM, 15.0)
        assertEquals(3_000.0, n.session.stops[2].alongM, 15.0)
    }

    @Test fun walkingTheLineIsQuietAndNeverSendsTheWalkerToTheStop() {
        val n = nav()
        var t = 0L
        var x = 0.0
        while (x <= 3_000.0) {
            val step = n.onFix(fix(x, 3.0, t))
            assertNull("no question at $x m: ${n.prompt}", n.prompt)
            assertNull("no re-route job at $x m", step.job)
            assertFalse("the line did not change at $x m", step.lineChanged)
            x += 5.0; t += 1_000L
        }
        assertEquals(plan, n.session.original)
        assertFalse("never rerouted", n.session.rerouted)
        // the unreachable stop stays open (as it did on the old detour line); start and destination are reached
        assertFalse(1 in n.session.visited)
        assertTrue(0 in n.session.visited)
        assertTrue("the destination is reached in plan order by the progress along the line", 2 in n.session.visited)
        assertTrue(n.session.skipped.isEmpty())
    }
}
