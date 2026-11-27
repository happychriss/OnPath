package com.onpath.app.domain

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits

/**
 * Which marker a planned point gets on the map (map/MapController `setWaypoints`). Pure.
 * - "g" unreached (grey hollow), "p" passed in a running tour (grey), "s" start (①), "v" stop, "e" goal (the flag).
 * - A round trip's start is its goal too: it shows the goal's flag, never the „1“ (owner 2026-11-27). [loopStart] =
 *   the loop's ① ([com.onpath.app.routing.PlanEdits.loopStart]; stops on the way before it keep their numbers).
 */
object PointMarks {
    fun kind(i: Int, size: Int, unreached: Set<Int> = emptySet(), passed: Set<Int> = emptySet(), loopStart: Int? = null): String =
        when {
            i in unreached -> "g"
            i in passed -> "p"
            i == loopStart -> "e"
            i == 0 -> "s"
            i == size - 1 && size > 1 -> "e"
            else -> "v"
        }

    /** The kind of every point of [points] (the round trip read from them, [PlanEdits.loopStart]). */
    fun kinds(points: List<GeoPoint>, unreached: Set<Int> = emptySet(), passed: Set<Int> = emptySet()): List<String> {
        val loopStart = PlanEdits.loopStart(points)
        return points.indices.map { kind(it, points.size, unreached, passed, loopStart) }
    }
}
