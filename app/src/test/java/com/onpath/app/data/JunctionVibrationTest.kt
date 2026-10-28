package com.onpath.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three junction vibration choices (owner 2026-10-28) and the migration of the old on/off switch. */
class JunctionVibrationTest {
    @Test fun offNeverPlays() { assertFalse(JunctionVibration.OFF.plays(true)); assertFalse(JunctionVibration.OFF.plays(false)) }
    @Test fun alwaysPlaysOnScreenAndLocked() { assertTrue(JunctionVibration.ALWAYS.plays(false)); assertTrue(JunctionVibration.ALWAYS.plays(true)) }
    @Test fun lockedPlaysOnlyLocked() { assertTrue(JunctionVibration.LOCKED.plays(true)); assertFalse(JunctionVibration.LOCKED.plays(false)) }
    @Test fun defaultIsAlways() = assertEquals(JunctionVibration.ALWAYS, AppSettings().junctionVibration)
    @Test fun oldSwitchMigrates() {
        assertEquals(JunctionVibration.ALWAYS, JunctionVibration.of(null, oldOn = true))
        assertEquals(JunctionVibration.OFF, JunctionVibration.of(null, oldOn = false))
        assertEquals(JunctionVibration.LOCKED, JunctionVibration.of("LOCKED", oldOn = true))
        assertEquals(JunctionVibration.ALWAYS, JunctionVibration.of("garbage", oldOn = true))
    }
}
