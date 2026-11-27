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

    /** Since 2026-11-27 (owner, variant 1 + ■) the recording shows on Plan's tab, no pill at the top. */
    @Test fun wiring() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertFalse(map.contains("RecordingPill(Modifier"))
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertTrue(panel.contains("PlanModeLabel(editing = ui.tourRunning, recElapsedS = ui.tour?.takeIf { !it.paused }?.elapsedS, onEnd = cb.onEndTour)"))
        assertTrue(RecTab.time(42 * 60) == "0:42" && RecTab.time(3 * 3600 + 5 * 60) == "3:05")
    }
}
