package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of the app: prepares the emulator for a monkey crash hunt (night job 2026-11-12). Leaves behind a clean
 * app with the tiny map package (+ the real routing data when pushed), a saved three-point plan in Meran and the
 * developer tools unlocked (Demo location). The hunt then starts the app by hand, rides the demo and runs `monkey`.
 */
@RunWith(AndroidJUnit4::class)
class CrashHuntSetup : E2EBase() {

    @Test fun prepare() {
        launch {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            Prefs(ctx).apply {
                planWaypoints = listOf(GeoPoint(MERAN_LAT, MERAN_LON), GeoPoint(MERAN_LAT + 0.0046, MERAN_LON - 0.0100),
                    GeoPoint(MERAN_LAT + 0.0086, MERAN_LON))
                devToolsUnlocked = true
            }
        }
        assertEquals("the saved plan is back", 3, waypointCount())
        waitUntil("route calculated", 90_000) { var done = false; scenario.onActivity { done = vm(it).route != null && !vm(it).routing }; done }
    }
}
