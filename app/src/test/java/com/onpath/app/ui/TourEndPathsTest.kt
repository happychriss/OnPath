package com.onpath.app.ui

import com.onpath.app.domain.tour.TourEndFlow
import com.onpath.app.domain.tour.TourEndFlow.Next
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There are three ways to end a tour – End tour, "You have arrived" → Save and the "Interrupted tour" question after a
 * restart. All three show the bus / car / lift summary when a ride was found (core feature C12). The dialogs' buttons
 * (Continue · Delete · Save, the bin note) are in the screenshot frames `unfinishedTour`; the flows in the E2E tests
 * (arrival: Rundgang flow 2, end sheet: flow 7, CoreFieldE2ETest endTour…).
 */
class TourEndPathsTest {
    @Test fun endTourAndArrivalRunTheRideCheck() {
        // End tour: rides → the summary; none → the plain end sheet
        assertEquals(Next.RIDES, TourEndFlow.afterRideCheck(ridesFound = true, saveIfNoRides = false))
        assertEquals(Next.ASK_PLAIN, TourEndFlow.afterRideCheck(ridesFound = false, saveIfNoRides = false))
        // "You have arrived" → Save: rides → the summary too; none → saved at once, no second question
        assertEquals(Next.RIDES, TourEndFlow.afterRideCheck(ridesFound = true, saveIfNoRides = true))
        assertEquals(Next.SAVE_NOW, TourEndFlow.afterRideCheck(ridesFound = false, saveIfNoRides = true))
    }

    @Test fun anInterruptedTourSaveRunsTheRideCheck() {
        assertEquals(Next.RIDES, TourEndFlow.unfinishedSave(ridesFound = true))
        assertEquals(Next.SAVE_NOW, TourEndFlow.unfinishedSave(ridesFound = false))
    }

    /** Field bug 2026-10-31: after a reboot the map behind "Interrupted tour" was empty – no service, no track. */
    @Test fun anInterruptedTourIsDrawnBehindItsQuestion() {
        val rec = listOf("running")
        val old = listOf("interrupted")
        assertEquals("the running recording wins", rec, TourEndFlow.trackShown(rec, unfinishedOpen = true, unfinishedTrack = old))
        assertEquals("no service after a reboot: the interrupted tour's track",
            old, TourEndFlow.trackShown(null, unfinishedOpen = true, unfinishedTrack = old))
        assertTrue("question answered: nothing left over", TourEndFlow.trackShown(null, unfinishedOpen = false, unfinishedTrack = old).isEmpty())
        assertTrue(TourEndFlow.trackShown<String>(null, unfinishedOpen = true, unfinishedTrack = null).isEmpty())
    }

    /** An arrival box from Navigate waits for Navigate – Plan shows it not (owner 2026-11-03: Plan is silent). */
    @Test fun theArrivalBoxShowsOnlyInNavigateWhileRecording() {
        assertTrue(TourEndFlow.arrivalBoxShows(asked = true, recording = true, navigate = true))
        assertFalse("Plan: it waits", TourEndFlow.arrivalBoxShows(asked = true, recording = true, navigate = false))
        assertFalse("the tour ended meanwhile", TourEndFlow.arrivalBoxShows(asked = true, recording = false, navigate = true))
        assertFalse(TourEndFlow.arrivalBoxShows(asked = false, recording = true, navigate = true))
    }

    /**
     * Wiring pin (no JVM harness for TourDb): deleting an interrupted tour marks it "done" FIRST – restored from the
     * recycle bin it is a normal tour, never "unfinished" again.
     */
    @Test fun deletingAnInterruptedTourMarksItDoneFirst() {
        val vm = java.io.File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val del = vm.substringAfter("fun deleteUnfinished(").substringBefore("\n    }\n")
        assertTrue(del, del.indexOf("\"done\"") in 0 until del.indexOf("setDeleted"))
    }
}
