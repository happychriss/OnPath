package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Novice walkthrough N1 (2026-11-06): in Plan during a tour the notification must not look like guidance is on. */
class RecNotifTextTest {
    private val following = { s: String, n: String -> "$s · Route: $n" }
    private val paused = "Navigation pausiert – tippen zum Weiterfahren"

    @Test fun planDuringARouteSaysPaused() =
        assertEquals(paused, RecNotifText.line("Aufnahme", "Hilden → Bonn", planQuiet = true, following, paused))

    @Test fun navigateSaysTheRoute() =
        assertEquals("Aufnahme · Route: Hilden → Bonn", RecNotifText.line("Aufnahme", "Hilden → Bonn", planQuiet = false, following, paused))

    @Test fun aFreeTourStaysRecording() =
        assertEquals("Aufnahme", RecNotifText.line("Aufnahme", null, planQuiet = true, following, paused))

    @Test fun noRouteLinesInPlan() { assertFalse(RecNotifText.showRouteLines(true)); assertTrue(RecNotifText.showRouteLines(false)) }

}
