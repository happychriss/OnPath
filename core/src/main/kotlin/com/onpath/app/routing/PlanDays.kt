package com.onpath.app.routing

import com.onpath.app.data.Geo
import com.onpath.app.time.Factors
import com.onpath.app.time.PaceModel
import com.onpath.app.time.RouteTimes
import kotlin.math.abs

/**
 * A multi-day tour's route line as the days need it (docs/design/multi-day-tours.md §5): per line point the distance,
 * ascent and descent from the start ([Geo.routeClimbAlong], the plan sheet's 10 m filter – the days add up to its
 * totals) and the moving time from OnPath's own model ([RouteTimes], the same as [TimeEstimate.planSeconds]).
 */
class DayLine(
    val points: List<GeoPoint>,
    val distM: DoubleArray,
    val upM: DoubleArray,
    val downM: DoubleArray,
    val timeS: DoubleArray,
) {
    val lastIndex: Int get() = points.lastIndex

    companion object {
        /** The [line] of a plan routed for [activity]; [factors] = the pace setting ([TimeEstimate.planFactors]). */
        fun of(line: List<GeoPoint>, activity: Activity, factors: Factors = Factors.ONE): DayLine {
            val dist = Geo.cumulative(line)
            val (up, down) = Geo.routeClimbAlong(line.map { it.ele }, Planner.ELE_FILTER_M)
            val times = RouteTimes.of(line, activity)
            val time = DoubleArray(line.size) { k ->
                val g = times.between(0.0, dist[k])
                (0 until PaceModel.GROUPS).sumOf { factors[it] * g[it] }
            }
            return DayLine(line, dist, up, down, time)
        }
    }
}

/** What ends a day: a grey proposal (worked out, never stored), an Übernachtung (blue) or a hotel (green). */
enum class DayEndKind { PROPOSAL, OVERNIGHT, HOTEL }

/**
 * A day end on the line: [alongM] from the start, at line point [lineIndex] ([point]); [planIndex] = the plan point of
 * an Übernachtung / hotel, null for a proposal.
 */
data class DayEnd(val kind: DayEndKind, val alongM: Double, val lineIndex: Int, val point: GeoPoint, val planIndex: Int? = null)

/** A limit per day a day is above (R8: its row turns orange with that value). */
enum class DayLimitKind { TIME, ASCENT }

/** Day [number] (1 …) from [fromM] to [toM] along the line; [end] = its day end, null for the last day (the finish). */
data class TourDay(
    val number: Int,
    val fromM: Double,
    val toM: Double,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val timeS: Double,
    val end: DayEnd?,
    val over: Set<DayLimitKind>,
)

/**
 * The days of a multi-day tour: one row per day, the grey [proposals] (N − 1 − night marks of them) and the number of
 * days the limits ask for ([suggestedDays], null without limits).
 */
data class TourDays(val days: List<TourDay>, val proposals: List<DayEnd>, val suggestedDays: Int?)

/** A rule's result: the new plan; [dayAdded] = N went up by one (R1 with no grey proposal left – said in one line). */
data class DayEdit(val plan: SharedPlan, val dayAdded: Boolean = false)

/**
 * Multi-day tours (docs/design/multi-day-tours.md §2, owner 2026-11-16/17): a normal plan + N + night marks on plan
 * points ([SharedPlan.days], [SharedPlan.nights]). Pure Kotlin, shared with the web planner (it plans the tours, the
 * phone follows a day). The grey proposals are worked out here every time, never stored; the rules R1–R7 are pure
 * functions on the plan – routing the changed points is the caller's job.
 */
object PlanDays {
    /** A tap this close to a plan point marks that point instead of adding one (R1). */
    const val SAME_POINT_M = 25.0
    /**
     * Line points this close on the way to and from a hotel are the same way (R4: where the route leaves for it) – the
     * router goes back over the same nodes.
     */
    const val SAME_WAY_M = 5.0

    // ---- the days ----------------------------------------------------------------------------------------------

    /**
     * The days of [plan] along its routed [line]: the plan points sit on the line where [WaypointInsertion.routeIndices]
     * finds them.
     */
    fun of(plan: SharedPlan, line: DayLine): TourDays =
        of(line, WaypointInsertion.routeIndices(plan.points, line.points), plan.days ?: 1, plan.limits, plan.nights)

