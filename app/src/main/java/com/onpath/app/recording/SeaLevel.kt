package com.onpath.app.recording

import com.onpath.app.data.Geo

/**
 * Heights above sea level for the recorded track (owner's ride 2026-10-28: 81–135 m on a ride at 35–45 m). Android's
 * `Location.altitude` is above the WGS84 ellipsoid; near Düsseldorf the geoid is ~47 m above it, in the Alps ~48 m.
 * Rules (pure, unit-tested):
 * - the phone's own sea-level height (`mslAltitudeMeters`, when the GNSS chip gives it) wins;
 * - else ellipsoid height − geoid offset, the offset computed in the background (Android 14+ AltitudeConverter) and
 *   reused while within [OFFSET_VALID_M] of where it was computed (the geoid changes by < 1 m per 10 km);
 * - no offset known (yet / Android < 14 has no converter): the height as given (as before);
 * - the first [SETTLE_MS] after the first fix: no height (the GPS height settles – a false 40 m descent at the start).
 */
object SeaLevel {
    const val SETTLE_MS = 15_000L
    const val OFFSET_VALID_M = 10_000.0

    data class Offset(val lat: Double, val lon: Double, val metres: Double)

    fun height(ellipsoidM: Double?, mslM: Double?, offset: Offset?, lat: Double, lon: Double, sinceFirstFixMs: Long): Double? {
        if (ellipsoidM == null && mslM == null) return null
        if (sinceFirstFixMs < SETTLE_MS) return null
        if (mslM != null) return mslM
        val o = offset?.takeIf { Geo.distance(it.lat, it.lon, lat, lon) <= OFFSET_VALID_M }
        return if (o != null) ellipsoidM!! - o.metres else ellipsoidM
    }

    /** A new offset is needed here (none yet, or moved away from where it was computed). */
    fun needsOffset(offset: Offset?, lat: Double, lon: Double): Boolean =
        offset == null || Geo.distance(offset.lat, offset.lon, lat, lon) > OFFSET_VALID_M / 2
}
