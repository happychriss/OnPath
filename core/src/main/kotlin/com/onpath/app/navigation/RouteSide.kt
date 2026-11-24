package com.onpath.app.navigation

import com.onpath.app.data.Geo
import kotlin.math.abs

/**
 * Where the route lies from a walker who left it (owner 2026-11-21, `docs/design/navigation-v2.md` §3 E): four sectors
 * around the walking direction – "vor dir / rechts / links / hinter dir". Pure, unit-tested (`RouteSideTest`).
 */
enum class RouteSide {
    AHEAD, RIGHT, LEFT, BEHIND;

    companion object {
        /** Within this of the walking direction the route is ahead. */
        const val AHEAD_DEG = 45.0
        /** Beyond this it is behind; in between it is to the side. */
        const val BEHIND_DEG = 135.0

        /** The side of the route's nearest point at [bearingDeg] for a walker heading [headingDeg]; null = either unknown. */
        fun of(headingDeg: Double?, bearingDeg: Double?): RouteSide? {
            if (headingDeg == null || bearingDeg == null || !headingDeg.isFinite() || !bearingDeg.isFinite()) return null
            val a = Geo.angleDiff(headingDeg, bearingDeg)
            return when {
                abs(a) <= AHEAD_DEG -> AHEAD
                abs(a) >= BEHIND_DEG -> BEHIND
                a > 0 -> RIGHT
                else -> LEFT
            }
        }
    }
}