    /**
     * The days for [days] = N, [limits] per day and the night marks [nights] (by plan index) on [line]; [pointAt] = the
     * line index of each plan point. Grey proposals: between start, night marks and finish so that no open day is above
     * a limit where that is possible, otherwise about equal by time (owner 2026-11-17). A day above a limit is flagged
     * ([TourDay.over], R8); nothing moves by itself.
     */
    fun of(line: DayLine, pointAt: IntArray, days: Int, limits: DayLimits, nights: Map<Int, NightMark>): TourDays {
        if (line.points.isEmpty()) return TourDays(emptyList(), emptyList(), null)
        val last = line.lastIndex
        val marks = marksOnLine(pointAt, nights, last)
        val n = maxOf(days, marks.size + 1, 1)
        // the stretches between start, night marks and finish; each gets its share of the grey proposals
        val bounds = listOf(0) + marks.map { it.second } + last
        val segs = bounds.indices.drop(1).map { bounds[it - 1] to bounds[it] }
        val minDays = segs.map { (s, e) -> minDays(line, s, e, limits) }
        val share = share(line, segs, minDays, n - 1 - marks.size, limits)
        val proposals = ArrayList<DayEnd>()
        for ((k, seg) in segs.withIndex()) {
            for (c in cuts(line, seg.first, seg.second, share[k], minDays[k], limits))
                proposals += DayEnd(DayEndKind.PROPOSAL, line.distM[c], c, line.points[c])
        }
        val ends = (proposals + marks.map { (i, at) ->
            DayEnd(if (nights[i]?.isHotel == true) DayEndKind.HOTEL else DayEndKind.OVERNIGHT, line.distM[at], at, line.points[at], i)
        }).sortedWith(compareBy({ it.lineIndex }, { it.planIndex ?: -1 }))
        val out = ArrayList<TourDay>()
        var from = 0
        for (d in 0..ends.size) {
            val end = ends.getOrNull(d)
            val to = end?.lineIndex ?: last
            out += TourDay(d + 1, line.distM[from], line.distM[to], line.distM[to] - line.distM[from],
                line.upM[to] - line.upM[from], line.downM[to] - line.downM[from], line.timeS[to] - line.timeS[from], end,
                over(line, from, to, limits))
            from = to
        }
        val suggested = if (limits.isNone) null else minDays.sum()
        return TourDays(out, proposals, suggested)
    }

    /**
     * The number of days the [limits] ask for (the days box: "mindestens 6 Tage", owner 2026-11-17) with the night marks
     * [nights] where they are; null without limits. The user may still choose another N.
     */
    fun suggestDays(line: DayLine, pointAt: IntArray, limits: DayLimits, nights: Map<Int, NightMark> = emptyMap()): Int? {
        if (limits.isNone || line.points.isEmpty()) return null
        val marks = marksOnLine(pointAt, nights, line.lastIndex)
        val bounds = listOf(0) + marks.map { it.second } + line.lastIndex
        return (1 until bounds.size).sumOf { minDays(line, bounds[it - 1], bounds[it], limits) }
    }

    /**
     * Night marks in plan order with their line index (never on the start or the last point); never backwards along the
     * line, whatever [pointAt] says.
     */
    private fun marksOnLine(pointAt: IntArray, nights: Map<Int, NightMark>, last: Int): List<Pair<Int, Int>> {
        var at = 0
        return nights.keys.filter { it > 0 && it < pointAt.lastIndex }.sorted()
            .map { i -> at = maxOf(at, pointAt[i].coerceIn(0, last)); i to at }
    }

    private fun timeOf(line: DayLine, a: Int, b: Int) = line.timeS[b] - line.timeS[a]
    private fun upOf(line: DayLine, a: Int, b: Int) = line.upM[b] - line.upM[a]

    private fun over(line: DayLine, a: Int, b: Int, limits: DayLimits): Set<DayLimitKind> {
        val out = HashSet<DayLimitKind>(2)
        limits.maxTimeMin?.let { if (timeOf(line, a, b) > it * 60.0) out += DayLimitKind.TIME }
        limits.maxAscentM?.let { if (upOf(line, a, b) > it) out += DayLimitKind.ASCENT }
        return out
    }

    /** How far a day [a]..[b] fills the limits (1 = at a limit); without limits its time. */
    private fun load(line: DayLine, a: Int, b: Int, limits: DayLimits): Double {
        if (limits.isNone) return timeOf(line, a, b)
        var l = 0.0
        limits.maxTimeMin?.let { l = maxOf(l, timeOf(line, a, b) / (it * 60.0)) }
        limits.maxAscentM?.let { l = maxOf(l, upOf(line, a, b) / it) }
        return l
    }

    /**
     * Fewest days for the stretch [s]..[e] with no day above the limits: each day goes as far as it may; a single line
     * step above a limit is a day of its own (it cannot be split). 1 without limits.
     */
    private fun minDays(line: DayLine, s: Int, e: Int, limits: DayLimits): Int =
        if (limits.isNone || e <= s) 1 else greedyCuts(line, s, e, limits, { 0.0 }, 0.0).size + 1

