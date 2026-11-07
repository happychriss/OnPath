package com.onpath.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31, no double question: while OnPath is on screen the re-route question is the box at the top – the
 * notification then goes on the quiet navigation channel (lock screen keeps it, no heads-up); a heads-up only when the
 * app is not on screen.
 */
class QuestionNotificationTest {

    @Test fun onScreenTheQuestionIsNoHeadsUp() {
        assertEquals(TourChannels.NAVIGATION, TourChannels.forQuestion(appVisible = true))
        // the navigation channel: lock screen, no sound, no buzz – and below HIGH, so never a heads-up
        assertTrue(TourChannels.navigationSpec.lockScreenPublic)
        assertFalse(TourChannels.navigationSpec.sound)
        assertTrue(TourChannels.navigationSpec.importance < android.app.NotificationManager.IMPORTANCE_HIGH)
    }

    @Test fun notOnScreenItIsAHeadsUp() {
        assertEquals(TourChannels.QUESTION, TourChannels.forQuestion(appVisible = false))
        assertEquals("offroute-question", TourChannels.QUESTION)
        assertEquals(RecordingService.CHANNEL_NAV, TourChannels.QUESTION)
        assertNotEquals(TourChannels.forQuestion(true), TourChannels.forQuestion(false))
    }

    @Test fun visibilityCountsStartedActivities() {
        val c = AppVisibility.Counter()
        assertFalse(c.visible)
        c.started(); assertTrue(c.visible)
        // a rotation: the new activity starts before the old one stops – still on screen
        c.started(); c.stopped(); assertTrue(c.visible)
        c.stopped(); assertFalse("home / screen off", c.visible)
        c.stopped(); assertFalse("never below zero", c.visible)
        c.started(); assertTrue(c.visible)
    }

    /** The wiring: the app feeds AppVisibility, the service picks the channel from it for the question. */
    @Test fun wiring() {
        val app = File("src/main/java/com/onpath/app/OnPathApp.kt").readText()
        assertTrue(app.contains("registerActivityLifecycleCallbacks(com.onpath.app.recording.AppVisibility.callbacks)"))
        val alert = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
            .substringAfter("private fun updateAlert()").substringBefore("\n    }\n")
        assertTrue(alert.contains("TourChannels.forQuestion(AppVisibility.visible)"))
        assertTrue(alert.contains("NotificationCompat.Builder(this, channel)"))
        // a change of visibility re-posts on the other channel
        assertTrue(alert.contains("+ \"@\" + channel"))
    }

    /**
     * Owner 2026-11-01 (replaces review H1): "Navigation Stopp" = the phone is quiet, also with the screen off; walking off
     * with the phone pocketed brings the guidance back (H1's worry, StopQuietTest). Owner 2026-11-03: Plan is silent for
     * everything from movement / navigation ([ModeQuiet], ModeQuietTest). Wiring in the service.
     */
    @Test fun navigationStoppIsQuietUntilContinue() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.substringAfter("private fun tick()").substringBefore("\n    }\n").contains("applyNavigating()"))
        assertTrue(svc.contains("setNavigating(navigatingApplied)"))
        assertTrue("only Continue ends the quiet (owner 2026-11-03)", svc.contains("val now = navigating\n"))
        assertTrue(svc.contains("stopQuiet.set(stopped = !navigating)"))
        // no voice and no junction vibration while quiet (owner 2026-11-03): GuidanceGate (ModeQuietTest)
        assertTrue(svc.contains("GuidanceGate.junction(planQuiet()) == GuidanceGate.Junction.FOLLOW_SILENTLY"))
        assertTrue(svc.contains("private fun planQuiet(): Boolean = tourId >= 0 && stopQuiet.quiet"))
    }

    /** Review M1: every notification of a tour has its own id – Pause / Resume never cancels "cannot be saved". */
    @Test fun notificationIdsAreDistinct() {
        val ids = listOf(TourChannels.NOTIF_ID, RecordingService.ALERT_ID, RecordingService.PAUSE_ALERT_ID,
            RecordingService.SAVE_FAIL_ID, TourInterruptedReceiver.INTERRUPTED_ID, com.onpath.app.cells.MapLoadService.NOTE_ID)
        assertEquals(ids.size, ids.toSet().size)
    }

    /** Review M3: no action of the service and no broken session file can kill the process (and with it the tour). */
    @Test fun serviceActionsAreGuarded() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        assertTrue(svc.contains("override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try {"))
        assertTrue(svc.contains("CoroutineExceptionHandler"))
        assertTrue(svc.substringAfter("private fun loadFollow() {").substringBefore("\n    }\n").contains("catch (e: Exception)"))
    }
}
