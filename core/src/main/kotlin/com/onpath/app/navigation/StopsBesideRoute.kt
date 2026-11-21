package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint

/**
 * Stops (not the goal) the planned line never comes within the "reached" radius of – a point the chosen level cannot
 * reach, the plan sheet's red line ([com.onpath.app.domain.PlanGaps]). Following the route, the place on their leg nearest
 * to them is as close as one gets: there they count as passed by ([NavSession.passedBy], arrival, C5). Worked out once
 * per line and stops (the line only changes with an activity switch).
 */
class StopsBesideRoute {
    private var line: NavLine? = null
    private var stops: List<NavStop>? = null
    private var spots: Map<Int, GeoPoint> = emptyMap()

    /** The stops not yet [visited] / passed by that [p] passes by now; [inOrder] = the stop may count now. */
    fun passedNow(s: NavSession, planned: NavLine, p: GeoPoint, visited: Set<Int>, inOrder: (Int) -> Boolean): Set<Int> {
        if (line !== planned || stops !== s.stops) { line = planned; stops = s.stops; spots = of(s, planned) }
        if (spots.isEmpty()) return emptySet()
        val radius = NavSession.visitedM(s.activity)
        return spots.filter { (i, spot) ->
            i !in visited && i !in s.passedBy && inOrder(i) && Geo.distance(p, spot) <= radius
        }.keys
    }

    companion object {
        /** Stop index → the place on its leg nearest to it, for the stops [line] does not reach. */
        fun of(s: NavSession, line: NavLine): Map<Int, GeoPoint> {
            if (!line.isUsable) return emptyMap()
            val radius = NavSession.visitedM(s.activity)
            val out = LinkedHashMap<Int, GeoPoint>()
            for (i in 0 until s.stops.lastIndex) {
                val st = s.stops[i]
                // the usual case without a search of the whole line: its place on the line is close enough
                if (Geo.distance(st.point, line.pointAt(st.alongM)) <= radius) continue
                if ((line.match(st.point)?.distanceM ?: continue) <= radius) continue
                out[i] = (line.match(st.point, st.alongM, s.stops[i + 1].alongM) ?: continue).point
            }
            return out
        }
    }
}
