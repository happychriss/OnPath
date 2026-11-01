package com.onpath.app.navigation

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits

/**
 * Plan edits during a tour (owner 2026-10-31, `docs/design/tour-edit-position.md`): the user's position splits the plan
 * into **passed** points and points **ahead**; the tour follows position → ahead. Pure, app only – never part of the code
 * the web planner shares (`SharedCoreRuleTest`): it decides *which part* of the plan is followed, not how a plan is
 * computed or edited.
 */
object TourSplit {
    /** Came this close to a point: passed (on foot / on a bike activity). */
    const val NEAR_HIKE_M = 40.0
    const val NEAR_BIKE_M = 60.0
    /** "Clearly closer to the next point": at least this much closer to it than to the point before … */
    const val CLOSER_M = 100.0
    /** … and the point before at least this far away (clearly left behind). */
    const val LEFT_M = 200.0

    /** What the tour knows when the plan is edited. */
    data class Tour(
        /** The recorded track so far (all segments). */
        val track: List<List<GeoPoint>>,
        /** Current position; null = no fix (only the track counts then). */
        val position: GeoPoint?,
        /** The user has been on the followed line (`NavSession.reached`); before that nothing is passed by the closer rule. */
        val reached: Boolean,
        val bike: Boolean,
        /** Stops of the followed line with their place on it (`NavSession.stops`). */
        val stops: List<NavStop> = emptyList(),
        /** Furthest progress along the followed line (metres). */
        val progressM: Double = 0.0,
        /** Stops already marked visited (`NavSession.visited`, by point). */
        val visited: Set<GeoPoint> = emptySet(),
    )

    /**
     * The plan points that count as passed, as indices (a prefix in plan order: everything before a passed point is passed
     * too). A round trip's goal (its last point, the start again) is never passed. In doubt a point is ahead.
     * [known] = points already found passed earlier in this tour (by coordinate): they stay passed after the tour
     * follows an edited plan whose session no longer knows them (field 2026-11-01 – the plan lines vanished).
     */
    fun passed(plan: List<GeoPoint>, t: Tour, known: Set<GeoPoint> = emptySet()): Set<Int> {
        if (plan.isEmpty()) return emptySet()
        val last = if (PlanEdits.isRoundTrip(plan)) plan.size - 2 else plan.size - 1
        val near = if (t.bike) NEAR_BIKE_M else NEAR_HIKE_M
        var lastPassed = -1
        for (i in 0..last) {
            val p = plan[i]
            val hit = p in t.visited || known.any { sameSpot(it, p) } || nearTrack(p, t.track, near) ||
                t.stops.firstOrNull { sameSpot(it.point, p) }?.let { it.alongM <= t.progressM && t.reached } == true ||
                (t.reached && i < plan.size - 1 && clearlyCloserToNext(p, plan[i + 1], t.position))
            if (hit) lastPassed = i
        }
        return (0..lastPassed).toSet()
    }

    /** The points ahead: after the last passed one, in plan order. */
    fun ahead(plan: List<GeoPoint>, passed: Set<Int>): List<GeoPoint> {
        val lastPassed = passed.maxOrNull() ?: -1
        return plan.drop(lastPassed + 1)
    }

    /**
     * The passed points of [before] carried into [after] (an edit: delete / insert / move): matched by coordinate,
     * not by index – indices shift. Returns indices into [after], again a prefix; a round trip's goal stays ahead.
     */
    fun carry(before: List<GeoPoint>, passedBefore: Set<Int>, after: List<GeoPoint>): Set<Int> {
        if (passedBefore.isEmpty() || after.isEmpty()) return emptySet()
        val pts = passedBefore.mapNotNull { before.getOrNull(it) }
        val last = if (PlanEdits.isRoundTrip(after)) after.size - 2 else after.size - 1
        var lastPassed = -1
        for (i in 0..last) if (pts.any { sameSpot(it, after[i]) }) lastPassed = i
        return (0..lastPassed).toSet()
    }

    /** What an edit during a tour means for the guidance. */
    sealed interface Outcome {
        /** Nothing passed (or no tour): the plan is followed as a whole, as before. */
        data object WholePlan : Outcome
        /** The points ahead did not change (only passed points were edited): keep the guidance, no new route. */
        data object Unchanged : Outcome
        /** Follow position → these points. */
        data class Follow(val ahead: List<GeoPoint>) : Outcome
        /** Points are left, but none ahead: ask "Kein Ziel mehr vor dir." */
        data object NothingAhead : Outcome
        /** No points at all: guidance ends, recording goes on (`PlanGuidance`). */
        data object Empty : Outcome
    }

    fun outcome(before: List<GeoPoint>, after: List<GeoPoint>, passedBefore: Set<Int>): Outcome {
        if (after.isEmpty()) return Outcome.Empty
        if (passedBefore.isEmpty()) return Outcome.WholePlan
        val aheadAfter = ahead(after, carry(before, passedBefore, after))
        if (aheadAfter.isEmpty()) return Outcome.NothingAhead
        if (aheadAfter == ahead(before, passedBefore)) return Outcome.Unchanged
        return Outcome.Follow(aheadAfter)
    }

    /**
     * Where a new point goes during a tour: [insertAhead] = its index among [[position] + ahead] as `WaypointInsertion`
     * decided it (0 = before the position, taken as 1); mapped back into the whole plan – never behind the user.
     */
    fun insertIndex(passed: Set<Int>, insertAhead: Int): Int = (passed.maxOrNull() ?: -1) + maxOf(1, insertAhead)

    private fun nearTrack(p: GeoPoint, track: List<List<GeoPoint>>, m: Double): Boolean =
        track.any { seg -> seg.any { Geo.distance(it, p) <= m } }

    private fun clearlyCloserToNext(p: GeoPoint, next: GeoPoint, pos: GeoPoint?): Boolean {
        pos ?: return false
        val toP = Geo.distance(pos, p)
        return toP >= LEFT_M && Geo.distance(pos, next) <= toP - CLOSER_M
    }

    private fun sameSpot(a: GeoPoint, b: GeoPoint) = a == b || Geo.distance(a, b) < 1.0
}
