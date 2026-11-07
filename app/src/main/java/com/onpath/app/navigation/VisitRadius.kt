package com.onpath.app.navigation

import com.onpath.app.routing.Activity

/**
 * How close a route point must be passed to count as reached: 25 m on foot, 40 m on a bike. Pure (no Android), so the
 * Rerouter and the JVM routing tests can use it; [NavSession.visitedM] reads it.
 */
object VisitRadius {
    const val HIKE_M = 25.0
    const val BIKE_M = 40.0
    fun of(a: Activity): Double = if (a == Activity.HIKE) HIKE_M else BIKE_M
}
