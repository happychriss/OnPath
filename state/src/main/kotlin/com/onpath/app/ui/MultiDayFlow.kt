package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.AnchorsJson
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.debug.Problems
import com.onpath.app.domain.plan.DayFollow
import com.onpath.app.domain.plan.DayPart
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorProfile
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.PlanAnchors
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
 * The multi-day trip on the phone (docs/design/multi-day-tours.md §0, owner 2026-11-28: the phone guides ONE day of the trip,
 * never the whole plan): the day picked in My routes' day sheet and followed as the plan. The plan itself stays the view
 * model's (points, pins, names by value); this keeps beside it
 * - [tour]: which saved trip the day belongs to and its days (N, limits, description);
 * - [marks]: the trip's night marks **by value**, like the pins (a point keeps its mark through every edit, a deleted point's
 *   mark goes – R2 – and comes back with Undo);
 * - [day]: the picked day while it is the plan ([DayFollow]): every change of the plan is written back into the trip in
 *   memory; Save stores the trip while the day's start and goal stay (MD-3), a copy otherwise (MD-4).
 * A whole trip kept as the plan by an older version ([overview]) is only kept safe (Save keeps its days); it opens no view.
 * Kept in the prefs like the plan, so a restart keeps them. Never throws to the caller (reported).
 */
class MultiDayFlow(private val prefs: MultiDayPrefs, private val linkedId: () -> Long) {
    /** The saved multi-day route the plan was opened from: its id, uuid, name and days (the marks are in [marks]). */
    data class TourRef(val id: Long, val uuid: String, val name: String, val days: MultiDay)

    /**
     * The day being followed: [whole] = the tour as it is now (edits written back), [part] = the day as the plan,
     * [wholeOpened] = the tour's points as last saved (Save's "changed elsewhere?" check), [startAt] / [endAt] = where the
     * day starts / ends on the tour's stored line ([DayFollow.spliceAt]).
     */
    data class DayContext(val tour: TourRef, val whole: SharedPlan, val part: DayPart, val wholeOpened: List<GeoPoint>?,
                          val startAt: GeoPoint, val endAt: GeoPoint,
                          /**
                           * The profile the tour's detour points ([whole]'s anchors) lie on (docs/design/detour-stitch.md §8.16). It is not
                           * [whole]'s activity / T level: a day picked under another profile still holds the tour's points for their own, and
                           * they count again when the plan is back on it. Null = none.
                           */
                          val anchorProfile: AnchorProfile? = null,
                          /**
                           * The day's points as it was picked (the router's snaps follow them): its first and last are the day's start
                           * and goal ([endsKept]); null = not known (a state kept before it existed: [startAt] / [endAt] stand in).
                           */
                          val picked: List<GeoPoint>? = null) {
        /**
         * The plan [points] still start and end where the day did (MD-3 / MD-5, owner 2026-11-28): each within
         * [SAME_END_M] of the day's start / goal as picked (the router's snaps follow them) –
         * points added, moved or deleted in between keep the day. False when start or goal moved (MD-4: Save writes a copy, the
         * recording no longer counts for the day).
         */
        fun endsKept(points: List<GeoPoint>): Boolean {
            if (points.size < 2) return false
            val a = picked?.firstOrNull() ?: startAt
            val b = picked?.lastOrNull() ?: endAt
            val m = SAME_END_M
            return com.onpath.app.data.Geo.distance(points.first(), a) <= m && com.onpath.app.data.Geo.distance(points.last(), b) <= m
        }
        /** [anchors] (the tour's, or the day's) with that profile. */
        fun anchorSet(anchors: Map<Int, Anchors> = whole.anchors): AnchorSet = anchorProfile?.takeIf { anchors.isNotEmpty() }?.let { AnchorSet(anchors, it) } ?: AnchorSet.NONE
    }

    /** The days worked out along a trip's line ([compute]); [key] = what they were worked out for. */
    data class DaysView(val days: TourDays, val pointAt: IntArray, val line: List<GeoPoint>, val key: Any)

    var tour by mutableStateOf<TourRef?>(null)
        private set
    var marks by mutableStateOf<Map<GeoPoint, NightMark>>(emptyMap())
        private set
    var day by mutableStateOf<DayContext?>(null)
        private set

    /**
     * The followed day's state was there but unreadable at the start (corrupt prefs): the plan on the map may be a day of a
     * tour that no longer knows it – never saved over the tour (MultiDaySlice reloads the stored tour).
     */
    var dayLost = false
        private set

    /** A whole trip is the plan and no day is picked (only a state kept by an older version: the phone opens days, not trips). */
    val overview: Boolean get() = tour.let { it != null && day == null && linkedId() == it.id }

