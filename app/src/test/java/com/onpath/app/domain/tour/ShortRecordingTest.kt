package com.onpath.app.domain.tour

import com.onpath.app.ui.tour.TourController
import com.onpath.app.ui.tour.TourRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-12: a recording that ends with < 50 m of track (0 m / one GPS point included) is no tour – it goes to
 * the recycle bin at the end with a notice and Rückgängig; 30 days restorable, nothing lost.
 */
class ShortRecordingTest {
    private class NoService : TourRecorder {
        override fun start(activity: String, sac: Int) {}
        override fun continueTour(tourId: Long) {}
        override fun pause(pause: Boolean) {}
        override fun stop() {}
    }

    @Test fun lessThan50mIsShort_0mAndOnePointIncluded() {
        assertTrue(ShortRecording.bins(0.0))
        assertTrue(ShortRecording.bins(2.0))
        assertTrue(ShortRecording.bins(49.9))
        assertFalse("50 m is a tour", ShortRecording.bins(50.0))
        assertFalse(ShortRecording.bins(1_200.0))
        assertEquals(50.0, ShortRecording.MIN_M, 0.0)
    }

    /** The end of a tour: Löschen → the bin as chosen; else a short track → the bin with Undo; else the saved box. */
    @Test fun theEndDecidesBinChosenShortOrSaved() {
        val c = TourController(NoService())
        c.end(delete = true)
        assertEquals(TourController.Finished.DELETED, c.finishedOutcome(2.0))
        assertEquals("the delete is taken once", TourController.Finished.SHORT, c.finishedOutcome(2.0))
        assertEquals(TourController.Finished.SAVED, c.finishedOutcome(50.0))
        c.end(delete = false)
        assertEquals(TourController.Finished.SHORT, c.finishedOutcome(0.0))
        assertEquals(TourController.Finished.SAVED, c.finishedOutcome(3_000.0))
    }

    /** A delete chosen outranks the short rule – a tour of 1 km deleted is simply deleted, no short-notice. */
    @Test fun aChosenDeleteIsNeverShownAsShort() {
        val c = TourController(NoService())
        c.end(delete = true)
        assertEquals(TourController.Finished.DELETED, c.finishedOutcome(1_000.0))
    }
}
