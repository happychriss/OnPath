package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * Decides when to ask "You have arrived" (pure, unit-tested). Rules on top of [RouteProgress.arrived]:
 * - The user must first have been [awayM] from the destination: a round trip starts at its end, and a stale
 *   last-known fix at the car park must not end the tour before the first step.
 * - Fixes worse than [maxAccuracyM] never trigger it (a cell-tower fix in a tunnel).
 * - Asked once; when the user declines and walks [awayM] away again, it can ask again on return.
 */
class ArrivalGate(
    private val awayM: Double = 150.0,
    private val maxAccuracyM: Double = 50.0,
) {
    private var away = false
    private var asked = false

    /** New followed line or no line: start over. */
    fun reset() { away = false; asked = false }

    /** True exactly when the arrival question should be shown now. */
    fun onFix(position: GeoPoint?, destination: GeoPoint?, remainingAlongM: Double?, accuracyM: Double?): Boolean {
        if (position == null || destination == null) return false
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val d = Geo.distance(position, destination)
        if (d > awayM) { away = true; asked = false; return false }
        if (!away || asked) return false
        if (!RouteProgress.arrived(position, destination, remainingAlongM)) return false
        asked = true
        return true
    }
}
