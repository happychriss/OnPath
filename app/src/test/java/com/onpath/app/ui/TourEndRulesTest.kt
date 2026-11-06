package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field bug 2026-11-06 22:30 (Pixel, tour 169: started 22:29:41, end sheet 22:30:17, saved 22:30:18, 0 m). */
class TourEndRulesTest {
    @Test fun theFieldTourHasNoLine() {
        // tour 169: no GPS fix moved it – 0 m; one fix or none
        assertFalse(TrackLine.has(points = 0, distanceM = 0.0))
        assertFalse(TrackLine.has(points = 1, distanceM = 0.0))
        // GPS wander while standing: a few points, a few metres
        assertFalse(TrackLine.has(points = 6, distanceM = 12.0))
        assertFalse(TrackLine.hasDistance(0.0))
    }

    @Test fun aRealTourHasALine() {
        assertTrue(TrackLine.has(points = 2, distanceM = TrackLine.MIN_M))
        assertTrue(TrackLine.has(points = 400, distanceM = 3_200.0))
        assertTrue(TrackLine.hasDistance(842.0))
        // many metres but a single point (a broken file) is still nothing to draw
        assertFalse(TrackLine.has(points = 1, distanceM = 500.0))
    }

    @Test fun theThumbFromBeendenDoesNotSave() {
        val shown = 10_000L
        // the thumb still on the screen as the sheet opens, let go a second later (the field case: saved after 1.1 s)
        assertFalse(EndSheetGuard.accepts(shownAtMs = shown, pressedAtMs = shown))
        assertFalse(EndSheetGuard.accepts(shownAtMs = shown, pressedAtMs = shown + 200))
        assertFalse(EndSheetGuard.accepts(shownAtMs = shown, pressedAtMs = shown + EndSheetGuard.GUARD_MS - 1))
    }

    @Test fun aTapAfterLookingCounts() {
        val shown = 10_000L
        assertTrue(EndSheetGuard.accepts(shownAtMs = shown, pressedAtMs = shown + EndSheetGuard.GUARD_MS))
        assertTrue(EndSheetGuard.accepts(shownAtMs = shown, pressedAtMs = shown + 1_100))
    }
}
