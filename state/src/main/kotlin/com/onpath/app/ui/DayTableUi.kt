package com.onpath.app.ui

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLimitKind
import com.onpath.app.routing.DayRowText
import com.onpath.app.routing.DayTable
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TourDay

/**
 * One day of the multi-day day table (owner 2026-11-27, `docs/design/img/profilauswahl/mehrtage-tabelle.png`, the planner's
 * table): day [n], the grey „von – nach“ under „Tag N“ ([places], null = none), the core [cells] (km · Σ km · ↑ m · time with
 * their units, exceeded values marked), its end ([end]: moon / bed; null = the finish, the goal flag) and [over] (a limit per
 * day exceeded: the row in warn yellow).
 */
data class DayTableLine(val n: Int, val places: String?, val cells: DayTable.Cells, val end: DayEndKind?, val over: Boolean) {
    val day: String get() = DayRowText.day(n)
}

/** The table: one line per day and the total row („Gesamt“ – [totalLabel]). */
data class DayTableUi(val lines: List<DayTableLine>, val total: DayTable.Cells, val totalLabel: String)

/**
 * The day table from the days ([DayTable], the same shared code as the planner's WebDays) – for My routes' day sheet (DayPick;
 * the plan sheet shows no whole-trip table since 2026-11-28, MD-1). Pure – DayTableUiTest.
 */
object DayTables {
    /** The table of [days]; [places] = each day's grey line (same order, shorter = none). */
    fun of(days: List<TourDay>, places: List<String?>): DayTableUi {
        val cells = DayTable.rows(days)
        val lines = days.mapIndexed { i, d -> DayTableLine(d.number, places.getOrNull(i), cells[i], d.end?.kind, d.over.isNotEmpty()) }
        return DayTableUi(lines, DayTable.total(days), DayTable.totalLabel())
    }

    /** A day of numbers only (My routes' day sheet keeps no line positions): what [DayTable] reads of it. */
    fun day(n: Int, distanceM: Double, ascentM: Double, timeS: Double, end: DayEndKind?, over: Set<DayLimitKind>): TourDay =
        TourDay(n, 0.0, 0.0, distanceM, ascentM, 0.0, timeS, end?.let { com.onpath.app.routing.DayEnd(it, 0.0, 0, GeoPoint(0.0, 0.0)) }, over)
}
