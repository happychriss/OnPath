package com.onpath.app.recording

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-10-30 ("A"): the tour notification while following a route shows on the lock screen, still silent. */
class TourChannelsTest {

    @Test fun followingARouteUsesTheNavigationChannel() {
        assertEquals(TourChannels.NAVIGATION, TourChannels.forTour(followingRoute = true).channel)
    }

    @Test fun aPlainRecordingKeepsTheQuietRecordingChannel() {
        assertEquals(TourChannels.RECORDING, TourChannels.forTour(followingRoute = false).channel)
        assertEquals(TourChannels.IMPORTANCE_LOW, TourChannels.recordingSpec.importance)
    }

    @Test fun switchingChannelKeepsTheForegroundNotificationId() {
        // a switch is an update of the same foreground notification – never a second one, never a service restart
        val plain = TourChannels.forTour(false)
        val route = TourChannels.forTour(true)
        assertEquals(42, plain.id)
        assertEquals(plain.id, route.id)
        assertNotEquals(plain.channel, route.channel)
    }

    @Test fun navigationChannelIsANewIdBecauseImportanceCannotBeRaised() {
        // Android keeps an existing channel's importance: "recording" (LOW) can never become DEFAULT
        assertNotEquals(TourChannels.RECORDING, TourChannels.NAVIGATION)
        assertNotEquals("offroute", TourChannels.NAVIGATION)          // the deleted old off-route channel
        assertNotEquals(RecordingService.CHANNEL_NAV, TourChannels.NAVIGATION) // the off-route question (HIGH)
        assertEquals(TourChannels.NAVIGATION, TourChannels.navigationSpec.id)
    }

    @Test fun navigationChannelShowsOnTheLockScreenButNeverAlerts() {
        val s = TourChannels.navigationSpec
        assertEquals(TourChannels.IMPORTANCE_DEFAULT, s.importance) // DEFAULT: not "silent", so on the lock screen
        assertTrue("never HIGH: no heads-up popup", s.importance < NotificationManager.IMPORTANCE_HIGH)
        assertFalse(s.sound)
        assertFalse(s.vibration)
        assertFalse(s.lights)
        assertTrue(s.lockScreenPublic)
    }

    @Test fun importanceValuesMatchAndroid() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, TourChannels.IMPORTANCE_LOW)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, TourChannels.IMPORTANCE_DEFAULT)
    }

    @Test fun phoneSetupWatchesTheNavigationChannel() {
        // switched off by the user → the phone check says so (N1)
        assertTrue(TourChannels.NAVIGATION in com.onpath.app.ui.setup.PhoneStateReader.CHANNELS)
        assertTrue(TourChannels.RECORDING in com.onpath.app.ui.setup.PhoneStateReader.CHANNELS)
    }
}
