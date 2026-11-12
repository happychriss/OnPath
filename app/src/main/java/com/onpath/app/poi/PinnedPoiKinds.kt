package com.onpath.app.poi

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place

/**
 * Which POI kind a route point pinned on a POI shows (owner 2026-11-10, variant A: the POI keeps its coloured symbol,
 * a ring in route blue around it, a small numbered badge top right – map/MapController `poiMarker`). Pure Kotlin.
 *
 * The plan stores only *that* a point is pinned (by value), not on what: the kind comes from the tap that pinned it
 * ([known], this app run) or else from the offline place index – a POI within [SAME_SPOT_M] of the point (a plan loaded
 * from My routes, a link, a backup). Nothing found → the plain numbered point, as before.
 */
object PinnedPoiKinds {
    /** A POI this close to a pinned point is the one it was pinned on (vector-tile positions are rounded ~1 m). */
    const val SAME_SPOT_M = 5.0

    /** Only kinds the map draws with their own symbol ([PoiKinds], sprite "poi-<kind>"). */
    fun isPoiKind(kind: String?): Boolean = kind != null && PoiKinds.all.any { it.key == kind }

    /** The keys [Place]s are looked up with in the index (`PlaceIndex.placesIn`). */
    val indexKinds: Set<String> get() = PoiKinds.all.map { it.key }.toSet()

    /** The nearest POI of [places] within [SAME_SPOT_M] of [p]; null when none. */
    fun lookup(p: GeoPoint, places: List<Place>): String? = places
        .filter { isPoiKind(it.kind) }
        .map { it to Geo.distance(p.lat, p.lon, it.lat, it.lon) }
        .filter { it.second <= SAME_SPOT_M }
        .minByOrNull { it.second }?.first?.kind

    /**
     * Kind by point index for the [pinned] indices of [points]: [known] first, else [found] (index lookups); points that
     * are not pinned never get one (a held point near a café stays a plain point).
     */
    fun byIndex(points: List<GeoPoint>, pinned: Set<Int>, known: Map<GeoPoint, String>,
                found: Map<GeoPoint, String> = emptyMap()): Map<Int, String> =
        pinned.filter { it in points.indices }.mapNotNull { i ->
            val p = points[i]
            (known[p]?.takeIf { isPoiKind(it) } ?: found[p]?.takeIf { isPoiKind(it) })?.let { i to it }
        }.toMap()
}
