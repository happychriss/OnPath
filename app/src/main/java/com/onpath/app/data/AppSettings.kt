package com.onpath.app.data

/** When to vibrate before a junction (owner): off, always, or only with the screen locked / off. */
enum class JunctionVibration {
    OFF, ALWAYS, LOCKED;

    /** Whether a junction cue vibrates now; [locked] = screen off or the lock screen showing. */
    fun plays(locked: Boolean): Boolean = this == ALWAYS || (this == LOCKED && locked)

    companion object {
        /** Stored name → value; older installs stored a boolean "junctionVibrate" (false = off, else always). */
        fun of(stored: String?, oldOn: Boolean): JunctionVibration =
            entries.firstOrNull { it.name == stored } ?: if (oldOn) ALWAYS else OFF
    }
}

/** User settings (Settings page). Persisted by [Prefs]; immutable so Compose can diff it. */
data class AppSettings(
    /** POI kinds the user switched off (keys of poi/PoiKinds). Empty = show all. */
    val hiddenPoiKinds: Set<String> = emptySet(),
    val contours: Boolean = true,
    val hillshade: Boolean = true,
    val offRouteWarning: Boolean = true,
    /** Vibrate with the off-route warning (owner: on by default – the phone is in the pocket). */
    val offRouteVibrate: Boolean = true,
    val offRouteM: Int = 40,
    /** Navigate: vibrate before a junction of the followed route – 1× left, 2× right (owner). */
    val junctionVibration: JunctionVibration = JunctionVibration.ALWAYS,
    /**
     * Vibrate when planning: 1× a point set, 2× a point deleted, 1× a reorder, a light tick for a menu (owner: on by
     * default, independent of the system "Touch feedback" setting).
     */
    val planVibrate: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Navigate: the dot sits on the followed route while within ~25 m of it (owner, like Komoot; default on). */
    val positionOnRoute: Boolean = true,
    /** Map theme key, see map/StyleLoader. Only "classic" is bundled; others = adb overrides. */
    val mapTheme: String = "classic",
    /** Navigate: map turns so the walking direction points up (true, default) or stays north-up (false). */
    val navHeadingUp: Boolean = true,
    // --- time estimate (docs/plan-time-estimate.md): pace factor on time only, never on the route ---
    val hikePace: com.onpath.app.time.Pace = com.onpath.app.time.Pace.DEFAULT,
    val bikePace: com.onpath.app.time.Pace = com.onpath.app.time.Pace.DEFAULT,
    /** Settings → Offline maps: map areas download only on Wi-Fi, not even the own area on mobile data (F-OFF-03). */
    val mapsWifiOnly: Boolean = false,
) {
    companion object {
        val OFF_ROUTE_CHOICES = listOf(25, 40, 75)
    }
}
