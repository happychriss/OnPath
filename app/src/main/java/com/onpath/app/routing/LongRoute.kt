package com.onpath.app.routing

import com.onpath.app.data.Geo
import kotlin.math.roundToInt

/**
 * A plan far too long for one day (owner 2026-10-22: a point set in Germany by mistake → "29 days", maps for the whole
 * way started to load). Before such a plan is routed, the app asks once. Multi-day planning may come later; until then
 * 24 h is the line. The estimate needs no router: straight lines between the points × [DETOUR], flat.
 */
object LongRoute {
    const val ASK_ABOVE_S = 24 * 3600.0
    /** Real ways are longer than the straight line between the points. */
    const val DETOUR = 1.3

    fun estimateS(points: List<GeoPoint>, activity: Activity): Double =
        if (points.size < 2) 0.0 else Geo.estimateSeconds(Geo.length(points) * DETOUR, 0.0, 0.0, activity)

    fun needsAsk(points: List<GeoPoint>, activity: Activity): Boolean = estimateS(points, activity) > ASK_ABOVE_S

    /** "about 29 days" / "about 30 hours" (plain words for the question). */
    fun describe(seconds: Double): String {
        val h = seconds / 3600.0
        return if (h >= 48) "about ${(h / 24).roundToInt()} days" else "about ${h.roundToInt()} hours"
    }
}
