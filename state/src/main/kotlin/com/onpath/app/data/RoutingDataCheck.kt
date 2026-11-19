package com.onpath.app.data

import com.onpath.app.routing.GeoPoint

/** Pure rules of the routing data refresh (unit-tested). */
object RoutingDataCheck {
    /** The server's file ([serverDate], 0 = unknown) is newer than the local one (its download time). */
    fun isNewer(serverDate: Long, localDate: Long?): Boolean = serverDate > 0 && localDate != null && serverDate > localDate

    /**
     * The route changed in a way the user would notice: a point of one line is more than [toleranceM] from every
     * point of the other (sampled), or the length differs by more than 2 %.
     */
    fun differs(a: List<GeoPoint>, b: List<GeoPoint>, toleranceM: Double = 30.0): Boolean {
        if (a.size < 2 || b.size < 2) return a.size != b.size
        val la = Geo.length(a); val lb = Geo.length(b)
        if (kotlin.math.abs(la - lb) > 0.02 * maxOf(la, lb)) return true
        fun far(x: List<GeoPoint>, y: List<GeoPoint>): Boolean {
            val step = maxOf(1, x.size / 200)
            for (i in x.indices step step) if (y.minOf { Geo.fastDistance(x[i], it) } > toleranceM) return true
            return false
        }
        return far(a, b) || far(b, a)
    }

}
