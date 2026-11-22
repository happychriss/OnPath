package com.onpath.app.domain.plan

import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.domain.PlanGuidance
import com.onpath.app.domain.PlanHistory
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.PlanAnchors

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
    anchorsAtStart: AnchorSet = AnchorSet.NONE,
) {
    /**
     * The hidden via points by point index (docs/design/detour-stitch.md): they follow every edit here
     * ([PlanAnchors.edited]), Undo / Redo bring them back. This stage only reads them: a plan gets them from where it was
     * loaded ([arriving]), no edit makes new ones. They count only for the profile they lie on ([anchorsFor]).
     */
    var anchorSet: AnchorSet = anchorsAtStart
        private set

    /** The anchors as kept, whatever the profile is now: to carry them along, never to route or save with. */
    val anchors: Map<Int, Anchors> get() = anchorSet.anchors

    /** The anchors that count for [activity] / [sac]: what is routed and saved. */
    fun anchorsFor(activity: Activity, sac: Int): Map<Int, Anchors> = anchorSet.forProfile(activity, sac)

    /** The anchors of the points the next change puts in ([arriving], Undo / Redo). */
    private var incoming: Pair<List<GeoPoint>, AnchorSet>? = null

    /** The next change that makes the plan exactly [list] (a loaded plan) brings [set] with it. */
    fun arriving(list: List<GeoPoint>, set: AnchorSet) { incoming = list.toList() to set }

    /** The points changed from [before]: the anchors that arrive with them, else the old ones follow. */
    private fun anchorsAfter(before: List<GeoPoint>) {
        val now = points.toList()
        val got = incoming?.takeIf { it.first == now }
        incoming = null
        anchorSet = got?.second ?: anchorSet.copy(anchors = PlanAnchors.edited(before, now, anchorSet.anchors))
    }

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
        history.record(points, anchorSet)
        val before = points.toList()
        points.block()
        anchorsAfter(before)
        return before
    }

    /** The plan Undo brings back (taken off the undo stack, not applied yet – [replace]); null = nothing to undo. */
    fun undo(): List<GeoPoint>? = history.undo(points, anchorSet)?.also { incoming = it to history.takenAnchors }

    /** The plan Redo brings back (like [undo]); null = nothing to redo. */
    fun redo(): List<GeoPoint>? = history.redo(points, anchorSet)?.also { incoming = it to history.takenAnchors }

    /** Puts [list] in place of the points without an Undo step (Undo / Redo, rules that rewrite the same step). */
    fun replace(list: List<GeoPoint>) {
        val before = points.toList()
        points.clear(); points.addAll(list)
        anchorsAfter(before)
    }

    /**
     * Undo / Redo start afresh (a multi-day tour's day becomes the plan, or the whole tour again: an Undo across that would
     * put the other plan's points into this one).
     */
    fun forgetHistory() = history.restore(emptyList(), emptyList())

    /** The router snapped points onto the way ([moves] old → new): Undo / Redo show the snapped points too. */
    fun snapped(moves: Map<GeoPoint, GeoPoint>, anchorsCount: Boolean = true) = history.replacePoints(moves, anchorsCount)

    /**
     * The points [routed] were moved to [snapped] in place by a run that did not route the detour points (they lie on another
     * profile than the run's): a moved point is a new one, so its anchors, and the facing sides of its neighbours, go
     * ([PlanAnchors.edited], docs/design/detour-stitch.md §2). A run that did route them keeps them with the moved point.
     */
    fun snapDropsAnchors(routed: List<GeoPoint>, snapped: List<GeoPoint>) {
        if (anchorSet.anchors.isEmpty() || routed == snapped) return
        anchorSet = anchorSet.copy(anchors = PlanAnchors.edited(routed, snapped, anchorSet.anchors))
    }

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
        anchorSet = AnchorSet.NONE; incoming = null
    }

    /** The plan becomes position → the one point left (like "Hierhin"), in the same Undo step. */
    fun fromHere(position: GeoPoint, goal: GeoPoint) {
        oneLeftStart = position
        replace(listOf(position, goal))
    }
}
