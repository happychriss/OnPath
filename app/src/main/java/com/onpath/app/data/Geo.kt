package com.onpath.app.data

import com.onpath.app.routing.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geo helpers (pure Kotlin, unit-testable). */
object Geo {
    private const val R = 6_371_000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: GeoPoint, b: GeoPoint) = distance(a.lat, a.lon, b.lat, b.lon)

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
        val kx = cos(Math.toRadians(lat)) * 111_320.0
        val ky = 110_540.0
        var best = Double.MAX_VALUE
        for (i in 1 until line.size) {
            val ax = (line[i - 1].lon - lon) * kx; val ay = (line[i - 1].lat - lat) * ky
            val bx = (line[i].lon - lon) * kx; val by = (line[i].lat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best
    }

    fun bounds(points: List<GeoPoint>): DoubleArray? {
        if (points.isEmpty()) return null
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in points) { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
        return doubleArrayOf(s, w, n, e)
    }
}

/**
 * Numbers and units as the user reads them. Decimals follow the text language: "12.5 km" in English, "12,5 km" in
 * German. Units stay the same in both (km, m, h, min – as on Alpine signposts:
 * "1 h 05 min"). Machine formats (GPX, file names, logs) never use these.
 */
object Format {
    /** Locale of shown numbers: the phone's German variant when the texts are German, else US English. */
    fun locale(): java.util.Locale =
        if (com.onpath.strings.Txt.language() == "de") java.util.Locale.getDefault() else java.util.Locale.US

    /** A decimal number for the user: number(12.46, 1) = "12.5" / "12,5". */
    fun number(v: Double, decimals: Int): String = String.format(locale(), "%.${decimals}f", v)

    fun distance(m: Double): String = when {
        m < 1000 -> "${m.toInt()} m"
        m < 100_000 -> String.format(locale(), "%.1f km", m / 1000)
        else -> "${(m / 1000).toInt()} km"
    }

    fun duration(s: Double): String {
        val total = (s / 60).toLong()
        val h = total / 60
        val m = total % 60
        return if (h > 0) "${h} h ${m.toString().padStart(2, '0')} min" else "$m min"
    }

    /** Short time to go for the Navigate strip (owner): "2:50 h", under an hour "25 min". */
    fun durationShort(s: Double): String {
        val total = (s / 60).toLong()
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')} h" else "$m min"
    }

    fun clock(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return "%d:%02d:%02d".format(h, m, sec)
    }

    fun meters(m: Double) = "${m.toInt()} m"
}
