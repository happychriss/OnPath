package com.onpath.app.ui.plan

import androidx.compose.runtime.snapshotFlow
import com.onpath.app.cells.CellId
import com.onpath.app.data.Geo
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.plan.DayFollow
import com.onpath.app.domain.plan.DayPart
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.AnchorProfile
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.SharedPlan
import com.onpath.app.domain.AppMode
import com.onpath.app.ui.DayHeadUi
import com.onpath.app.ui.MultiDayFlow
import com.onpath.app.ui.MultiDayPrefs
import com.onpath.app.ui.TourMapAreas
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.cells.MapCells
import com.onpath.app.ui.routes.ToursStore
import com.onpath.app.ui.routes.PlannedRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import com.onpath.strings.msg_route_save_failed
import com.onpath.strings.multiday_own_tour
import com.onpath.strings.multiday_own_tour_activity
import com.onpath.strings.multiday_own_tour_level

/**
 * What [MultiDaySlice] asks of the rest of the app (main thread unless it says otherwise). The names differ from the view
 * model's functions on purpose (`ViewModelInitOrderTest` follows every name an `init` can reach).
 */
interface MultiDayHost {
    /** Plan or Navigate. */
    val mode: AppMode
    /** The plan's route (the run's result; null = none yet). */
    val route: PlannedRoute?
    /** A tour records (the service has a state). */
    val touring: Boolean
    /** The map areas ([MapCells.tourCells] kept after a restart, [MapCells.unmetered]); the app's lazy, first read where the old code read it. */
    val cells: MapCells
    /** The name of a plan point (stop name: the point's own, the search's); null = none. */
    fun placeNameOf(p: GeoPoint): String?
    /** My routes opens with the day sheet of the multi-day route [id] (MD-2: „‹ Alle Tage“ leads back to the day list). */
    fun showDays(id: Long)
    /** My routes' list is read again. */
    fun reloadTourList()
    /** The dialog that reports a failed save (until OK). */
    fun showSaveProblem(text: String)
    /** The backup is told that tour [id] changed. */
    fun tourChanged(id: Long)
    /** The browser sync is told. */
    fun syncChanged(why: String)
    /** The map areas along [line] ([MapDataFlow.tourMaps], blocking geometry – off the main thread); null = not worked out (reported). */
    fun tourMaps(line: List<GeoPoint>): TourMapAreas?
    /**
     * Loads [want] along [line] ([MapDataFlow.loadTourMaps]) in the normal map loading window with its multi-day line (MD-11) and
     * its usual Wi-Fi / mobile data handling.
     */
    fun loadTourMaps(want: List<CellId>, line: List<GeoPoint>)
}

/**
 * Multi-day tours on the phone (docs/design/multi-day-tours.md §0, MD-0 … MD-11, owner 2026-11-28 „kristallklar“): the browser
 * plans the trip, the phone guides ONE day of it – never the whole plan. The view model's slice: a day picked in My routes' day
 * sheet is worked out along the trip's stored line ([prepareDay]) and, once My routes opened it as the plan, followed
 * ([followDay]) – its maps and those of every other day load at once (MD-11); every change of the day goes into the trip in
 * memory, Save writes the day into the trip while its start and goal stay (MD-3, [overwriteWhole]) – else Save makes a
 * standalone copy (MD-4, [leavesTrip] → SaveState); „‹ Alle Tage“ leads back to the day sheet ([allDays], MD-2); the recorded
 * tour remembers the day while its start and goal stay ([followedDay], MD-5). The state itself is [flow] ([MultiDayFlow]: the
 * trip, night marks by value, the followed day – kept in the prefs).
 *
 * Rebuild phase 2 piece 20: moved out of the app as it was; the view model's reads are [host] and the neighbour slices, the
 * tour database is [tours], the prefs are [prefs]. No work in the constructor but what [MultiDayFlow] has always done (it reads
 * its state from the prefs); [start] runs at the place the view model's last `init` called it before.
 */
