package com.onpath.app.domain.plan

import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.TourDays

/**
 * The days [first] … [last] (1 …, of [total]) of a multi-day tour taken out as a plan of their own ([DayFollow.part]):
 * [points] with their [pinned] / [names] / [nights] by index. The whole plan's points [from] … [to] are its middle; a
 * grey day end (a worked-out proposal, no plan point) is put in as [startAdded] / [endAdded] – the day's start or
 * destination. [startLine] / [endLine] = where the days start / end on the whole route line it was taken from
 * ([DayFollow.splice]).
 */
data class DayPart(
    val first: Int,
    val last: Int,
    val total: Int,
    val points: List<GeoPoint>,
    val pinned: Set<Int>,
    val names: Map<Int, String>,
    val nights: Map<Int, NightMark>,
    val from: Int,
    val to: Int,
    val startAdded: GeoPoint?,
    val endAdded: GeoPoint?,
    val startLine: Int,
    val endLine: Int,
) {
    /** "Tag 2" or "Tag 3–4" – the same in Navigate's top card ("Tag 4 von 6"). */
    val single: Boolean get() = first == last
}

/**
 * Following a day of a multi-day tour (docs/design/multi-day-tours.md §3 "Phone", §5; owner 2026-11-17: „Einen Tag
 * werden wir immer auswählen“): the picked day(s) become a normal plan ([part]); every edit of that plan ("Planen" while
 * riding, #14) goes back into the whole tour ([writeBack]), the night marks follow their points ([PlanDays.afterEdit]);
 * saving stores the whole tour with the day's new line in place of the old one ([splice]). Pure – `DayFollowTest`.
 */
object DayFollow {
    /**
     * Days [first] … [last] of [plan] with its [days] on the routed [line] ([pointAt] = line index of each plan point).
     * Null when the range is not one of the tour's days or leaves fewer than two points.
     */
    fun part(plan: SharedPlan, days: TourDays, pointAt: IntArray, line: List<GeoPoint>, first: Int, last: Int): DayPart? {
        val n = days.days.size
        if (n == 0 || first < 1 || last > n || first > last || line.isEmpty() || pointAt.size != plan.points.size || plan.points.size < 2) return null
        val lastLine = line.lastIndex
        // never backwards along the line (as the day ends are found)
        val at = IntArray(pointAt.size)
        var m = 0
        for (i in pointAt.indices) { m = maxOf(m, pointAt[i].coerceIn(0, lastLine)); at[i] = m }
        val startEnd = if (first == 1) null else days.days[first - 2].end
        val endEnd = days.days[last - 1].end
        val startLine = startEnd?.lineIndex?.coerceIn(0, lastLine) ?: 0
        val endLine = endEnd?.lineIndex?.coerceIn(0, lastLine) ?: lastLine
        // the start: the tour's start, a night's plan point, or a grey day end (a plan point right there, else a new one)
        var startAdded: GeoPoint? = null
        val from = when {
            first == 1 -> 0
            startEnd != null && startEnd.kind != DayEndKind.PROPOSAL && startEnd.planIndex != null -> startEnd.planIndex!!
            else -> {
                val i = at.indices.firstOrNull { at[it] >= startLine && it > 0 } ?: plan.points.lastIndex
                if (at[i] != startLine) startAdded = line[startLine].bare()
                i
            }
        }
        var endAdded: GeoPoint? = null
        val to = when {
            endEnd == null -> plan.points.lastIndex
            endEnd.kind != DayEndKind.PROPOSAL && endEnd.planIndex != null -> endEnd.planIndex!!
            else -> {
                val j = at.indices.lastOrNull { at[it] <= endLine && it < plan.points.lastIndex } ?: 0
                if (at[j] != endLine) endAdded = line[endLine].bare()
                j
            }
        }
        val middle = if (from <= to) plan.points.subList(from, to + 1) else emptyList()
        val pts = listOfNotNull(startAdded) + middle + listOfNotNull(endAdded)
        if (pts.size < 2) return null
        val shift = (if (startAdded != null) 1 else 0) - from
        fun <T> inMiddle(m: Map<Int, T>) = m.filterKeys { it in from..to }.mapKeys { it.key + shift }
        return DayPart(first, last, n, pts, plan.pinned.filter { it in from..to }.map { it + shift }.toSet(), inMiddle(plan.names),
            inMiddle(plan.nights), from, to, startAdded, endAdded, startLine, endLine)
    }

