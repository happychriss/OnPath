package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * The small route preview of My routes (owner 2026-11-07: "erst klein, und groß passend auf den Bildschirm gezoomt"):
 * the route's shape as a line on a paper tile, start dot + goal flag, no map tiles – instant and offline. Pure: points in,
 * tile coordinates out (unit-tested, RouteShapeTest).
 */
object RouteShape {
    /** Points drawn at most (a long recorded tour has thousands; a 52 dp tile needs ~200). */
    const val MAX_POINTS = 200

    /** Fewer distinct points than this, or all within [MIN_EXTENT_DEG] of each other: no shape (a quiet placeholder). */
    const val MIN_POINTS = 2
    /** ~1 m: a "line" smaller than this is one spot (a 0 m tour, a GPX with one point). */
    const val MIN_EXTENT_DEG = 0.00001

    /**
     * The shape fitted into a [w] × [h] tile with [pad] on every side, the aspect kept (lon scaled by cos(latitude)),
     * centred. [lines]: one FloatArray per segment, x/y interleaved; [start] / [end]: the first / last point.
     */
    class Shape(val lines: List<FloatArray>, val startX: Float, val startY: Float, val endX: Float, val endY: Float)

    fun of(segments: List<List<GeoPoint>>, w: Float, h: Float, pad: Float, maxPoints: Int = MAX_POINTS): Shape? {
        val segs = segments.filter { it.isNotEmpty() }
        val all = segs.flatten()
        if (all.size < MIN_POINTS) return null
        var s = 90.0; var n = -90.0; var west = 180.0; var e = -180.0
        for (p in all) { s = min(s, p.lat); n = max(n, p.lat); west = min(west, p.lon); e = max(e, p.lon) }
        if (n - s < MIN_EXTENT_DEG && e - west < MIN_EXTENT_DEG) return null
        val k = cos(Math.toRadians((s + n) / 2))
        val ex = (e - west) * k
        val ey = n - s
        val room = (w - 2 * pad).coerceAtLeast(1f).toDouble() to (h - 2 * pad).coerceAtLeast(1f).toDouble()
        val scale = min(if (ex > 0) room.first / ex else Double.MAX_VALUE, if (ey > 0) room.second / ey else Double.MAX_VALUE)
        // centred: the free room split on both sides
        val ox = pad + (room.first - ex * scale) / 2
        val oy = pad + (room.second - ey * scale) / 2
        fun x(p: GeoPoint) = (ox + (p.lon - west) * k * scale).toFloat()
        fun y(p: GeoPoint) = (oy + (n - p.lat) * scale).toFloat()
        val stride = max(1, ceil(all.size / maxPoints.toDouble()).toInt())
        val lines = segs.mapNotNull { seg ->
            val kept = seg.filterIndexed { i, _ -> i % stride == 0 || i == seg.lastIndex }
            if (kept.size < 2) null else FloatArray(kept.size * 2) { i -> if (i % 2 == 0) x(kept[i / 2]) else y(kept[i / 2]) }
        }
        val first = all.first(); val last = all.last()
        return Shape(lines, x(first), y(first), x(last), y(last))
    }

    /** Points drawn for [segments] (a check for the cap). */
    fun drawnPoints(shape: Shape): Int = shape.lines.sumOf { it.size / 2 }

    /**
     * The line's colour by kind (owner rule: plan blue, done green): a plan and an imported GPX (a route one follows) in
     * plan blue, a recorded tour in navigation green.
     */
    enum class Tint { PLAN, DONE }

    fun tint(kind: TourKind): Tint = if (kind == TourKind.RECORDED) Tint.DONE else Tint.PLAN
}

/**
 * The large overview's map region: the route's bounds, at least [MIN_SPAN_DEG] wide and high (a 50 m loop is not zoomed
 * to the street lamps). [south, west, north, east] or null without points. Pure, unit-tested.
 */
object OverviewRegion {
    /** ~300 m. */
    const val MIN_SPAN_DEG = 0.003

    fun of(points: List<GeoPoint>): DoubleArray? {
        val b = com.onpath.app.data.Geo.bounds(points) ?: return null
        val growLat = max(0.0, MIN_SPAN_DEG - (b[2] - b[0])) / 2
        val growLon = max(0.0, MIN_SPAN_DEG - (b[3] - b[1])) / 2
        return doubleArrayOf(b[0] - growLat, b[1] - growLon, b[2] + growLat, b[3] + growLon)
    }
}
