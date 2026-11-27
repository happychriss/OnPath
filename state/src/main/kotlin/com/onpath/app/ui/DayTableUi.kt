package com.onpath.app.ui

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLimitKind
import com.onpath.app.routing.DayLimits
import com.onpath.app.routing.DayRowText
import com.onpath.app.routing.DayTable
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.TourDay
import com.onpath.strings.*

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
 * The day table from the days ([DayTable], the same shared code as the planner's WebDays) – for the plan sheet's overview
 * (MultiDaySlice.daysUi) and My routes' day sheet (DayPick). Pure – DayTableUiTest.
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

/** The save state in the multi-day panel's head (planner: „Nicht gespeichert / Geändert · nicht gespeichert / Gespeichert“). */
enum class DaySaveState {
    NOT_SAVED, CHANGED, SAVED;

    fun text(): String = Txt.s(when (this) {
        NOT_SAVED -> Res.string.days_not_saved
        CHANGED -> Res.string.days_changed
        SAVED -> Res.string.days_saved
    })

    companion object {
        /** [savedName] = the name the plan was last saved / opened as (null = never), [unsaved] = changed since. */
        fun of(savedName: String?, unsaved: Boolean): DaySaveState = when {
            savedName == null -> NOT_SAVED
            unsaved -> CHANGED
            else -> SAVED
        }
    }
}

/**
 * The multi-day panel's head (owner's sketch 2026-11-27, planner d7d01bd7b): the tour's [title] with „Speichern“ beside it,
 * the [save] state under it, and „Gesamte Reise“ [whole] („672,4 km · 35 h 05 · ↑ 1.808 m · ↓ 1.321 m“; null = no route).
 */
data class MultiDayHeadUi(val title: String, val save: DaySaveState, val whole: String?)

/** „Planung“ and the tour's chips: „8 Tage ✎“, „4,5 h / Tag“, „500 m ↑ / Tag“ (a chip only for a limit that is set). */
data class PlanningUi(val days: String, val hours: String?, val ascent: String?)

/**
 * The texts of the multi-day panel (owner's sketch 2026-11-27, parity with the planner's multi-day panel): head, planning
 * chips, the profile's title and one day's part of the profile. Pure – DayTableUiTest.
 */
object MultiDayPanel {
    /** The head of tour [name] (null / blank = „Mehrtagestour“) with the route's totals (null = not routed). */
    fun head(name: String?, savedName: String?, unsaved: Boolean, distanceM: Double?, timeS: Double, ascentM: Double, descentM: Double): MultiDayHeadUi =
        MultiDayHeadUi(name?.trim()?.takeIf { it.isNotEmpty() } ?: Txt.s(Res.string.days_multi_day_tour), DaySaveState.of(savedName, unsaved),
            distanceM?.let { whole(it, timeS, ascentM, descentM) })

    /** „672,4 km · 35 h 05 · ↑ 1.808 m · ↓ 1.321 m“ – the table's own number forms. */
    fun whole(distanceM: Double, timeS: Double, ascentM: Double, descentM: Double): String =
        listOf(DayTable.km(distanceM), DayTable.time(timeS), DayTable.up(ascentM), DayTable.down(descentM)).joinToString(" · ")

    /** The chips of a tour of [days] days with [limits]. */
    fun planning(days: Int, limits: DayLimits): PlanningUi {
        // as the planner writes them: „4,5“, „1.000“ (grouped, at most one decimal)
        fun num(x: Double) = if (x % 1.0 == 0.0) String.format(com.onpath.app.data.Format.locale(), "%,d", x.toLong())
            else com.onpath.app.data.Format.number(x, 1)
        return PlanningUi(Txt.p(Res.plurals.multiday_days_count, days, days),
            limits.maxTimeMin?.let { Txt.s(Res.string.days_chip_hours, num(it / 60.0)) },
            limits.maxAscentM?.let { Txt.s(Res.string.days_chip_ascent, num(it.toDouble())) })
    }

    /** „Höhenprofil · gesamte Reise“, or „Höhenprofil · Tag N“ for an open day [day]. */
    fun profileTitle(day: Int?): String =
        if (day == null) Txt.s(Res.string.days_profile_whole_trip) else Txt.s(Res.string.days_profile_day, DayRowText.day(day))

    /**
     * Day [day]'s part of the whole trip's [profile] (the planner's dayProfile: only that day, 0 … its km): the points between
     * its day borders ([borders] = share of the route where each day ends, in order). The whole profile when the day is not
     * known or the profile is too short.
     */
    fun dayProfile(profile: List<GeoPoint>, borders: List<Float>, day: Int?): List<GeoPoint> {
        if (day == null || profile.size < 2 || day < 1 || day > borders.size + 1) return profile
        val along = DoubleArray(profile.size)
        for (i in 1 until profile.size) along[i] = along[i - 1] + com.onpath.app.data.Geo.distance(profile[i - 1], profile[i])
        val total = along.last().takeIf { it > 0 } ?: return profile
        val from = (if (day == 1) 0f else borders[day - 2]) * total
        val to = (if (day == borders.size + 1) 1f else borders[day - 1]) * total
        // the line points at or just outside the borders, so the day's part reaches its border (1 m: rounding of the shares)
        val a = along.indexOfLast { it <= from + 1.0 }.coerceAtLeast(0)
        val b = along.indexOfFirst { it >= to - 1.0 }.let { if (it < 0) along.lastIndex else it }.coerceAtLeast(a)
        val part = profile.subList(a, b + 1)
        return if (part.size >= 2) part else profile
    }
}