    /**
     * The day's plan edited to [edited] ([pinned], [nameOf], [nightOf] by value, as the plan keeps them) written back into
     * [whole] (of which [part] was taken): the points before and after the day stay with their pins, names and night
     * marks ([PlanDays.afterEdit]: they follow their points), the day's points and their marks come from the edit; a grey
     * day end put in is no point of the tour and goes again. N stays (a night deleted on the way gives a grey day back,
     * R2). Returns the new whole plan and the part as it is now (for the next edit).
     */
    fun writeBack(whole: SharedPlan, part: DayPart, edited: List<GeoPoint>, pinned: Set<GeoPoint>, nameOf: (GeoPoint) -> String?,
                  nightOf: (GeoPoint) -> NightMark?): Pair<SharedPlan, DayPart> {
        var middle = edited
        if (part.startAdded != null && middle.firstOrNull() == part.startAdded) middle = middle.drop(1)
        if (part.endAdded != null && middle.lastOrNull() == part.endAdded) middle = middle.dropLast(1)
        val keptTo = maxOf(part.to, part.from - 1)
        val before = whole.points.subList(0, part.from.coerceIn(0, whole.points.size))
        val after = whole.points.subList((keptTo + 1).coerceIn(0, whole.points.size), whole.points.size)
        val points = before + middle + after
        val moved = PlanDays.afterEdit(whole, points)
        val range = before.size until before.size + middle.size
        val pins = moved.pinned.filterTo(HashSet()) { it !in range }
        val names = moved.names.filterKeys { it !in range }.toMutableMap()
        val nights = moved.nights.filterKeys { it !in range }.toMutableMap()
        for ((k, p) in middle.withIndex()) {
            val i = before.size + k
            if (p in pinned) pins += i
            nameOf(p)?.let { names[i] = it }
            nightOf(p)?.let { nights[i] = it }
        }
        val plan = moved.copy(pinned = pins, names = names, nights = PlanDays.clean(points, nights))
        val now = part.copy(points = edited, from = part.from, to = part.from + middle.size - 1,
            pinned = edited.indices.filter { edited[it] in pinned }.toSet(),
            names = edited.indices.mapNotNull { i -> nameOf(edited[i])?.let { i to it } }.toMap(),
            nights = edited.indices.mapNotNull { i -> nightOf(edited[i])?.let { i to it } }.toMap())
        return plan to now
    }

    /**
     * The whole tour's line with the day's new [dayLine] in place of the old stretch [DayPart.startLine] …
     * [DayPart.endLine] of [wholeLine] – the line saved with the tour (a stand-in until its legs are routed again).
     * Returns the line and where the day now ends on it.
     */
    fun splice(wholeLine: List<GeoPoint>, part: DayPart, dayLine: List<GeoPoint>): Pair<List<GeoPoint>, Int> {
        if (wholeLine.isEmpty() || dayLine.isEmpty()) return wholeLine to part.endLine
        val s = part.startLine.coerceIn(0, wholeLine.lastIndex)
        val e = part.endLine.coerceIn(s, wholeLine.lastIndex)
        val out = wholeLine.subList(0, s) + dayLine + wholeLine.subList(e + 1, wholeLine.size)
        return out to s + dayLine.lastIndex
    }

    /**
     * [splice] on a stored line where the indices are not known (the tour as saved, read back): the stretch from the line
     * point nearest [startAt] to the one nearest [endAt] after it.
     */
    fun spliceAt(wholeLine: List<GeoPoint>, startAt: GeoPoint, endAt: GeoPoint, dayLine: List<GeoPoint>): List<GeoPoint> {
        if (wholeLine.isEmpty() || dayLine.isEmpty()) return wholeLine
        val s = wholeLine.indices.minBy { com.onpath.app.data.Geo.distance(wholeLine[it], startAt) }
        val e = (s until wholeLine.size).minBy { com.onpath.app.data.Geo.distance(wholeLine[it], endAt) }
        return wholeLine.subList(0, s) + dayLine + wholeLine.subList(e + 1, wholeLine.size)
    }

    private fun GeoPoint.bare() = GeoPoint(lat, lon)
}
