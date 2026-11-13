package com.onpath.app.domain.tour

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.ui.tour.TourController
import com.onpath.app.ui.tour.TourRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-12: a recording that never got away from its start (< 50 m, 0 m / one GPS point included) is no tour –
 * it goes to the recycle bin at the end with the box "Kurze Aufzeichnung" and Rückgängig; 30 days restorable, nothing
 * lost. Review 2026-11-13: a REAL tour must never be binned – a GPS gap / jump starts a new piece that adds 0 m, so
 * every point of every piece must lie within 50 m of the first point, and a tour with a ride part is never short.
 */
class ShortRecordingTest {
    private class NoService : TourRecorder {
        override fun start(activity: String, sac: Int) {}
        override fun continueTour(tourId: Long) {}
        override fun pause(pause: Boolean) {}
        override fun stop() {}
    }

    /** A point [m] metres north of Meran's Thermenplatz, at [t] seconds. */
    private fun north(m: Double, t: Long = 0) = TrackPoint(46.6688 + m / 111_320.0, 11.1590, null, t * 1000)
    private fun tour(id: Long = 7, m: Double = 0.0) = Tour(id, TourKind.RECORDED, "Tour 12 Nov, 10:12", 0L, "HIKE", 2, m, 0.0, 0.0, 0.0, "done", emptyList())

    @Test fun aStartByMistakeIsShort_noPointOnePointOrAClusterAtTheStart() {
        assertTrue("no point at all", ShortRecording.bins(0.0, emptyList(), rides = false))
        assertTrue("an empty piece", ShortRecording.bins(0.0, listOf(emptyList()), rides = false))
        assertTrue("one point", ShortRecording.bins(0.0, listOf(listOf(north(0.0))), rides = false))
        val wander = listOf(north(0.0), north(8.0), north(3.0), north(12.0)) // GPS standing still wanders that much
        assertTrue(ShortRecording.bins(14.0, listOf(wander), rides = false))
        assertTrue("49.9 m within the start", ShortRecording.bins(49.9, listOf(listOf(north(0.0), north(49.0))), rides = false))
        assertEquals(50.0, ShortRecording.MIN_M, 0.0)
    }

    /** The gap case: a GPS gap > 5 min (PointFilter.BRIDGE_MAX_MS) starts a new piece that adds 0 m – a 2 h hike with ~0 m stored. */
    @Test fun aGpsGapThatStartedANewPieceFarAwayIsATour() {
        val pieces = listOf(listOf(north(0.0, 0)), listOf(north(3_000.0, 2 * 3600))) // 3 km on, two hours later
        assertFalse(ShortRecording.bins(0.0, pieces, rides = false))
        // the second piece's points stay near each other but far from the first point: still a tour
        val p2 = listOf(listOf(north(0.0), north(5.0)), listOf(north(900.0), north(905.0), north(910.0)))
        assertFalse(ShortRecording.bins(12.0, p2, rides = false))
    }

    /** The backpack case: sparse fixes far apart (Doze, forest) – each a jump the filter started a piece for, 0 m counted. */
    @Test fun sparseFixesFarApartAreATour() {
        val pieces = (0..5).map { i -> listOf(north(i * 400.0, i * 900L)) } // a fix every 15 min, 400 m on
        assertFalse(ShortRecording.bins(0.0, pieces, rides = false))
        // one far point in a long piece is enough – the user was there
        val one = listOf(List(20) { north(it * 2.0) } + north(60.0))
        assertFalse(ShortRecording.bins(40.0, one, rides = false))
    }

    /** The ride case: a bus / car / lift found (C12) – never binned, whatever the walked distance after the choice. */
    @Test fun aRecordingWithARidePartIsNeverShort() {
        assertFalse(ShortRecording.bins(0.0, listOf(listOf(north(0.0), north(3.0))), rides = true))
        assertFalse(ShortRecording.bins(0.0, emptyList(), rides = true))
    }

    @Test fun fiftyMetresRecordedIsATour() {
        assertFalse(ShortRecording.bins(50.0, listOf(listOf(north(0.0), north(20.0))), rides = false))
        assertFalse(ShortRecording.bins(1_200.0, listOf(listOf(north(0.0), north(1_200.0))), rides = false))
    }

    /** The end of a tour: Löschen → the bin as chosen; else a short track → the bin with the box; else the saved box. */
    @Test fun theEndDecidesBinChosenShortOrSaved() {
        val c = TourController(NoService())
        c.end(delete = true)
        assertEquals(TourController.Finished.DELETED, c.finishedOutcome(short = true))
        assertEquals("the delete is taken once", TourController.Finished.SHORT, c.finishedOutcome(short = true))
        assertEquals(TourController.Finished.SAVED, c.finishedOutcome(short = false))
        c.end(delete = true)
        assertEquals("a delete chosen outranks the short rule", TourController.Finished.DELETED, c.finishedOutcome(short = false))
    }

    /** The box "Kurze Aufzeichnung" → Rückgängig / OK: taken once; the bin not writable → the saved box (never lost). */
    @Test fun theShortBoxShowsAfterTheBinWriteAndTheFallbackIsTheSavedBox() {
        val c = TourController(NoService())
        val t = tour(m = 12.0)
        c.afterShortBin(t, binned = true, times = null)
        assertEquals(t, c.shortBinned); assertNull("no saved box beside it", c.finishedTour)
        assertEquals("Rückgängig / OK take the box once", t, c.takeShortBinned()); assertNull(c.shortBinned); assertNull(c.takeShortBinned())
        // the bin could not be written: it stays a normal tour – the saved box says so
        c.afterShortBin(t, binned = false, times = null)
        assertNull(c.shortBinned); assertEquals(t, c.finishedTour)
        // Rückgängig restored it: the saved box for it, as if saved at the end
        c.closeFinished(); c.afterShortBin(t, binned = true, times = null)
        c.takeShortBinned()?.let { c.showFinished(it, null) }
        assertEquals(t, c.finishedTour); assertNull(c.shortBinned)
    }
}
