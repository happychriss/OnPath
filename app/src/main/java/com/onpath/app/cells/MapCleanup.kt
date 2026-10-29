package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint

/**
 * Settings → Offline maps → "Clean up": which installed map areas stay and which go. Pure, unit-tested (`MapCleanupTest`). Kept: the areas along the current plan (its route, else its points), along every saved route,
 * along a running tour (recorded line + followed route) and around the current position (+ [POSITION_MARGIN_M]).
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

    /** Corridor kept along lines – the same as the route corridor that downloads them (no download churn). */
    const val CORRIDOR_M = CellTriggers.CORRIDOR_M
    /** Margin kept around the current position. */
    const val POSITION_MARGIN_M = 10_000.0

    /** Cells to keep for [inputs] (whether installed or not). Lines of one point keep the area around that point. */
    fun keep(inputs: Inputs): Set<CellId> {
        val out = HashSet<CellId>()
        fun line(l: List<GeoPoint>) {
            if (l.isEmpty()) return
            if (l.size == 1) out += CellId.near(l[0], CORRIDOR_M) else out += CellId.alongLine(l, CORRIDOR_M, max = 2000)
        }
        line(inputs.plan)
        inputs.savedRoutes.forEach(::line)
        inputs.tour.forEach(::line)
        line(inputs.followed)
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
