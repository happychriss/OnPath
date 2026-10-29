package com.onpath.app.search

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.strings.*
import kotlin.math.roundToInt

/**
 * What "Add to route" will do, said before the tap: computed with the same
 * [WaypointInsertion.decide] the map hold uses, so the card never promises something else.
 */
object AddPreview {

    /** Straight-line detour → typical path detour (paths are rarely straight). An estimate, shown with "about". */
    const val DETOUR_FACTOR = 1.3

    data class Preview(val decision: WaypointInsertion.Decision, val subtitle: String, val extraM: Double?)

    /**
     * @param speedMps average speed of the current route (distance / duration), for "+15 min"; null = no time
     */
    fun of(waypoints: List<GeoPoint>, p: GeoPoint, route: List<GeoPoint>?, speedMps: Double? = null,
           lineSnapM: Double = 30.0): Preview {
        val d = WaypointInsertion.decide(waypoints, p, route, lineSnapM)
        val n = waypoints.size
        val extra: Double? = when {
            n < 2 -> null
            d.kind == WaypointInsertion.Kind.DESTINATION -> Geo.distance(waypoints.last(), p) * DETOUR_FACTOR
            d.kind == WaypointInsertion.Kind.VIA_BEST_FIT -> {
                val a = waypoints[d.index - 1]; val b = waypoints[d.index]
                (Geo.distance(a, p) + Geo.distance(p, b) - Geo.distance(a, b)).coerceAtLeast(0.0) * DETOUR_FACTOR
            }
            else -> null // on the line: practically no detour
        }
        val text = when (d.kind) {
            WaypointInsertion.Kind.START -> Txt.s(Res.string.search_add_as_start)
            WaypointInsertion.Kind.DESTINATION -> if (n <= 1) Txt.s(Res.string.search_add_as_destination)
                else Txt.s(Res.string.search_add_as_new_destination) + extraText(extra, speedMps)
            WaypointInsertion.Kind.VIA_ON_LINE -> Txt.s(Res.string.search_add_as_stop_on_route, d.index + 1)
            WaypointInsertion.Kind.VIA_BEST_FIT -> Txt.s(Res.string.search_add_as_stop_best, d.index + 1) + extraText(extra, speedMps)
        }
        return Preview(d, text, extra)
    }

    /** " · about +0.9 km, +15 min" */
    fun extraText(m: Double?, speedMps: Double?): String {
        if (m == null) return ""
        val dist = if (m < 1000) "+${(m / 50).roundToInt().coerceAtLeast(1) * 50} m" else "+" + Format.distance(m)
        val time = speedMps?.takeIf { it > 0.1 }?.let { v ->
            val min = (m / v / 60).roundToInt()
            if (min < 1) null else ", +" + Format.duration(min * 60.0)
        } ?: ""
        return " · " + Txt.s(Res.string.search_add_about, dist + time)
    }

    /** Subtitle for a place that already is a route point. */
    fun existingRole(index: Int, count: Int): String = when {
        index == 0 -> Txt.s(Res.string.search_is_start)
        index == count - 1 -> Txt.s(Res.string.search_is_destination)
        else -> Txt.s(Res.string.search_is_stop, index + 1)
    }
}
