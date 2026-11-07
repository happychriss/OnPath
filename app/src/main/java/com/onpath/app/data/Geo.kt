package com.onpath.app.data

import com.onpath.app.routing.GeoPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small geo helpers (pure Kotlin, unit-testable) – the ONE copy of the app's geo math (code audit 2026-11-07: point-to-line
 * distance, flat projections and bearings were pasted into ten files with three different metre-per-degree values).
 * Everything works on one sphere ([R]): the flat frame ([Flat]) uses the same metres per degree as [distance], so a
 * projected distance and a great-circle distance of the same short span agree.
 */
object Geo {
    /** Earth radius of every distance in the app (metres). */
    const val R = 6_371_000.0

    /** Metres per degree of latitude on [R] – also per degree of longitude at the equator (≈ 111 195 m). */
    const val M_PER_DEG = R * Math.PI / 180.0

    /** Metres per degree of longitude at [lat]. */
    fun mPerDegLon(lat: Double): Double = M_PER_DEG * cos(Math.toRadians(lat))

    /** Degrees of latitude for [m] metres. */
    fun degLat(m: Double): Double = m / M_PER_DEG

    /** Degrees of longitude for [m] metres at [lat] (never more than at ~89.4°, so boxes near a pole stay finite). */
    fun degLon(m: Double, lat: Double): Double = m / (M_PER_DEG * cos(Math.toRadians(lat)).coerceAtLeast(0.01))

    /**
     * A local flat (equirectangular) frame at latitude [lat]: x east, y north, metres. Accurate to well under a metre over
     * the few kilometres where the app compares points (snapping, off-route, matching on a line). Build it once per
     * question and use it for every segment.
     */
    class Flat(lat: Double) {
        val kx: Double = mPerDegLon(lat)
        val ky: Double = M_PER_DEG

        /**
         * Where on segment [a]–[b] the point nearest to [p] lies: 0 = at a, 1 = at b (clamped). A segment of length 0
         * gives 0.
         */
        fun t(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            val ax = (a.lon - p.lon) * kx; val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - a.lon) * kx; val dy = (b.lat - a.lat) * ky
            val len2 = dx * dx + dy * dy
            return if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        }

        /** Metres from [p] to the point at [t] of segment [a]–[b]. */
        fun distanceAt(p: GeoPoint, a: GeoPoint, b: GeoPoint, t: Double): Double {
            val x = ((a.lon - p.lon) + t * (b.lon - a.lon)) * kx
            val y = ((a.lat - p.lat) + t * (b.lat - a.lat)) * ky
            return sqrt(x * x + y * y)
        }

        /** Metres from [p] to segment [a]–[b]. */
        fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double = distanceAt(p, a, b, t(p, a, b))

        /** Metres between two points in this frame. */
        fun distance(a: GeoPoint, b: GeoPoint): Double {
            val x = (b.lon - a.lon) * kx; val y = (b.lat - a.lat) * ky
            return sqrt(x * x + y * y)
        }
    }

    /** The point at [t] of segment [a]–[b] (elevation interpolated when both ends have one). */
    fun along(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint = when (t) {
        0.0 -> a
        1.0 -> b
        else -> GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon),
            if (a.ele != null && b.ele != null) a.ele + t * (b.ele - a.ele) else null)
    }

    /** The point of segment [a]–[b] nearest to [p] (flat frame at [p]). */
    fun closestOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): GeoPoint = along(a, b, Flat(p.lat).t(p, a, b))

    /** Metres from [p] to segment [a]–[b] (flat frame at [p]). */
    fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double = Flat(p.lat).distanceToSegment(p, a, b)

    /** The point of the polyline nearest to [p] (flat frame at [p]); a one-point line is that point. */
    fun closestOnLine(p: GeoPoint, line: List<GeoPoint>): GeoPoint {
        if (line.size == 1) return line[0]
        val f = Flat(p.lat)
        var best = line[0]; var bestD = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val t = f.t(p, line[i - 1], line[i])
            val d = f.distanceAt(p, line[i - 1], line[i], t)
            if (d < bestD) { bestD = d; best = along(line[i - 1], line[i], t) }
        }
        return best
    }

    /** Compass bearing [a] → [b] in degrees [0, 360) (great circle). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat); val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return normDeg(Math.toDegrees(atan2(y, x)))
    }

    /** Signed turn from heading [from] to heading [to], in (-180, 180] (> 0 = right). */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    /** A heading in [0, 360). */
    fun normDeg(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: GeoPoint, b: GeoPoint) = distance(a.lat, a.lon, b.lat, b.lon)

    /**
     * Quick flat distance (equirectangular, metres) for nearby points – where many distances are compared (snapping,
     * BRouter's matched spots). The one copy (review Arch 8: it was pasted into WaypointSnap and BRouterEngine).
     */
    fun fastDistance(a: GeoPoint, b: GeoPoint): Double = Flat((a.lat + b.lat) / 2.0).distance(a, b)

    fun length(points: List<GeoPoint>): Double {
        var d = 0.0
        for (i in 1 until points.size) d += distance(points[i - 1], points[i])
        return d
    }

    /** Cumulative distance per point (metres), same size as [points]. */
    fun cumulative(points: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /**
     * Ascent / descent of a planned line's elevations the way BRouter counts them (10 m hysteresis buffer) – the plan
     * sheet's numbers. Navigate's "remaining ascent" uses the same, so the number does not jump at Start (review routing
     * M1). [climb] stays for recorded GPS tracks.
     */
    fun routeClimb(eles: List<Double?>, filterM: Double = 10.0): Pair<Double, Double> {
        var up = 0.0; var down = 0.0; var bufUp = 0.0; var bufDown = 0.0
        var last: Double? = null
        for (e in eles) {
            if (e == null) continue
            val prev = last
            if (prev != null) {
                bufUp += e - prev; bufDown += prev - e
                if (bufUp > filterM) { up += bufUp - filterM; bufUp = filterM } else if (bufUp < 0.0) bufUp = 0.0
                if (bufDown > filterM) { down += bufDown - filterM; bufDown = filterM } else if (bufDown < 0.0) bufDown = 0.0
            }
            last = e
        }
        return up to down
    }

    /** Ascent/descent with a hysteresis threshold so GPS/DEM noise does not inflate totals. */
    fun climb(eles: List<Double?>, threshold: Double = 4.0): Pair<Double, Double> {
        var up = 0.0
        var down = 0.0
        var ref: Double? = null
        for (e in eles) {
            if (e == null) continue
            val r = ref
            if (r == null) { ref = e; continue }
            val diff = e - r
            if (diff >= threshold) { up += diff; ref = e } else if (diff <= -threshold) { down -= diff; ref = e }
        }
        return up to down
    }

    /**
     * Shortest distance (m) from point p to the polyline, using a local equirectangular
     * projection (accurate enough for off-route checks).
     */
    fun distanceToLine(lat: Double, lon: Double, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return distance(lat, lon, line[0].lat, line[0].lon)
        val p = GeoPoint(lat, lon)
        val f = Flat(lat)
        var best = Double.MAX_VALUE
        for (i in 1 until line.size) best = min(best, f.distanceToSegment(p, line[i - 1], line[i]))
        return best
    }

    /** Metres from [p] to the polyline (flat frame at [p]). */
    fun distanceToLine(p: GeoPoint, line: List<GeoPoint>): Double = distanceToLine(p.lat, p.lon, line)

    fun bounds(points: List<GeoPoint>): DoubleArray? {
        if (points.isEmpty()) return null
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in points) { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
        return doubleArrayOf(s, w, n, e)
    }
}
