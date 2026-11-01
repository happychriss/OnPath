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

    /** Review M5: battery saver came on mid-tour on a phone where it stops GPS with the screen off – said once. */
    @Test fun batterySaverDuringATourIsSaidOnceWhereItStopsGps() {
        val L = com.onpath.app.setup.LocationSaverMode
        assertTrue(L.warnDuringTour(powerSave = true, mode = L.GPS_OFF_SCREEN_OFF, warned = false))
        assertTrue(L.warnDuringTour(true, L.ALL_OFF_SCREEN_OFF, false))
        assertFalse("once per tour", L.warnDuringTour(true, L.GPS_OFF_SCREEN_OFF, warned = true))
        assertFalse("GPS keeps running there", L.warnDuringTour(true, L.FOREGROUND_ONLY, false))
        assertFalse(L.warnDuringTour(true, L.NO_CHANGE, false))
        assertFalse("saver off", L.warnDuringTour(false, L.GPS_OFF_SCREEN_OFF, false))
        val svc = java.io.File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.contains("PowerManager.ACTION_POWER_SAVE_MODE_CHANGED"))
    }
}
