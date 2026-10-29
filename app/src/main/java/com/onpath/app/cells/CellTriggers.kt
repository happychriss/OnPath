package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint

/** What the trigger rules need from [MapCells] (an interface so the rules are unit-tested with a fake). */
interface CellSource {
    fun cellsAround(p: GeoPoint, radiusM: Double = 5000.0): List<CellId>
    fun cellsAlong(line: List<GeoPoint>, corridorM: Double = 10_000.0): List<CellId>
    /** Of [cells]: neither installed nor queued. */
    fun missing(cells: List<CellId>): List<CellId>
    /** An installed cell or the region package covers [p]. */
    fun hasDetailedMap(p: GeoPoint): Boolean
    fun request(cells: List<CellId>, reason: CellReason, ownPoint: Boolean = reason == CellReason.POINT): List<CellId>
    /** Of [cells]: those installed on the phone – default none (test fakes). */
    fun installedAmong(cells: List<CellId>): List<CellId> = emptyList()
    /** Installed cells built for an older style (content schema) – default none (test fakes). */
    fun outdated(): List<CellId> = emptyList()
    fun markUsed(cells: Collection<CellId>)
    /** Corridor requests belong to the current plan: drop those outside [keep]; returns the dropped ones. */
    fun keepCorridor(keep: Collection<CellId>): List<CellId> = emptyList()
    /** False when the build has no map server key. */
    val downloadsAvailable: Boolean
}

/** Why the map is fetched – the card says it (map-cells.md §9). */
enum class CellWhy {
    /** T1 / T4: a point was set or a route computed there. */
    PLANNED,
    /** T3 / T5: the own position. */
    HERE,
    /** Installed areas built for an older map style are loaded again (content schema). */
    UPDATE,
    /** A search result where there is no detailed map (owner: a search is a trigger). */
    SEARCHED,
    /** A short tap on the world map, after the user said "Load" to the question. */
    TAPPED,
}

/** Cells one trigger asked for: [own] (the point's / position's cell, null for a corridor) first in [cells]. */
data class CellJob(val cells: List<CellId>, val own: CellId?, val why: CellWhy)

/**
 * When the map is fetched (docs/design/map-cells.md §9): **the map comes where you plan or where you are**. A search
 * result loads at once (T2), a tap on the world map asks first (T2b); scrolling and zooming never download. Pure rules
 * on a [CellSource]; the ViewModel calls them and shows the card for the returned [CellJob]; T5 and T6 are quiet.
 *
 * A cell counts as "without detailed map" when no installed cell and no region package covers its centre (cells fully
 * inside the region package are not fetched – they would only duplicate it).
 */
