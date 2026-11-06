package com.onpath.app.ui

import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellState
import com.onpath.app.data.Format
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.strings.*

/**
 * "Ohne Netz ✓ / ⟳ / ⚠" in the plan's mode pill (owner 2026-11-03, comparison card 3 – offline made visible, A-020 §4):
 * can the current route be ridden without a network? Pure, unit-tested (`OfflineReadyTest`).
 *
 * - The **map** along the route: every map region within [ROUTE_M] of the line is on the phone (an installed cell, a
 *   region package, or one downloaded during a tour and waiting to be joined).
 * - The **ways** (routing data) in the route's corridor: the same parts the app loads for a computed route
 *   (`RoutingTiles.forLine`, ~10 km either side) are on the phone.
 * - READY when both hold; LOADING when everything missing is on its way (a cell requested / building / downloading, a
 *   routing part in the loader's running list); MISSING when something is missing and not loading (no network, Wi-Fi
 *   only, failed, outside the regions loaded for a long route).
 */
object OfflineReady {
    /** Map regions within this distance of the line count (the same as the map card's "the route crosses them"). */
    const val ROUTE_M = com.onpath.app.cells.CellTriggers.ROUTE_M

    enum class State { READY, LOADING, MISSING }

    /** One map region's part in the answer. */
    enum class Cell { HERE, LOADING, MISSING }

    /**
     * The answer for a route. [missingMapM]: metres of the line over a region that is not on the phone (loading or
     * missing); [waysMissing]: routing data for the corridor is not all on the phone; [loadingBytes]: what is still to
     * download (0 = unknown).
     */
    data class Result(val state: State, val missingMapM: Double, val waysMissing: Boolean, val loadingBytes: Long)

    /**
     * A region's state as the map cells report it, [onPhone] = installed or covered by a region package. Only a region
     * that is IN the map counts (owner 2026-11-06: "Ohne Netz" only when every map of the route really is on the phone):
     * still being joined ([CellState.Installing]) or downloaded during a tour and joined only afterwards
     * ([CellState.Downloaded] – the map does not show it yet) is LOADING, not HERE.
     */
    fun cell(s: CellState?, onPhone: Boolean): Cell = when {
        onPhone -> Cell.HERE
        s is CellState.Ready -> Cell.HERE
        s is CellState.Installing || s is CellState.Downloaded -> Cell.LOADING
        s is CellState.Requested || s is CellState.Queued || s is CellState.Building || s is CellState.Downloading -> Cell.LOADING
        else -> Cell.MISSING // None, waiting for Wi-Fi, offline, failed: nothing comes by itself now
    }

    /**
     * The state of the route [line]: [cells] = the regions along it with their [Cell] state, [parts] = the corridor's
     * routing parts, [partHere] = on the phone, [partsLoading] = in the loader's running list. Null for no route.
     */
    fun of(line: List<GeoPoint>, cells: Map<CellId, Cell>, parts: Collection<String>, partHere: (String) -> Boolean,
           partsLoading: Set<String>, loadingBytes: Long = 0): Result? {
        if (line.size < 2) return null
        val notHere = cells.filterValues { it != Cell.HERE }
        val partsMissing = parts.filter { !partHere(it) }
        if (notHere.isEmpty() && partsMissing.isEmpty()) return Result(State.READY, 0.0, false, 0)
        val stuck = notHere.values.any { it == Cell.MISSING } || partsMissing.any { it !in partsLoading }
        return Result(if (stuck) State.MISSING else State.LOADING, lengthOver(line, notHere.keys), partsMissing.isNotEmpty(),
            loadingBytes.coerceAtLeast(0))
    }

    /** Metres of [line] whose pieces lie over one of [cells] (a piece counts by its middle). */
    fun lengthOver(line: List<GeoPoint>, cells: Set<CellId>): Double {
        if (cells.isEmpty() || line.size < 2) return 0.0
        var m = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]; val b = line[i]
            val mid = CellId.at((a.lat + b.lat) / 2, (a.lon + b.lon) / 2) ?: continue
            if (mid in cells) m += Geo.distance(a, b)
        }
        return m
    }

    /** The pill's text after "PLANEN · ": "Ohne Netz" – the sign comes as an icon beside it. */
    val pillWord: String get() = Txt.s(Res.string.plan_offline_pill)

    /** The line a tap on the pill shows (plain words, what the code can tell: km of missing map, MB loading). */
    fun explain(r: Result): String = when (r.state) {
        State.READY -> Txt.s(Res.string.plan_offline_ready)
        State.LOADING -> if (r.loadingBytes > 0) Txt.s(Res.string.plan_offline_loading_mb, mb(r.loadingBytes))
            else Txt.s(Res.string.plan_offline_loading)
        State.MISSING -> if (r.missingMapM >= 50) Txt.s(Res.string.plan_offline_missing_map, Format.distance(r.missingMapM))
            else Txt.s(Res.string.plan_offline_missing_ways)
    }

    private fun mb(bytes: Long): String = "${((bytes + 524_288) / 1_048_576).coerceAtLeast(1)} MB"
}
