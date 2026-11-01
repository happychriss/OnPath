package com.onpath.app.routing

import com.onpath.app.data.Geo

/**
 * Decides when to ask "You have arrived" (pure, unit-tested). Rules on top of [RouteProgress.arrived]:
 * - The user must first have been [awayM] from the destination: a round trip starts at its end, and a stale
 *   last-known fix at the car park must not end the tour before the first step.
 * - Fixes worse than [maxAccuracyM] never trigger it (a cell-tower fix in a tunnel).
 * - Asked once; when the user declines and walks [awayM] away again, it can ask again on return.
 * - Never by being near the goal alone (owner 2026-11-01, round trip and park loop: "Ziel erreicht" after 544 m / with
 *   54 m and a turn still to go): every point of the plan reached ([openStops] = 0) and the progress along the line in
 *   its last [END_M] (the progress, not a match – off the line the furthest progress counts).
 */
class ArrivalGate(
    private val awayM: Double = 150.0,
    private val maxAccuracyM: Double = 50.0,
) {
    companion object {
        /** The progress along the line must be within this of its end. */
        const val END_M = 50.0
    }

    private var away = false
    private var asked = false

    /** New followed line or no line: start over. */
    fun reset() { away = false; asked = false }

    /** True exactly when the arrival question should be shown now. */
    fun onFix(position: GeoPoint?, destination: GeoPoint?, remainingAlongM: Double?, accuracyM: Double?, openStops: Int = 0): Boolean {
        if (position == null || destination == null) return false
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val d = Geo.distance(position, destination)
        if (d > awayM) { away = true; asked = false; return false }
        if (!away || asked) return false
        if (openStops > 0 || remainingAlongM == null || remainingAlongM > END_M) return false
        if (!RouteProgress.arrived(position, destination, remainingAlongM)) return false
        asked = true
        return true
    }
}
