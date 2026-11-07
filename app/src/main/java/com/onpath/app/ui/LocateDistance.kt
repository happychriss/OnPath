package com.onpath.app.ui

import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.roundToLong

/**
 * Where am I, when the plan is far away (owner 2026-11-07, variant 3 of the "far position" page): in Plan, while my
 * position is outside the visible map, the locate button carries a small distance under its icon ("790 km") and a tap
 * frames the plan and me together. In view, without a position (Plan is quiet about GPS, [PlanGps]) and in Navigate:
 * no label, the tap does what it did. Pure.
 */
object LocateDistance {
    /** The visible map (south, west, north, east in degrees); west > east = across the date line. */
    data class View(val south: Double, val west: Double, val north: Double, val east: Double) {
        val centre: GeoPoint get() = GeoPoint((south + north) / 2,
            if (west <= east) (west + east) / 2 else ((west + east + 360) / 2).let { if (it > 180) it - 360 else it })

        companion object {
            /** From the map's camera key (MapScreen hintKey: south, west, north, east, …); null = unknown. */
            fun of(key: List<Double>?): View? = key?.takeIf { it.size >= 4 }?.let { View(it[0], it[1], it[2], it[3]) }
        }
    }

    fun inView(me: GeoPoint, v: View): Boolean =
        me.lat in v.south..v.north && (if (v.west <= v.east) me.lon in v.west..v.east else me.lon >= v.west || me.lon <= v.east)

    /** Metres from me to the middle of what the map shows; null = no label (not Plan, no position, I am in view). */
    fun metres(plan: Boolean, located: Boolean, me: GeoPoint?, view: View?): Double? {
        if (!plan || !located || me == null || view == null || inView(me, view)) return null
        return Geo.distance(me, view.centre)
    }

    /** "850 m" (to 10 m), "3,2 km", "790 km" – calm: no metre jitter under a kilometre. */
    fun text(m: Double): String = Format.distance(if (m < 1000) (m / 10).roundToLong() * 10.0 else m)

    /** The label under the locate icon; null = none. */
    fun label(plan: Boolean, located: Boolean, me: GeoPoint?, view: View?): String? =
        metres(plan, located, me, view)?.let(::text)

    /**
     * Throttle (the label must not tick with every GPS fix): a new position counts once it moved ≥ [MOVE_M] or ≥
     * [MOVE_SHARE] of the last shown distance. [last] = the position the label was made from.
     */
    fun moved(last: GeoPoint?, now: GeoPoint, lastDistanceM: Double?): Boolean {
        if (last == null) return true
        val d = Geo.distance(last, now)
        return d >= MOVE_M || (lastDistanceM != null && d >= lastDistanceM * MOVE_SHARE)
    }

    const val MOVE_M = 50.0
    const val MOVE_SHARE = 0.05

    enum class Tap {
        /** Plan + me together (I am off the map). */
        FRAME_BOTH,
        /** What locate did before ([PlanGps.tap]). */
        AS_BEFORE,
    }

    fun tap(labelShown: Boolean): Tap = if (labelShown) Tap.FRAME_BOTH else Tap.AS_BEFORE
}
