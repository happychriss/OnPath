package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * The stopped head's words (owner 2026-11-07, "Anhalten" variant 5 – it replaced the pause band): since when the tour
 * stands still – the clock time the stop began, not a running duration – and what rests.
 */
class StoppedTextTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    @Test fun theHeadSaysTheClockTimeTheStopBegan() {
        // 2026-11-07 13:32:10 UTC = 14:32 in Berlin (winter time)
        val ms = 1_794_058_330_000L
        assertEquals("14:32", StoppedText.since(ms, berlin))
        assertEquals("13:32", StoppedText.since(ms, TimeZone.getTimeZone("UTC")))
        assertEquals("24 h, no seconds", "00:05", StoppedText.since(1_794_009_900_000L, TimeZone.getTimeZone("UTC")))
    }

    @Test fun aFreeTourRestsOnlyTheRecording() {
        assertTrue("free tour: \"Aufzeichnung ruht\"", StoppedText.freeLine(free = true))
        assertFalse("with a route: \"Aufzeichnung und Navigation ruhen\"", StoppedText.freeLine(free = false))
    }

    /** Owner 2026-11-07: the stop state is "Anhalten" / "Angehalten" everywhere it means this state – never "Pause". */
    @Test fun theStopStateIsAnhaltenNotPause() {
        val de = File("../strings/src/commonMain/composeResources/values-de/strings_navigate.xml").readText()
        fun v(key: String) = Regex("<string name=\"$key\">([^<]*)</string>").find(de)!!.groupValues[1]
        assertEquals("Anhalten", v("nav_action_pause"))
        assertEquals("Anhalten", v("nav_pause"))
        assertEquals("Angehalten", v("nav_status_paused"))
        assertEquals("Angehalten", v("nav_rec_paused_title"))
        assertEquals("Angehalten seit %1\$s", v("nav_stopped_since_head"))
        assertEquals("Aufzeichnung und Navigation ruhen", v("nav_stopped_line"))
        assertEquals("Tour beenden", v("nav_end_tour"))
        // the other meanings stay: the estimate stands still, breaks
        assertEquals("Zeit pausiert", v("nav_time_paused"))
        assertEquals("Pausen %1\$s", v("nav_breaks"))
    }
}
