package com.onpath.app.domain.tour

import com.onpath.app.domain.tour.BatterySave.NEXT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „Akku sparen“ always holds for the current tour only (Chriss 2026-11-28 12:55, item battery-critical a58f56955): set during
 * a tour → that tour; set before a start → the next tour; after the tour's end it is off – also when set by hand. A restart
 * in the middle of the tour (R-07) keeps it: the tour goes on under the same id.
 */
class BatterySaveTourTest {
    @After fun reset() { BatterySave.mark = null; BatterySave.runningTour = null }

    @Test fun setDuringATourHoldsForThatTour() {
        assertEquals(7L, BatterySave.switchTo(on = true, runningTour = 7))
        assertTrue(BatterySave.effective(mark = 7, runningTour = 7))
        assertFalse("the tour ended", BatterySave.effective(mark = 7, runningTour = null))
        assertFalse("the next tour", BatterySave.effective(mark = 7, runningTour = 8))
    }

    @Test fun setBeforeAStartHoldsForTheNextTourThenEnds() {
        val m = BatterySave.switchTo(on = true, runningTour = null)
        assertEquals(NEXT, m)
        assertTrue("shown „an“ before the start", BatterySave.effective(m, runningTour = null))
        val bound = BatterySave.afterTourChange(m, prevTour = null, nowTour = 9)
        assertEquals("the next tour takes it", 9L, bound)
        assertTrue(BatterySave.effective(bound, runningTour = 9))
        assertNull("that tour ends: off", BatterySave.afterTourChange(bound, prevTour = 9, nowTour = null))
    }

    @Test fun theTourEndTurnsItOffAlsoWhenSetByHand() {
        assertNull(BatterySave.afterTourChange(mark = 7, prevTour = 7, nowTour = null))
        assertNull("a new tour right after", BatterySave.afterTourChange(mark = 7, prevTour = 7, nowTour = 8))
        assertEquals("still the same tour", 7L, BatterySave.afterTourChange(mark = 7, prevTour = 7, nowTour = 7))
        assertNull(BatterySave.switchTo(on = false, runningTour = 7))
    }

    @Test fun aRestartInTheMiddleOfTheTourKeepsIt() {
        // the app starts: the stored mark 7, no tour known yet; then the service reports tour 7 again
        assertEquals(7L, BatterySave.afterTourChange(mark = 7, prevTour = null, nowTour = 7))
        BatterySave.mark = 7; BatterySave.runningTour = null
        assertFalse(BatterySave.on)
        BatterySave.runningTour = 7
        assertTrue(BatterySave.on)
        assertTrue(BatterySave.rateMs() > BatterySave.FAST_MS)
    }
}
