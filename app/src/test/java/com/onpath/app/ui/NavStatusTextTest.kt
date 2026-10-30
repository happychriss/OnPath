package com.onpath.app.ui

import com.onpath.app.time.Factors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Navigate status card texts. */
class NavStatusTextTest {
    private val toGo = ToGo(7_420.0, 512.0, 3600.0, 12.0)
    private val nav = NavUi(toGo = toGo, recordedM = 5_320.0, movingS = 72 * 60.0, breakS = 11 * 60.0)

    @Test fun normalNavigationHasNoRecordingLine() {
        assertNull(recordingLine(nav, 5_320.0))
        assertEquals("Moving 1:12 · breaks 0:11", movingLine(nav))
    }

    @Test fun pausedAndOffRouteAndFreeRecordingShowIt() {
        assertEquals("Paused · moving 1:12", recordingLine(nav.copy(paused = true), 5_320.0))
        assertEquals("Recording · 5.3 km", recordingLine(nav, 5_320.0, offRoute = true))
        assertEquals("Recording · 5.3 km · moving 1:12 · breaks 0:11", recordingLine(nav.copy(toGo = null), 5_320.0))
    }

    @Test fun noGpsIsOneWarningLine() {
        assertEquals("No GPS", noGpsLine(20))
        assertEquals("No GPS · position 4 min old", noGpsLine(250))
        assertEquals("Time paused", timePausedText(toGo.copy(pausedBecause = "no GPS"), waitingGps = true))
        assertEquals("Time paused · no progress", timePausedText(toGo.copy(pausedBecause = "no progress"), waitingGps = false))
    }

    @Test fun approximatePositionLineSaysHowRough() {
        assertEquals("Approximate position (Wi-Fi, ±80 m) – GPS comes outside",
            approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WIFI, 80)))
        assertEquals("Approximate position (±70 m) – GPS still weak",
            approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WEAK_GPS, 70)))
    }

    @Test fun paceInPlainWordsOnlyWhenClearlyDifferent() {
        assertNull(paceNote(Factors(down = 1.1, flat = 1.0, up = 0.9)))
        assertEquals("Faster than planned uphill", paceNote(Factors(up = 0.8)))
        assertEquals("Slower than planned downhill", paceNote(Factors(down = 1.25)))
        assertEquals("Faster than planned uphill · slower than planned downhill", paceNote(Factors(down = 1.2, up = 0.8)))
        assertEquals("Faster than planned", paceNote(Factors(0.8, 0.8, 0.8)))
        assertEquals("Slower than planned uphill and on the flat", paceNote(Factors(flat = 1.2, up = 1.3)))
    }
}
