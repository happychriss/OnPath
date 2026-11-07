package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint

/**
 * "Position on the route" (like Komoot; default on): while navigating and close to the followed line,
 * the dot is drawn on the line instead of at the GPS fix – no wobble beside the road. Off the route (or not matched,
 * or a reroute question open) the real fix, so leaving the route is seen at once. Only the dot: the recorded track and
 * every check stay on the GPS fixes. Pure, unit-tested.
 */
object SnapToRoute {
    /**
     * Within this of the line the dot sits on it. Owner 2026-11-06: 25 m put the dot on the cycle path while he rode the
     * road beside it, 10–15 m away – "5 oder 6 Meter dürfen es sein". Beyond it the dot is the real fix.
     */
    const val MAX_OFF_M = 6.0

    /** Where to draw the dot on [line], or null = at the GPS fix. */
    fun position(enabled: Boolean, line: NavLine?, alongM: Double?, offM: Double?, prompt: NavPrompt?): GeoPoint? {
        if (!enabled || line == null || !line.isUsable || alongM == null || offM == null || prompt != null) return null
        if (offM > MAX_OFF_M) return null
        return line.pointAt(alongM)
    }
}
