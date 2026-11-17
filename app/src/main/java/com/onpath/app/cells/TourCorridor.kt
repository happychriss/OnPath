package com.onpath.app.cells

import com.onpath.app.routing.GeoPoint

/**
 * The map areas of a multi-day tour (docs/design/multi-day-tours.md §7, stage 0 measured: München–Venedig ≈ 340 MB with
 * a 10 km corridor, 27 areas; ≈ 520 MB with 20 km): a **10 km** corridor and **all** its areas – never capped at
 * [CellTriggers.CORRIDOR_MAX_CELLS] – loaded only after the user said so (the map question at the first opening, or
 * "Laden" for a picked day). Pure: the area lookups come in as functions ([MapCells.cellsAlong], [MapCells.notOnPhone],
 * [MapCells.knownSize]); `TourCorridorTest`.
 */
object TourCorridor {
    /** Either side of a multi-day tour's line (bike plans otherwise use 20 km, OfflineCorridor). */
    const val CORRIDOR_M = 10_000.0

    /**
     * One map area with its share of the region view and the routing data, when the server has not told its size yet
     * (stage 0: 340 MB / 27 areas ≈ 12.6 MB).
     */
    const val CELL_BYTES = 12_500_000L

    /** What a tour needs: every area of its corridor, those not on the phone, and about how many bytes they are. */
    data class Maps(val all: List<CellId>, val missing: List<CellId>, val bytes: Long) {
        val complete: Boolean get() = missing.isEmpty()
    }

    /**
     * The maps of [line] (empty below two points). [along] = the areas within a corridor of a line, in order;
     * [notOnPhone] = of some areas, those neither installed nor covered by the region package; [knownSize] = the server's
     * size of an area, null = not known yet ([CELL_BYTES] then).
     */
    fun of(line: List<GeoPoint>, along: (List<GeoPoint>, Double) -> List<CellId>, notOnPhone: (List<CellId>) -> List<CellId>,
           knownSize: (CellId) -> Long? = { null }): Maps {
        val all = if (line.size < 2) emptyList() else along(line, CORRIDOR_M).distinct()
        val missing = notOnPhone(all)
        return Maps(all, missing, missing.sumOf { knownSize(it)?.takeIf { s -> s > 0 } ?: CELL_BYTES })
    }

    /** The stretch of [line] from [fromM] to [toM] along it (a day's line, for "Only day n" / the day check). */
    fun stretch(line: List<GeoPoint>, fromM: Double, toM: Double): List<GeoPoint> {
        if (line.size < 2) return line
        var d = 0.0
        val out = ArrayList<GeoPoint>()
        for (i in line.indices) {
            if (i > 0) d += com.onpath.app.data.Geo.distance(line[i - 1], line[i])
            if (d >= fromM - 0.5 && d <= toM + 0.5) out += line[i]
            if (d > toM + 0.5) break
        }
        return if (out.size >= 2) out else line.take(0)
    }
}
