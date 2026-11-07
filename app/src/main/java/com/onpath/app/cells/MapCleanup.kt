package com.onpath.app.cells

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.OfflineCorridor

/**
 * Settings → Offline maps → "Clean up": which installed map areas stay and which go. Pure, unit-tested (`MapCleanupTest`). Kept: the areas along the current plan (its route, else its points), along every saved route,
 * along a running tour (recorded line + followed route) and around the current position (+ [POSITION_MARGIN_M]).
 * Along a line the kept corridor is the one that downloads it ([OfflineCorridor]: hike 10 km, bike 20 km) – an unknown
 * activity keeps the largest, so a bike plan's corridor is never deleted (no download churn).
 * Everything else installed goes – nothing automatic, the user sees [Plan] first.
 */
object MapCleanup {
    /** What the user has right now. Lines are in map order; empty = none. */
    data class Inputs(
        /** Route line of the current plan, or its points when it has no route. */
        val plan: List<GeoPoint> = emptyList(),
        /** Saved routes (planned, imported) – one line each. */
        val savedRoutes: List<List<GeoPoint>> = emptyList(),
        /** Running tour: recorded segments. */
        val tour: List<List<GeoPoint>> = emptyList(),
        /** Running tour: the followed route. */
        val followed: List<GeoPoint> = emptyList(),
        val position: GeoPoint? = null,
        /** The current plan's activity (null = not known → the largest corridor). */
        val planActivity: Activity? = null,
        /** Activity of each of [savedRoutes], same order; missing or null = not known (the largest corridor). */
        val savedActivities: List<Activity?> = emptyList(),
        /** The running tour's activity, for [tour] and [followed] (null = not known). */
        val tourActivity: Activity? = null,
    )

    data class Plan(
        val keep: Set<CellId>,
        /** Installed areas that go, in installed order. */
        val remove: List<CellId>,
        /** Bytes freed (map files; routing parts are counted by the caller). */
        val removeBytes: Long,
    ) {
        val nothingToDo: Boolean get() = remove.isEmpty()
    }

    /** Margin kept around the current position. */
    const val POSITION_MARGIN_M = 10_000.0

    /** Cells to keep for [inputs] (whether installed or not). Lines of one point keep the area around that point. */
    fun keep(inputs: Inputs): Set<CellId> {
        val out = HashSet<CellId>()
        fun line(l: List<GeoPoint>, activity: Activity?) {
            if (l.isEmpty()) return
            val m = OfflineCorridor.marginM(activity)
            if (l.size == 1) out += CellId.near(l[0], m) else out += CellId.alongLine(l, m, max = 2000)
        }
        line(inputs.plan, inputs.planActivity)
        inputs.savedRoutes.forEachIndexed { i, l -> line(l, inputs.savedActivities.getOrNull(i)) }
        inputs.tour.forEach { line(it, inputs.tourActivity) }
        line(inputs.followed, inputs.tourActivity)
        inputs.position?.let { out += CellId.near(it, POSITION_MARGIN_M) }
        return out
    }

    /** [installed] = id and size of every installed area. */
    fun plan(installed: List<Pair<CellId, Long>>, inputs: Inputs): Plan {
        val k = keep(inputs)
        val gone = installed.filter { it.first !in k }
        return Plan(k.intersect(installed.map { it.first }.toSet()), gone.map { it.first }, gone.sumOf { it.second })
    }
}
