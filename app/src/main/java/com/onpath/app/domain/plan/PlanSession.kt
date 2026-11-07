package com.onpath.app.domain.plan

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.PlanGuidance
import com.onpath.app.ui.PlanHistory

/**
 * The plan being edited – its points, Undo / Redo, the pinned (POI) points and the start the one-point rule put in –
 * and the plan-side decisions after an edit (audit package 4, 2026-11-07: AppViewModel stage 1 split). Pure Kotlin, no
 * Android: [points] is whatever list the caller observes (the view model passes its Compose state list, the tests a
 * plain list). The view model keeps the side effects – storing, routing, the tour's guidance, the map – and asks this
 * class what the plan is.
 */
class PlanSession(
    val points: MutableList<GeoPoint>,
    pinnedAtStart: Set<GeoPoint> = emptySet(),
    private val history: PlanHistory = PlanHistory(),
) {
    /**
     * Points added by tapping a POI (hut, viewpoint …), kept by value so reorder / reverse / Undo keep them; a dragged
     * point is a new value and so no longer pinned. Routed exactly (Planner).
     */
    val pinned: MutableSet<GeoPoint> = pinnedAtStart.toMutableSet()

    /**
     * The start the one-point rule put in during a tour (my position then): no point of the user's – deleting the goal
     * of that plan empties it instead of building "position → that start" again or asking "Route back to the start?".
     */
    var oneLeftStart: GeoPoint? = null

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    fun pinnedIndices(): Set<Int> = points.indices.filter { points[it] in pinned }.toSet()

    /** Every point reachable by Undo or Redo. */
    fun historyPoints(): Set<GeoPoint> = history.allPoints()

    /** One undoable change: records the step, applies [block]; returns the points before it. */
    fun edit(block: MutableList<GeoPoint>.() -> Unit): List<GeoPoint> {
        history.record(points)
        val before = points.toList()
        points.block()
        return before
    }

    /** The plan Undo brings back (taken off the undo stack, not applied yet – [replace]); null = nothing to undo. */
    fun undo(): List<GeoPoint>? = history.undo(points)

    /** The plan Redo brings back (like [undo]); null = nothing to redo. */
    fun redo(): List<GeoPoint>? = history.redo(points)

    /** Puts [list] in place of the points without an Undo step (Undo / Redo, rules that rewrite the same step). */
    fun replace(list: List<GeoPoint>) {
        points.clear(); points.addAll(list)
    }

    /** The router snapped points onto the way ([moves] old → new): Undo / Redo show the snapped points too. */
    fun snapped(moves: Map<GeoPoint, GeoPoint>) = history.replacePoints(moves)

    /** Forget pinned marks of points neither planned nor reachable by Undo / Redo; true when some went (store them). */
    fun prunePinned(): Boolean {
        if (pinned.isEmpty()) return false
        val alive = HashSet(points).apply { addAll(history.allPoints()) }
        return pinned.retainAll(alive)
    }

    /**
     * The one point left is where I stand, or only the start [oneLeftStart] put in ([PlanGuidance.leftIsHere]): nothing
     * to ask about after an edit – the plan empties.
     */
    fun leftIsHere(position: GeoPoint?): Boolean {
        val left = points.singleOrNull() ?: return false
        return PlanGuidance.leftIsHere(left == oneLeftStart, position?.let { Geo.distance(it, left) })
    }

    /** What an edit during a tour that left one point does ([PlanGuidance.oneLeft]) – with this plan's facts. */
    fun oneLeft(pointsBefore: Int, recording: Boolean, following: Boolean, followIsTrack: Boolean, routeBackAsk: Boolean,
                position: GeoPoint?): PlanGuidance.OneLeft {
        val left = points.singleOrNull()
        return PlanGuidance.oneLeft(pointsBefore, points.size, recording, following, followIsTrack, routeBackAsk,
            left != null && left == oneLeftStart, if (left != null && position != null) Geo.distance(position, left) else null)
    }

    /** The plan empties (the one-point rule found nothing to plan): no step of its own, the guidance ends. */
    fun empty() {
        oneLeftStart = null
        points.clear()
    }

    /** The plan becomes position → the one point left (like "Hierhin"), in the same Undo step. */
    fun fromHere(position: GeoPoint, goal: GeoPoint) {
        oneLeftStart = position
        replace(listOf(position, goal))
    }
}
