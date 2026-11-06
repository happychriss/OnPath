package com.onpath.app.recording

/**
 * The recording notification's line (pure, `RecNotifTextTest`). Owner rule 2026-11-03: in Plan during a tour the phone
 * is quiet (no voice, no vibration, no off-route question). The notification kept saying "Aufnahme · Route: …", so a
 * user who put the phone away in Plan thought the guidance was on (flow map, novice walkthrough N1, 2026-11-06): while
 * a followed route is quiet the line says "Navigation pausiert – tippen zum Weiterfahren"; the recording goes on.
 */
object RecNotifText {
    /** [status]: "Aufnahme" / "Pause" …; [followName]: the route followed (null = none); [planQuiet]: the app is in Plan. */
    fun line(status: String, followName: String?, planQuiet: Boolean, following: (String, String) -> String,
             pausedInPlan: String): String = when {
        planQuiet && followName != null -> pausedInPlan
        followName != null -> following(status, followName)
        else -> status
    }

    /** The lock-screen route lines (next junction, time to go) only while the guidance speaks – not in Plan. */
    fun showRouteLines(planQuiet: Boolean): Boolean = !planQuiet
}
