package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review H3: a service restarted by itself that gets no GPS says so once after 5 min – never for a normal start. */
class GpsWatchdogTest {
    @Test fun firesOnceFiveMinutesAfterARestartWithoutAFix() {
        val w = GpsWatchdog()
        assertFalse("not armed (normal start indoors)", w.due(10 * 60_000L, paused = false))
        w.arm(0)
        assertFalse(w.due(GpsWatchdog.AFTER_MS - 1, paused = false))
        assertFalse("paused", w.due(GpsWatchdog.AFTER_MS, paused = true))
        assertTrue(w.due(GpsWatchdog.AFTER_MS, paused = false))
        assertFalse("once", w.due(GpsWatchdog.AFTER_MS * 3, paused = false))
    }

    @Test fun aFixDisarms() {
        val w = GpsWatchdog(); w.arm(0); w.disarm()
        assertEquals(false, w.due(GpsWatchdog.AFTER_MS * 2, paused = false))
    }
}
