package com.onpath.app.domain

import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint

/**
 * Undo / redo of the planned waypoints. Each entry is a full
 * snapshot of the point list, so a Clear is just one more step that Undo can revert. Each step keeps the plan's hidden
 * via points beside its points (docs/design/detour-stitch.md §2): Undo brings them back with them.
 */
class PlanHistory(private val limit: Int = 100) {
    private val undo = ArrayDeque<List<GeoPoint>>()
    private val redo = ArrayDeque<List<GeoPoint>>()
    private val undoAnchors = ArrayDeque<Map<Int, Anchors>>()
    private val redoAnchors = ArrayDeque<Map<Int, Anchors>>()

    /** The anchors of the step the last [undo] / [redo] gave back. */
    var takenAnchors: Map<Int, Anchors> = emptyMap()
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Call before changing the plan; [current] is the list as it is now. */
    fun record(current: List<GeoPoint>, anchors: Map<Int, Anchors> = emptyMap()) {
        undo.addLast(current.toList()); undoAnchors.addLast(anchors)
        if (undo.size > limit) { undo.removeFirst(); undoAnchors.removeFirst() }
        redo.clear(); redoAnchors.clear()
    }

    /** The list to show after undo, or null when there is nothing to undo. */
    fun undo(current: List<GeoPoint>, anchors: Map<Int, Anchors> = emptyMap()): List<GeoPoint>? =
        step(undo, undoAnchors, redo, redoAnchors, current, anchors)
    fun redo(current: List<GeoPoint>, anchors: Map<Int, Anchors> = emptyMap()): List<GeoPoint>? =
        step(redo, redoAnchors, undo, undoAnchors, current, anchors)

    private fun step(from: ArrayDeque<List<GeoPoint>>, fromAnchors: ArrayDeque<Map<Int, Anchors>>,
                     to: ArrayDeque<List<GeoPoint>>, toAnchors: ArrayDeque<Map<Int, Anchors>>,
                     current: List<GeoPoint>, anchors: Map<Int, Anchors>): List<GeoPoint>? {
        val prev = from.removeLastOrNull() ?: return null
        takenAnchors = fromAnchors.removeLastOrNull() ?: emptyMap()
        to.addLast(current.toList()); toAnchors.addLast(anchors)
        return prev
    }

    /** No step keeps anchors any more (an activity / T level change: they lie on the other profile's line, §2). */
    fun clearAnchors() {
        for (q in listOf(undoAnchors, redoAnchors)) for (i in q.indices) q[i] = emptyMap()
        takenAnchors = emptyMap()
    }

    /**
     * Rewrites point values in every undo/redo snapshot ([moves]: old → new), without adding a step.
     * Used when routing snapped points onto the way, so undo/redo show the same (snapped) markers.
     */
    fun replacePoints(moves: Map<GeoPoint, GeoPoint>) {
        if (moves.isEmpty()) return
        for (q in listOf(undo, redo)) for (i in q.indices) {
            val s = q[i]
            if (s.any { it in moves }) q[i] = s.map { moves[it] ?: it }
        }
    }

    /**
     * The snapshots as they are ([undo] oldest first, [redo] oldest first) – the browser planner hands them to a fresh
     * engine worker after it stopped a long plan that blocked the old one (web-planner.md §26); the app never needs it.
     */
    fun snapshot(): Pair<List<List<GeoPoint>>, List<List<GeoPoint>>> = undo.toList() to redo.toList()

    /** The anchors of [snapshot]'s steps, in the same order. */
    fun anchorSnapshot(): Pair<List<Map<Int, Anchors>>, List<Map<Int, Anchors>>> = undoAnchors.toList() to redoAnchors.toList()

    /** Takes [snapshot]'s lists back as they were (no step, nothing trimmed), with [anchorSnapshot]'s; a step without any has none. */
    fun restore(undoSteps: List<List<GeoPoint>>, redoSteps: List<List<GeoPoint>>,
                undoAnchorSteps: List<Map<Int, Anchors>> = emptyList(), redoAnchorSteps: List<Map<Int, Anchors>> = emptyList()) {
        undo.clear(); undo.addAll(undoSteps.takeLast(limit)); redo.clear(); redo.addAll(redoSteps)
        undoAnchors.clear(); undoAnchors.addAll(undoSteps.indices.map { undoAnchorSteps.getOrElse(it) { emptyMap() } }.takeLast(limit))
        redoAnchors.clear(); redoAnchors.addAll(redoSteps.indices.map { redoAnchorSteps.getOrElse(it) { emptyMap() } })
    }

    /** Every point reachable by undo or redo (to keep their "pinned" marks alive). */
    fun allPoints(): Set<GeoPoint> = HashSet<GeoPoint>().apply { undo.forEach { addAll(it) }; redo.forEach { addAll(it) } }
}

/**
 * Generation counter for routing requests: a result is applied only while its generation is the
 * newest one. [invalidate] (e.g. on Clear or when fewer than two points are left) makes every
 * running request stale, so a late BRouter result can never bring an old line back.
 */
class RouteRequests {
    private var gen = 0
    fun next(): Int = ++gen
    fun invalidate() { gen++ }
    fun isCurrent(g: Int): Boolean = g == gen
}

/**
 * The route point a point menu is open for: its index when opened AND its position (core feature C1). The menu acts
 * on the point the user held, not on whatever sits at that index later – the plan can change underneath an open menu
 * (route ⋯ → Points… delete, a tour opened, a snapped point moving).
 */
data class PointMenuTarget(val index: Int, val point: GeoPoint) {
    /** The point's index in [plan] now: the same index while it still holds the point, else the nearest equal point. */
    fun indexIn(plan: List<GeoPoint>): Int? {
        if (plan.getOrNull(index) == point) return index
        return plan.indices.filter { plan[it] == point }.minByOrNull { kotlin.math.abs(it - index) }
    }

    /** After the router snapped points ([moves] old → new position): follow this point to its snapped position. */
    fun afterSnap(moves: Map<GeoPoint, GeoPoint>): PointMenuTarget = moves[point]?.let { copy(point = it) } ?: this
}


/**
 * A position → ahead route during a tour (`followAhead`, [com.onpath.app.navigation.TourSplit]) is applied only while it
 * is the newest and the plan is still the one it was routed for (fix-tour-edit-line.md cause 3): every later edit, Undo,
 * clear or end of the guidance makes it stale – a slow old result never brings back deleted points or hides the plan.
 */
class SplitRequests {
    private var gen = 0
    private var plan: List<GeoPoint>? = null
    fun next(planNow: List<GeoPoint>): Int { plan = planNow.toList(); return ++gen }
    fun invalidate() { gen++; plan = null }
    fun isCurrent(g: Int, planNow: List<GeoPoint>): Boolean = g == gen && plan == planNow
}
