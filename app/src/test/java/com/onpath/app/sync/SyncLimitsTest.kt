package com.onpath.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLimitsTest {
    @Test fun twoHundredFiftyToursPerPhone() {
        assertEquals(250, SyncLimits.MAX_TOURS)
        assertTrue(SyncLimits.canAdd(0))
        assertTrue(SyncLimits.canAdd(249)) // the 250th may still be saved
        assertFalse(SyncLimits.canAdd(250)) // the 251st is refused
        assertFalse(SyncLimits.canAdd(400))
    }

    @Test fun amberFromEightyPercent() {
        assertEquals(200, SyncLimits.WARN_TOURS)
        assertFalse(SyncLimits.nearlyFull(199))
        assertTrue(SyncLimits.nearlyFull(200))
    }
}
