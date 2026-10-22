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
    fun refreshDue(ageDays: Int = 30): List<CellId>
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
    /** A search result where there is no detailed map (owner 2026-10-21: a search IS a trigger). */
    SEARCHED,
    /** A short tap on the world map, after the user said "Load" to the question. */
    TAPPED,
}

/** Cells one trigger asked for: [own] (the point's / position's cell, null for a corridor) first in [cells]. */
data class CellJob(val cells: List<CellId>, val own: CellId?, val why: CellWhy)

/**
 * When the map is fetched (docs/design/map-cells.md §9): **the map comes where you plan or where you are**. Searching,
 * scrolling and zooming never download (T2 rejected). Pure rules on a [CellSource]; the ViewModel calls them and shows
 * the card for the returned [CellJob] (T1, T3, T4); T5 and T6 are quiet.
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
    private var lastRefreshCheck = 0L
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

    /** T4: a route was computed – every missing cell of the corridor (~10 km, F-OFF-02). Marks its cells used (T6). */
    fun routeComputed(line: List<GeoPoint>): CellJob? {
        if (line.size < 2) { planRoute(null); return null }
        val corridor = cells.cellsAlong(line, CORRIDOR_M)
        cells.keepCorridor(corridor) // the corridor of an older route goes (field bug 2026-10-22)
        cells.markUsed(corridor)
        val want = needed(corridor)
        if (want.isEmpty()) return null
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
     * Installed cells of an older content schema (e.g. no small streets): request them again at once (field report
     * 2026-10-21 – the style moved small streets into the overlays, older cells had none). Not rate-limited: the
     * queue skips cells already asked for.
     */
    fun updateOutdated(): List<CellId> {
        if (!cells.downloadsAvailable) return emptyList()
        val old = cells.outdated()
        return if (old.isEmpty()) emptyList() else cells.request(old, CellReason.UPDATE, ownPoint = true)
    }

    /**
     * After a route was computed (owner 2026-10-21: "at the moment I plan, I want a recent map"): ask the server for a
     * newer version of every installed cell of the corridor – a newer map style (content schema) or newer OSM data
     * (the server rebuilds areas older than its refresh age). Already built → quick download, the map switches (blurred
     * while it loads); to be built → the old map stays until the new one is ready. Each cell at most every
     * [FRESH_CHECK_MS]. Quiet: no card. Returns the cells asked for.
     */
    fun freshenAlong(line: List<GeoPoint>): List<CellId> {
        if (!cells.downloadsAvailable || line.size < 2) return emptyList()
        val t = now()
        val inst = cells.installedAmong(cells.cellsAlong(line, CORRIDOR_M))
            .filter { c -> lastFresh[c]?.let { t - it >= FRESH_CHECK_MS } ?: true }
        if (inst.isEmpty()) return emptyList()
        inst.forEach { lastFresh[it] = t }
        return cells.request(inst, CellReason.REFRESH, ownPoint = false)
    }

    /** T6: at most once a day, request a newer version of used cells (downloads only on Wi-Fi, MapCells). Quiet. */
    fun refreshIfDue(): List<CellId> {
        if (!cells.downloadsAvailable) return emptyList()
        val t = now()
        if (lastRefreshCheck != 0L && t - lastRefreshCheck < DAY_MS) return emptyList()
        lastRefreshCheck = t
        val due = cells.refreshDue(30)
        return if (due.isEmpty()) emptyList() else cells.request(due, CellReason.REFRESH, ownPoint = false)
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
        const val AHEAD_M = 5_000.0
        const val T3_MAX_ACCURACY_M = 500f
        const val AHEAD_RETRY_MS = 30 * 60_000L
        const val DAY_MS = 24 * 3600_000L
        const val FRESH_CHECK_MS = 3600_000L
    }
}
