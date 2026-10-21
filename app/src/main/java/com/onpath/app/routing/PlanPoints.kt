package com.onpath.app.routing

import com.onpath.app.data.Geo
import kotlin.math.roundToInt

enum class PointRole { START, STOP, GOAL }

/**
 * One row of the plan sheet's Points section. [planIndex] = index in the plan (row i is plan point i); [legM] =
 * distance from the previous point (for a round trip's start: the way back to it), [approx] = straight line, not
 * along the route; [loop] = round trip start (start = goal, shown once with ↺).
 */
data class PlanPointRow(
    val planIndex: Int,
    val role: PointRole,
    val name: String,
    val legM: Double?,
    val approx: Boolean,
    val loop: Boolean = false,
)

/**
 * The Points section of the plan sheet (owner 2026-10-21, core feature C9): rows, reorder (hold ≡ and drag), delete.
 * Pure, unit tested (PlanPointsTest). A round trip is shown and edited as its loop (the closing point is not a row):
 * after every edit the loop is closed again at its (new) start.
 */
object PlanPoints {
    /** A route point found this far from the line still counts as on it (earliest such pass wins). */
    private const val PASS_SLACK_M = 25.0

    /**
     * Rows for [plan]; [line] = the route line for these points (null while none / it is being calculated – then
     * straight-line distances); [name] = known name of plan point i (pinned POI, search, nearest place) or null.
     */
    fun rows(plan: List<GeoPoint>, line: List<GeoPoint>?, name: (Int) -> String?): List<PlanPointRow> {
        if (plan.isEmpty()) return emptyList()
        val loop = PlanEdits.isRoundTrip(plan)
        val legs = legs(plan, line)
        val exact = legs != null
        val legM = legs ?: (listOf<Double?>(null) + (1 until plan.size).map { Geo.distance(plan[it - 1], plan[it]) })
        val count = if (loop) plan.size - 1 else plan.size
        return (0 until count).map { i ->
            val role = when {
                i == 0 -> PointRole.START
                !loop && i == count - 1 -> PointRole.GOAL
                else -> PointRole.STOP
            }
            PlanPointRow(
                planIndex = i, role = role,
                name = name(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "Point ${i + 1}",
                legM = if (loop && i == 0) legM[plan.lastIndex] else legM[i],
                approx = !exact, loop = loop && i == 0,
            )
        }
    }

    /**
     * Distance along [line] from point i-1 to point i (index 0 = null), or null without a usable line. Each point is
     * matched to the line moving forward only, at the first pass within [PASS_SLACK_M] of its closest approach.
     */
    fun legs(plan: List<GeoPoint>, line: List<GeoPoint>?): List<Double?>? {
        if (line == null || line.size < 2 || plan.size < 2) return null
        val cum = DoubleArray(line.size)
        for (k in 1 until line.size) cum[k] = cum[k - 1] + Geo.distance(line[k - 1], line[k])
        val at = IntArray(plan.size)
        at[0] = 0
        for (i in 1 until plan.size) {
            if (i == plan.lastIndex) { at[i] = line.lastIndex; continue }
            val from = at[i - 1]
            var best = Double.MAX_VALUE
            for (k in from until line.size) best = minOf(best, Geo.distance(line[k], plan[i]))
            at[i] = (from until line.size).first { Geo.distance(line[it], plan[i]) <= best + PASS_SLACK_M }
        }
        return listOf<Double?>(null) + (1 until plan.size).map { cum[at[it]] - cum[at[it - 1]] }
    }

    /** Row [from] dropped at row [to]; to = 0 makes it the start. Round trips stay closed at the new start. */
    fun move(plan: List<GeoPoint>, from: Int, to: Int): List<GeoPoint> {
        val loop = PlanEdits.isRoundTrip(plan)
        val rows = if (loop) plan.dropLast(1) else plan
        if (from !in rows.indices || from == to) return plan
        val moved = PlanEdits.move(rows, from, to.coerceIn(0, rows.lastIndex))
        return if (loop) moved + moved.first() else moved
    }

    /** Row [row] deleted (Undo brings it back). In a round trip the loop closes at its start again. */
    fun delete(plan: List<GeoPoint>, row: Int): List<GeoPoint> {
        val loop = PlanEdits.isRoundTrip(plan)
        val rows = if (loop) plan.dropLast(1) else plan
        if (row !in rows.indices) return plan
        val left = rows.toMutableList().apply { removeAt(row) }
        return if (loop && left.size >= 2) left + left.first() else left
    }

    /** The row a row dragged from [from] by [offsetPx] lands on ([rowPx] = row height, 0 = not measured). */
    fun dropIndex(from: Int, offsetPx: Float, rowPx: Float, count: Int): Int {
        if (rowPx <= 0f || count <= 0) return from
        return (from + (offsetPx / rowPx).roundToInt()).coerceIn(0, count - 1)
    }

    /** By how many rows row [i] moves aside while row [from] is dragged over row [to] (-1 up, +1 down, 0). */
    fun shift(i: Int, from: Int, to: Int): Int = when {
        from < to && i in (from + 1)..to -> -1
        to < from && i in to until from -> 1
        else -> 0
    }
}
