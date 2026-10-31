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
}
