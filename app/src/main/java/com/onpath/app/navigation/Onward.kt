package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A new route that goes on the way one is going (field tour 221, owner on foot 2026-11-07: four new routes in 2½ minutes,
 * "Neue Route. Bitte umdrehen." while he walked on another way). The shortest route from the position often starts by
 * leading back over the metres just walked (to the junction behind); when that is so and the user is moving, [Rerouter]
 * also asks for a route on through a point ahead in the walking direction ([viaAhead]) and takes it when it really goes on
 * (no way back over the walked metres, no there-and-back to the point ahead) and costs at most [worthIt] more.
 * A turn round stays when it is the only sensible way (a dead end ahead, a much longer way on). Pure; `OnwardTest`.
 */
object Onward {
    /** A recent position at least this far behind the user … */
    const val BEHIND_MIN_M = 10.0
    /** … and at most this far (the last metres walked, not the whole tour) … */
    const val BEHIND_MAX_M = 60.0
    /** … this near the new line = the line leads back over where the user just walked. */
    const val NEAR_M = 6.0
    /** Only this first part of the line counts (a way back later on, after a loop, is not "back"). */
    const val CHECK_M = 80.0
    /** The line's first metres are left out (its start is the position snapped onto a way, maybe a little behind). */
    const val START_SKIP_M = 4.0
    /** A line coming back within this of a part it used [THERE_AND_BACK_GAP_M] before goes there and back. */
    const val THERE_AND_BACK_M = 6.0
    const val THERE_AND_BACK_GAP_M = 20.0
    /** The way on may cost this share of the shortest route more … */
    const val EXTRA_SHARE = 0.25
    /** … but never more than this … */
    const val EXTRA_MAX_S = 180.0
    /** … and always at least this (a short rest of the route). */
    const val EXTRA_MIN_S = 30.0

    /** Points ahead to route through, nearest first (walking ~30 s / riding ~15 s on). */
    fun aheadM(activity: Activity): List<Double> = if (activity == Activity.HIKE) listOf(40.0, 80.0) else listOf(80.0, 160.0)

    /** The point [m] metres from [p] in direction [deg]. */
    fun viaAhead(p: GeoPoint, deg: Double, m: Double): GeoPoint {
        val r = Math.toRadians(deg)
        return GeoPoint(p.lat + Geo.degLat(m * cos(r)), p.lon + Geo.degLon(m * sin(r), p.lat))
    }

    /**
     * [line] (a new route from the position) leads back over where the user just walked ([recent], oldest first): its
     * first [CHECK_M] pass within [NEAR_M] of a position [BEHIND_MIN_M]..[BEHIND_MAX_M] behind. Unknown (too few
     * positions, a short line) = false.
     */
    fun goesBack(recent: List<GeoPoint>, line: List<GeoPoint>): Boolean {
        if (recent.size < 2 || line.size < 2) return false
        val now = recent.last()
        val behind = recent.filter { Geo.distance(it, now) in BEHIND_MIN_M..BEHIND_MAX_M }
        if (behind.isEmpty()) return false
        val cum = Geo.cumulative(line)
        for (k in 1 until line.size) {
            if (cum[k - 1] > CHECK_M) break
            if (cum[k] < START_SKIP_M) continue
            val a = if (cum[k - 1] >= START_SKIP_M) line[k - 1]
                else Geo.along(line[k - 1], line[k], (START_SKIP_M - cum[k - 1]) / (cum[k] - cum[k - 1]))
            if (behind.any { Geo.distanceToSegment(it, a, line[k]) <= NEAR_M }) return true
        }
        return false
    }

    /** The first [withinM] of [line] go somewhere and come back the same way (a dead end, a spur to a point ahead). */
    fun thereAndBack(line: List<GeoPoint>, withinM: Double): Boolean {
        val cum = Geo.cumulative(line)
        for (j in 1 until line.size) {
            if (cum[j] > withinM) break
            for (k in 1 until line.size) {
                if (cum[k] > cum[j] - THERE_AND_BACK_GAP_M) break
                if (Geo.distanceToSegment(line[j], line[k - 1], line[k]) <= THERE_AND_BACK_M) return true
            }
        }
        return false
    }

    /** The way on ([onS] seconds) instead of the shortest ([shortestS]): at most [EXTRA_SHARE] (≤ [EXTRA_MAX_S]) more. */
    fun worthIt(shortestS: Double, onS: Double): Boolean =
        onS - shortestS <= maxOf(EXTRA_MIN_S, min(EXTRA_SHARE * shortestS, EXTRA_MAX_S))
}
