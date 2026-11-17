package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.domain.plan.DayFollow
import com.onpath.app.domain.plan.DayPart
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayLine
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.NightMark
import com.onpath.app.routing.PlanDays
import com.onpath.app.routing.SharedPlan
import com.onpath.app.routing.TourDays
import com.onpath.app.routing.WaypointInsertion
import org.json.JSONArray
import org.json.JSONObject
import com.onpath.strings.*

/**
 * The open multi-day tour on the phone (docs/design/multi-day-tours.md §3 "Phone", stage 4): showing it, picking a day
 * and following it. The plan itself stays the view model's (points, pins, names by value); this keeps beside it
 * - [tour]: which saved route the plan is and its days (N, limits, description) – they count only while the plan still
 *   belongs to that route ([overview]: a cleared or replaced plan is a normal plan; Undo back makes it the tour again);
 * - [marks]: the night marks **by value**, like the pins (a point keeps its mark through every edit, a deleted point's
 *   mark goes – R2 – and comes back with Undo);
 * - [day]: the picked day(s) while they are the plan ([DayFollow]): every change of the plan is written back into the
 *   whole tour, Save stores the whole tour.
 * Kept in the prefs like the plan, so a restart keeps them. Never throws to the caller (reported).
 */
class MultiDayFlow(private val prefs: com.onpath.app.data.Prefs, private val linkedId: () -> Long) {
    /** The saved multi-day route the plan was opened from: its id, uuid, name and days (the marks are in [marks]). */
    data class TourRef(val id: Long, val uuid: String, val name: String, val days: MultiDay)

    /**
     * The day(s) being followed: [whole] = the tour as it is now (edits written back), [part] = the day as the plan,
     * [wholeOpened] = the tour's points as last saved (Save's "changed elsewhere?" check), [startAt] / [endAt] = where the
     * day starts / ends on the tour's stored line ([DayFollow.spliceAt]).
     */
    data class DayContext(val tour: TourRef, val whole: SharedPlan, val part: DayPart, val wholeOpened: List<GeoPoint>?,
                          val startAt: GeoPoint, val endAt: GeoPoint)

    /** The days worked out for the plan's route ([compute]); [key] = what they were worked out for. */
    data class DaysView(val days: TourDays, val pointAt: IntArray, val line: List<GeoPoint>, val key: Any)

    var tour by mutableStateOf<TourRef?>(null)
        private set
    var marks by mutableStateOf<Map<GeoPoint, NightMark>>(emptyMap())
        private set
    var day by mutableStateOf<DayContext?>(null)
        private set
    /** The day rows picked in the overview (1 …): one day, or several in a row. */
    var selected by mutableStateOf<IntRange?>(null)
    var view by mutableStateOf<DaysView?>(null)
        private set

    /** The overview shows: a multi-day tour is the plan and no day is picked. */
    val overview: Boolean get() = tour.let { it != null && day == null && linkedId() == it.id }

    /** A day is followed / planned as the plan (and the plan still belongs to its tour). */
    val following: Boolean get() = day.let { it != null && linkedId() == it.tour.id }

    init { restore() }

    // ---- open / leave ---------------------------------------------------------------------------------------------

    /** A saved route was opened with its points [pts] (as loaded): a multi-day tour shows its overview; else nothing here. */
    fun opened(t: Tour, pts: List<GeoPoint>) {
        val d = t.days
        day = null; selected = null; view = null
        if (d == null) { tour = null; marks = emptyMap(); save(); return }
        tour = TourRef(t.id, t.uuid, t.name, d.copy(nights = emptyMap()))
        marks = d.nights.mapNotNull { (i, m) -> pts.getOrNull(i)?.let { it to m } }.toMap()
        Problems.trace("plan", "Multi-day tour opened: ${d.days} days, ${marks.size} night mark(s)")
        save()
    }

