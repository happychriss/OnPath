package com.onpath.app.ui.tour

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No new tour beside an interrupted one (review 2026-11-12): while the start-up check for a tour still marked as
 * recording runs, or its question (Continue · Delete · Save) waits for an answer, a start does nothing – the question
 * decides first. Continue is never held; after Save / Delete a new tour starts again.
 */
class UnfinishedStartGuardTest {

    private class Recorder : TourRecorder {
        var starts = 0
        var continued: Long? = null
        override fun start(activity: String, sac: Int) { starts++ }
        override fun continueTour(tourId: Long) { continued = tourId }
        override fun pause(pause: Boolean) = Unit
        override fun stop() = Unit
    }

    private val old = Tour(7L, TourKind.RECORDED, "Interrupted", 0L, "HIKE", 2, 1200.0, 30.0, 10.0, 900.0, "active", emptyList())

    @Test fun nothingStartsWhileTheStartUpCheckRuns() {
        val r = Recorder(); val c = TourController(r)
        c.unfinishedPending = true
        assertFalse(c.canStartNew)
        c.start("HIKE", 2)
        assertEquals("no tour beside the one still checked", 0, r.starts)
        c.unfinishedPending = false
        assertTrue(c.canStartNew)
        c.start("HIKE", 2)
        assertEquals(1, r.starts)
    }

    @Test fun nothingStartsWhileTheQuestionWaits() {
        val r = Recorder(); val c = TourController(r)
        c.showUnfinished(old, emptyList())
        assertFalse(c.canStartNew)
        c.start("HIKE", 2)
        assertEquals("the question decides first", 0, r.starts)
        // Save with rides found: the ride summary is still the question
        c.unfinished = null; c.unfinishedRides = old to com.onpath.app.rides.RideView.Summary(emptyList(), emptyList(), emptyList(),
            com.onpath.app.rides.RideChoice.entries.first(), emptyMap())
        assertFalse(c.canStartNew)
        c.start("HIKE", 2)
        assertEquals(0, r.starts)
        c.closeUnfinished()
        assertTrue(c.canStartNew)
        c.start("HIKE", 2)
        assertEquals("after Save / Delete a new tour starts", 1, r.starts)
    }

    @Test fun continueIsNeverHeld() {
        val r = Recorder(); val c = TourController(r)
        c.unfinishedPending = true
        c.showUnfinished(old, emptyList())
        c.continueTour(old)
        assertEquals(old.id, r.continued)
        assertEquals("continued, not started anew", 0, r.starts)
    }
}
