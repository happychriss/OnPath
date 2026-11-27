package com.onpath.app.recording

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * The position dot while the phone lies still (owner's Pixel 2026-11-27 23:32, Navigate, indoors: „Warum wandert mein
 * Handy hin und her? Das liegt still“). The dot took every GPS fix – indoors 25–50 m apart – while the track already
 * called them drift ([PointFilter]). Same rule for the dot: the motion sensor says still and the fix lies within twice
 * its accuracy of the last track point → the dot stays on that point. Farther is a real move and shows at once; without
 * the sensor's word (or no track point yet) nothing changes. Pure.
 */
object StillDot {
    /** The point the dot stays on, or null = show the fix itself. */
    fun hold(lastTrack: GeoPoint?, lat: Double, lon: Double, accuracyM: Double?, sensorStill: Boolean): GeoPoint? {
        if (!sensorStill || lastTrack == null || accuracyM == null || accuracyM <= 0) return null
        return lastTrack.takeIf { Geo.distance(it.lat, it.lon, lat, lon) < 2 * accuracyM }
    }
}
