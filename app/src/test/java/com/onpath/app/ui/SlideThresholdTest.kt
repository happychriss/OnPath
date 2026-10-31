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

    /** "Change route · Pause · End" only when it fits beside the thumb and under the main line; else main line only. */
    @Test fun smallLineOnlyWhenItFits() {
        assertTrue(SlideLabel.subFits(subWidth = 200, availWidth = 300, mainHeight = 22, subHeight = 20, availHeight = 56))
        assertFalse(SlideLabel.subFits(subWidth = 320, availWidth = 300, mainHeight = 22, subHeight = 20, availHeight = 56))
        assertFalse(SlideLabel.subFits(subWidth = 200, availWidth = 300, mainHeight = 33, subHeight = 30, availHeight = 56))
    }

    @Test fun noTrackNeverCompletes() {
        assertFalse(SlideThreshold.isComplete(0f, 0f))
        assertFalse(SlideThreshold.isComplete(10f, -5f))
        assertEquals(0f, SlideThreshold.fraction(10f, 0f))
    }
}
