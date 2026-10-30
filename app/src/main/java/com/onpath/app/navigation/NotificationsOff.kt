package com.onpath.app.navigation

/**
 * Navigate's amber "Notifications off" chip (owner 2026-10-30): with OnPath's notifications off (permission denied,
 * app notifications or one of its channels switched off – [com.onpath.app.setup.PhoneCheck], CheckId.NOTIFICATIONS)
 * a tour has no info on the lock screen and no off-route question with the screen off. Shown during a tour; tap → the
 * phone setup step, OK → gone for this tour only (it comes back at the next tour, never a permanent "never again"). It
 * never goes by itself while notifications are off; it goes when they are on (re-read on every return to the app). Pure.
 */
object NotificationsOff {
    /**
     * [tourId] = the running tour (null = none), [notificationsOk] = PhoneCheck's NOTIFICATIONS check, [okForTour] = the
     * tour whose chip was confirmed with OK (null = none; kept in memory, not stored).
     */
    fun shown(tourId: Long?, notificationsOk: Boolean, okForTour: Long?): Boolean =
        tourId != null && !notificationsOk && okForTour != tourId
}