class MultiDaySlice(
    /** The view model's plan (points, pins, saved link). */
    private val plan: PlanDocument,
    private val planRoute: PlanRouteSlice,
    private val tours: ToursStore,
    private val prefs: MultiDayPrefs,
    private val host: MultiDayHost,
    /** The view model's "saved as" (it also tells the running tour's plan copy). */
    private val setSaved: (String?, Long, List<GeoPoint>?) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val default: CoroutineDispatcher,
) {
    val flow = MultiDayFlow(prefs) { plan.savedId }
    private val activity get() = planRoute.activity
    private val sac get() = planRoute.sac
    private val waypoints get() = plan.points
    private val route get() = host.route
    private fun stopName(p: GeoPoint) = host.placeNameOf(p)

    /** The whole tour's save runs: a second tap on Save makes no second write. */
    private var savingWhole = false

    /** Called once at the view model's start (every property is set by then). */
    fun start() {
        // the plan's saved link and the router's snap reach the night marks (PlanDocument)
        plan.onLinked = flow::linked
        plan.onSnapped = { moves, runProfile -> flow.snapped(moves, runProfile) }
        // the kept multi-day map areas stay asked for after a restart (MapCells.tourCells)
        runCatching {
            host.cells.tourCells = prefs.multiDayMapCells.mapNotNull(CellId::parse).toSet()
        }.onFailure { Problems.warn("cells", "Kept multi-day map areas not restored – a plan change may drop their requests", it) }
        // every change of a followed day's plan goes back into the whole tour (any edit, Undo, the router's snap)
        scope.launchSafe("app", "multiDayWriteBack") {
            // the points and the detour points: "Ganz neu berechnen" (and its Undo) change only the latter
            snapshotFlow { waypoints.toList() to plan.anchorRev }.collect { (pts, _) -> flow.pointsChanged(pts, plan.pinned.toSet(), anchorsOf(pts), ::stopName) }
        }
        // a day of a tour whose followed-day state is gone: never a day saved over the tour; a whole trip kept as the plan: its day sheet
        reloadIfDayLost()
    }

    /** A whole trip kept as the plan by an older version (its overview): the day sheet to pick one day (MD-0 / MD-2). */
    private fun wholeTripAtStart() {
        val t = flow.tour?.takeIf { flow.overview } ?: return
        Problems.warn("plan", "Multi-day tour: the whole trip \"${t.name}\" is the plan (kept by an older version) – its day sheet opens")
        host.showDays(t.id)
    }

    /**
     * Robust first: after a restart the plan belongs to a saved multi-day tour but no followed day is known – its state was
     * unreadable ([MultiDayFlow.dayLost]) or is missing while the plan was opened as other points than the tour's (a day's).
     * A whole trip kept as the plan by an older version opens its day sheet ([wholeTripAtStart]).
     * Otherwise the plan is unlinked from the tour (it stays on the map as an unsaved plan – a Save makes a route of its own, never
     * a day saved over the whole tour) and the tour's day sheet opens to pick the day again (MD-2). Not during a recording
     * (the followed plan stays; Save's "changed elsewhere" check writes a copy then).
     */
    private fun reloadIfDayLost() {
        val id = plan.linkId
        if (flow.following || id < 0 || plan.points.size < 2) return
        val opened = plan.savedPoints?.map { it.lat to it.lon } ?: run { wholeTripAtStart(); return }
        scope.launchSafe("app", "multiDayReload") {
            val t = withContext(io) {
                runCatching { tours.getTour(id) }.onFailure { Problems.warn("db", "Saved route not read at the start – no multi-day check", it) }.getOrNull()
            } ?: return@launchSafe
            if (t.deletedMs != null) return@launchSafe
            if (!reloadsTour(flow.dayLost, flow.following, t.days != null, opened, t.waypoints.map { it.lat to it.lon })) { wholeTripAtStart(); return@launchSafe }
            if (host.touring) {
                Problems.warn("plan", "Multi-day tour: the followed day is unknown after the restart, a tour records – plan kept, Save writes a copy"); return@launchSafe
            }
            if (plan.linkId != id) return@launchSafe // another plan meanwhile
            Problems.warn("plan", "Multi-day tour: the followed day is unknown after the restart – the plan is unlinked from \"${t.name}\" (never a day saved over it), its day sheet opens")
            setSaved(null, -1L, null)
            host.showDays(t.id)
        }
    }

    /** A saved route was opened (the view model's openTour): the multi-day state follows it (a day's stand-in is no trip). */
    fun opened(t: Tour, pts: List<GeoPoint>) = flow.opened(t, pts)

    /** A whole trip is the plan (kept by an older version; no day picked) – the plan run treats its corridor as a tour's. */
    val overview: Boolean get() = flow.overview

    /** The plan's detour points when [pts] still are the plan's points (a collected change can be one step behind). */
    private fun anchorsOf(pts: List<GeoPoint>) = plan.anchorSet.takeIf { pts == waypoints.toList() }

    /** What Save stores as the days of [points] (a whole trip kept as the plan, [overview]); null for a normal plan or a followed day. */
    fun daysFor(points: List<GeoPoint>): MultiDay? = flow.daysFor(points)

    /** My routes (blocking, IO): "Karten fehlen" on a multi-day tour while map areas of its corridor are not on the phone. */
    fun withMaps(t: Tour): Tour = if (t.kind != TourKind.PLANNED || t.days == null || t.deletedMs != null) t
        else t.copy(mapsMissing = runCatching { host.tourMaps(tours.loadSegments(t.id).flatten().map { it.toGeo() })?.missing?.isNotEmpty() }
            .onFailure { Problems.warn("routes", "Maps of the multi-day tour ${t.id} not checked – no tag", it) }.getOrNull())

    // ---- one day of the trip becomes the plan (MD-2) ------------------------------------------------------------------

    /**
     * Day [n] of trip [tour], worked out along its stored [line] ([DayPick] made the same split for the sheet): [standIn] is the
     * day as a route My routes opens (its points with names, pins and detour points, linked to the trip; no days, no uuid – the
     * plan is the day, not the trip), [followDay] then ties it to the trip.
     */
    class DayStart(val tour: Tour, val whole: SharedPlan, val view: MultiDayFlow.DaysView, val part: DayPart,
                   val anchorProfile: AnchorProfile?) {
        /** The day's stretch of the trip's line (the stand-in's line until the run routes it; the camera). */
        val dayLine: List<GeoPoint> get() = view.line.subList(part.startLine.coerceIn(0, view.line.lastIndex),
            part.endLine.coerceIn(part.startLine.coerceIn(0, view.line.lastIndex), view.line.lastIndex) + 1)

        val standIn: Tour get() = tour.copy(
            waypoints = part.points.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, part.names[i]) },
            pinned = part.pinned, anchors = part.anchors, days = null, uuid = "", mapsMissing = null)
    }

    /**
     * My routes' day sheet: day [n] of [t] along its stored [line] – the trip as a plan (its points, pins, names, days, nights,
     * detour points), its days as the sheet shows them, the day's part. Null when it cannot be worked out (reported) – the sheet
     * says so in a line, never the whole trip as the plan (MD-0).
     */
    suspend fun prepareDay(t: Tour, line: List<GeoPoint>, n: Int): DayStart? {
        val d = t.days ?: return null
        if (line.size < 2 || t.waypoints.size < 2) return null
        val act = planRoute.activityOrNull(t.activity) ?: planRoute.activity
        val f = planRoute.planFactors(act)
        return withContext(default) {
            runCatching {
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                val names = t.waypoints.mapIndexedNotNull { i, w -> w.name?.takeIf { it.isNotBlank() }?.let { i to it } }.toMap()
                val anchors = t.anchors.filterKeys { it in pts.indices }
                val whole = d.into(SharedPlan(pts, act, t.sac, t.pinned.filter { it in pts.indices }.toSet(), names, t.name, anchors = anchors))
                val v = flow.compute(whole, line, f, line) ?: return@runCatching null
                if (n !in 1..v.days.days.size) return@runCatching null
                val raw = DayFollow.part(whole, v.days, v.pointAt, v.line, n, n) ?: return@runCatching null
                // the day's points as a route opens them (lat / lon only): the plan and the followed day are the same values
                fun flat(p: GeoPoint) = GeoPoint(p.lat, p.lon)
                val part = raw.copy(points = raw.points.map(::flat), startAdded = raw.startAdded?.let(::flat), endAdded = raw.endAdded?.let(::flat))
                DayStart(t, whole, v, part, anchors.takeIf { it.isNotEmpty() }?.let { AnchorProfile.of(act, t.sac) })
            }.onFailure { Problems.warn("plan", "Day $n of the multi-day tour \"${t.name}\" not worked out", it) }.getOrNull()
        }
    }

    /**
     * The day [s] was opened as the plan (My routes' openTour of [DayStart.standIn]): it is followed now – tied to the trip for
     * Save (MD-3 / MD-4) and the recording (MD-5) – and the maps of ALL days of the trip load in the normal map loading window
     * (MD-11, owner 2026-11-28: „sonst übernachte ich auf einer Hütte im Nirvana und mir fehlt der nächste Tag“).
     */
    fun followDay(s: DayStart) {
        val pts = waypoints.toList()
        if (plan.linkId != s.tour.id || pts != s.part.points) {
            Problems.warn("plan", "Day ${s.part.first} of \"${s.tour.name}\" opened, but the plan is another one now – not followed"); return
        }
        flow.follow(s.tour, s.whole, s.view, s.part, s.whole.points, s.anchorProfile)
        loadAllMaps(s.view.line)
    }

    /** MD-11: the map areas along the whole trip's [line] that are not on the phone load (kept after a restart). */
    private fun loadAllMaps(line: List<GeoPoint>) {
        scope.launchSafe("app", "multiDayMaps") {
            val m = withContext(default) { host.tourMaps(line) } ?: return@launchSafe
            if (m.missing.isEmpty()) return@launchSafe
            Problems.info("cells", "Multi-day tour: a day opened – the maps of all days load (${m.missing.size} area(s), ≈ ${m.bytes / 1_000_000} MB)")
            host.loadTourMaps(m.missing, line)
            prefs.multiDayMapCells = prefs.multiDayMapCells + m.missing.map { it.id }
        }
    }

    fun dayLabel(): String? = flow.dayLabel()

    /**
     * The plan is one day of a saved multi-day route whose start and goal stayed ([MultiDayFlow.DayContext.endsKept]; MD-5,
     * owner 2026-11-28: points added / moved / deleted in between keep it, a moved start or goal drops it): its uuid and the
     * day – the recorded tour keeps them. A copy of the route ("Speichern als Kopie", a re-import, MD-4's own tour) gets a new
     * uuid: its days start unridden – on purpose.
     */
    fun followedDay(): Pair<String, Int>? = flow.day?.takeIf { flow.following && leaveReason() == null }
        ?.let { it.tour.uuid to it.part.first }

    /** Why a followed day no longer belongs to its trip ([leaveReason]). */
    enum class Leave { ENDS, ACTIVITY, LEVEL }

    /**
     * Why the followed day leaves its trip: its start or goal moved (MD-4, [Leave.ENDS] – said first when more changed), or the
     * plan's activity ([Leave.ACTIVITY]) or, hiking, its T level ([Leave.LEVEL]) is not the trip's (MD-12, owner's decision card
     * 2026-11-28 09:37: the phone never changes the whole trip's activity). Null = it still belongs to the trip (or no day).
     */
    fun leaveReason(): Leave? {
        val c = flow.day?.takeIf { flow.following } ?: return null
        return when {
            !c.endsKept(waypoints.toList()) -> Leave.ENDS
            activity != c.whole.activity -> Leave.ACTIVITY
            activity == com.onpath.app.routing.Activity.HIKE && sac != c.whole.sac -> Leave.LEVEL
            else -> null
        }
    }

    /**
     * A day is followed and leaves its trip ([leaveReason]): Save makes a standalone copy, the trip stays as it was – points,
     * activity, T level; the plan sheet says so before ([dayHeadUi]) and SaveState when it saves ([leavesTripNote]).
     */
    fun leavesTrip(): Boolean = leaveReason() != null

    /** The line that says why ([leaveReason]); null when the day still belongs to its trip. */
    fun leavesTripNote(): String? = when (leaveReason()) {
        Leave.ENDS -> com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_own_tour)
        Leave.ACTIVITY -> com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_own_tour_activity)
        Leave.LEVEL -> com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_own_tour_level)
        null -> null
    }

    /** "‹ Alle Tage" (MD-2): My routes with the trip's day sheet – the day stays the plan until another day is picked. */
    fun allDays() {
        val c = flow.day?.takeIf { flow.following } ?: return
        Problems.trace("plan", "Multi-day tour: back to the day sheet of \"${c.tour.name}\"")
        host.showDays(c.tour.id)
    }

    // ---- saving the whole tour while a day is the plan (#14) ------------------------------------------------------

    /**
     * ☰ Speichern / the tour start's save while a day is followed (MD-3: its start and goal stayed – SaveState asks
     * [leavesTrip] first and saves a standalone copy otherwise, MD-4): the whole tour with the day's change is written over
     * the saved tour (its line = the stored line with the day's new line spliced in), with its days, and synced like any
     * saved route (MD-8). False when no day is followed – the view model saves as always.
     */
    fun overwriteWhole(name: String, then: (() -> Unit)?): Boolean {
        val pts = waypoints.toList()
        flow.pointsChanged(pts, plan.pinned.toSet(), anchorsOf(pts), ::stopName) // the newest names and pins in too
        val c = flow.day?.takeIf { flow.following } ?: return false
        val w = c.whole
        val days = MultiDay.of(w) ?: return false
        if (savingWhole) return true
        savingWhole = true
        // the trip's own profile (MD-12): Save over the trip only runs while the day's is the trip's ([leavesTrip]) – the phone
        // never changes the whole trip's activity or T level, and the trip's detour points stay as they are
        val act = w.activity; val level = w.sac; val f = planRoute.planFactors(act)
        val dayLine = route?.points?.takeIf { it.size >= 2 } ?: pts
        val stops = com.onpath.app.gpx.PlanGpx.stops(w.points) { w.names[it] }
        scope.launchSafe("app", "saveWholeTour") {
            try {
                val ok = withContext(io) {
                    runCatching {
                        val (line, n) = wholeLine(c.tour.id, w.points, c.startAt, c.endAt, dayLine, act, f)
                        tours.replacePlanned(c.tour.id, PlannedRow(act.name, level, n[0], n[1], n[2], n[3], listOf(line), stops, w.pinned, null, days,
                            w.anchors))
                            .also { if (it && name != c.tour.name) tours.rename(c.tour.id, name) }
                    }.onFailure { Problems.error("db", "Saving the multi-day tour with the day's change failed", it) }
                }
                if (ok.getOrNull() != true) {
                    host.showSaveProblem(com.onpath.strings.Txt.s(com.onpath.strings.Res.string.msg_route_save_failed)); return@launchSafe
                }
                Problems.trace("plan", "Multi-day tour saved with the change of day ${c.part.first} (${w.points.size} points)")
                host.tourChanged(c.tour.id); host.syncChanged("multi-day tour saved")
                flow.wholeSaved(w.points, dayLine, name)
                setSaved(name, c.tour.id, pts)
                host.reloadTourList(); then?.invoke()
            } finally { savingWhole = false }
        }
        return true
    }

    /** Blocking (IO): the tour's stored line with the day's [dayLine] in place, and distance, ascent, descent, time. */
    private fun wholeLine(tourId: Long, points: List<GeoPoint>, startAt: GeoPoint, endAt: GeoPoint, dayLine: List<GeoPoint>,
                          act: com.onpath.app.routing.Activity, f: com.onpath.app.time.Factors): Pair<List<TrackPoint>, DoubleArray> {
        val stored = runCatching { tours.loadSegments(tourId).flatten().map { it.toGeo() } }
            .onFailure { Problems.warn("db", "The multi-day tour's line not read – saved with its points as the line", it) }.getOrNull()
        val line = if (stored.isNullOrEmpty()) points else DayFollow.spliceAt(stored, startAt, endAt, dayLine)
        val (up, down) = Geo.routeClimbAlong(line.map { it.ele }, com.onpath.app.routing.Planner.ELE_FILTER_M)
        val time = runCatching { com.onpath.app.time.TimeEstimate.planSeconds(line, act, f) }.getOrDefault(0.0)
        return line.map { TrackPoint(it.lat, it.lon, it.ele) } to doubleArrayOf(Geo.length(line), up.lastOrNull() ?: 0.0, down.lastOrNull() ?: 0.0, time)
    }

    // ---- what the screens show -------------------------------------------------------------------------------------

    /**
     * The followed day's head line in the plan sheet: "Tag 4 von 6", "‹ Alle Tage" (not during a tour) and, when its start or
     * goal moved, MD-4's line before saving; null when no day is followed.
     */
    fun dayHeadUi(): DayHeadUi? = flow.dayLabel()?.let { DayHeadUi(it, !host.touring, leavesTrip(), leavesTripNote()) }

    companion object {
        /**
         * The plan must not stay linked to the stored multi-day tour ([reloadIfDayLost]): it belongs to a saved route with days
         * ([storedHasDays]), no day is followed, and either the followed day's state was lost ([dayLost]) or the plan was
         * opened as other points ([opened]) than the tour's ([stored]) – a day's, whose state is missing. The tour's own
         * points moved onto their ways by the router ([SAME_POINT_M]) are still the tour's. Pure – MultiDayFlowTest.
         */
        fun reloadsTour(dayLost: Boolean, following: Boolean, storedHasDays: Boolean, opened: List<Pair<Double, Double>>,
                        stored: List<Pair<Double, Double>>): Boolean = storedHasDays && !following && (dayLost ||
            opened.size != stored.size || opened.indices.any { i ->
                Geo.distance(GeoPoint(opened[i].first, opened[i].second), GeoPoint(stored[i].first, stored[i].second)) > SAME_POINT_M
            })

        /** The router's snap moves a plan point at most about this far (a free point onto its way). */
        const val SAME_POINT_M = 100.0
    }
}
