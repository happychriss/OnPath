package com.onpath.app.search

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * Proposes a short route name from where it starts and ends ("Algund → Meran", "Round trip from Algund",
 * "Algund → Hochmuth (via Leiter Alm)"), offline, from the search index's places (pure, unit-tested).
 *
 * Label of an end point, best first:
 *  1. the name of a stop added from a place (pinned POI, search result),
 *  2. a hut / peak / pass / castle / waterfall within [LANDMARK_M],
 *  3. an inn or café (often an Alm) within [INN_M] when no village or town is within 1 km,
 *  4. a town / village / hamlet / farm close by (radius by class, nearest relative to its radius wins),
 *  5. "near X": the nearest town or village within [NEAR_M].
 * Returns null when nothing fits – the caller then keeps its old default name.
 */
object RouteNamer {
    const val MAX_LEN = 40
    /** End within this distance of the start: a round trip. */
    const val LOOP_M = 200.0
    const val LANDMARK_M = 150.0
    const val INN_M = 100.0
    const val NEAR_M = 8000.0

    private val LANDMARKS = setOf("hut", "bivouac", "peak", "saddle", "castle", "waterfall")
    private val INNS = setOf("restaurant", "cafe", "shelter")
    private val PLACE_RADIUS = mapOf(
        "city" to 3000.0, "town" to 3000.0, "village" to 2000.0, "hamlet" to 1000.0,
        "isolated_dwelling" to 300.0, "locality" to 300.0,
    )
    private val NEAR_CLASSES = setOf("city", "town", "village")

    /**
     * [line]: the route or track (at least start and end); [places]: offline index places (may be empty);
     * [startName] / [endName]: names of the first / last stop when they came from a place; [viaNames]: names of
     * middle stops that came from a place, in route order.
     */
    fun name(line: List<GeoPoint>, places: List<Place>, startName: String? = null, endName: String? = null,
             viaNames: List<String> = emptyList()): String? {
        if (line.size < 2) return null
        val start = line.first(); val end = line.last()
        val near = candidates(line, places)
        val s = startName?.trim()?.takeIf { it.isNotEmpty() } ?: label(start, near)
        val e = endName?.trim()?.takeIf { it.isNotEmpty() } ?: label(end, near)
        val via = viaNames.map { it.trim() }.firstOrNull { it.isNotEmpty() && it != s && it != e }
        val loop = Geo.distance(start, end) <= LOOP_M
        if (loop || (s != null && s == e)) {
            val top = via ?: farthestLandmark(line, near)?.takeIf { it != s }
            if (s == null) return top?.let { fit("Round trip to $it") }
            if (!loop && top != null) "$s – $top – $s".takeIf { it.length <= MAX_LEN }?.let { return it }
            return fit("Round trip from $s")
        }
        return when {
            s != null && e != null -> {
                val base = "$s → $e"
                via?.let { "$base (via $it)" }?.takeIf { it.length <= MAX_LEN } ?: fit(base)
            }
            s != null -> fit("From $s")
            e != null -> fit("To $e")
            else -> null
        }
    }

    /** Named places in the route's box plus [NEAR_M] (keeps the per-point scans small). */
    private fun candidates(line: List<GeoPoint>, places: List<Place>): List<Place> {
        val b = Geo.bounds(line) ?: return emptyList()
        val dLat = NEAR_M / 111_000.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians(((b[0] + b[2]) / 2).coerceIn(-80.0, 80.0)))
        return places.filter {
            !it.name.isNullOrBlank() && it.lat in (b[0] - dLat)..(b[2] + dLat) && it.lon in (b[1] - dLon)..(b[3] + dLon)
        }
    }

    /** Label of one end point (rules 2–5 above), or null. */
    fun label(p: GeoPoint, places: List<Place>): String? {
        var landmark: Place? = null; var landmarkD = LANDMARK_M
        var place: Place? = null; var placeScore = 1.0
        var inn: Place? = null; var innD = INN_M
        var nearest: Place? = null; var nearestD = NEAR_M
        var villageWithin1km = false
        for (c in places) {
            if (c.name.isNullOrBlank()) continue
            val d = Geo.distance(p.lat, p.lon, c.lat, c.lon)
            if (d > NEAR_M) continue
            when {
                c.kind in LANDMARKS -> if (d <= landmarkD) { landmark = c; landmarkD = d }
                c.kind in INNS -> if (d <= innD) { inn = c; innD = d }
                c.kind == "place" -> {
                    val r = PLACE_RADIUS[c.sub] ?: continue
                    if (d / r <= placeScore) { place = c; placeScore = d / r }
                    if (c.sub in NEAR_CLASSES) {
                        if (d <= 1000.0) villageWithin1km = true
                        if (d <= nearestD) { nearest = c; nearestD = d }
                    }
                }
            }
        }
        return landmark?.name ?: inn?.takeIf { !villageWithin1km }?.name ?: place?.name ?: nearest?.name?.let { "near $it" }
    }

    /** Hut / peak / pass near the point of the line farthest from its start (the "top" of a round trip). */
    private fun farthestLandmark(line: List<GeoPoint>, places: List<Place>): String? {
        val start = line.first()
        val top = line.maxByOrNull { Geo.distance(start, it) } ?: return null
        if (Geo.distance(start, top) < 500.0) return null
        return places.filter { it.kind in LANDMARKS }
            .map { it to Geo.distance(top.lat, top.lon, it.lat, it.lon) }
            .filter { it.second <= 300.0 }.minByOrNull { it.second }?.first?.name
    }

    /** Over-long names (rare) are cut with an ellipsis so lists stay readable. */
    private fun fit(s: String): String = if (s.length <= MAX_LEN + 15) s else s.take(MAX_LEN + 14).trimEnd() + "…"
}
