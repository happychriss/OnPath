package com.onpath.app.routing

import com.onpath.app.data.Geo

/** Pure edits on the waypoint list (reverse, round trip, move); unit-tested. */
object PlanEdits {

    fun reverse(w: List<GeoPoint>): List<GeoPoint> = w.reversed()

    /** Adds the start as the final destination (no-op if it already ends at the start). */
    fun roundTrip(w: List<GeoPoint>, sameM: Double = 15.0): List<GeoPoint> {
        if (w.size < 2) return w
        return if (Geo.distance(w.first(), w.last()) <= sameM) w else w + w.first()
    }

    /** Moves the point at [from] to position [to] (index in the resulting list). */
    fun move(w: List<GeoPoint>, from: Int, to: Int): List<GeoPoint> {
        if (from !in w.indices) return w
        val out = w.toMutableList()
        val p = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), p)
        return out
    }

    fun replace(w: List<GeoPoint>, index: Int, p: GeoPoint): List<GeoPoint> =
        if (index !in w.indices) w else w.toMutableList().also { it[index] = p }

    /**
     * Best stop position for point [p] that must NOT become the destination:
     * the leg with the cheapest straight-line detour; returns the insert index (1..size-1).
     */
    fun bestViaIndex(w: List<GeoPoint>, p: GeoPoint): Int {
        if (w.size < 2) return w.size
        var best = 1
        var bestCost = Double.MAX_VALUE
        for (i in 0 until w.size - 1) {
            val cost = Geo.distance(w[i], p) + Geo.distance(p, w[i + 1]) - Geo.distance(w[i], w[i + 1])
            if (cost < bestCost) { bestCost = cost; best = i + 1 }
        }
        return best
    }
}
