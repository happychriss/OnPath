package com.onpath.app.search

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import com.onpath.strings.*
import kotlin.math.roundToInt

/**
 * What adding a place will do, said on the button before the tap ("Set as destination", "Add as stop 2", "Add as
 * new destination") – computed with the same [WaypointInsertion.decide] the map hold uses, so the button never
 * promises something else. [Preview.subtitle] is the short reason under it ("fits best · about +0.9 km").
 */
object AddPreview {

    /** Straight-line detour → typical path detour (paths are rarely straight). An estimate, shown with "about". */
    const val DETOUR_FACTOR = 1.3

    data class Preview(val decision: WaypointInsertion.Decision, val label: String, val subtitle: String, val extraM: Double?)

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
        val label = label(d, n)
        val text = when (d.kind) {
            WaypointInsertion.Kind.START -> ""
            WaypointInsertion.Kind.DESTINATION -> if (n <= 1) "" else extraText(extra, speedMps).removePrefix(" · ")
            WaypointInsertion.Kind.VIA_ON_LINE -> Txt.s(Res.string.search_add_on_route)
            WaypointInsertion.Kind.VIA_BEST_FIT -> Txt.s(Res.string.search_add_fits_best) + extraText(extra, speedMps)
        }
        return Preview(d, label, text, extra)
    }

    /** The button text for adding a point to a plan of [n] points ([d] from [WaypointInsertion.decide]). */
    fun label(d: WaypointInsertion.Decision, n: Int): String = when (d.kind) {
        WaypointInsertion.Kind.START -> Txt.s(Res.string.search_card_set_start)
        WaypointInsertion.Kind.DESTINATION -> if (n <= 1) Txt.s(Res.string.search_card_set_destination)
            else Txt.s(Res.string.search_card_new_destination)
        WaypointInsertion.Kind.VIA_ON_LINE, WaypointInsertion.Kind.VIA_BEST_FIT -> Txt.s(Res.string.search_card_add_stop, d.index + 1)
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