    /** The plan belongs to another route now ([id]; -1 = none): a tour that is not [id] is left. */
    fun linked(id: Long) {
        val t = day?.tour ?: tour ?: return
        if (t.id == id || id < 0 && tour != null && day == null) return // a plan cleared: Undo brings the tour back
        tour = null; day = null; marks = emptyMap(); selected = null; view = null
        save()
    }

    /** Save gave the tour a new id (it was deleted meanwhile: saved anew) – the days go with it. */
    fun rebind(id: Long) {
        tour = tour?.copy(id = id)
        day = day?.let { it.copy(tour = it.tour.copy(id = id)) }
        save()
    }

    // ---- the plan's edits -----------------------------------------------------------------------------------------

    /** The night mark of a plan point (by value). */
    fun nightOf(p: GeoPoint): NightMark? = marks[p]

    /** The router snapped points onto the way ([moves] old → new): their marks (and a day's added ends) go with them. */
    fun snapped(moves: Map<GeoPoint, GeoPoint>) {
        if (moves.isEmpty() || (marks.isEmpty() && day == null)) return
        marks = marks.entries.associate { (p, m) -> (moves[p] ?: p) to m }
        day = day?.let { c -> c.copy(part = c.part.copy(startAdded = c.part.startAdded?.let { moves[it] ?: it },
            endAdded = c.part.endAdded?.let { moves[it] ?: it })) }
        save()
    }

    /**
     * The plan's points are [points] now (any edit, Undo, the router's snap): while a day is followed, the change goes
     * back into the whole tour ([DayFollow.writeBack]). [pinned] / [nameOf] = the plan's pins and names by value.
     */
    fun pointsChanged(points: List<GeoPoint>, pinned: Set<GeoPoint>, nameOf: (GeoPoint) -> String?) {
        val c = day ?: return
        if (linkedId() != c.tour.id || points.size < 2 || points == c.part.points) return
        try {
            val (whole, part) = DayFollow.writeBack(c.whole, c.part, points, pinned, nameOf, ::nightOf)
            day = c.copy(whole = whole, part = part)
            Problems.trace("plan", "Day ${c.part.first}–${c.part.last}: change written back into the tour (${whole.points.size} points)")
            save()
        } catch (e: Exception) {
            Problems.error("plan", "A change of the day could not be written back into the multi-day tour – Save keeps the day's plan", e)
        }
    }

    // ---- the days -------------------------------------------------------------------------------------------------

    /** The whole tour as a plan (overview): [points] with their pins and names, N, limits, marks; null without a tour. */
    fun plan(points: List<GeoPoint>, activity: Activity, sac: Int, pinned: Set<Int>, names: Map<Int, String>): SharedPlan? {
        val t = tour ?: return null
        val nights = points.indices.mapNotNull { i -> marks[points[i]]?.let { i to it } }.toMap()
        return SharedPlan(points, activity, sac, pinned, names, t.name, t.days.days, t.days.limits, PlanDays.clean(points, nights),
            t.days.description)
    }

    /** What Save stores as the days of [points] while the overview shows ([Tour.days]); null for a normal plan. */
    fun daysFor(points: List<GeoPoint>): MultiDay? {
        if (!overview) return null
        val t = tour ?: return null
        val nights = points.indices.mapNotNull { i -> marks[points[i]]?.let { i to it } }.toMap()
        return t.days.copy(nights = PlanDays.clean(points, nights))
    }

    /**
     * The days of [plan] along its routed [line] (blocking: call off the main thread) – kept in [view] for the sheet and
     * the map. [factors] = the pace setting.
     */
    fun compute(plan: SharedPlan, line: List<GeoPoint>, factors: com.onpath.app.time.Factors, key: Any): DaysView? = try {
        val dl = DayLine.of(line, plan.activity, factors)
        val at = WaypointInsertion.routeIndices(plan.points, line)
        DaysView(PlanDays.of(dl, at, plan.days ?: 1, plan.limits, plan.nights), at, line, key)
    } catch (e: Exception) {
        Problems.warn("plan", "Days of the multi-day tour not worked out – the route shows without its days", e); null
    }

