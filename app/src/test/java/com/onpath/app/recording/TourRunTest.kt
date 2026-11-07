package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A second tour on the same service instance (lead 2026-11-07: "the service never resets its track or distance when a
 * new tour starts on the same instance"). In the field every tour so far ran on a fresh instance – the Pixel's database
 * of 2026-10-22..11-07 (157 recorded tours, many started 8–20 s after the one before) has every tour's points from
 * number 0 and no saved distance above its points, while the old start path never set the point number back.
 * But a start delivered to the instance before Android destroyed it would have begun the new tour with the old
 * distance, time, track and point numbers. Now the per-tour state is one [TourRun], made new at every start.
 */
class TourRunTest {

    private fun pt(i: Int) = TrackPoint(51.0 + i * 0.0001, 6.9, 40.0 + i, 1_000L * i)

    /** Tour 1 the way the service records it: points counted, numbered, shown; a pause; arrival; Navigation Stopp. */
    private fun recordTourOne(run: TourRun) {
        repeat(5) { i ->
            run.count(11.1, newPiece = false)
            run.ascent += 2.0; run.descent += 1.0
            run.seq++
            run.show(pt(i))
        }
        run.elapsedS = 600; run.tickRestMs = 400
        run.paused = true; run.pausedSinceMs = 123L
        run.seg++ // resumed after the pause: a new piece
        run.show(pt(9))
        run.saveFailed = true
        run.arrived = true; run.arrivedToken = 7L; run.lineLength = 3 to 999.0
        run.junctionsTracedVersion = 4; run.t5LastAt = GeoPoint(51.0, 6.9)
        run.offRouteShown = true; run.tracedStep = "q3"
        run.navigating = false; run.navigatingApplied = false; run.stopQuiet.set(stopped = true)
        run.firstFixElapsed = 5_000L; run.lastGoodFixElapsed = 6_000L; run.lastFixDemo = true; run.saverWarned = true
    }

    @Test fun aSecondTourBeginsAtZero() {
        val tourOne = TourRun()
        recordTourOne(tourOne)
        assertTrue(tourOne.distance > 50.0)
        assertEquals(2, tourOne.track.snapshot().size)

        // the service's start path for a new tour on the same instance
        val tourTwo = TourRun()
        assertEquals(0.0, tourTwo.distance, 0.0)
        assertEquals(0.0, tourTwo.ascent, 0.0)
        assertEquals(0.0, tourTwo.descent, 0.0)
        assertEquals(0L, tourTwo.elapsedS)
        assertEquals(0L, tourTwo.tickRestMs)
        assertEquals("points of the new tour are numbered from 0", 0, tourTwo.seq)
        assertEquals(0, tourTwo.seg)
        assertTrue("no track of tour 1 on the map", tourTwo.track.isEmpty())
        assertNull("the first point is not measured from tour 1's last", tourTwo.last)
        assertFalse(tourTwo.paused); assertNull(tourTwo.pausedSinceMs)
        assertFalse(tourTwo.saveFailed)
        assertFalse(tourTwo.arrived); assertNull(tourTwo.arrivedToken); assertNull(tourTwo.lineLength)
        assertEquals(Int.MIN_VALUE, tourTwo.junctionsTracedVersion); assertNull(tourTwo.t5LastAt)
        assertFalse(tourTwo.offRouteShown); assertNull(tourTwo.tracedStep)
        assertTrue("a tour starts in Navigate, not in tour 1's Navigation Stopp", tourTwo.navigating)
        assertTrue(tourTwo.navigatingApplied); assertFalse(tourTwo.stopQuiet.quiet)
        assertEquals(0L, tourTwo.firstFixElapsed); assertEquals(0L, tourTwo.lastGoodFixElapsed)
        assertFalse(tourTwo.lastFixDemo); assertFalse(tourTwo.saverWarned)

        // its first points count only themselves
        tourTwo.count(0.0, newPiece = false); tourTwo.seq++; tourTwo.show(pt(0))
        tourTwo.count(11.1, newPiece = false); tourTwo.seq++; tourTwo.show(pt(1))
        assertEquals(11.1, tourTwo.distance, 1e-9)
        assertEquals(listOf(2), tourTwo.track.snapshot().map { it.size })
        assertEquals("tour 1 untouched", 2, tourOne.track.snapshot().size)
    }

    @Test fun aContinuedTourCarriesOnlyItsOwnSavedTotals() {
        val before = TourRun()
        recordTourOne(before) // another tour ran on this instance before
        val saved = listOf(listOf(GeoPoint(50.0, 7.0), GeoPoint(50.001, 7.0)), listOf(GeoPoint(50.002, 7.0)))
        val run = TourRun.resumed(distanceM = 222.0, ascentM = 5.0, descentM = 3.0, durationS = 900, nextSeq = 3,
            lastSeg = 1, segments = saved)
        assertEquals(222.0, run.distance, 0.0)
        assertEquals(5.0, run.ascent, 0.0); assertEquals(3.0, run.descent, 0.0)
        assertEquals(900L, run.elapsedS)
        assertEquals(3, run.seq)
        assertEquals("the next point goes into a new piece (no straight jump)", 2, run.seg)
        assertEquals(saved, run.track.snapshot())
        assertNull(run.last); assertFalse(run.paused); assertFalse(run.arrived); assertTrue(run.navigating)
        run.count(10.0, newPiece = false); run.show(pt(0))
        assertEquals(listOf(2, 1, 1), run.track.snapshot().map { it.size })
    }

    @Test fun aLongGapStartsANewPiece() {
        val run = TourRun()
        run.count(0.0, newPiece = false); run.show(pt(0))
        run.count(5.0, newPiece = false); run.show(pt(1))
        run.count(0.0, newPiece = true); run.show(pt(2))
        assertEquals(1, run.seg)
        assertEquals(listOf(2, 1), run.track.snapshot().map { it.size })
        assertEquals(5.0, run.distance, 0.0)
    }

    /** The wiring: both start branches make a new run before the tour's first stats write; no per-tour field outside it. */
    @Test fun theServiceStartsEveryTourWithAFreshRun() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        val start = svc.substringAfter("private fun startCommand(").substringBefore("\n    private fun goForeground(")
        val firstStats = start.indexOf("db.updateStats(tourId, distance")
        assertTrue(firstStats > 0)
        val fresh = start.indexOf("tourRun = TourRun()")
        val resumed = start.indexOf("tourRun = TourRun.resumed(")
        assertTrue("a new tour gets a fresh run", fresh in 0 until firstStats)
        assertTrue("a continued tour gets a fresh run with its saved totals", resumed in 0 until firstStats)
        // the per-tour state lives in TourRun only (a field here would outlive the tour on a reused instance)
        for (name in listOf("seq", "seg", "paused", "distance", "ascent", "descent", "elapsedS", "trackSeg", "last",
            "arrived", "saveFailed", "navigating")) {
            assertFalse("$name is a field of the service again", Regex("""\n    private var $name\b[^\n]*=""").containsMatchIn(svc))
        }
        assertFalse(svc.contains("= GrowingTrack()"))
        assertFalse(svc.contains("= AscentCounter()"))
    }

    /**
     * Field S10 2026-11-07 20:37:47 (bike, ▶ Sim): "The tour follows position → 10 points ahead" ~75 m before the goal –
     * the service loaded the new line and the arrival gate started over, "been away" forgotten: no "Arrived at the end",
     * no "Ziel erreicht", no end sheet. The followed line loaded again keeps it; a re-route (same token) keeps the arrival.
     */
    @Test fun aNewFollowedLineNearTheGoalStillArrives() {
        val run = TourRun()
        val goal = GeoPoint(51.0, 6.9)
        fun east(m: Double) = GeoPoint(51.0, 6.9 + m / 70_000.0)
        run.followLoaded(token = 1L)
        assertFalse(run.arrivalGate.onFix(east(2_000.0), goal, remainingAlongM = 2_000.0, accuracyM = 5.0))
        run.followLoaded(token = 2L) // the plan edited 75 m before the goal
        assertFalse(run.arrived)
        assertFalse(run.arrivalGate.onFix(east(75.0), goal, remainingAlongM = 75.0, accuracyM = 5.0))
        assertTrue("arrives (the service then says \"Ziel erreicht\" and the app shows the end sheet)",
            run.arrivalGate.onFix(east(30.0), goal, remainingAlongM = 30.0, accuracyM = 5.0))
        run.arrived = true; run.arrivedToken = 2L
        run.followLoaded(token = 2L) // a re-route of the same session
        assertTrue("a re-route keeps the arrival", run.arrived)
        run.followLoaded(token = 3L)
        assertFalse("the app's new line clears it", run.arrived)
    }
}
