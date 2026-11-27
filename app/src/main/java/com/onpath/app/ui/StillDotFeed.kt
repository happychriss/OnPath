package com.onpath.app.ui

import android.location.Location
import com.onpath.app.recording.RecState
import com.onpath.app.recording.StillDot
import com.onpath.app.routing.GeoPoint

/**
 * What the position dot is fed (MapScreen): the phone lying still by its motion sensor and the fix within twice its
 * accuracy of the last track point → that point ([StillDot], owner's Pixel 2026-11-27: the dot wandered indoors); else the
 * point on the route ([on], „Position auf der Route“), else the fix. Only the drawn dot – everything else uses the fix.
 */
internal object StillDotFeed {
    fun shown(loc: Location, on: GeoPoint?, rec: RecState?): Location {
        val held = rec?.takeIf { !it.paused }?.let { r ->
            StillDot.hold(r.track.lastOrNull()?.lastOrNull(), loc.latitude, loc.longitude,
                if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, r.sensorStill)
        }
        val p = held ?: on ?: return loc
        return Location(loc).apply { latitude = p.lat; longitude = p.lon }
    }
}
