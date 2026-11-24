package com.onpath.app.recording

import android.location.Location
import android.os.SystemClock
import com.onpath.app.navigation.GoalSide
import com.onpath.app.navigation.OffRouteSay
import com.onpath.app.routing.GeoPoint

/**
 * The last GPS fix for the voice (owner field ride 2026-11-07): how old it was when it came ([ageMs]), where it is
 * ([here]) and the heading ridden over the last metres ([heading], [GoalSide.heading]; null while standing) – set on
 * every fix before it is used. [off] decides the side and metres of the off-route sentence (owner 2026-11-21).
 */
class VoiceFix {
    var ageMs = 0L; private set
    var here: GeoPoint? = null; private set
    var heading: Double? = null; private set
    val off = OffRouteSay()

    /** Fix [loc] at [here]; [lastTrack] = the track points before it (newest last). */
    fun onFix(loc: Location, here: GeoPoint, lastTrack: List<GeoPoint>) {
        val fixNs = loc.elapsedRealtimeNanos
        ageMs = if (fixNs > 0) ((SystemClock.elapsedRealtimeNanos() - fixNs) / 1_000_000L).coerceAtLeast(0) else 0
        this.here = here
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
        heading = GoalSide.heading(lastTrack, here, speed)
        off.onFix(here, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            if (loc.hasBearing()) loc.bearing.toDouble() else null, speed, SystemClock.elapsedRealtime(), ageMs)
    }

    /** The fix could not be read: nothing is known about it. */
    fun clear() { ageMs = 0; heading = null; off.reset() }
}
