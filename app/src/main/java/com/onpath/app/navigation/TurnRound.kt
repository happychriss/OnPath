package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TurnDetector
import kotlin.math.abs

/**
 * Does a way back start by turning round (otherwise a new route that sends the rider back the same way looks like
 * nothing happened)? Compares the direction of the last [MOVE_M] the rider moved with the first [AHEAD_M]
 * of the new line. Pure, unit-tested. Unknown (standing still, short line) = false.
 */
object TurnRound {
    /** The rider must have moved this far for a direction. */
    const val MOVE_M = 25.0
    /** Direction of the new line over its first metres. */
    const val AHEAD_M = 30.0
    /** More than this between the two directions = turn round. */
    const val ANGLE_DEG = 135.0

    /** [recent] = the rider's latest positions, oldest first; [line] = the new route from the position on. */
    fun needed(recent: List<GeoPoint>, line: List<GeoPoint>): Boolean {
        if (recent.size < 2 || line.size < 2) return false
        val now = recent.last()
        val from = recent.asReversed().firstOrNull { Geo.distance(it, now) >= MOVE_M } ?: return false
        val start = line.first()
        val to = line.firstOrNull { Geo.distance(start, it) >= AHEAD_M } ?: return false
        val moving = TurnDetector.bearing(from, now)
        val route = TurnDetector.bearing(start, to)
        return abs(TurnDetector.diff(moving, route)) > ANGLE_DEG
    }
}
