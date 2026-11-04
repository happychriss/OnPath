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
 * keeps telling about itself (a recording problem, walking off while paused).
 */
class ModeQuietTest {
    private val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
    private fun body(start: String) = svc.substringAfter(start).substringBefore("\n    }\n")

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
        val arrived = body("private fun checkArrived(")
        assertTrue(arrived, arrived.indexOf("ModeQuiet.allows(planQuiet(), ModeQuiet.Event.ARRIVAL)") in 0 until arrived.indexOf("arrived = true"))
    }

    /** An arrival box from Navigate waits for Navigate – Plan shows it not (it stays pending). */
    @Test fun theArrivalBoxWaitsForNavigate() {
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertTrue(map.contains("if (vm.arrivalAsk && rec != null && vm.mode == AppMode.NAVIGATE) ArrivalDialog("))
    }

    /** An app event ("Kein Ziel mehr" after an edit in Plan, arrival) is not spoken in Plan. */
    @Test fun noAppEventVoiceInPlan() {
        val say = svc.substringAfter("ACTION_SAY -> {").substringBefore("return START_STICKY")
        assertTrue(say, say.contains("ModeQuiet.allows(planQuiet(), ModeQuiet.Event.APP_EVENT_VOICE)"))
    }

    /** A pending off-route question has no notification in Plan (it stays in the navigator; Navigate asks again). */
    @Test fun noOffRouteNotificationInPlan() {
        val alert = body("private fun updateAlert()")
        assertTrue(alert, alert.indexOf("ModeQuiet.allows(planQuiet(), ModeQuiet.Event.OFF_ROUTE_NOTIFICATION)") in 0 until alert.indexOf("NotificationCompat.Builder"))
        // a re-route that finishes while in Plan does not vibrate either
        val handle = body("private fun handle(step: Navigator.Step")
        assertTrue(handle, handle.contains("step.vibrate && ModeQuiet.allows(planQuiet(), ModeQuiet.Event.OFF_ROUTE_QUESTION)"))
    }

    /**
     * No backlog on the way back: in Plan the voice keeps its memory up to date without speaking (stops passed, the
     * off-route state, "no GPS"), so "Navigation fortsetzen" never brings "Stopp 3 erreicht" from ten minutes ago.
     */
    @Test fun theVoiceFollowsSilentlyInPlan() {
        val cue = body("private fun junctionCue(")
        val quietAt = cue.indexOf("voiceStep(st, nowEl, speak = false)")
        assertTrue(cue, quietAt >= 0 && quietAt < cue.indexOf("vibrate("))
        val step = body("private fun voiceStep(")
        assertTrue(step, step.contains("if (phrase != null && speak)"))
        // back in Navigate: sentences that waited are dropped, not said late
        assertTrue(svc.contains("voice.dropWaiting()"))
    }
}
