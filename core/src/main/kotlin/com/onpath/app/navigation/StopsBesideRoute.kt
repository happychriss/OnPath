package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import kotlin.math.abs
import kotlin.math.min

/**
 * Stops (not the goal) the planned line never comes within the "reached" radius of – a point the chosen level cannot
 * reach, the plan sheet's red line ([com.onpath.app.domain.PlanGaps]). Following the route, the place on the line nearest
 * to them is as close as one gets: there they count as passed by ([NavSession.passedBy], arrival, C5).
 *
 * A false "you have arrived" is worse than a missing one, so in doubt a stop stays open. It counts when the rider
 * - came within the "reached" radius of its place on the line followed, with the progress there too (a hairpin's other
 *   branch can run that close), or of the plan's own place while on a line taken instead – from a good fix – or
 * - walked past that place on the followed line and went on: progress crossed it in a small step ([STEP_M]) within
 *   [PAST_FACTOR] × the radius of it (from a fix too poor for "reached", up to [POOR_FIX_M]: within the radius) and then got
 *   [ON_M] beyond it (a noisy fix is no walk past it). That is a parallel way beside the line, or poor GPS at the place.
 * A taken line has a place of its own only for a stop the planned line cannot reach either, ahead on that line and about
 * as near to the stop as the plan's ([onTaken]): a way back that merely leads away from an early turnaround passes nothing.
 */
class StopsBesideRoute {
    /** The place on a line nearest to a stop: where, how far along it, how far from the stop. */
    class Spot(val point: GeoPoint, val alongM: Double, val distanceM: Double)

    private var line: NavLine? = null
    private var stops: List<NavStop>? = null
    private var spots: Map<Int, Spot> = emptyMap()
    private var followed: NavLine? = null
    private var followedSpots: Map<Int, Spot> = emptyMap()
    private var lastAlongM: Double? = null
    /** Places the progress crossed on the followed line, not yet [ON_M] behind the rider. */
    private val crossed = HashSet<Int>()

    /**
     * The stops not yet [visited] / passed by that the fix [p] passes by now. [planned] = the session's original line,
     * [now] = the line followed, [progressM] = the progress on it; [inOrder] = the stop may count now.
     */
    fun passedNow(s: NavSession, planned: NavLine, now: NavLine, p: GeoPoint, accuracyM: Double, progressM: Double,
                  visited: Set<Int>, inOrder: (Int) -> Boolean): Set<Int> {
        if (line !== planned || stops !== s.stops) { line = planned; stops = s.stops; spots = of(s, planned); followed = null }
        if (spots.isEmpty()) return emptySet()
        if (followed !== now) {
            followed = now; lastAlongM = null; crossed.clear()
            followedSpots = if (now === planned) spots else onTaken(s, now, spots)
        }
        val before = lastAlongM
        lastAlongM = progressM
        if (accuracyM > POOR_FIX_M) return emptySet()
        val radius = NavSession.visitedM(s.activity)
        val good = accuracyM <= NavSession.VISITED_MAX_ACCURACY_M
        fun at(spot: Spot?) = good && spot != null && Geo.distance(p, spot.point) <= radius
        // on the followed line also by the progress: a hairpin's other branch can run within the radius of the place
        fun atHere(spot: Spot?) = at(spot) && abs(progressM - spot!!.alongM) <= radius * PAST_FACTOR
        val past = walkedPast(now, p, before, progressM, if (good) radius * PAST_FACTOR else radius)
        return spots.keys.filter { i ->
            i !in visited && i !in s.passedBy && inOrder(i) &&
                ((now !== planned && at(spots[i])) || atHere(followedSpots[i]) || i in past)
        }.toSet()
    }

    /** The places on the followed line the rider has now walked past (see the class comment). */
    private fun walkedPast(now: NavLine, p: GeoPoint, before: Double?, progressM: Double, reachM: Double): Set<Int> {
        if (before == null) return emptySet()
        val out = HashSet<Int>()
        for ((i, spot) in followedSpots) {
            if (progressM < spot.alongM) { crossed -= i; continue }
            if (before < spot.alongM && progressM - before <= STEP_M && Geo.distance(p, spot.point) <= reachM) crossed += i
            if (i in crossed && progressM >= min(spot.alongM + ON_M, now.lengthM - 1.0)) out += i
        }
        return out
    }

    companion object {
        /** Past a place: the two fixes across it at most this far apart along the line (a bigger step is GPS noise). */
        const val STEP_M = 40.0
        /** … and the progress then got this far beyond it. */
        const val ON_M = 30.0
        /** Past a place: within this many times the "reached" radius of it (a parallel way beside the line). */
        const val PAST_FACTOR = 2.0
        /** Fixes up to this accuracy count for walking past a place – every fix the recording keeps. */
        const val POOR_FIX_M = 50.0
        /** A taken line's place counts only this far along it: at its very start it is where the rider stood, no goal. */
        const val TAKEN_FROM_M = 50.0
        /** A taken line's place counts only when at most this much farther from the stop than the plan's own place. */
        const val TAKEN_SLACK_M = 100.0

        /** Stop index → the place on [line] nearest to it, for the stops [line] does not reach. */
        fun of(s: NavSession, line: NavLine): Map<Int, Spot> {
            if (!line.isUsable) return emptyMap()
            val radius = NavSession.visitedM(s.activity)
            val out = LinkedHashMap<Int, Spot>()
            for (i in 0 until s.stops.lastIndex) {
                val st = s.stops[i]
                // the usual case without a search of the whole line: its place on the line is close enough
                if (Geo.distance(st.point, line.pointAt(st.alongM)) <= radius) continue
                if ((line.match(st.point)?.distanceM ?: continue) <= radius) continue
                // to the end of the line: the next stop's place can lie on an earlier pass of the same way (out and back)
                val m = line.match(st.point, st.alongM) ?: continue
                out[i] = Spot(m.point, m.alongM, m.distanceM)
            }
            return out
        }

        /** The places on a taken line for the stops of [planned] (see the class comment); a stop it reaches has none. */
        private fun onTaken(s: NavSession, taken: NavLine, planned: Map<Int, Spot>): Map<Int, Spot> {
            if (!taken.isUsable) return emptyMap()
            val radius = NavSession.visitedM(s.activity)
            val out = LinkedHashMap<Int, Spot>()
            for ((i, own) in planned) {
                val m = taken.match(s.stops[i].point) ?: continue
                if (m.distanceM <= radius || m.alongM < TAKEN_FROM_M || m.distanceM > own.distanceM + TAKEN_SLACK_M) continue
                out[i] = Spot(m.point, m.alongM, m.distanceM)
            }
            return out
        }
    }
}