    fun show(v: DaysView?) { view = v; selected = selected?.takeIf { s -> v != null && s.last <= v.days.days.size } }

    /** A day row tapped: that day alone; tapped again: none. */
    fun tapDay(n: Int) { selected = if (selected == n..n) null else n..n }

    /** A day row held: the days from the picked one to this one in a row ("Tag 3–4"); nothing picked: this day. */
    fun holdDay(n: Int) {
        val s = selected
        selected = if (s == null) n..n else minOf(s.first, n)..maxOf(s.last, n)
    }

    /**
     * "Tag n fahren": the picked days of [plan] (the whole tour as the plan is now, [savedPoints] = as last saved) become
     * the plan. Returns the day's part (its points, pins, names) for the view model to load; null when it cannot be taken
     * (reported).
     */
    fun pickDay(plan: SharedPlan, savedPoints: List<GeoPoint>?): DayPart? {
        val t = tour ?: return null
        val v = view ?: return null
        val s = selected ?: return null
        return try {
            val part = DayFollow.part(plan, v.days, v.pointAt, v.line, s.first, s.last) ?: return null
            day = DayContext(t, plan, part, savedPoints, v.line[part.startLine], v.line[part.endLine])
            Problems.trace("plan", "Multi-day tour: day ${s.first}–${s.last} of ${v.days.days.size} as the plan (${part.points.size} points)")
            save()
            part
        } catch (e: Exception) {
            Problems.error("plan", "The picked day could not be taken out of the multi-day tour", e); null
        }
    }

    /** "‹ Alle Tage": the whole tour (with the day's edits) is the plan again. Returns it and its saved points. */
    fun leaveDay(): Pair<SharedPlan, List<GeoPoint>?>? {
        val c = day ?: return null
        day = null
        marks = c.whole.nights.mapNotNull { (i, m) -> c.whole.points.getOrNull(i)?.let { it to m } }.toMap()
        tour = c.tour
        selected = c.part.first..c.part.last
        save()
        return c.whole to c.wholeOpened
    }

    /** Saved: the whole tour's points as stored now ([pts]), the day's stretch on the new line ([dayLine]). */
    fun wholeSaved(pts: List<GeoPoint>, dayLine: List<GeoPoint>, name: String) {
        val c = day ?: run { tour = tour?.copy(name = name); save(); return }
        day = c.copy(wholeOpened = pts, startAt = dayLine.firstOrNull() ?: c.startAt, endAt = dayLine.lastOrNull() ?: c.endAt,
            tour = c.tour.copy(name = name))
        save()
    }

