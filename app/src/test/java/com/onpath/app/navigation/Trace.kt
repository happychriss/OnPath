package com.onpath.app.navigation

import com.onpath.app.routing.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/** Simulated lines and GPS traces in local metres around Algund (x = east, y = north). */
object Trace {
    private const val LAT0 = 46.68
    private const val LON0 = 11.13
    private val kx = cos(Math.toRadians(LAT0)) * 111_320.0
    private const val KY = 110_540.0

    fun pt(x: Double, y: Double, ele: Double? = null) = GeoPoint(LAT0 + y / KY, LON0 + x / kx, ele)

    /** Polyline through the corner points, densified every [stepM]. */
    fun line(vararg corners: Pair<Double, Double>, stepM: Double = 10.0): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (i in 0 until corners.size - 1) {
            val (x0, y0) = corners[i]; val (x1, y1) = corners[i + 1]
            val n = maxOf(1, (hypot(x1 - x0, y1 - y0) / stepM).toInt())
            for (k in 0 until n) out += pt(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n)
        }
        out += pt(corners.last().first, corners.last().second)
        return out
    }

    /** Walk through the corner points at [speed] m/s, one fix per second, starting at [t0Ms]. */
    fun walk(vararg corners: Pair<Double, Double>, speed: Double = 1.3, acc: Double = 8.0, t0Ms: Long = 0): List<OffRouteDetector.Fix> {
        val out = ArrayList<OffRouteDetector.Fix>()
        var t = t0Ms
        for (i in 0 until corners.size - 1) {
            val (x0, y0) = corners[i]; val (x1, y1) = corners[i + 1]
            val n = maxOf(1, (hypot(x1 - x0, y1 - y0) / speed).toInt())
            for (k in 0 until n) {
                val p = pt(x0 + (x1 - x0) * k / n, y0 + (y1 - y0) * k / n)
                out += OffRouteDetector.Fix(p.lat, p.lon, acc, t); t += 1000
            }
        }
        val e = pt(corners.last().first, corners.last().second)
        out += OffRouteDetector.Fix(e.lat, e.lon, acc, t)
        return out
    }

    fun fix(x: Double, y: Double, tMs: Long, acc: Double = 8.0): OffRouteDetector.Fix {
        val p = pt(x, y); return OffRouteDetector.Fix(p.lat, p.lon, acc, tMs)
    }
}
