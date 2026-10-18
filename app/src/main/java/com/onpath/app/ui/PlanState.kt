package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint

/**
 * Undo / redo of the planned waypoints (pure, no Android – unit tested). Each entry is a full
 * snapshot of the point list, so a Clear is just one more step that Undo can revert.
 */
class PlanHistory(private val limit: Int = 100) {
    private val undo = ArrayDeque<List<GeoPoint>>()
    private val redo = ArrayDeque<List<GeoPoint>>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Call before changing the plan; [current] is the list as it is now. */
    fun record(current: List<GeoPoint>) {
        undo.addLast(current.toList())
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    /** The list to show after undo, or null when there is nothing to undo. */
    fun undo(current: List<GeoPoint>): List<GeoPoint>? = step(undo, redo, current)
    fun redo(current: List<GeoPoint>): List<GeoPoint>? = step(redo, undo, current)

    private fun step(from: ArrayDeque<List<GeoPoint>>, to: ArrayDeque<List<GeoPoint>>, current: List<GeoPoint>): List<GeoPoint>? {
        val prev = from.removeLastOrNull() ?: return null
        to.addLast(current.toList())
        return prev
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

object PlanClear {
    /** Ask "Clear this route?" only when there is a route worth keeping (two or more points). */
    fun needsConfirm(pointCount: Int): Boolean = pointCount >= 2
}
