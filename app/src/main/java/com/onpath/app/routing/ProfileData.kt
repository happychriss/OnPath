package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * The elevation profile's numbers (pure; the app's `ui/ElevationProfile` draws them, the web planner gets them through
 * [PlanResult] – the same curve in both): the points of a line that have an elevation, at their distance along the
 * WHOLE line ([Geo.cumulative] over every point, with or without elevation).
 *
 * [dist] / [ele] per kept point, [idx] = its index in the line (the cursor's map marker), [min] / [max] elevation,
 * [total] = the whole line's length.
 */
class ProfileData(val dist: DoubleArray, val ele: DoubleArray, val idx: IntArray, val min: Double, val max: Double, val total: Double) {
    /** Gradient class per piece between two kept points ([RouteSummary.steepness], 100 m window) – the coloured line. */
    fun steepness(): List<RouteSummary.Steepness> = RouteSummary.steepness(dist, ele)

    companion object {
        /** Keeps only points with elevation; null if there is not enough data (fewer than 2 such points, no length). */
        fun of(points: List<GeoPoint>): ProfileData? {
            if (points.size < 2) return null
            val cum = Geo.cumulative(points)
            val keep = points.indices.filter { points[it].ele != null }
            if (keep.size < 2 || cum.last() <= 0) return null
            val d = DoubleArray(keep.size) { cum[keep[it]] }
            val e = DoubleArray(keep.size) { points[keep[it]].ele!! }
            return ProfileData(d, e, keep.toIntArray(), e.min(), e.max(), cum.last())
        }
    }
}
