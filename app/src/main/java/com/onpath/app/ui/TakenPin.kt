package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * A found place (search hit / shared place, the pin) the user just added to the plan: the pin went, the numbered point
 * shows the place. When the app takes that add back by itself – Cancel at the very-long-plan question or of a running
 * calculation undoes the edit ([AppViewModel] cancelRun) – the place must not vanish with it (field 2026-11-07, S10:
 * a far destination "forgotten" after its map loaded): the pin comes back, so the user can add it another way.
 * Pure, unit-tested (TakenPinTest).
 */
data class TakenPin(val place: com.onpath.app.search.Place, val at: GeoPoint) {
    companion object {
        /**
         * The pin to show again after the plan became [now], or null: [taken] is no longer a point of the plan and no
         * other found place is marked ([mark]) – a newer search pick wins.
         */
        fun back(taken: TakenPin?, now: List<GeoPoint>, mark: com.onpath.app.search.Place?): com.onpath.app.search.Place? {
            if (taken == null || mark != null) return null
            val still = now.any { kotlin.math.abs(it.lat - taken.at.lat) < 1e-7 && kotlin.math.abs(it.lon - taken.at.lon) < 1e-7 }
            return if (still) null else taken.place
        }
    }
}
