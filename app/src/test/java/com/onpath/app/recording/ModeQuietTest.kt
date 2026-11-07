package com.onpath.app.recording

import com.onpath.app.recording.ModeQuiet.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-03: "wenn ich im Planmodus bin, soll die Stimme auf jeden Fall schweigen zu allem … keine Meldungen, die
 * von Bewegung, von der Navigation kommen … Das Handy verhält sich dann still, Plan only." The recording goes on and
 * keeps telling about itself (a recording problem, walking off while paused). The service asks [GuidanceGate] at each
 * gate; the arrival box waits for Navigate (TourEndPathsTest).
 */
class ModeQuietTest {
    @Test fun planIsSilentForEverythingFromMovementOrNavigation() {
        val navigation = listOf(Event.VOICE, Event.TURN_VIBRATION, Event.OFF_ROUTE_QUESTION, Event.OFF_ROUTE_NOTIFICATION,
            Event.REROUTE_PROPOSAL, Event.STOP_REACHED, Event.ARRIVAL, Event.NO_GPS, Event.APP_EVENT_VOICE)
        for (e in navigation) {
            assertTrue("Navigate: $e", ModeQuiet.allows(planQuiet = false, e))
            assertFalse("Plan: $e", ModeQuiet.allows(planQuiet = true, e))
        }
        // the recording's own messages stay – robust first (R-07): a tour that cannot be saved is never silent
        for (e in listOf(Event.RECORDING_PROBLEM, Event.PAUSE_WALK_OFF)) {
            assertTrue("Plan: $e", ModeQuiet.allows(planQuiet = true, e))
            assertTrue("Navigate: $e", ModeQuiet.allows(planQuiet = false, e))
        }
        assertEquals(Event.entries.toSet(), (navigation + Event.RECORDING_PROBLEM + Event.PAUSE_WALK_OFF).toSet())
    }

    /** Arrival is not checked in Plan – it is checked again with the next fix after "Navigation fortsetzen" (no replay). */
    @Test fun noArrivalInPlan() {
        assertFalse(GuidanceGate.arrivalCheck(arrived = false, paused = false, planQuiet = true))
        assertTrue(GuidanceGate.arrivalCheck(arrived = false, paused = false, planQuiet = false))
    }

    /** An app event ("Kein Ziel mehr" after an edit in Plan, arrival) is not spoken in Plan. */
    @Test fun noAppEventVoiceInPlan() {
        assertFalse(GuidanceGate.appEventVoice(hasPhrase = true, voiceOn = true, planQuiet = true))
        assertTrue(GuidanceGate.appEventVoice(hasPhrase = true, voiceOn = true, planQuiet = false))
        assertFalse("voice off", GuidanceGate.appEventVoice(hasPhrase = true, voiceOn = false, planQuiet = false))
        assertFalse("nothing to say", GuidanceGate.appEventVoice(hasPhrase = false, voiceOn = true, planQuiet = false))
    }

    /** A pending off-route question has no notification in Plan; a re-route that finishes in Plan does not vibrate. */
    @Test fun noOffRouteNotificationOrVibrationInPlan() {
        assertFalse(GuidanceGate.offRouteNotification(planQuiet = true))
        assertTrue(GuidanceGate.offRouteNotification(planQuiet = false))
        assertFalse(GuidanceGate.rerouteVibrates(stepVibrates = true, planQuiet = true))
        assertTrue(GuidanceGate.rerouteVibrates(stepVibrates = true, planQuiet = false))
        assertFalse(GuidanceGate.rerouteVibrates(stepVibrates = false, planQuiet = false))
    }

    /**
     * No backlog on the way back: in Plan the voice keeps its memory up to date without speaking (stops passed, the
     * off-route state, "no GPS"), so "Navigation fortsetzen" never brings "Stopp 3 erreicht" from ten minutes ago.
     */
    @Test fun theVoiceFollowsSilentlyInPlan() {
        assertEquals(GuidanceGate.Junction.FOLLOW_SILENTLY, GuidanceGate.junction(planQuiet = true))
        assertEquals(GuidanceGate.Junction.CUE, GuidanceGate.junction(planQuiet = false))
        assertFalse(GuidanceGate.voiceSpeaks(planQuiet = true))
        assertTrue(GuidanceGate.voiceSpeaks(planQuiet = false))
        // wiring pin: back in Navigate the sentences that waited are dropped, not said late
        assertTrue(File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText().contains("voice.dropWaiting()"))
    }
}
