package com.onpath.app.domain

import com.onpath.app.domain.MapGestures.Action
import com.onpath.app.domain.MapGestures.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chriss 2026-11-28 09:41 (picture halten-lernen-abc (a)): the hold pill – F-PLAN-24. */
class HoldHintTest {
    private fun pill(editable: Boolean = true, press: Press = Press.TAP, action: Action = Action.None, poi: Boolean = false,
                     marker: Int = -1, planEmpty: Boolean = true, loadAsked: Boolean = false, holds: Int = 0) =
        HoldHint.pillOnTap(editable, press, action, poi, marker, planEmpty, loadAsked, holds)

    @Test fun aTapOnTheEmptyMapOfAnEmptyPlanShowsThePill() = assertTrue(pill())

    @Test fun neverOnAPlaceAPointAHoldInNavigateOrWhileTheMapIsAsked() {
        assertFalse("a place shows its info", pill(poi = true, action = Action.PoiDetails))
        assertFalse("a route point", pill(marker = 0))
        assertFalse("a hold does something itself", pill(press = Press.HOLD, action = Action.FirstPointMenu))
        assertFalse("Navigate / a shown track", pill(editable = false))
        assertFalse("„Karte laden?“ asks", pill(loadAsked = true))
        assertFalse("a tap that closes a menu", pill(action = Action.CloseMenu))
    }

    @Test fun withAPlanTheSheetLineStaysAndThePillDoesNotCome() = assertFalse(pill(planEmpty = false))

    @Test fun afterThreeCountedHoldsItNeverComesAgain() {
        var n = 0
        repeat(2) { n = HoldHint.next(n) }
        assertTrue(pill(holds = n))
        n = HoldHint.next(n)
        assertEquals(HoldHint.LEARNED_HOLDS, n)
        assertFalse(pill(holds = n))
        assertEquals("stops counting at the limit", HoldHint.LEARNED_HOLDS, HoldHint.next(n))
        assertEquals("a broken stored value counts from 0", 1, HoldHint.next(-5))
    }

    @Test fun onlyHoldsThatDidSomethingCount() {
        for (a in listOf(Action.PointMenu(1), Action.PoiMenu, Action.FirstPointMenu, Action.AddPoint))
            assertTrue("$a", HoldHint.counts(Press.HOLD, a))
        assertFalse(HoldHint.counts(Press.HOLD, Action.CloseMenu))
        assertFalse(HoldHint.counts(Press.HOLD, Action.None))
        assertFalse(HoldHint.counts(Press.TAP, Action.PoiDetails))
    }
}
