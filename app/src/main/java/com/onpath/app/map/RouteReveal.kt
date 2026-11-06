package com.onpath.app.map

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * The route draws itself (owner 2026-11-06, polish 2): a NEW route for the plan runs from start to goal in
 * [DURATION_MS], then a short haptic. The line is revealed by distance – the source gets the first part of the legs
 * each frame (MapLibre Android 13.7 has no line-trim-offset; progressive coordinates work on every version and the
 * casing, the dashed straight legs and the direction arrows follow by themselves). Pure, unit-tested (RouteRevealTest).
 */
object RouteReveal {
    const val DURATION_MS = 500L
    /** Longer lines are drawn at once: ~30 frames × this many points stay cheap on the main thread. */
    const val MAX_POINTS = 4_000

    /**
     * Whether this route draws itself: only a route that is new to the map ([sameRoute] false – not the same one shown
     * again after Navigate or a redraw), not the first drawing after the map (re)loaded ([firstDraw] – a restored plan
     * is not news), only in Plan (a re-route while navigating just appears), not too long, and only with animations on
     * (the phone's "remove animations" / animator scale 0).
     */
    fun shouldAnimate(firstDraw: Boolean, sameRoute: Boolean, planMode: Boolean, pointCount: Int, animationsOn: Boolean): Boolean =
        !firstDraw && !sameRoute && planMode && pointCount in 2..MAX_POINTS && animationsOn

    /** Slows down towards the goal (decelerate): fraction of the line shown at time share [t] 0..1. */
    fun ease(t: Float): Float { val c = t.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) }

    /** The legs measured once; [cut] gives the part up to a share of the whole length, frame by frame. */
    class Line(private val legs: List<List<GeoPoint>>) {
        /** Cumulative metres per leg point, continuing across legs. */
        private val cum: List<DoubleArray>
        val total: Double

        init {
            var run = 0.0
            cum = legs.map { pts ->
                DoubleArray(pts.size) { i ->
                    if (i > 0) run += Geo.distance(pts[i - 1], pts[i])
                    run
                }
            }
            total = run
        }

        /**
         * The legs up to [fraction] of the length: whole legs before, the cut leg ending in a point between its two
         * samples, nothing after. A leg with fewer than 2 points left is dropped. fraction ≥ 1 = all legs as they are.
         */
        fun cut(fraction: Double): List<List<GeoPoint>> {
            if (fraction >= 1.0 || total <= 0.0) return legs
            val at = fraction.coerceAtLeast(0.0) * total
            val out = ArrayList<List<GeoPoint>>()
            for ((k, pts) in legs.withIndex()) {
                val c = cum[k]
                if (pts.size < 2) continue
                if (c.last() <= at) { out += pts; continue }
                if (c.first() >= at) break
                // the first sample beyond [at]
                var hi = 1
                while (hi < c.size && c[hi] <= at) hi++
                val a = pts[hi - 1]; val b = pts[hi]
                val t = ((at - c[hi - 1]) / (c[hi] - c[hi - 1]).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
                val end = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                out += pts.subList(0, hi) + end
                break
            }
            return out
        }
    }
}
