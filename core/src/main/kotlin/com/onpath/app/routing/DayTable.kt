package com.onpath.app.routing

import com.onpath.app.data.Format
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*

/**
 * The multi-day overview as a table of days (owner 2026-11-27, `docs/design/img/profilauswahl/mehrtage-tabelle.png`):
 * per day the cells km · Σ km (running total) · ↑ m · time, the unit behind each number so the columns line up, a total
 * row („Gesamt“), and a day above a limit per day with only the exceeded values marked ([Cells.overTime],
 * [Cells.overAscent]). The open day's fact line says by how much („1 h 10 und 120 m über deinem Tag“ – facts only).
 * Pure; shared by the app and the web planner (WebDays).
 */
object DayTable {
    /** One row's cells; [sum] = the running total (null in the total row). */
    data class Cells(
        val km: String,
        val sum: String?,
        val up: String,
        val time: String,
        val overTime: Boolean = false,
        val overAscent: Boolean = false,
    )

    /** The day rows' cells, in day order. */
    fun rows(days: List<TourDay>): List<Cells> {
        var along = 0.0
        return days.map { d ->
            along += d.distanceM
            Cells(km(d.distanceM), "Σ " + kmWhole(along), up(d.ascentM), time(d.timeS),
                DayLimitKind.TIME in d.over, DayLimitKind.ASCENT in d.over)
        }
    }

    /** The total row („Gesamt“ is [totalLabel]): km, ↑ m and time of all days. */
    fun total(days: List<TourDay>): Cells =
        Cells(km(days.sumOf { it.distanceM }), null, up(days.sumOf { it.ascentM }), time(days.sumOf { it.timeS }))

    fun totalLabel(): String = Txt.s(Res.string.days_total)

    /**
     * The open day's fact line when it is above a limit (owner: facts only): „1 h 10 und 120 m über deinem Tag“, only the
     * exceeded parts; null when the day keeps its limits (or none are set).
     */
    fun overLine(day: TourDay, limits: DayLimits): String? {
        val parts = buildList {
            val t = limits.maxTimeMin
            if (t != null && DayLimitKind.TIME in day.over) add(time(day.timeS - t * 60.0))
            val a = limits.maxAscentM
            if (a != null && DayLimitKind.ASCENT in day.over) add(meters(day.ascentM - a))
        }
        if (parts.isEmpty()) return null
        val what = if (parts.size == 2) Txt.s(Res.string.days_and, parts[0], parts[1]) else parts[0]
        return Txt.s(Res.string.days_over_day, what)
    }

    /** „87,1 km“ – one decimal, the table's day column. */
    fun km(m: Double): String = Format.number(m / 1000, 1) + " km"

    /** „646 km“ – whole km with the language's grouping, the running total. */
    fun kmWhole(m: Double): String = String.format(Format.locale(), "%,d km", Math.round(m / 1000))

    /** „↑ 1.783 m“. */
    fun up(m: Double): String = "↑ " + meters(m)

    private fun meters(m: Double): String = String.format(Format.locale(), "%,d m", Math.round(m.coerceAtLeast(0.0)))

    /** „4 h 10“ (the table's short time, rounded like every planned time), under an hour „45 min“. */
    fun time(s: Double): String {
        val r = TimeEstimate.round(s.coerceAtLeast(0.0))
        val total = Math.round(r / 60)
        val h = total / 60; val m = total % 60
        return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
    }
}
