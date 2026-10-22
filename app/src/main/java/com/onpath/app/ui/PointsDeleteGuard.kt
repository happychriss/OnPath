package com.onpath.app.ui

import com.onpath.app.routing.PlanPointRow

/**
 * Safe ✕ in the Points list (owner decision after field test 2026-10-22: two deletes 1 s apart – the rows moved up
 * under the finger and the second tap deleted the next point). After a ✕ the deleted row stays in place as a quiet
 * "Deleted" placeholder for [QUIET_MS], so nothing shifts under the finger; meanwhile taps on the list (✕, row, drag)
 * are ignored. Then the placeholder goes and the list is the plan again. Undo on the map brings the point back as
 * always. Pure (unit-tested); the composable keeps one instance per list.
 */
class PointsDeleteGuard {
    /** One shown row: a plan row ([rowIndex] = its index in the plan's rows) or the deleted placeholder. */
    data class Entry(val row: PlanPointRow, val rowIndex: Int?) {
        val deleted: Boolean get() = rowIndex == null
    }

    private var ghostAt = -1
    private var ghostRow: PlanPointRow? = null
    private var ghostRowsBefore = 0
    private var since = Long.MIN_VALUE / 2

    fun quiet(now: Long): Boolean = now - since < QUIET_MS

    /** A tap on the list may act now. */
    fun allowsTap(now: Long): Boolean = !quiet(now)

    /** ✕ on row [index] ([row] its content, out of [rowsBefore] rows). False = ignored (too soon after a delete). */
    fun onDelete(index: Int, row: PlanPointRow, rowsBefore: Int, now: Long): Boolean {
        if (quiet(now)) return false
        ghostAt = index; ghostRow = row; ghostRowsBefore = rowsBefore; since = now
        return true
    }

    /** What the list shows for the plan's [rows] now. */
    fun entries(rows: List<PlanPointRow>, now: Long): List<Entry> {
        val g = ghostRow
        val plain = rows.mapIndexed { i, r -> Entry(r, i) }
        if (g == null || !quiet(now)) return plain
        // the plan did not lose a row (Undo, another edit): show it as it is
        if (rows.size != ghostRowsBefore - 1) return plain
        val at = ghostAt.coerceIn(0, rows.size)
        return plain.take(at) + Entry(g, null) + plain.drop(at)
    }

    /** The quiet time is over (or the list was rebuilt): forget the placeholder. */
    fun clear() { ghostRow = null; ghostAt = -1; since = Long.MIN_VALUE / 2 }

    companion object {
        /** Long enough for the field case (second tap 1 s later), short enough to delete several points calmly. */
        const val QUIET_MS = 1_200L
    }
}
