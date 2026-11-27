package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.TourDays
import com.onpath.app.routing.WaypointInsertion

/**
 * One day of a saved multi-day route in My routes' day sheet (owner 2026-11-27): day [n], the names at its ends as the plan has
 * them ([from] / [to]; null = not known – the row says "Tag n" only), km, ↑ m, time, its end ([end]: night not decided /
 * Übernachtung / Hotel; null = the finish), [riddenMs] = when the newest recorded tour that rode it was recorded (null = not
 * ridden), and the day's stretch of the stored line ([fromLine]..[toLine]) for the day's own overview.
 */
data class DayPickRow(val n: Int, val from: String?, val to: String?, val distanceM: Double, val ascentM: Double, val timeS: Double,
                      val end: DayEndKind?, val riddenMs: Long?, val fromLine: Int, val toLine: Int)

/**
 * The day sheet of a saved multi-day route: the route, its stored line (display only), the rows and the next day not yet ridden
 * ([next], highlighted; null = every day ridden).
 */
data class DayPickUi(val tour: Tour, val line: List<GeoPoint>, val rows: List<DayPickRow>, val next: Int?)

/**
 * The day sheet's rules (owner 2026-11-27, "ja"): the days of a stored multi-day route along its stored line (the same split the
 * plan sheet's overview makes – [PlanDays] over [DayLine] – so the day a row loads is the day it shows), the names at the day
 * ends as the plan has them, the days ridden (a recorded tour not in the bin that remembers the route's uuid and the day) and
 * the next open day. Pure – DayPickTest.
 */
object DayPick {
    /** The days of [t] along its stored [line] with the pace [factors] and [activity]; null = no multi-day route / no line. */
    fun days(t: Tour, line: List<GeoPoint>, activity: Activity, factors: com.onpath.app.time.Factors): Pair<TourDays, IntArray>? {
        val d = t.days ?: return null
        val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
        if (line.size < 2 || pts.size < 2) return null
        val at = WaypointInsertion.routeIndices(pts, line)
        return PlanDays.of(DayLine.of(line, activity, factors), at, d.days, d.limits, PlanDays.clean(pts, d.nights)) to at
    }

    /** The rows of [t]'s [days]; [tours] = My routes' list (the recorded tours that rode a day). */
    fun rows(t: Tour, days: TourDays, tours: List<Tour>): List<DayPickRow> {
        val nights = t.days?.nights.orEmpty()
        fun known(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        // a plan point's name, else its night's hotel name – "as the plan has them", no place lookup
        fun nameAt(i: Int?): String? = i?.let { known(t.waypoints.getOrNull(it)?.name) ?: nights[it]?.hotel?.let { h -> known(h.name) } }
        val ends: List<Int?> = listOf<Int?>(0) + days.days.map { d -> d.end?.planIndex ?: if (d.end == null) t.waypoints.lastIndex else null }
        val rode = ridden(t.uuid, tours)
        var startLine = 0
        return days.days.mapIndexed { k, d ->
            val endLine = d.end?.lineIndex ?: Int.MAX_VALUE
            DayPickRow(d.number, nameAt(ends[k]), nameAt(ends[k + 1]), d.distanceM, d.ascentM, d.timeS, d.end?.kind, rode[d.number],
                startLine, endLine).also { startLine = d.end?.lineIndex ?: startLine }
        }
    }

    /**
     * The days of route [uuid] ridden: day → when the newest recorded tour that rode it was recorded. A tour in the recycle bin or
     * still recording does not count (deleting it takes the ✓ away).
     */
    fun ridden(uuid: String, tours: List<Tour>): Map<Int, Long> {
        if (uuid.isEmpty()) return emptyMap()
        return tours.filter { it.kind == TourKind.RECORDED && it.deletedMs == null && it.state != "active" && it.planUuid == uuid && (it.planDay ?: 0) > 0 }
            .groupBy { it.planDay!! }.mapValues { (_, l) -> l.maxOf { it.createdMs } }
    }

    /**
     * The day to highlight: the one after the last day ridden, while it is not ridden itself; else the first day not ridden;
     * null when every day of [count] is ridden.
     */
    fun next(count: Int, ridden: Set<Int>): Int? {
        val after = ridden.filter { it in 1..count }.maxOrNull()?.plus(1)
        if (after != null && after <= count && after !in ridden) return after
        return (1..count).firstOrNull { it !in ridden }
    }

    /** The saved multi-day route with [uuid] in My routes' list (planned, not in the bin); null = none. */
    fun route(uuid: String, tours: List<Tour>): Tour? =
        tours.firstOrNull { it.uuid == uuid && it.kind == TourKind.PLANNED && it.deletedMs == null && !it.draft && it.days != null }

    /** A card of My routes opens the day sheet (a saved multi-day route), not the normal overview. */
    fun opens(t: Tour): Boolean = t.kind == TourKind.PLANNED && t.days != null && !t.draft && t.deletedMs == null && t.uuid.isNotEmpty()

    /** Gemacht's line: the recorded tours' route names ([Tour.planName]) and whether that route's day sheet can open. */
    fun named(list: List<Tour>, planName: (Long) -> String?): List<Tour> {
        val routes = list.filter { it.kind == TourKind.PLANNED && it.deletedMs == null && !it.draft && it.days != null }.associateBy { it.uuid }
        return list.map { t ->
            val uuid = t.planUuid?.takeIf { t.kind == TourKind.RECORDED && (t.planDay ?: 0) > 0 } ?: return@map t
            val r = routes[uuid]
            if (r != null) t.copy(planName = r.name, planOpens = true) else t.copy(planName = planName(t.id), planOpens = false)
        }
    }
}