    /**
     * Day ends on [s]..[e] when each day goes as far as it may within the limits and at most [maxSpan] of [measure]
     * (the time).
     */
    private fun greedyCuts(line: DayLine, s: Int, e: Int, limits: DayLimits, measure: (Int) -> Double, maxSpan: Double): List<Int> {
        val out = ArrayList<Int>()
        var start = s; var k = s + 1
        while (k <= e) {
            if ((limits.isNone || load(line, start, k, limits) <= 1.0) && measure(k) - measure(start) <= maxSpan) { k++; continue }
            val end = if (k - 1 > start) k - 1 else k
            if (end >= e) break
            out += end; start = end; k = end + 1
        }
        return out
    }

    /**
     * How many grey proposals each stretch gets ([greys] in all): first so that every stretch can stay within the
     * limits (the fullest per day first), then to the stretch with the longest days by time (about equal days).
     */
    private fun share(line: DayLine, segs: List<Pair<Int, Int>>, minDays: List<Int>, greys: Int, limits: DayLimits): IntArray {
        val days = IntArray(segs.size) { 1 }
        var left = greys
        fun perDay(k: Int) = timeOf(line, segs[k].first, segs[k].second) / days[k]
        while (left > 0) {
            val short = segs.indices.filter { days[it] < minDays[it] }
            val k = if (short.isNotEmpty())
                short.maxBy { load(line, segs[it].first, segs[it].second, limits) / days[it] }
            else segs.indices.maxWith(compareBy({ perDay(it) }, { (line.distM[segs[it].second] - line.distM[segs[it].first]) / days[it] }))
            days[k]++; left--
        }
        return IntArray(segs.size) { days[it] - 1 }
    }

    /**
     * [count] proposals on the stretch [s]..[e]: equal by time; when that puts a day above a limit although
     * [minDays] days would stay within them, the days stay within the limits with the longest day as short as possible.
     */
    private fun cuts(line: DayLine, s: Int, e: Int, count: Int, minDays: Int, limits: DayLimits): List<Int> {
        if (count <= 0) return emptyList()
        val even = evenCuts(line, s, e, count)
        val d = count + 1
        if (limits.isNone || minDays > d) return even
        val ends = listOf(s) + even + e
        if ((1 until ends.size).none { load(line, ends[it - 1], ends[it], limits) > 1.0 }) return even
        // within the limits, the longest day by time as short as possible: bisect it, cut greedily
        val m = measureOf(line, s, e)
        var lo = 0.0; var hi = m(e) - m(s)
        repeat(50) { val mid = (lo + hi) / 2; if (greedyCuts(line, s, e, limits, m, mid).size < d) hi = mid else lo = mid }
        val out = greedyCuts(line, s, e, limits, m, hi).toMutableList()
        // fewer days needed than there are: the rest are spread evenly over the last day
        if (out.size < count) out += evenCuts(line, out.lastOrNull() ?: s, e, count - out.size)
        return out
    }

    /** What makes days equal on [s]..[e]: the time; the distance without time; the line points without both. */
    private fun measureOf(line: DayLine, s: Int, e: Int): (Int) -> Double = when {
        timeOf(line, s, e) > 0 -> { k -> line.timeS[k] }
        line.distM[e] - line.distM[s] > 0 -> { k -> line.distM[k] }
        else -> { k -> k.toDouble() }
    }

    /** [count] cuts on [s]..[e] at equal shares of the time (of the distance without time, of the points without both). */
    private fun evenCuts(line: DayLine, s: Int, e: Int, count: Int): List<Int> {
        val measure = measureOf(line, s, e)
        val a = measure(s); val b = measure(e)
        val out = ArrayList<Int>(count)
        var prev = s
        for (j in 1..count) {
            val target = a + (b - a) * j / (count + 1)
            // after the cut before it; room is kept for the cuts still to come where the line has it
            val lo = minOf(prev + 1, e)
            val hi = maxOf(lo, e - 1 - (count - j))
            var k = lo
            while (k < hi && abs(measure(k + 1) - target) <= abs(measure(k) - target)) k++
            out += k
            prev = k
        }
        return out
    }

    // ---- the rules R1–R7 (each one undo step for the caller) ------------------------------------------------------

    /**
     * The days box's "Fertig" (and "Als Mehrtagestour planen"): [plan] becomes a multi-day tour of [days] days
     * (1 … [PlanCode.MAX_DAYS], at least one more than night marks); its night marks stay.
     */
    fun start(plan: SharedPlan, days: Int): SharedPlan {
        val nights = clean(plan.points, plan.nights)
        return plan.copy(days = minOf(maxOf(days, nights.size + 1), PlanCode.MAX_DAYS), nights = nights)
    }

