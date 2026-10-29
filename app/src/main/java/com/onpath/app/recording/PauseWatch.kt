package com.onpath.app.recording

import com.onpath.app.data.Geo

/**
 * While a tour is paused (owner): GPS keeps running slowly and this notices when you walk on
 * without resuming – "Pause pressed, forgot, walked off". The first good fix of the pause is the anchor; a good fix
 * more than [MOVE_M] away from it asks once (vibration + notification with Resume). Never resumes by itself.
 * Pure, unit-tested.
 */
class PauseWatch {
    companion object {
        /** GPS interval while paused (slower, never off). */
        const val GPS_MS = 10_000L
        /** Walked this far from where the pause started → remind. */
        const val MOVE_M = 150.0
        /** Fixes worse than this are ignored (a jump must not wake the walker). */
        const val MAX_ACCURACY_M = 30.0
    }

    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    var warned = false; private set

    fun reset() { anchorLat = Double.NaN; anchorLon = Double.NaN; warned = false }

    /** A fix during the pause; true exactly once when the walker has moved away. */
    fun onFix(lat: Double, lon: Double, accuracyM: Double): Boolean {
        if (warned || accuracyM.isNaN() || accuracyM > MAX_ACCURACY_M) return false
        if (anchorLat.isNaN()) { anchorLat = lat; anchorLon = lon; return false }
        if (Geo.distance(anchorLat, anchorLon, lat, lon) <= MOVE_M) return false
        warned = true
        return true
    }
}
