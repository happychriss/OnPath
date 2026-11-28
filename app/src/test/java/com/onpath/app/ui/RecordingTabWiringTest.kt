package com.onpath.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The recording while planning during a tour shows on Plan's tab (owner 2026-11-27, variant 1 + ■); the old top pill is gone (RecordingPill.kt deleted 2026-11-28). */
class RecordingTabWiringTest {
    /** Since 2026-11-27 (owner, variant 1 + ■) the recording shows on Plan's tab, no pill at the top. */
    @Test fun wiring() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertFalse(map.contains("RecordingPill("))
        assertFalse(File("src/main/java/com/onpath/app/ui/RecordingPill.kt").exists())
        val panel = File("src/main/java/com/onpath/app/ui/PlanPanel.kt").readText()
        assertTrue(panel.contains("PlanModeLabel(editing = ui.tourRunning, recElapsedS = ui.tour?.takeIf { !it.paused }?.elapsedS, onEnd = cb.onEndTour)"))
        assertTrue(RecTab.time(42 * 60) == "0:42" && RecTab.time(3 * 3600 + 5 * 60) == "3:05")
    }
}
