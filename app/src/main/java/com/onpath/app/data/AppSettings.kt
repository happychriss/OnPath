package com.onpath.app.data

/** User settings (Settings page). Persisted by [Prefs]; immutable so Compose can diff it. */
data class AppSettings(
    /** POI kinds the user switched off (keys of poi/PoiKinds). Empty = show all. */
    val hiddenPoiKinds: Set<String> = emptySet(),
    val routeOverlay: Boolean = true,
    val contours: Boolean = true,
    val hillshade: Boolean = true,
    val offRouteWarning: Boolean = true,
    val offRouteM: Int = 40,
    val keepScreenOn: Boolean = true,
    /** Map theme key, see map/StyleLoader ("classic", "high-contrast", …). */
    val mapTheme: String = "classic",
    // --- Navigate orientation (navigation agent) ---
    /** Navigate: map turns so the walking direction points up (true, default) or stays north-up (false). */
    val navHeadingUp: Boolean = true,
    /** The app starts with this activity (and SAC limit for hiking); the activity chip only changes the current route. */
    val defaultActivity: com.onpath.app.routing.Activity = com.onpath.app.routing.Activity.HIKE,
    val defaultSac: Int = 3,
    // --- time estimate (docs/plan-time-estimate.md): pace factor on time only, never on the route ---
    val hikePace: com.onpath.app.time.Pace = com.onpath.app.time.Pace.NORMAL,
    val bikePace: com.onpath.app.time.Pace = com.onpath.app.time.Pace.NORMAL,
) {
    companion object {
        val OFF_ROUTE_CHOICES = listOf(25, 40, 75)
    }
}
