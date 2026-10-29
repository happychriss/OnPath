package com.onpath.app.routing

import com.onpath.app.data.Geo

/** Whether the user has arrived at the destination (pure, unit-tested; [ArrivalGate] adds the rules on top). */
object RouteProgress {
    /** Within this distance of the destination the app asks "You have arrived – end tour?". */
    const val ARRIVAL_RADIUS_M = 50.0

    /** Arrival also needs the followed line to be nearly done (a round trip ends where it starts). */
    const val ARRIVAL_MAX_REMAINING_M = 150.0

    /**
     * True when the user has reached [destination]: within [ARRIVAL_RADIUS_M] and, when a followed
     * line is known, with at most [ARRIVAL_MAX_REMAINING_M] of it left ([remainingAlongM]).
     */
    fun arrived(position: GeoPoint?, destination: GeoPoint?, remainingAlongM: Double?): Boolean {
        if (position == null || destination == null) return false
        if (Geo.distance(position, destination) > ARRIVAL_RADIUS_M) return false
        return remainingAlongM == null || remainingAlongM <= ARRIVAL_MAX_REMAINING_M
    }
}