    /** A day is followed as the plan (and the plan still belongs to its tour). */
    val following: Boolean get() = day.let { it != null && linkedId() == it.tour.id }

    init { restore() }

    // ---- open / leave ---------------------------------------------------------------------------------------------

    /**
     * A saved route was opened with its points [pts] (as loaded): a day's stand-in or a normal route leaves any trip (My routes
     * opens a multi-day route's day sheet instead of the trip, so [t] with days is only an older state's way back).
     */
    fun opened(t: Tour, pts: List<GeoPoint>) {
        val d = t.days
        day = null
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
        tour = null; day = null; marks = emptyMap()
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
    fun snapped(moves: Map<GeoPoint, GeoPoint>, runProfile: AnchorProfile? = null) {
        if (moves.isEmpty() || (marks.isEmpty() && day == null)) return
        marks = marks.entries.associate { (p, m) -> (moves[p] ?: p) to m }
        day = day?.let { c -> c.copy(whole = snappedDay(c, moves, runProfile), part = c.part.copy(startAdded = c.part.startAdded?.let { moves[it] ?: it },
            endAdded = c.part.endAdded?.let { moves[it] ?: it }), picked = c.picked?.map { moves[it] ?: it }) }
        save()
    }

    /**
     * A snap is no edit: the day's points move in the whole tour too, so its detour points stay on the legs at the day's
     * borders. Only a tour with some (a plain tour takes the new values in [pointsChanged] as ever), only inside the day.
     */
    private fun snappedDay(c: DayContext, moves: Map<GeoPoint, GeoPoint>, runProfile: AnchorProfile?): SharedPlan {
        if (c.whole.anchors.isEmpty()) return c.whole
        val moved = c.whole.points.mapIndexed { i, p -> if (i in c.part.from..c.part.to) moves[p] ?: p else p }
        // the snap came from a run of another profile than the one the tour's detour points lie on (§2, §8.16): the moved points lose them
        val other = runProfile != null && c.anchorProfile != runProfile
        return c.whole.copy(points = moved, anchors = if (other) PlanAnchors.edited(c.whole.points, moved, c.whole.anchors) else c.whole.anchors)
    }

    /**
     * The plan's points are [points] now (any edit, Undo, the router's snap): while a day is followed, the change goes
     * back into the whole tour ([DayFollow.writeBack]). [pinned] / [nameOf] = the plan's pins and names by value, [anchors] =
     * its detour points by index of [points] (null: not known for these points, or of another profile than the tour's: the
     * tour's follow their points).
     */
    fun pointsChanged(points: List<GeoPoint>, pinned: Set<GeoPoint>, anchors: AnchorSet? = null, nameOf: (GeoPoint) -> String?) {
        val c = day ?: return
        if (linkedId() != c.tour.id || points.size < 2) return
        // a tour without detour points yet takes the day's first stitch with the profile it lies on (review of the writers:
        // else the tour kept none, and a reload or another device routed the day's detour in full – another line)
        val adopt = anchors?.profile?.takeIf { anchors.anchors.isNotEmpty() && c.whole.anchors.isEmpty() && it != c.anchorProfile }
        val profile = adopt ?: c.anchorProfile
        val dayAnchors = anchors?.takeIf { it.anchors.isEmpty() || it.profile == profile }?.anchors
        // equal points go on when the detour points differ ("Ganz neu berechnen" and its Undo keep the points)
        if (points == c.part.points && (dayAnchors == null || dayAnchors == c.part.anchors)) return
        try {
            val (whole, part) = DayFollow.writeBack(c.whole, c.part, points, pinned, nameOf, ::nightOf, dayAnchors)
            day = c.copy(whole = whole, part = part, anchorProfile = profile)
            Problems.trace("plan", "Day ${c.part.first}–${c.part.last}: change written back into the tour (${whole.points.size} points)")
            save()
        } catch (e: Exception) {
            Problems.error("plan", "A change of the day could not be written back into the multi-day tour – Save keeps the day's plan", e)
        }
    }

    // ---- the days -------------------------------------------------------------------------------------------------

    /** What Save stores as the days of [points] while a whole trip is the plan ([overview], [Tour.days]); null for a normal plan or a day. */
    fun daysFor(points: List<GeoPoint>): MultiDay? {
        if (!overview) return null
        val t = tour ?: return null
        val nights = points.indices.mapNotNull { i -> marks[points[i]]?.let { i to it } }.toMap()
        return t.days.copy(nights = PlanDays.clean(points, nights))
    }

    /**
     * The days of [plan] along its [line] (blocking: call off the main thread) – the same split as My routes' day sheet
     * (DayPick.days). [factors] = the pace setting. The number of days is the trip's own: the phone never changes it (MD-1).
     */
    fun compute(plan: SharedPlan, line: List<GeoPoint>, factors: com.onpath.app.time.Factors, key: Any): DaysView? = try {
        val dl = DayLine.of(line, plan.activity, factors)
        val at = WaypointInsertion.routeIndices(plan.points, line)
        DaysView(PlanDays.of(dl, at, plan.days ?: 1, plan.limits, plan.nights), at, line, key)
    } catch (e: Exception) {
        Problems.warn("plan", "Days of the multi-day tour not worked out", e); null
    }

    /**
     * Day [part] of trip [t] (as [plan], its days [v] along its stored line, [savedPoints] = its points as stored) is the plan
     * now (My routes' day sheet opened it, MD-2): followed from here on. [anchorProfile] = what the trip's detour points lie on.
     */
    fun follow(t: Tour, plan: SharedPlan, v: DaysView, part: DayPart, savedPoints: List<GeoPoint>?, anchorProfile: AnchorProfile?) {
        val d = t.days ?: return
        try {
            val ref = TourRef(t.id, t.uuid, t.name, d.copy(nights = emptyMap()))
            tour = ref
            marks = plan.nights.mapNotNull { (i, m) -> plan.points.getOrNull(i)?.let { it to m } }.toMap()
            day = DayContext(ref, plan, part, savedPoints, v.line[part.startLine.coerceIn(0, v.line.lastIndex)],
                v.line[part.endLine.coerceIn(0, v.line.lastIndex)], anchorProfile, picked = part.points)
            Problems.trace("plan", "Multi-day tour: day ${part.first} of ${v.days.days.size} as the plan (${part.points.size} points)")
            save()
        } catch (e: Exception) {
            Problems.error("plan", "The picked day could not be followed – it stays a plain plan", e)
            tour = null; day = null; marks = emptyMap()
        }
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
        return com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_day_of, p.first.toString(), p.total.toString())
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
                .put("anchorActivity", c.anchorProfile?.activity?.name).put("anchorSac", c.anchorProfile?.sac)
                .put("picked", c.picked?.let { ptsJson(it) })
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
                    pt(o.getJSONObject("startAt")), pt(o.getJSONObject("endAt")), anchorProfileOf(o, whole), o.optJSONArray("picked")?.let(::pts))
            }
        } catch (e: Exception) {
            Problems.error("plan", "Multi-day tour state unreadable after the restart – the plan shows without its days (the saved tour keeps them)", e)
            tour = null; day = null; marks = emptyMap()
            dayLost = prefs.planDayFollow != null // a followed day lost: the start reloads the whole tour (MultiDaySlice)
        }
    }

    /** The kept profile of the tour's detour points; a state from before it was kept has them on the whole plan's own profile. */
    private fun anchorProfileOf(o: JSONObject, whole: SharedPlan): AnchorProfile? {
        val name = o.optString("anchorActivity")
        val own = Activity.entries.firstOrNull { it.name == name }
        if (own != null && o.has("anchorSac")) return AnchorProfile.of(own, o.getInt("anchorSac"))
        return whole.anchors.takeIf { it.isNotEmpty() }?.let { AnchorProfile.of(whole.activity, whole.sac) }
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

    companion object {
        /**
         * How far the plan's start / goal may lie from the day's and still be that day (MD-3 / MD-5, owner 2026-11-27 / 11-28):
         * 200 m – the same as the ticks of My routes' day sheet (`DayPick.SAME_END_M` is this value).
         */
        const val SAME_END_M = 200.0
    }

    private fun planJson(p: SharedPlan) = JSONObject().put("points", ptsJson(p.points))
        .put("activity", p.activity.name).put("sac", p.sac).put("pinned", JSONArray(p.pinned.sorted()))
        .put("names", JSONObject().apply { p.names.forEach { (i, n) -> put(i.toString(), n) } })
        .put("title", p.title).put("days", MultiDay.of(p)?.let { JSONObject(it.toJson()) })
        .put("anchors", AnchorsJson.write(p.points, p.anchors)?.let { JSONArray(it) })

    private fun plan(o: JSONObject): SharedPlan {
        val points = pts(o.getJSONArray("points"))
        val pa = o.getJSONArray("pinned")
        val no = o.getJSONObject("names")
        val base = SharedPlan(points, Activity.valueOf(o.getString("activity")), o.getInt("sac"),
            (0 until pa.length()).map { pa.getInt(it) }.toSet(), no.keys().asSequence().associate { it.toInt() to no.getString(it) },
            o.optString("title").ifEmpty { null }, anchors = AnchorsJson.read(o.optJSONArray("anchors")?.toString(), points))
        return o.optJSONObject("days")?.let { MultiDay.fromJson(it.toString()) }?.into(base) ?: base
    }
}
