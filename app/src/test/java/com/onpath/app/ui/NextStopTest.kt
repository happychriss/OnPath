package com.onpath.app.ui

import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavStop
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The next stop on the turn card (owner 2026-11-03, comparison card 1): "1,2 km · Stopp 2 · Leiteralm" when the stop
 * comes before the turn shown; a turn first keeps the turn; reached / left-out stops never; the plan's start is no stop
 * ahead; the map's number ("Stopp 7" after a plan edit); the goal as "Ziel".
 */
class NextStopTest {
    private val before = Locale.getDefault()
    @After fun back() = Locale.setDefault(before)

    private val p = GeoPoint(51.0, 7.0)
    private fun session(
        vararg stops: Pair<Double, String?>, visited: Set<Int> = emptySet(), skipped: Set<Int> = emptySet(),
        firstNumber: Int = 1, kind: NavKind = NavKind.PLAN,
    ) = NavSession(kind, "Plan", Activity.HIKE, 2, listOf(p, p), stops.map { (m, n) -> NavStop(p, m, n) },
        visited = visited + skipped, skipped = skipped, firstNumber = firstNumber)

    private val plan = session(0.0 to "Start", 1_200.0 to "Leiteralm", 3_000.0 to null, 5_000.0 to "Hütte")

    @Test fun theNextStopAheadWithItsNumberAndName() {
        val s = NextStop.ahead(plan, 0.0)!!
        assertEquals(2, s.number); assertEquals("Leiteralm", s.name); assertEquals(1_200.0, s.distanceM, 0.01)
        // past stop 2 (reached): stop 3, unnamed
        val t = NextStop.ahead(plan.copy(visited = setOf(1)), 1_300.0)!!
        assertEquals(3, t.number); assertNull(t.name); assertFalse(t.goal); assertEquals(1_700.0, t.distanceM, 0.01)
        // the last one is the goal
        assertTrue(NextStop.ahead(plan.copy(visited = setOf(1, 2)), 3_100.0)!!.goal)
    }

    @Test fun theStartIsNoStopAheadReachedAndLeftOutNever() {
        // standing at the start (0 m): not "Stopp 1"
        assertEquals(2, NextStop.ahead(plan, -50.0)!!.number)
        // stop 2 left out on purpose ("Neue Route ohne Stopp 2"): stop 3 next
        assertEquals(3, NextStop.ahead(session(0.0 to null, 1_200.0 to "A", 3_000.0 to null, skipped = setOf(1)), 0.0)!!.number)
        // at a stop (within 5 m): the one after it
        assertEquals(3, NextStop.ahead(plan, 1_197.0)!!.number)
        // all done: nothing; a followed track: nothing
        assertNull(NextStop.ahead(plan.copy(visited = setOf(1, 2, 3)), 4_000.0))
        assertNull(NextStop.ahead(plan.copy(kind = NavKind.TRACK), 0.0))
    }

    @Test fun afterAPlanEditTheMapsNumbers() {
        // "follow position → points ahead": the tour's first stop is the plan's point 7 (and it is a real stop ahead)
        val s = session(400.0 to "Kiosk", 900.0 to null, firstNumber = 7)
        assertEquals(7, NextStop.ahead(s, 0.0)!!.number)
    }

    @Test fun afterAReRouteTheRestIsShiftedToTheNewLine() {
        // new line: 300 m way back, then the original's rest from 1 000 m on
        val s = plan.copy(current = listOf(p, p), spliceAtM = 300.0, resumeAlongM = 1_000.0)
        val a = NextStop.alongs(s)
        assertNull("stop before the rejoin", a[0])
        assertEquals(500.0, a[1]!!, 0.01)  // 300 + (1 200 − 1 000)
        assertEquals(2, NextStop.ahead(s, 100.0)!!.number)
    }

    @Test fun aTurnFirstKeepsTheTurn() {
        val stop = NextStop.ahead(plan, 0.0)!!
        assertTrue("straight 2,4 km to the next turn", NextStop.shown(2_400.0, stop))
        assertFalse("a turn in 300 m comes first", NextStop.shown(300.0, stop))
        assertTrue("no turn at all", NextStop.shown(null, stop))
        assertFalse(NextStop.shown(300.0, null))
    }

    @Test fun cardTexts() {
        Locale.setDefault(Locale.GERMANY)
        // owner 2026-11-03: the map's circle instead of the word – {2} is drawn as ② by StopText, TalkBack says "Stopp 2"
        assertEquals("{2} Leiteralm", TurnHint(TurnKind.SLIGHT_RIGHT, 1_200.0, stop = StopAhead(2, "Leiteralm", false, 1_200.0)).label)
        assertEquals("Stopp 2 Leiteralm", StopTokens.plain(StopAhead(2, "Leiteralm", false, 1.0).label))
        assertEquals("{3}", StopAhead(3, null, false, 1.0).label)
        assertEquals("Ziel · Hütte", StopAhead(4, "Hütte", true, 1.0).label)
        assertEquals("Ziel", StopAhead(4, null, true, 1.0).label)
        Locale.setDefault(Locale.UK)
        assertEquals("{2} Leiteralm", StopAhead(2, "Leiteralm", false, 1.0).label)
        assertEquals("Destination", StopAhead(4, null, true, 1.0).label)
    }

    @Test fun navigateWiresItAndUTurnStays() {
        val nav = File("src/main/java/com/onpath/app/ui/NavigateScreen.kt").readText()
        assertTrue(nav.contains("if (!h.turnRound && NextStop.shown(h.distanceM, stop))"))
        assertTrue(nav.contains("if (t.stop != null) StopSign(t.stop"))
    }
}
