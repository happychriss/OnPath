package com.onpath.app.domain.map

/**
 * One map orientation for the whole session, Plan and Navigate alike (owner 2026-11-07: "bleibt immer in der eingestellten
 * Richtung – wenn einmal Planen in Gehrichtung, dann die ganze Session"; a poor map reader is lost when the map turns by
 * itself). The persisted setting `AppSettings.navHeadingUp` holds it; the compass button is the only quick switch.
 *
 * - [Orientation.NORTH]: every camera move ends north up – following tracks north up in both modes, fits / flies /
 *   "plan shown whole" set the bearing to 0; a two-finger rotation stays until the compass tap snaps it back.
 * - [Orientation.TRAVEL]: following turns the map with the walking direction in both modes (Navigate: the route ahead);
 *   fits / flies / "plan shown whole" keep the CURRENT bearing; leaving Navigate keeps it; when following stops (an edit,
 *   a touch) the map just stays.
 *
 * Nothing but the compass tap (and the settings page) changes the orientation: no mode switch, fit, load, search, tour
 * start / end, return to the app or rest view. Pure; MapController / AppViewModel ask it.
 */
enum class Orientation { TRAVEL, NORTH }

object MapOrientation {
    fun of(headingUp: Boolean): Orientation = if (headingUp) Orientation.TRAVEL else Orientation.NORTH

    /** How the camera follows the position, the same in Plan and Navigate. */
    enum class Follow {
        /** The map turns with the walking direction (MapLibre TRACKING_COMPASS fed by HeadingEngine). */
        TRAVEL,
        /** North up (MapLibre TRACKING_GPS_NORTH, bearing 0). */
        NORTH,
    }

    fun follow(o: Orientation): Follow = if (o == Orientation.TRAVEL) Follow.TRAVEL else Follow.NORTH

    /** An app camera move (fit, fly to a place, centre, plan shown whole): north up and flat, or keep the bearing. */
    fun moveNorthUp(o: Orientation): Boolean = o == Orientation.NORTH

    /** Leaving Navigate without following: turn the map north (only with [Orientation.NORTH] – TRAVEL keeps it). */
    fun northOnLeavingNavigate(o: Orientation, following: Boolean): Boolean = o == Orientation.NORTH && !following

    /**
     * What a compass tap does. [following]: the camera follows the position now; [mapTurned]: the bearing is not north.
     * NORTH, map turned by hand → back to north (stays NORTH). NORTH, north up → TRAVEL and follow (needs a fix, else
     * a neutral note). TRAVEL → NORTH, once animated: while following the north-up tracking turns it (no extra camera move
     * – MapLibre would drop the tracking), else the map is turned north.
     */
    data class Tap(
        val orientation: Orientation,
        /** true = follow the position (again); null = leave following as it is. */
        val follow: Boolean? = null,
        /** Turn the map north by an app camera move (only when not following). */
        val turnNorth: Boolean = false,
        /** No position for the walking direction: say so (neutral in Plan). */
        val noFix: Boolean = false,
    )

    fun tap(o: Orientation, mapTurned: Boolean, following: Boolean, hasFix: Boolean): Tap = when (o) {
        Orientation.NORTH -> when {
            mapTurned && following -> Tap(Orientation.NORTH, follow = true)
            mapTurned -> Tap(Orientation.NORTH, turnNorth = true)
            hasFix -> Tap(Orientation.TRAVEL, follow = true)
            else -> Tap(Orientation.NORTH, noFix = true)
        }
        Orientation.TRAVEL ->
            if (following) Tap(Orientation.NORTH, follow = true) else Tap(Orientation.NORTH, turnNorth = true)
    }
}