    /** The grey proposals N − 1 − (night marks); never below 0. */
    fun proposalCount(plan: SharedPlan): Int = maxOf(0, (plan.days ?: 1) - 1 - clean(plan.points, plan.nights).size)

    /**
     * R1 "Übernachtung hier" / R7 a grey proposal dropped there (owner 2026-11-17: „Wird dort blau“): a night at line
     * point [lineIndex] of [line] ([pointAt] = the line index of each plan point). A plan point there (within
     * [SAME_POINT_M]) gets the mark, else a new point is put in at that spot of the line (unpinned, unnamed – the route
     * does not change). N stays and a grey proposal goes; with none left N + 1 ([DayEdit.dayAdded]). The start, the
     * finish or a point with a mark already: unchanged.
     */
    fun addNight(plan: SharedPlan, line: List<GeoPoint>, pointAt: IntArray, lineIndex: Int): DayEdit {
        val pts = plan.points
        if (pts.size < 2 || line.isEmpty() || pointAt.size != pts.size) return DayEdit(plan)
        val at = lineIndex.coerceIn(0, line.lastIndex)
        val spot = line[at]
        // the leg the spot lies on: leg i runs from point i to i + 1
        val leg = (pts.size - 2 downTo 0).firstOrNull { pointAt[it] <= at } ?: 0
        val near = listOf(leg, leg + 1).filter { Geo.distance(pts[it], spot) <= SAME_POINT_M }
            .minByOrNull { Geo.distance(pts[it], spot) }
        val (points, index) = if (near != null) pts to near
        else (pts.take(leg + 1) + GeoPoint(spot.lat, spot.lon) + pts.drop(leg + 1)) to leg + 1
        if (index <= 0 || index >= points.lastIndex) return DayEdit(plan)
        val moved = if (points === pts) plan else afterEdit(plan, points)
        if (moved.nights.containsKey(index)) return DayEdit(plan)
        return withNight(moved, index, NightMark.OVERNIGHT)
    }

    /** A night mark [mark] on plan point [index] (no mark yet); N + 1 when no grey proposal was left (R1). */
    private fun withNight(plan: SharedPlan, index: Int, mark: NightMark): DayEdit {
        val noGrey = proposalCount(plan) == 0
        val days = (plan.days ?: 1) + if (noGrey) 1 else 0
        return DayEdit(plan.copy(days = days.coerceAtMost(PlanCode.MAX_DAYS), nights = plan.nights + (index to mark)),
            dayAdded = noGrey)
    }

    /**
     * R2 "Übernachtung entfernen": N stays, a grey proposal comes back. An Übernachtung's point stays (the route does not
     * change). A hotel's point goes with it – it was there only for the hotel, the route no longer makes the detour
     * (decided 2026-11-17 in stage 1: the concept says nothing; the UI asks first, scenario N).
     */
    fun removeNight(plan: SharedPlan, index: Int): SharedPlan {
        val mark = plan.nights[index] ?: return plan
        if (!mark.isHotel) return plan.copy(nights = plan.nights - index)
        return afterEdit(plan, PlanPoints.deleteAt(plan.points, index))
    }

    /**
     * R3 "Hotel hinterlegen" / "Hotel ändern" (and scenario F, a hotel for a point without a mark: R1 + R3): blue → green,
     * the point moves to the hotel at [at] – the route leads there, the caller routes the two legs again. The point
     * carries the hotel's name (an older app shows the hotel as a named point, scenario Q; a named point keeps its
     * place when the route snaps the points); it is no longer pinned.
     */
    fun setHotel(plan: SharedPlan, index: Int, hotel: Hotel, at: GeoPoint): DayEdit {
        if (index <= 0 || index >= plan.points.lastIndex) return DayEdit(plan)
        val label = hotelLabel(hotel)
        val names = if (label != null) plan.names + (index to label) else plan.names - index
        val newPoints = PlanEdits.replace(plan.points, index, GeoPoint(at.lat, at.lon))
        val moved = plan.copy(points = newPoints, names = names, pinned = plan.pinned - index,
            anchors = PlanAnchors.follow(plan.points, newPoints, plan.anchors))
        val mark = NightMark(hotel)
        return if (plan.nights.containsKey(index)) DayEdit(moved.copy(nights = moved.nights + (index to mark)))
        else withNight(moved, index, mark)
    }

