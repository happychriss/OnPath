package com.onpath.app.gpx

import com.onpath.app.routing.GeoPoint

/** One recorded/imported point. [timeMs] is UTC epoch millis, null if unknown. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMs: Long? = null,
) {
    fun toGeo() = GeoPoint(lat, lon, ele)
}

data class GpxWaypoint(val lat: Double, val lon: Double, val name: String?, val ele: Double? = null)

/** A line with one or more segments (gaps between segments are not drawn). */
data class GpxLine(val name: String?, val segments: List<List<TrackPoint>>, val isRoute: Boolean)

data class GpxData(
    val name: String?,
    val lines: List<GpxLine>,
    val waypoints: List<GpxWaypoint>,
    /** Human-readable notes about things that were skipped or repaired. */
    val warnings: List<String> = emptyList(),
) {
    val allSegments: List<List<TrackPoint>> get() = lines.flatMap { it.segments }.filter { it.isNotEmpty() }
    val pointCount: Int get() = allSegments.sumOf { it.size }
    val isEmpty: Boolean get() = pointCount == 0 && waypoints.isEmpty()
}
