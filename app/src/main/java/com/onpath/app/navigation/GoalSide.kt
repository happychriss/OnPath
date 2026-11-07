package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.abs

/**
 * Where the goal lies from the rider, relative to the way they are going (owner 2026-11-07, bike: "Das Ziel ist rechts von
 * dir", "Ziel in 50 m, schräg rechts"). The heading is the bearing of the last metres actually ridden ([heading]) – never
 * the compass – and only while moving; pure, unit-tested (`GoalSideTest`).
 */
enum class GoalSide {
    AHEAD, HALF_RIGHT, RIGHT, BACK_RIGHT, BEHIND, BACK_LEFT, LEFT, HALF_LEFT;

    companion object {
        /** Within this of straight on the goal is "ahead" – nothing is said about its side. */
        const val AHEAD_DEG = 25.0
        /** Up to here "half right", up to [SIDE_DEG] "right", up to [BACK_DEG] "behind on the right", beyond "behind". */
        const val HALF_DEG = 65.0
        const val SIDE_DEG = 115.0
        const val BACK_DEG = 155.0
        /** Closer than this the side of the goal is GPS noise: nothing is said about it. */
        const val MIN_M = 6.0
        /** The heading is the bearing from the first track point at least this far back (a corner just taken counts) … */
        const val HEADING_M = 12.0
        /** … when that one is within this (a fix gap leaves no heading). */
        const val HEADING_MAX_M = 40.0
        /** Slower than this (m/s) there is no heading: standing or pushing on the spot. */
        const val MOVING_MPS = 0.8

        /** The side of [goal] for a rider at [here] heading [headingDeg]; null = not known (no heading, too close). */
        fun of(here: GeoPoint?, headingDeg: Double?, goal: GeoPoint?): GoalSide? {
            if (here == null || headingDeg == null || goal == null || !headingDeg.isFinite()) return null
            if (Geo.distance(here, goal) < MIN_M) return null
            val a = Geo.angleDiff(headingDeg, Geo.bearing(here, goal))
            val right = a > 0
            return when (abs(a)) {
                in 0.0..AHEAD_DEG -> AHEAD
                in AHEAD_DEG..HALF_DEG -> if (right) HALF_RIGHT else HALF_LEFT
                in HALF_DEG..SIDE_DEG -> if (right) RIGHT else LEFT
                in SIDE_DEG..BACK_DEG -> if (right) BACK_RIGHT else BACK_LEFT
                else -> BEHIND
            }
        }

        /**
         * The heading ridden: from the newest point of [track] (oldest first, [here] not in it) at least [HEADING_M] back
         * (and within [HEADING_MAX_M]) to [here]; null while not moving ([speedMps] under [MOVING_MPS] or unknown) or with
         * too little track.
         */
        fun heading(track: List<GeoPoint>, here: GeoPoint, speedMps: Double?): Double? {
            if (speedMps == null || !speedMps.isFinite() || speedMps < MOVING_MPS) return null
            for (k in track.indices.reversed()) {
                val d = Geo.distance(track[k], here)
                if (d >= HEADING_M) return if (d <= HEADING_MAX_M) Geo.bearing(track[k], here) else null
            }
            return null
        }
    }
}
