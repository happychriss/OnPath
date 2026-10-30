package com.onpath.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Notifications off" chip in Navigate: shown during a tour, OK hides it for that tour only (owner 2026-10-30). */
class NotificationsOffTest {
    @Test fun offDuringATourShowsTheChip() = assertTrue(NotificationsOff.shown(tourId = 7, notificationsOk = false, okForTour = null))

    @Test fun okHidesItForThisTour() = assertFalse(NotificationsOff.shown(tourId = 7, notificationsOk = false, okForTour = 7))

    @Test fun theNextTourShowsItAgain() = assertTrue(NotificationsOff.shown(tourId = 8, notificationsOk = false, okForTour = 7))

    @Test fun notificationsOnHideIt() {
        assertFalse(NotificationsOff.shown(tourId = 7, notificationsOk = true, okForTour = null))
        assertFalse(NotificationsOff.shown(tourId = 8, notificationsOk = true, okForTour = 7))
    }

    @Test fun noTourNoChip() = assertFalse(NotificationsOff.shown(tourId = null, notificationsOk = false, okForTour = null))
}
