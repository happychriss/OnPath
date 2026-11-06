package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideThresholdTest {
    @Test fun completesFrom85Percent() {
        assertFalse(SlideThreshold.isComplete(840f, 1000f))
        assertTrue(SlideThreshold.isComplete(850f, 1000f))
        assertTrue(SlideThreshold.isComplete(1000f, 1000f))
    }

    @Test fun halfwaySpringsBack() = assertFalse(SlideThreshold.isComplete(500f, 1000f))

    @Test fun clampsToTrack() {
        assertEquals(0f, SlideThreshold.clamp(-30f, 1000f))
        assertEquals(1000f, SlideThreshold.clamp(1300f, 1000f))
        assertEquals(1f, SlideThreshold.fraction(5000f, 1000f))
    }

    /**
     * Owner 2026-11-06 (flow map N2): under "» Entsperren" a small line says what is behind it – "Pause · Beenden · Plan"
     * with a recording, "Plan" without – only when it fits (never cut).
     */
    @Test fun smallLineSaysWhatIsBehindAndOnlyWhenItFits() {
        val slider = java.io.File("src/main/java/com/onpath/app/ui/SlideToUnlock.kt").readText()
        assertTrue(slider.contains("!recording -> Res.string.nav_slide_unlock_sub_plan; paused -> Res.string.nav_slide_unlock_sub_paused"))
        assertTrue(slider.contains("if (showSub) Text(sub"))
        val de = java.io.File("../strings/src/commonMain/composeResources/values-de/strings_navigate.xml").readText()
        // owner 2026-11-06: the blue button and the line say "Planen"
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub\">Pause · Beenden · Planen</string>"))
        assertTrue(de.contains("<string name=\"nav_slide_unlock_sub_plan\">Planen</string>"))
        assertTrue(SlideLabel.subFits(100, 120, 40, 30, 80))
        assertFalse("too wide", SlideLabel.subFits(130, 120, 40, 30, 80))
        assertFalse("too high (large font)", SlideLabel.subFits(100, 120, 50, 40, 80))
    }

    @Test fun noTrackNeverCompletes() {
        assertFalse(SlideThreshold.isComplete(0f, 0f))
        assertFalse(SlideThreshold.isComplete(10f, -5f))
        assertEquals(0f, SlideThreshold.fraction(10f, 0f))
    }
}
