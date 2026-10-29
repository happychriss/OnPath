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

    /** True when the route ends where it starts (≥ 3 points, last within [sameM] of the first). */
    fun isRoundTrip(w: List<GeoPoint>, sameM: Double = 15.0): Boolean =
        w.size >= 3 && Geo.distance(w.first(), w.last()) <= sameM

    /**
     * "Start here" on planned point [index] – the map's point menu and the search card's "As start" (owner):
     * a middle point drops the points before it ([startAt]), a round trip rotates the loop ([rotateLoop]),
     * the destination (not in the point menu) moves to the front. The start itself: unchanged.
     */
    fun startHere(w: List<GeoPoint>, index: Int): List<GeoPoint> = when {
        index !in w.indices || index == 0 -> w
        isRoundTrip(w) -> rotateLoop(w, index)
        index < w.lastIndex -> startAt(w, index)
        else -> move(w, index, 0)
    }

    /** "Start here": drop the points before [index]; the rest of the route stays as it is. */
    fun startAt(w: List<GeoPoint>, index: Int): List<GeoPoint> =
        if (index <= 0 || index >= w.lastIndex) w else w.subList(index, w.size).toList()

    /**
     * Round trip "Start & finish here": rotate the loop so it starts and ends at [index] – same points,
     * same order, same loop. The closing point is the new start again.
     */
    fun rotateLoop(w: List<GeoPoint>, index: Int): List<GeoPoint> {
        if (!isRoundTrip(w) || index <= 0 || index >= w.lastIndex) return w
        val loop = w.dropLast(1)
        val rotated = loop.drop(index) + loop.take(index)
        return rotated + rotated.first()
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
