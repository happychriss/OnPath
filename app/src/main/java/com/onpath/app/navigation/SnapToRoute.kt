package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint

/**
 * "Position on the route" (like Komoot; default on): while navigating and close to the followed line,
 * the dot is drawn on the line instead of at the GPS fix – no wobble beside the road. Off the route (or not matched,
 * or a reroute question open) the real fix, so leaving the route is seen at once. Only the dot: the recorded track and
 * every check stay on the GPS fixes. Pure, unit-tested.
 */
object SnapToRoute {
    /** Within this of the line the dot sits on it. */
    const val MAX_OFF_M = 25.0

    /** Where to draw the dot on [line], or null = at the GPS fix. */
    fun position(enabled: Boolean, line: NavLine?, alongM: Double?, offM: Double?, prompt: NavPrompt?): GeoPoint? {
        if (!enabled || line == null || !line.isUsable || alongM == null || offM == null || prompt != null) return null
        if (offM > MAX_OFF_M) return null
        return line.pointAt(alongM)
    }
}
