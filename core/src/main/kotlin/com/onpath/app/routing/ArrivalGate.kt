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

        /** A fix this good is taken at its word; a worse one (noise ±20–45 m) must be closer by the excess (2026-11-25). */
        const val CLEAN_ACCURACY_M = 10.0
        /** The arrival radius / end window never shrink below this, however poor the fix (still under [maxAccuracyM]). */
        const val MIN_REACH_M = 10.0

        /** How much closer than the clean radius [accuracyM] asks for (0 for a clean or unknown accuracy). */
        fun noiseM(accuracyM: Double?): Double = if (accuracyM == null) 0.0 else (accuracyM - CLEAN_ACCURACY_M).coerceAtLeast(0.0)
    }

    /**
     * The user has been [awayM] from a destination of this tour. Survives a new followed line ([newLine]) and a service
     * restart (the trip file): field S10 2026-11-07 – the plan edited ~75 m before the goal gave a new line, the old
     * [reset] forgot "away", and as the rider never got 150 m from the goal again no arrival came at all.
     */
    var away = false
        private set
    private var asked = false

    /** Start over (a new tour). */
    fun reset() { away = false; asked = false }

    /**
     * A new followed line during the tour (plan edit, position → points ahead, Guide me, Undo): asked again for it, but
     * "been away" stays – the rider did not teleport to the goal; the line's own rules (every point reached, the progress
     * at its end) still hold.
     */
    fun newLine() { asked = false }

    /** [away] as the trip file kept it (service restart during the tour). */
    fun restoreAway(wasAway: Boolean) { if (wasAway) away = true }

    /** True exactly when the arrival question should be shown now. */
    fun onFix(position: GeoPoint?, destination: GeoPoint?, remainingAlongM: Double?, accuracyM: Double?, openStops: Int = 0): Boolean {
        if (position == null || destination == null) return false
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val d = Geo.distance(position, destination)
        if (d > awayM) { away = true; asked = false; return false }
        if (!away || asked) return false
        // noisy fix (accuracy above CLEAN): both the distance and the progress window shrink by the excess, never below MIN_REACH_M
        val noise = noiseM(accuracyM)
        if (openStops > 0 || remainingAlongM == null || remainingAlongM > maxOf(END_M - noise, MIN_REACH_M)) return false
        if (d > maxOf(RouteProgress.ARRIVAL_RADIUS_M - noise, MIN_REACH_M)) return false
        if (!RouteProgress.arrived(position, destination, remainingAlongM)) return false
        asked = true
        return true
    }
}
