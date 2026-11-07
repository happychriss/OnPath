package com.onpath.app.ui

import com.onpath.app.navigation.NavPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Pause heißt Ruhe" (owner 2026-11-07): what Navigate shows while a tour is paused. */
class PauseQuietTest {
    private val far = OffRouteUi(NavPrompt.FarStart(id = 3, distanceM = 58_000.0, bearingDeg = 90.0))
    private val busy = NavUi(
        toGo = ToGo(58_000.0, 0.0, 0.0, 58_000.0, state = com.onpath.app.time.EstimateState.NOT_ON_ROUTE),
        recordedM = 1_200.0, elapsedS = 600, waitingGps = true, offRoute = far,
        turn = TurnHint(com.onpath.app.routing.TurnKind.LEFT, 120.0), approx = "Ungefähre Position (WLAN, ±80 m)",
        notificationsOff = true,
    )

    @Test fun pausedHidesQuestionsTurnsAndGpsWarnings() {
        val calm = PauseQuiet.calm(busy.copy(paused = true))
        assertNull("no navigation question (far start) while paused", calm.offRoute)
        assertNull("no turn card", calm.turn)
        assertFalse("no 'Kein GPS' line", calm.waitingGps)
        assertNull("no Wi-Fi position line", calm.approx)
        assertTrue("the card says no 'not on the route yet'", calm.calm)
        assertTrue("the notifications-off box is no navigation question: it stays", calm.notificationsOff)
        assertEquals("the recording itself is still shown", 1_200.0, calm.recordedM)
    }

    @Test fun afterWeiterEverythingComesBack() {
        // not answered, not dropped: the navigator keeps the question – unpaused, Navigate shows it as it is
        assertSame(busy, PauseQuiet.calm(busy))
        assertEquals(far, PauseQuiet.calm(busy).offRoute)
    }

    @Test fun withoutARecordingNothingIsCalmed() {
        val noTour = busy.copy(recordedM = null, paused = true)
        assertSame(noTour, PauseQuiet.calm(noTour))
    }
}
