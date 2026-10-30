package com.onpath.app.recording

/**
 * Which notification channel the tour's foreground notification uses (owner 2026-10-30, "A"). Pure – JVM tested.
 *
 * - A plain recording (no route) stays on the quiet [RECORDING] channel (importance LOW: no lock-screen promise).
 * - Following a route uses [NAVIGATION]: importance DEFAULT, so Android 14+ shows it on the lock screen (silent
 *   notifications are hidden there by default), but with no sound, no vibration and no light – updates never buzz,
 *   the service's own junction / off-route vibration stays the only cue. Never HIGH (no heads-up popup).
 *
 * A channel's importance cannot change after it was created, hence a new channel id instead of raising "recording".
 * Both use the same notification id [NOTIF_ID]: switching channel is a normal update of the foreground notification,
 * never a stop / restart of the service (core feature C4).
 */
object TourChannels {
    const val RECORDING = "recording"
    const val NAVIGATION = "navigation"
    /** The foreground service's notification id – one for both channels. */
    const val NOTIF_ID = 42

    /** android.app.NotificationManager importance values (kept here so the choice is testable on the JVM). */
    const val IMPORTANCE_LOW = 2
    const val IMPORTANCE_DEFAULT = 3

    /** How a channel is created. */
    data class Spec(
        val id: String,
        val importance: Int,
        val sound: Boolean,
        val vibration: Boolean,
        val lights: Boolean,
        /** Full content on the lock screen. */
        val lockScreenPublic: Boolean,
    )

    val recordingSpec = Spec(RECORDING, IMPORTANCE_LOW, sound = false, vibration = false, lights = false, lockScreenPublic = false)
    val navigationSpec = Spec(NAVIGATION, IMPORTANCE_DEFAULT, sound = false, vibration = false, lights = false, lockScreenPublic = true)

    /** The tour notification: [NOTIF_ID] on the channel for [followingRoute]. */
    data class Post(val id: Int, val channel: String)

    fun forTour(followingRoute: Boolean) = Post(NOTIF_ID, if (followingRoute) NAVIGATION else RECORDING)
}
