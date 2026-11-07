package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pause band's words (owner 2026-11-07, variant C): how long the pause lasts and what stands still. */
class PauseBandTextTest {
    @Test fun thePauseTimeRunsInMinutesAndSecondsThenHours() {
        assertEquals("0:00", PauseBandText.elapsed(0))
        assertEquals("0:07", PauseBandText.elapsed(7_400))
        assertEquals("12:04", PauseBandText.elapsed(724_000))
        assertEquals("59:59", PauseBandText.elapsed(3_599_999))
        assertEquals("1:00:00", PauseBandText.elapsed(3_600_000))
        assertEquals("1:02:30", PauseBandText.elapsed(3_750_000))
        assertEquals("a clock set back never shows a negative time", "0:00", PauseBandText.elapsed(-5_000))
    }

    @Test fun aFreeTourPausesOnlyTheRecording() {
        assertTrue("free tour: \"Aufnahme angehalten\"", PauseBandText.freeLine(free = true))
        assertFalse("with a route: \"Aufnahme und Navigation angehalten\"", PauseBandText.freeLine(free = false))
    }
}
