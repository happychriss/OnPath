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
 * Übernachtung / Hotel; null = the finish), [riddenMs] = when the newest recorded tour that rode it to its goal was recorded
 * (✓; null = not done), and the day's stretch of the stored line ([fromLine]..[toLine]) for the day's own overview.
 */
data class DayPickRow(val n: Int, val from: String?, val to: String?, val distanceM: Double, val ascentM: Double, val timeS: Double,
                      val end: DayEndKind?, val riddenMs: Long?, val fromLine: Int, val toLine: Int,
                      /** The limits per day this day is above (the table's warn-yellow row, bold values). */
                      val over: Set<com.onpath.app.routing.DayLimitKind> = emptySet(),
                      /** Not done, but a recording of it stopped before the goal (MD-5, „angefangen am …“): when the newest was recorded. */
                      val startedMs: Long? = null)

/**
 * The day sheet of a saved multi-day route: the route, its stored line (display only), the rows and the next day not yet ridden
 * ([next], highlighted; null = every day ridden).
 */
data class DayPickUi(val tour: Tour, val line: List<GeoPoint>, val rows: List<DayPickRow>, val next: Int?)

/**
 * The day sheet's rules (owner 2026-11-27, "ja"): the days of a stored multi-day route along its stored line (the same split the
 * plan sheet's overview makes – [PlanDays] over [DayLine] – so the day a row loads is the day it shows), the names at the day
 * ends as the plan has them, the days done (a recorded tour not in the bin that remembers the route's uuid and the day and
 * reached its goal), the days begun (it stopped before) and the next open day. Pure – DayPickTest.
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

    /**
     * How far a day's start or end may lie from where the ridden tour's day started / ended and still be that day (owner
     * 2026-11-27): 200 m – a stop the router moved onto its way, a night mark nudged in the planner stay the day; a day end
     * moved to the next village, a day added before it (the days renumber) or the route turned round do not.
     */
    const val SAME_END_M = 200.0

    /** The start and end of each day along [line] (the day's stretch, [DayPickRow.fromLine]..[DayPickRow.toLine]). */
    fun dayEnds(line: List<GeoPoint>, rows: List<DayPickRow>): Map<Int, Pair<GeoPoint, GeoPoint>> {
        if (line.isEmpty()) return emptyMap()
        return rows.associate { r ->
            val a = r.fromLine.coerceIn(0, line.lastIndex); val b = r.toLine.coerceIn(a, line.lastIndex)
            r.n to (line[a] to line[b])
        }
    }

    /**
     * Recorded tour [t] rode the day whose ends are [ends] now: both its ridden ends ([Tour.planEnds]) lie within [SAME_END_M]
     * of them. Unknown ends on either side never match (no ✓ claimed that cannot be checked).
     */
    fun matches(t: Tour, ends: Pair<GeoPoint, GeoPoint>?): Boolean {
        val r = t.planEnds ?: return false
        val e = ends ?: return false
        return com.onpath.app.data.Geo.distance(r.first, e.first) <= SAME_END_M && com.onpath.app.data.Geo.distance(r.second, e.second) <= SAME_END_M
    }

    /** The rows of [t]'s [days] along its stored [line]; [tours] = My routes' list (the recorded tours that rode a day). */
    fun rows(t: Tour, days: TourDays, tours: List<Tour>, line: List<GeoPoint> = emptyList()): List<DayPickRow> {
        val nights = t.days?.nights.orEmpty()
        fun known(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        // a plan point's name, else its night's hotel name – "as the plan has them", no place lookup
        fun nameAt(i: Int?): String? = i?.let { known(t.waypoints.getOrNull(it)?.name) ?: nights[it]?.hotel?.let { h -> known(h.name) } }
        val ends: List<Int?> = listOf<Int?>(0) + days.days.map { d -> d.end?.planIndex ?: if (d.end == null) t.waypoints.lastIndex else null }
        var startLine = 0
        val rows = days.days.mapIndexed { k, d ->
            val endLine = d.end?.lineIndex ?: Int.MAX_VALUE
            DayPickRow(d.number, nameAt(ends[k]), nameAt(ends[k + 1]), d.distanceM, d.ascentM, d.timeS, d.end?.kind, null,
                startLine, endLine, d.over).also { startLine = d.end?.lineIndex ?: startLine }
        }
        val e = dayEnds(line, rows)
        val rode = ridden(t.uuid, tours, e)
        val began = started(t.uuid, tours, e)
        return rows.map { it.copy(riddenMs = rode[it.n], startedMs = began[it.n]) }
    }

    /** The recorded tours (not in the bin, not recording) that rode a day of route [uuid] whose ends still [matches]. */
    private fun rides(uuid: String, tours: List<Tour>, ends: Map<Int, Pair<GeoPoint, GeoPoint>>): List<Tour> {
        if (uuid.isEmpty()) return emptyList()
        return tours.filter { it.kind == TourKind.RECORDED && it.deletedMs == null && it.state != "active" && it.planUuid == uuid && (it.planDay ?: 0) > 0 }
            .filter { matches(it, ends[it.planDay]) }
    }

    /**
     * The days of route [uuid] done (✓): day → when the newest recorded tour that rode it TO ITS GOAL ([Tour.planReached], MD-5,
     * owner 2026-11-28) was recorded – ridden twice, the newest finished one counts. A tour in the recycle bin or still recording
     * does not count (deleting it takes the ✓ away), nor one whose day no longer starts and ends where it rode it ([matches]
     * with the day's [ends] now – the route was changed since, owner 2026-11-27).
     */
    fun ridden(uuid: String, tours: List<Tour>, ends: Map<Int, Pair<GeoPoint, GeoPoint>>): Map<Int, Long> =
        rides(uuid, tours, ends).filter { it.planReached == true }.groupBy { it.planDay!! }.mapValues { (_, l) -> l.maxOf { it.createdMs } }

    /**
     * The days of route [uuid] begun but not done („angefangen am …“, MD-5): a recording tied to the day stopped before its goal
     * ([Tour.planReached] false); day → the newest such recording. A day that is done is not in here.
     */
    fun started(uuid: String, tours: List<Tour>, ends: Map<Int, Pair<GeoPoint, GeoPoint>>): Map<Int, Long> {
        val all = rides(uuid, tours, ends)
        val done = all.filter { it.planReached == true }.map { it.planDay!! }.toSet()
        return all.filter { it.planReached == false && it.planDay !in done }.groupBy { it.planDay!! }.mapValues { (_, l) -> l.maxOf { it.createdMs } }
    }

    /**
     * The recorded [track] reached [goal] (MD-5: a day is done when navigated to its goal – the arrival is within [SAME_END_M]):
     * a point within [SAME_END_M] of the goal after the track was farther away than that once – so a round day standing at its
     * start does not count, one ridden away and back does. Pure.
     */
    fun reached(track: List<GeoPoint>, goal: GeoPoint): Boolean {
        var away = false
        for (p in track) {
            val d = com.onpath.app.data.Geo.distance(p, goal)
            if (d > SAME_END_M) away = true else if (away) return true
        }
        return false
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

    /**
     * The rows as the planner's day table ([com.onpath.app.ui.DayTables], owner 2026-11-27): „Tag N“ + „von → nach“ (the
     * shared [com.onpath.app.routing.DayRowText]), km · Σ km · ↑ m · time, the total row.
     */
    fun table(rows: List<DayPickRow>): com.onpath.app.ui.DayTableUi =
        com.onpath.app.ui.DayTables.of(rows.map { com.onpath.app.ui.DayTables.day(it.n, it.distanceM, it.ascentM, it.timeS, it.end, it.over) },
            rows.map { com.onpath.app.routing.DayRowText.places(it.from, it.to) })

    /** The saved multi-day route with [uuid] in My routes' list (planned, not in the bin); null = none. */
    fun route(uuid: String, tours: List<Tour>): Tour? =
        tours.firstOrNull { it.uuid == uuid && it.kind == TourKind.PLANNED && it.deletedMs == null && !it.draft && it.days != null }

    /** A card of My routes opens the day sheet (a saved multi-day route), not the normal overview. */
    fun opens(t: Tour): Boolean = t.kind == TourKind.PLANNED && t.days != null && !t.draft && t.deletedMs == null && t.uuid.isNotEmpty()

    /**
     * Gemacht's line: the recorded tours' route names ([Tour.planName]) and whether that route's day sheet can open. A route in
     * My routes whose day no longer [matches] the ride ([endsOf]: its day ends now, null = not known) claims nothing – no line.
     * A route that is gone: the plan copy's name ([planName]), no tap.
     */
    fun named(list: List<Tour>, planName: (Long) -> String?, endsOf: (Tour) -> Map<Int, Pair<GeoPoint, GeoPoint>>?): List<Tour> {
        val routes = list.filter { it.kind == TourKind.PLANNED && it.deletedMs == null && !it.draft && it.days != null }.associateBy { it.uuid }
        return list.map { t ->
            val uuid = t.planUuid?.takeIf { t.kind == TourKind.RECORDED && (t.planDay ?: 0) > 0 } ?: return@map t
            val r = routes[uuid]
            when {
                r == null -> t.copy(planName = planName(t.id), planOpens = false)
                matches(t, endsOf(r)?.get(t.planDay)) -> t.copy(planName = r.name, planOpens = true)
                else -> t.copy(planName = null, planOpens = false)
            }
        }
    }
}