    /** "Tag 4 von 6" (Navigate's top card, the day's plan sheet); null when no day is followed. */
    fun dayLabel(): String? {
        val p = day?.part?.takeIf { following } ?: return null
        return if (p.single) com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_day_of, p.first.toString(), p.total.toString())
        else com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_days_of, p.first.toString(), p.last.toString(), p.total.toString())
    }

    // ---- kept in the prefs ----------------------------------------------------------------------------------------

    private fun save() = try {
        prefs.planMultiDay = tour?.let { t ->
            JSONObject().put("tour", refJson(t)).put("marks", JSONArray().apply {
                marks.forEach { (p, m) -> put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("m", markJson(m))) }
            }).toString()
        }
        prefs.planDayFollow = day?.let { c ->
            val p = c.part
            JSONObject().put("tour", refJson(c.tour)).put("whole", planJson(c.whole))
                .put("first", p.first).put("last", p.last).put("total", p.total).put("from", p.from).put("to", p.to)
                .put("startLine", p.startLine).put("endLine", p.endLine)
                .put("points", ptsJson(p.points)).put("startAdded", p.startAdded?.let { ptJson(it) }).put("endAdded", p.endAdded?.let { ptJson(it) })
                .put("opened", c.wholeOpened?.let { ptsJson(it) }).put("startAt", ptJson(c.startAt)).put("endAt", ptJson(c.endAt))
                .toString()
        }
    } catch (e: Exception) {
        Problems.warn("plan", "Multi-day tour state not kept – after a restart the plan shows without its days", e)
    }

    private fun restore() {
        try {
            prefs.planMultiDay?.let { s ->
                val o = JSONObject(s)
                tour = ref(o.getJSONObject("tour"))
                val a = o.getJSONArray("marks")
                marks = (0 until a.length()).associate { i ->
                    val m = a.getJSONObject(i); GeoPoint(m.getDouble("lat"), m.getDouble("lon")) to mark(m.getJSONObject("m"))
                }
            }
            prefs.planDayFollow?.let { s ->
                val o = JSONObject(s)
                val whole = plan(o.getJSONObject("whole"))
                val points = pts(o.getJSONArray("points"))
                val part = DayPart(o.getInt("first"), o.getInt("last"), o.getInt("total"), points, emptySet(), emptyMap(), emptyMap(),
                    o.getInt("from"), o.getInt("to"), o.optJSONObject("startAdded")?.let(::pt), o.optJSONObject("endAdded")?.let(::pt),
                    o.getInt("startLine"), o.getInt("endLine"))
                day = DayContext(ref(o.getJSONObject("tour")), whole, part, o.optJSONArray("opened")?.let(::pts),
                    pt(o.getJSONObject("startAt")), pt(o.getJSONObject("endAt")))
            }
        } catch (e: Exception) {
            Problems.error("plan", "Multi-day tour state unreadable after the restart – the plan shows without its days (the saved tour keeps them)", e)
            tour = null; day = null; marks = emptyMap()
        }
    }

    private fun refJson(t: TourRef) = JSONObject().put("id", t.id).put("uuid", t.uuid).put("name", t.name).put("days", JSONObject(t.days.toJson()))
    private fun ref(o: JSONObject) = TourRef(o.getLong("id"), o.optString("uuid"), o.optString("name"),
        MultiDay.fromJson(o.getJSONObject("days").toString()) ?: error("days unreadable"))
    private fun ptJson(p: GeoPoint) = JSONObject().put("lat", p.lat).put("lon", p.lon)
    private fun pt(o: JSONObject) = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
    private fun ptsJson(l: List<GeoPoint>) = JSONArray().apply { l.forEach { put(ptJson(it)) } }
    private fun pts(a: JSONArray) = (0 until a.length()).map { pt(a.getJSONObject(it)) }
    private fun markJson(m: NightMark) = JSONObject(MultiDay(1, nights = mapOf(0 to m)).toJson()).getJSONArray("nights").getJSONObject(0)
    private fun mark(o: JSONObject): NightMark =
        MultiDay.fromJson(JSONObject().put("days", 1).put("nights", JSONArray().put(o.put("i", 0))).toString())?.nights?.get(0) ?: NightMark.OVERNIGHT

    private fun planJson(p: SharedPlan) = JSONObject().put("points", ptsJson(p.points))
        .put("activity", p.activity.name).put("sac", p.sac).put("pinned", JSONArray(p.pinned.sorted()))
        .put("names", JSONObject().apply { p.names.forEach { (i, n) -> put(i.toString(), n) } })
        .put("title", p.title).put("days", MultiDay.of(p)?.let { JSONObject(it.toJson()) })

    private fun plan(o: JSONObject): SharedPlan {
        val points = pts(o.getJSONArray("points"))
        val pa = o.getJSONArray("pinned")
        val no = o.getJSONObject("names")
        val base = SharedPlan(points, Activity.valueOf(o.getString("activity")), o.getInt("sac"),
            (0 until pa.length()).map { pa.getInt(it) }.toSet(), no.keys().asSequence().associate { it.toInt() to no.getString(it) },
            o.optString("title").ifEmpty { null })
        return o.optJSONObject("days")?.let { MultiDay.fromJson(it.toString()) }?.into(base) ?: base
    }
}
