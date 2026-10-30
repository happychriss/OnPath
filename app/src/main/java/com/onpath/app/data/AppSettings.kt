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

/**
 * Screen while cycling (owner 2026-10-30, Settings → Navigation; Bike, Gravel, E-bike, MTB, E-MTB – a hike keeps
 * [AppSettings.keepScreenOn] with the pocket logic): [ALWAYS] = the map stays on while following a route; [SMART] (default)
 * = far from the next turn a dim rest view on black instead of the map (ui/RestView.kt), the map comes back before each
 * turn and on a tap; [PHONE] = no keep-screen-on, the phone's own timeout.
 */
enum class BikeScreen {
    ALWAYS, SMART, PHONE;

    /** The screen is kept on while following a route. */
    val keepsOn: Boolean get() = this != PHONE

    companion object {
        /** Stored name → value; before this setting existed the one switch "keep screen on" decided – off stays off. */
        fun of(stored: String?, keepScreenOn: Boolean): BikeScreen =
            entries.firstOrNull { it.name == stored } ?: if (keepScreenOn) SMART else PHONE
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
    /** Keep the screen on while following a route – hiking (bike activities: [bikeScreen]). */
    val keepScreenOn: Boolean = true,
    /** Screen while cycling: always on / smart rest view (default) / like the phone. */
    val bikeScreen: BikeScreen = BikeScreen.SMART,
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
