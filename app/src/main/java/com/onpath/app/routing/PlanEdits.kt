package com.onpath.app.routing

import com.onpath.app.data.Geo

/** Pure edits on the waypoint list (reverse, round trip, move); unit-tested. */
object PlanEdits {

    fun reverse(w: List<GeoPoint>): List<GeoPoint> = w.reversed()

    /**
     * Adds the start as the final destination (no-op if it already ends at the start, or already ends in a loop behind
     * stops on the way – [loopStart], owner 2026-11-03).
     */
    fun roundTrip(w: List<GeoPoint>, sameM: Double = 15.0): List<GeoPoint> {
        if (w.size < 2) return w
        return if (Geo.distance(w.first(), w.last()) <= sameM || loopStart(w, sameM) != null) w else w + w.first()
    }

    /** Moves the point at [from] to position [to] (index in the resulting list). */
    fun move(w: List<GeoPoint>, from: Int, to: Int): List<GeoPoint> {
        if (from !in w.indices) return w
        val out = w.toMutableList()
        val p = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), p)
        return out
    }

    /**
     * True when the route ends in a loop: the last point is back at the loop's start ([loopStart]) – the classic round
     * trip (last within [sameM] of the first, ≥ 3 points) or one with stops on the way before it (owner 2026-11-03).
     */
    fun isRoundTrip(w: List<GeoPoint>, sameM: Double = 15.0): Boolean = loopStart(w, sameM) != null

    /**
     * Index of the loop's first point (①) when the route ends in a loop, else null. 0 = a classic round trip (≥ 3
     * points, the last within [sameM] of the first). > 0 = **stops on the way** before the loop (owner 2026-11-03: a
     * point set while being led to a round trip – the bakery – is a stop on the way; the loop 1 → 2 → 3 → 1 stays):
     * the earliest point i ≥ 1 the last point is back at, with at least 3 points in the loop (i, i+1, i+2 before the
     * closing point) – an out-and-back A → B → C → B stays a plain route. The plan stays a plain list of points: the
     * stored format (saved routes, GPX, browser sync) is unchanged and an old plan reads as before.
     */
    fun loopStart(w: List<GeoPoint>, sameM: Double = 15.0): Int? {
        if (w.size < 3) return null
        val last = w.last()
        if (Geo.distance(w.first(), last) <= sameM) return 0
        for (i in 1..w.lastIndex - 3) if (Geo.distance(w[i], last) <= sameM) return i
        return null
    }

    /**
     * "Start here" on planned point [index] – the map's point menu and the search card's "As start" (owner):
     * a middle point drops the points before it ([startAt]), a round trip rotates the loop ([rotateLoop]) – only the
     * loop: stops on the way before it stay (a loop point other than ①); a stop on the way or ① itself drops the points
     * before it like a middle point; the destination (not in the point menu) moves to the front. The start itself:
     * unchanged.
     */
    fun startHere(w: List<GeoPoint>, index: Int): List<GeoPoint> {
        if (index !in w.indices || index == 0) return w
        val loop = loopStart(w)
        return when {
            loop != null && index > loop -> rotateLoop(w, index)
            index < w.lastIndex -> startAt(w, index)
            else -> move(w, index, 0)
        }
    }

    /** "Start here": drop the points before [index]; the rest of the route stays as it is. */
    fun startAt(w: List<GeoPoint>, index: Int): List<GeoPoint> =
        if (index <= 0 || index >= w.lastIndex) w else w.subList(index, w.size).toList()

    /**
     * Round trip "Start & finish here": rotate the loop so it starts and ends at [index] – same points,
     * same order, same loop. The closing point is the new ① again. Stops on the way before the loop ([loopStart] > 0)
     * stay where they are (owner 2026-11-03: a round trip taken over from someone else, started at another point).
     */
    fun rotateLoop(w: List<GeoPoint>, index: Int): List<GeoPoint> {
        val start = loopStart(w) ?: return w
        if (index <= start || index >= w.lastIndex) return w
        val loop = w.subList(start, w.lastIndex)
        val k = index - start
        val rotated = loop.drop(k) + loop.take(k)
        return w.take(start) + rotated + rotated.first()
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
