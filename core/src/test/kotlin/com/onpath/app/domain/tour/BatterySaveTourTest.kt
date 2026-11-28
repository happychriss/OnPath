package com.onpath.app.domain.tour

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Sparen] from the battery question holds for the running tour only and ends with it; the switch in ⚙ shows „an“ meanwhile;
 * set by hand in ⚙ it stays (captain 2026-11-28, item battery-critical af72dc940). A restart in the middle of the tour (R-07)
 * keeps it: the tour goes on under the same id.
 */
class BatterySaveTourTest {
    @After fun reset() { BatterySave.manual = false; BatterySave.forTour = null; BatterySave.runningTour = null }

    @Test fun fromTheQuestionItIsOnForThisTourOnly() {
        assertTrue(BatterySave.effective(manual = false, forTour = 7, runningTour = 7))
        assertFalse("the tour ended", BatterySave.effective(manual = false, forTour = 7, runningTour = null))
        assertFalse("the next tour", BatterySave.effective(manual = false, forTour = 7, runningTour = 8))
    }

    @Test fun byHandItStays() {
        assertTrue(BatterySave.effective(manual = true, forTour = null, runningTour = null))
        assertTrue(BatterySave.effective(manual = true, forTour = null, runningTour = 8))
    }

    @Test fun aRestartInTheMiddleOfTheTourKeepsIt() {
        BatterySave.forTour = 7               // stored with the answer
        BatterySave.runningTour = null        // the app starts: no tour known yet
        assertFalse(BatterySave.on)
        BatterySave.runningTour = 7           // the service reports the same tour again (R-07)
        assertTrue(BatterySave.on)
        BatterySave.runningTour = null        // the tour ends
        assertFalse(BatterySave.on)
    }

    @Test fun theRecordingReadsTheEffectiveState() {
        BatterySave.forTour = 3; BatterySave.runningTour = 3
        assertTrue(BatterySave.rateMs() > BatterySave.FAST_MS)
        BatterySave.runningTour = null
        assertTrue(BatterySave.rateMs() == BatterySave.FAST_MS)
    }
}