    /**
     * R4 "Hotel entfernen" (owner 2026-11-17: „Nacht bleibt, wird blau“): the night stays as an Übernachtung where the
     * route leaves for the hotel – walking back from the hotel along [line] ([pointAt] = line index of each plan point)
     * while the way back runs beside it (within [SAME_WAY_M]); the hotel's name goes from the point. A hotel the route
     * passes without a detour: the point stays where it is. The caller routes the two legs again.
     */
    fun removeHotel(plan: SharedPlan, index: Int, line: List<GeoPoint>, pointAt: IntArray): SharedPlan {
        val mark = plan.nights[index]?.takeIf { it.isHotel } ?: return plan
        val names = if (plan.names[index] == hotelLabel(mark.hotel!!)) plan.names - index else plan.names
        val blue = plan.copy(nights = plan.nights + (index to NightMark.OVERNIGHT), names = names)
        if (line.size < 2 || pointAt.size != plan.points.size || index !in 1 until plan.points.lastIndex) return blue
        val j = junction(line, pointAt[index - 1], pointAt[index].coerceIn(0, line.lastIndex), pointAt[index + 1])
        val p = line[j]
        val newPoints = PlanEdits.replace(plan.points, index, GeoPoint(p.lat, p.lon))
        return blue.copy(points = newPoints, anchors = PlanAnchors.follow(plan.points, newPoints, plan.anchors))
    }

    /**
     * Where the line leaves for a detour out to [h] and back: walking back from [h] (not before [s]) while the way on
     * after [h] (not after [e]) runs within [SAME_WAY_M] of it.
     */
    private fun junction(line: List<GeoPoint>, s: Int, h: Int, e: Int): Int {
        val from = s.coerceIn(0, h); val to = e.coerceIn(h, line.lastIndex)
        var j = h; var b = h
        for (a in h - 1 downTo from) {
            var best = -1; var bestD = Double.MAX_VALUE
            for (k in b + 1..minOf(to, b + SEARCH_AHEAD)) {
                val d = Geo.distance(line[a], line[k])
                if (d < bestD) { bestD = d; best = k }
            }
            if (best < 0 || bestD > SAME_WAY_M) break
            j = a; b = best
        }
        return j
    }

    /** Line points looked at ahead on the way back from a hotel per step back (a detour's way back is about as long). */
    private const val SEARCH_AHEAD = 200

    private fun hotelLabel(h: Hotel): String? = (h.name ?: h.address)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * R5 N − / +: only grey proposals come or go. Null when it is not possible – fewer days than night marks + 1
     * ("Erst eine Übernachtung entfernen"), below 1 or above [PlanCode.MAX_DAYS].
     */
    fun setDays(plan: SharedPlan, days: Int): SharedPlan? {
        val nights = clean(plan.points, plan.nights)
        if (days < nights.size + 1 || days < 1 || days > PlanCode.MAX_DAYS) return null
        return plan.copy(days = days, nights = nights)
    }

    /** "−" at the number of days is possible: a grey proposal is left (R5). */
    fun canRemoveDay(plan: SharedPlan): Boolean = proposalCount(plan) > 0

    /**
     * R6 and every other edit of the points ([before] → [after]: insert, delete, move, reverse, round trip, start here,
     * a drag, the router's snapping): pins, names and night marks follow their points ([PlanEdits.follow]); a deleted
     * point's night goes (a grey proposal comes back, N stays); a dragged Übernachtung stays one where it is dropped,
     * a dragged hotel point becomes an Übernachtung (it no longer sits at the hotel); a mark that ends up on the start
     * or the last point goes. The grey proposals spread again by themselves ([of]).
     */
    fun afterEdit(before: SharedPlan, after: List<GeoPoint>): SharedPlan {
        val old = before.points
        val pinned = PlanEdits.follow(old, after, before.pinned)
        val names = PlanEdits.follow(old, after, before.names)
        val kept = PlanEdits.follow(old, after, before.nights)
        val moved = PlanEdits.follow(old, after, before.nights, keepMoved = true)
        val nights = moved.mapValues { (j, m) -> if (m.isHotel && j !in kept) NightMark.OVERNIGHT else m }
        return before.copy(points = after, pinned = pinned, names = names, nights = clean(after, nights),
            anchors = if (after != old && after == old.reversed()) PlanAnchors.reversed(before.anchors, old.size)
            else PlanAnchors.follow(old, after, before.anchors))
    }

    /** Night marks only on points between the start and the last point. */
    fun clean(points: List<GeoPoint>, nights: Map<Int, NightMark>): Map<Int, NightMark> =
        if (nights.keys.all { it > 0 && it < points.lastIndex }) nights else nights.filterKeys { it > 0 && it < points.lastIndex }
}
