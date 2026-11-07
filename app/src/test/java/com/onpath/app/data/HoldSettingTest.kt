package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hold time of the tour's bar: 0–2 s in 0,5 s steps, migrated from the old lock switch (owner 2026-11-06 22:50). */
class HoldSettingTest {
    @Test fun theSliderHasFivePositions() {
        assertEquals(listOf(0, 500, 1_000, 1_500, 2_000), HoldSetting.STEPS_MS)
        assertEquals(3, HoldSetting.SLIDER_STEPS)
    }

    @Test fun aPositionSnapsToTheNearestHalfSecond() {
        assertEquals(0, HoldSetting.snap(120f))
        assertEquals(500, HoldSetting.snap(260f))
        assertEquals(1_000, HoldSetting.snap(1_100f))
        assertEquals(1_500, HoldSetting.snap(1_490f))
        assertEquals(2_000, HoldSetting.snap(1_900f))
    }

    @Test fun valuesOutsideTheRangeTakeTheNearestEnd() {
        assertEquals(0, HoldSetting.snap(-300))
        assertEquals(2_000, HoldSetting.snap(9_000))
    }

    @Test fun zeroMeansNoLock() {
        assertFalse(HoldSetting.locked(0))
        assertTrue(HoldSetting.locked(500))
    }

    @Test fun theNewValueWinsAndIsSnapped() {
        assertEquals(1_500, HoldSetting.fromPrefs(storedMs = 1_400, oldLock = false))
        assertEquals(0, HoldSetting.fromPrefs(storedMs = 0, oldLock = true))
    }

    @Test fun theOldSwitchMigrates() {
        assertEquals(0, HoldSetting.fromPrefs(storedMs = null, oldLock = false))
        assertEquals(1_000, HoldSetting.fromPrefs(storedMs = null, oldLock = true))
        assertEquals(1_000, HoldSetting.fromPrefs(storedMs = null, oldLock = null))
    }

    @Test fun theValueReadsInTheLanguage() {
        assertNull(HoldSetting.seconds(0, ','))
        assertEquals("0,5", HoldSetting.seconds(500, ','))
        assertEquals("1", HoldSetting.seconds(1_000, ','))
        assertEquals("1.5", HoldSetting.seconds(1_500, '.'))
        assertEquals("2", HoldSetting.seconds(2_000, '.'))
    }

    @Test fun theSettingsModelSaysLockedFromTheTime() {
        assertTrue(AppSettings().navLock)
        assertEquals(HoldSetting.DEFAULT_MS, AppSettings().navHoldMs)
        assertFalse(AppSettings(navHoldMs = 0).navLock)
    }
}
