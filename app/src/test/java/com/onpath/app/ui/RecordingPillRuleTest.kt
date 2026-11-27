package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** „◉ Aufnahme läuft“ while planning during a tour (owner 2026-11-27): only when nothing else speaks at the top. */
class RecordingPillRuleTest {
    @Test fun onlyDuringATourAndOnlyAlone() {
        assertTrue(RecordingPillRule.shows(true, box = false, mapPill = false, search = false, firstCard = false, waitWindow = false))
        assertFalse("no tour, no pill", RecordingPillRule.shows(false, false, false, false, false, false))
        assertFalse("a box speaks", RecordingPillRule.shows(true, true, false, false, false, false))
        assertFalse("the quiet map pill speaks", RecordingPillRule.shows(true, false, true, false, false, false))
        assertFalse("the search is open", RecordingPillRule.shows(true, false, false, true, false, false))
        assertFalse("the first-map card", RecordingPillRule.shows(true, false, false, false, true, false))
        assertFalse("the wait window", RecordingPillRule.shows(true, false, false, false, false, true))
    }

    /** The rule is what the Plan screen uses (Compose is not run on the JVM). */
    @Test fun wiring() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("val recOn = RecordingPillRule.shows(rec != null, message != null, pill != null, vm.searchFlow.searchOpen, firstCard != null, vm.mapData.loadWindowShown)"))
        assertTrue(map.contains("if (recOn) RecordingPill(rec!!.paused,"))
        assertTrue("the hints under it move down", map.contains("pill != null || recOn }"))
    }
}