class CellTriggers(
    private val cells: CellSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** T5: last request per cell, so a cell the server failed to build is not asked for on every fix. */
    private val lastAhead = HashMap<CellId, Long>()
    /** Last after-routing check per installed cell ([freshenAlong]). */
    private val lastFresh = HashMap<CellId, Long>()

    /** Close neighbours (Q1). */
    private fun needed(candidates: List<CellId>): List<CellId> =
        cells.missing(candidates).filter { !cells.hasDetailedMap(it.center) }

    /**
     * T1: a point was set (hold → Start here / Go here, a stop added, a point moved) – or T3 ([why] = HERE): the app
     * opened in Plan at a position. Nothing when the point already has the detailed map. Own cell with the own-point
     * rule (mobile data without asking), close neighbours within [NEAR_M] as NEAR.
     */
    fun pointSet(p: GeoPoint, why: CellWhy = CellWhy.PLANNED): CellJob? {
        if (cells.hasDetailedMap(p)) return null
        val around = cells.cellsAround(p, NEAR_M)
        val own = around.firstOrNull() ?: return null
        val want = needed(around)
        if (want.isEmpty()) return null
        if (!cells.downloadsAvailable) return CellJob(want, own, why)
        val added = ArrayList<CellId>()
        if (own in want) added += cells.request(listOf(own), CellReason.POINT)
        val near = want.filter { it != own }
        if (near.isNotEmpty()) added += cells.request(near, CellReason.NEAR, ownPoint = false)
        return if (added.isEmpty()) null else CellJob(added.sortedBy { if (it == own) 0 else 1 }, own.takeIf { it in added }, why)
    }

    /**
     * What a tap at [p] would load (the question "Load the map for this area?" first – never a silent download from a
     * tap): nothing where the detailed map is or everything is asked for already; else the point's cell and its close
     * neighbours without detailed map. Asks the server nothing.
     */
    fun preview(p: GeoPoint): List<CellId> {
        if (cells.hasDetailedMap(p)) return emptyList()
        return needed(cells.cellsAround(p, NEAR_M))
    }

    /**
     * T3: the app opened (or came back) in Plan with a fix of [accuracyM]. A network fix is enough to pick the cell;
     * worse than [T3_MAX_ACCURACY_M] → nothing (the overview stays).
     */
    fun appOpened(p: GeoPoint, accuracyM: Float?): CellJob? {
        if (accuracyM != null && accuracyM > T3_MAX_ACCURACY_M) return null
        return pointSet(p, CellWhy.HERE)
    }

    /**
     * The corridor cells of [line] (~10 km) – pure geometry (~8 ms for 250 km), so compute it off the main thread once
     * and pass it to [routeComputed] and [freshenAlong].
     */
    fun corridorOf(line: List<GeoPoint>): List<CellId> = if (line.size < 2) emptyList() else cells.cellsAlong(line, CORRIDOR_M)

    /** T4: a route was computed – every missing cell of the corridor (~10 km, F-OFF-02). Marks its cells used (T6). */
    fun routeComputed(line: List<GeoPoint>, corridorCells: List<CellId>? = null): CellJob? {
        if (line.size < 2) { planRoute(null); return null }
        val corridor = corridorCells ?: cells.cellsAlong(line, CORRIDOR_M)
        cells.keepCorridor(corridor) // the corridor of an older route goes
        cells.markUsed(corridor)
        val all = needed(corridor)
        if (all.isEmpty()) return null
        // never hundreds of regions for one route (owner): the first stretch loads,
        // the rest follows on tour (T5, the map ahead) – the corridor is in order along the line
        val want = all.take(CORRIDOR_MAX_CELLS)
        if (all.size > want.size) com.onpath.app.debug.Problems.info("cells",
            "Route corridor needs ${all.size} map regions – only the first $CORRIDOR_MAX_CELLS load now, the rest on tour")
        if (!cells.downloadsAvailable) return CellJob(want, null, CellWhy.PLANNED)
        val added = cells.request(want, CellReason.CORRIDOR, ownPoint = false)
        return if (added.isEmpty()) null else CellJob(added, null, CellWhy.PLANNED)
    }

    /**
     * The plan's route settled without a corridor to load ([line] null: cleared, long-route question, Cancel, not
     * routed, routing failed) or with [line]: corridor requests outside it are dropped – nothing new is requested.
     * Returns the dropped cells.
     */
    fun planRoute(line: List<GeoPoint>?): List<CellId> =
        cells.keepCorridor(if (line == null || line.size < 2) emptyList() else cells.cellsAlong(line, CORRIDOR_M))

    /**
     * T5: on tour, the position [p] is within [AHEAD_M] of the edge of the detailed map → the next cells (NEAR, own-point
     * rule: they load on mobile data – the user is out there). Quiet: no card. Each cell at most every [AHEAD_RETRY_MS].
     * Returns the cells asked for.
     */
    fun onTour(p: GeoPoint): List<CellId> {
        if (!cells.downloadsAvailable) return emptyList()
        val around = cells.cellsAround(p, AHEAD_M)
        cells.markUsed(around)
        val t = now()
        val want = needed(around).filter { c -> lastAhead[c]?.let { t - it >= AHEAD_RETRY_MS } ?: true }
        if (want.isEmpty()) return emptyList()
        want.forEach { lastAhead[it] = t }
        return cells.request(want, CellReason.NEAR, ownPoint = true)
    }

    /**
     * After a route was computed (owner: "at the moment I plan, I want a recent map"): ask the server for a
     * newer version of every installed cell of the corridor – a newer map style (content schema) or newer OSM data
     * (the server rebuilds areas older than its refresh age). Already built → quick download, the map switches (blurred
     * while it loads); to be built → the old map stays until the new one is ready. Each cell at most every
     * [FRESH_CHECK_MS]. Quiet: no card. Returns the cells asked for.
     */
    fun freshenAlong(line: List<GeoPoint>, corridorCells: List<CellId>? = null): List<CellId> {
        if (!cells.downloadsAvailable || line.size < 2) return emptyList()
        val t = now()
        val inst = cells.installedAmong(corridorCells ?: cells.cellsAlong(line, CORRIDOR_M))
            .filter { c -> lastFresh[c]?.let { t - it >= FRESH_CHECK_MS } ?: true }
        if (inst.isEmpty()) return emptyList()
        inst.forEach { lastFresh[it] = t }
        return cells.request(inst, CellReason.REFRESH, ownPoint = false)
    }

    companion object {
        /**
         * The card's job after a trigger: a new [next] joins the open card (its cells after the open ones, the first own
         * cell stays first); a finished card ([openDone]) or none is replaced.
         */
        fun merge(open: CellJob?, next: CellJob, openDone: Boolean): CellJob =
            if (open == null || openDone) next
            else CellJob((open.cells + next.cells).distinct(), open.own ?: next.own, open.why)

        /** Points in [after] that were not in [before] (by value): the points the user just set (T1). */
        fun newPoints(before: List<GeoPoint>, after: List<GeoPoint>): List<GeoPoint> {
            val old = before.toHashSet()
            return after.filter { it !in old }.distinct()
        }

        /**
         * A point / near request for [c] still belongs to one of [points] (planned points, the search mark, the
         * position): [c] is among the cells T1 / T3 would ask for there.
         */
        fun nearAny(c: CellId, points: List<GeoPoint>): Boolean = points.any { c in CellId.near(it, NEAR_M) }

        const val NEAR_M = 5_000.0
        const val CORRIDOR_M = 10_000.0
        /** At most this many corridor regions load for one route (≈ 300 km of route, ~300 MB). */
        const val CORRIDOR_MAX_CELLS = 30
        const val AHEAD_M = 5_000.0
        const val T3_MAX_ACCURACY_M = 500f
        const val AHEAD_RETRY_MS = 30 * 60_000L
        const val FRESH_CHECK_MS = 3600_000L
    }
}
