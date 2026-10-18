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

    @Test fun noTrackNeverCompletes() {
        assertFalse(SlideThreshold.isComplete(0f, 0f))
        assertFalse(SlideThreshold.isComplete(10f, -5f))
        assertEquals(0f, SlideThreshold.fraction(10f, 0f))
    }
}
