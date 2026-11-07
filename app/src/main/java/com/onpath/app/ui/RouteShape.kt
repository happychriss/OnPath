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

    fun of(segments: List<List<GeoPoint>>, w: Float, h: Float, pad: Float, maxPoints: Int = MAX_POINTS,
           corner: Float = 0f): Shape? {
        val segs = segments.filter { it.isNotEmpty() }
        val all = segs.flatten()
        if (all.size < MIN_POINTS) return null
        var s = 90.0; var n = -90.0; var west = 180.0; var e = -180.0
        for (p in all) { s = min(s, p.lat); n = max(n, p.lat); west = min(west, p.lon); e = max(e, p.lon) }
        if (n - s < MIN_EXTENT_DEG && e - west < MIN_EXTENT_DEG) return null
        val box = Box(s, n, west, e)
        val stride = max(1, ceil(all.size / maxPoints.toDouble()).toInt())
        val full = fit(segs, all, box, stride, pad, pad, w - pad, h - pad)
        if (corner <= 0f || !hits(full, w - corner, h - corner)) return full
        // the drawing reaches into the reserved corner (the ⤢ badge, owner 2026-11-07): fit it beside or above the
        // corner instead – whichever keeps it larger; no point is then drawn under the badge
        val beside = fit(segs, all, box, stride, pad, pad, w - max(pad, corner), h - pad)
        val above = fit(segs, all, box, stride, pad, pad, w - pad, h - max(pad, corner))
        return if (spanOf(beside) >= spanOf(above)) beside else above
    }

    /**
     * Keep-out size of the ⤢ badge's corner in a preview tile: the badge's part inside the tile [inside] plus the start /
     * goal dot's ring [dot] – a fit with this [of]'s `corner` draws nothing under the badge.
     */
    fun cornerFor(inside: Float, dot: Float): Float = inside + dot

    private class Box(val s: Double, val n: Double, val west: Double, val e: Double)

    /** [all] fitted into the area [left]..[right] × [top]..[bottom], the aspect kept, centred in it. */
    private fun fit(segs: List<List<GeoPoint>>, all: List<GeoPoint>, b: Box, stride: Int,
                    left: Float, top: Float, right: Float, bottom: Float): Shape {
        val k = cos(Math.toRadians((b.s + b.n) / 2))
        val ex = (b.e - b.west) * k
        val ey = b.n - b.s
        val room = (right - left).coerceAtLeast(1f).toDouble() to (bottom - top).coerceAtLeast(1f).toDouble()
        val scale = min(if (ex > 0) room.first / ex else Double.MAX_VALUE, if (ey > 0) room.second / ey else Double.MAX_VALUE)
        // centred: the free room split on both sides
        val ox = left + (room.first - ex * scale) / 2
        val oy = top + (room.second - ey * scale) / 2
        fun x(p: GeoPoint) = (ox + (p.lon - b.west) * k * scale).toFloat()
        fun y(p: GeoPoint) = (oy + (b.n - p.lat) * scale).toFloat()
        val lines = segs.mapNotNull { seg ->
            val kept = seg.filterIndexed { i, _ -> i % stride == 0 || i == seg.lastIndex }
            if (kept.size < 2) null else FloatArray(kept.size * 2) { i -> if (i % 2 == 0) x(kept[i / 2]) else y(kept[i / 2]) }
        }
        val first = all.first(); val last = all.last()
        return Shape(lines, x(first), y(first), x(last), y(last))
    }

    /** True when a drawn point, the start or the goal lies right of [x0] and below [y0] (the badge's corner). */
    fun hits(shape: Shape, x0: Float, y0: Float): Boolean {
        if (shape.startX >= x0 && shape.startY >= y0) return true
        if (shape.endX >= x0 && shape.endY >= y0) return true
        for (l in shape.lines) {
            var i = 0
            while (i < l.size) { if (l[i] >= x0 && l[i + 1] >= y0) return true; i += 2 }
        }
        return false
    }

    private fun spanOf(shape: Shape): Float {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (l in shape.lines) {
            var i = 0
            while (i < l.size) { x0 = min(x0, l[i]); x1 = max(x1, l[i]); y0 = min(y0, l[i + 1]); y1 = max(y1, l[i + 1]); i += 2 }
        }
        return max(x1 - x0, y1 - y0)
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
