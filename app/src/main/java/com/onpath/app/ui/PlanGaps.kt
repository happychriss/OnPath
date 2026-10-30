package com.onpath.app.ui

import com.onpath.strings.*
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint

/**
 * A plan point the route does not reach: the router only gets to [routeEnd] (its nearest allowed way, up to 1 km away –
 * a summit above the T level, a via ferrata, a point off any path). [distanceM] = the missing piece. [skipped] = a stop on
 * a way the route still leaves out (BRouter's via correction, see `PlannedRoute.skipped`) – reached by a way, but not
 * by this line.
 */
data class PlanGap(val index: Int, val point: GeoPoint, val routeEnd: GeoPoint, val distanceM: Double, val skipped: Boolean = false)

/**
 * Unreachable plan points (owner): no message box – the map draws the missing piece grey dashed and the point
 * grey and hollow; the plan sheet shows ONE red line while it holds, e.g. `Last 180 m to "Taser Alm" not possible by
 * gravel bike` (+ "Show"). One reason only: the ways near the point are not known to the app, so it says just "not
 * possible by <activity>".
 */
object PlanGaps {
    /** Closer = the router's normal snapping onto the way next to the point (no gap worth a word). */
    const val FAR_M = 150.0
    /**
     * A skipped stop (matched on its way, line cut past it) is a gap from this far: a few metres are snapping noise. The
     * rule for "skipped" (`WaypointSnap.missesMatched`: matched spot > 15 m and > 3 × the tap's distance off the line)
     * already keeps the stop at least ~10 m off the line; this floor only guards rounding.
     */
    const val SKIPPED_MIN_M = 10.0

    /**
     * Gaps of [waypoints] on a route whose router positions are [snapped] (same order; other sizes → none): every point
     * at least [FAR_M] from the line, and the [skipped] stops (`PlannedRoute.skipped`) from [SKIPPED_MIN_M].
     */
    fun of(waypoints: List<GeoPoint>, snapped: List<GeoPoint?>, skipped: Set<Int> = emptySet()): List<PlanGap> {
        if (waypoints.size < 2 || snapped.size != waypoints.size) return emptyList()
        return waypoints.indices.mapNotNull { i ->
            val s = snapped[i] ?: return@mapNotNull null
            if (!s.lat.isFinite() || !s.lon.isFinite()) return@mapNotNull null
            val d = Geo.distance(waypoints[i], s)
            when {
                d >= FAR_M -> PlanGap(i, waypoints[i], s, d)
                i in skipped && d >= SKIPPED_MIN_M -> PlanGap(i, waypoints[i], s, d, skipped = true)
                else -> null
            }
        }
    }

    /** Indices with a gap (they keep their place – no snapping onto a far way, no silent move of a skipped stop). */
    fun farIndices(waypoints: List<GeoPoint>, snapped: List<GeoPoint?>, skipped: Set<Int> = emptySet()): Set<Int> =
        of(waypoints, snapped, skipped).map { it.index }.toSet()

    /** Plain activity name inside a sentence ("by gravel bike"). */
    fun activityWords(a: Activity): String = a.label

    /**
     * The sheet line for the first gap, or null without gaps: `Last 180 m to "Taser Alm" not possible by gravel bike`;
     * the start: `First 180 m from point 1 …`; a skipped stop: `Route misses point 3 by 50 m`; more gaps: ` (+2 more)`.
     * [nameOf] = a point's name (index), or null.
     */
    fun text(gaps: List<PlanGap>, activity: Activity, nameOf: (Int) -> String?): String? {
        val g = gaps.firstOrNull() ?: return null
        val d = Format.distance(Math.round(g.distanceM / 10.0) * 10.0)
        val target = nameOf(g.index)?.trim()?.takeIf { it.isNotEmpty() }?.let { Txt.s(Res.string.plan_quoted, it) }
            ?: Txt.s(Res.string.plan_gap_point, g.index + 1)
        val line = if (g.skipped) Txt.s(Res.string.plan_gap_skipped, d, target)
            else Txt.s(if (g.index == 0) Res.string.plan_gap_first else Res.string.plan_gap_last, d, target, activityWords(activity))
        return if (gaps.size > 1) withMore(line, gaps.size - 1) else line
    }

    /** "<line> (+2 more)": a red line with [more] further problems behind it. */
    fun withMore(line: String, more: Int): String = Txt.s(Res.string.plan_more, line, more)

    /** The line already says "(+N more)" (any language: the count follows "(+"). */
    fun hasMore(line: String): Boolean = Regex("""\(\+\d+""").containsMatchIn(line)

    /** Where "Show" moves the map: the middle of the missing piece, close enough to see both ends. */
    fun showTarget(g: PlanGap): Pair<GeoPoint, Double> =
        GeoPoint((g.point.lat + g.routeEnd.lat) / 2, (g.point.lon + g.routeEnd.lon) / 2) to
            when { g.distanceM < 300 -> 17.0; g.distanceM < 600 -> 16.0; else -> 15.0 }
}
