package com.onpath.app.data

/** User settings (Settings page). Persisted by [Prefs]; immutable so Compose can diff it. */
data class AppSettings(
    /** POI kinds the user switched off (keys of poi/PoiKinds). Empty = show all. */
    val hiddenPoiKinds: Set<String> = emptySet(),
    val contours: Boolean = true,
    val hillshade: Boolean = true,
    val offRouteWarning: Boolean = true,
    /** Vibrate with the off-route warning (owner 2026-10-20: on by default – the phone is in the pocket). */
    val offRouteVibrate: Boolean = true,
    val offRouteM: Int = 40,
    /** Navigate: vibrate before a junction of the followed route – 2× left, 3× right (owner 2026-10-22, default on). */
    val junctionVibrate: Boolean = true,
    /**
     * Vibrate when planning: 1× a point set, 2× a point deleted, 1× a reorder, a light tick for a menu (owner 2026-10-21:
     * on by default, independent of the system "Touch feedback" setting – his phone has that off and he wants them).
     */
    val planVibrate: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Map theme key, see map/StyleLoader. Only "classic" is bundled since 0.7.0 (one style); others = adb overrides. */
    val mapTheme: String = "classic",
    // --- Navigate orientation (navigation agent) ---
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
