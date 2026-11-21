package com.onpath.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.onpath.app.cells.CellId
import com.onpath.app.cells.TourCorridor
import com.onpath.app.data.Geo
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.debug.launchSafe
import com.onpath.app.domain.plan.DayFollow
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.domain.AppMode
import com.onpath.app.ui.DayHeadUi
import com.onpath.app.ui.DayRowUi
import com.onpath.app.ui.DaysUi
import com.onpath.app.ui.Camera
import com.onpath.app.ui.EndNamer
import com.onpath.app.ui.MultiDayFlow
import com.onpath.app.ui.MultiDayPrefs
import com.onpath.app.ui.TourMapAreas
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.cells.MapCells
import com.onpath.app.ui.routes.ToursStore
import com.onpath.app.ui.routes.PlannedRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import com.onpath.strings.msg_route_save_failed

/**
 * What [MultiDaySlice] asks of the rest of the app (main thread unless it says otherwise). The names differ from the view
 * model's functions on purpose (`ViewModelInitOrderTest` follows every name an `init` can reach).
 */
interface MultiDayHost {
    /** Plan or Navigate (the map shows the day ends only in Plan). */
    val mode: AppMode
    /** The plan's route (the run's result; null = none yet) – Compose state, read inside `snapshotFlow`. */
    val route: PlannedRoute?
    /** A tour records (the service has a state). */
    val touring: Boolean
    /** The map areas ([MapCells.tourCells] kept after a restart, [MapCells.unmetered]); the app's lazy, first read where the old code read it. */
    val cells: MapCells
    /** The name of a plan point (stop name: the point's own, the search's); null = none. */
    fun placeNameOf(p: GeoPoint): String?
    /** The whole stored tour is opened as the plan again (My routes' open, as the user would). */
    fun reopenTour(t: Tour)
    /** My routes' list is read again. */
    fun reloadTourList()
    /** The dialog that reports a failed save (until OK). */
    fun showSaveProblem(text: String)
    /** The backup is told that tour [id] changed. */
    fun tourChanged(id: Long)
    /** The browser sync is told. */
    fun syncChanged(why: String)
    /** The offline place index, ready to name day ends (suspends while the index is built; may throw). */
    suspend fun endNamer(): EndNamer
    /** The map areas along [line] ([MapDataFlow.tourMaps], blocking geometry – off the main thread); null = not worked out (reported). */
    fun tourMaps(line: List<GeoPoint>): TourMapAreas?
    /** Loads [want] along [line] ([MapDataFlow.loadTourMaps]); on mobile data they wait for Wi-Fi ([wifiWait]). */
    fun loadTourMaps(want: List<CellId>, line: List<GeoPoint>, wifiWait: Boolean)
}

/**
 * Multi-day tours on the phone (docs/design/multi-day-tours.md §3 "Phone", stage 4) – the view model's slice (after the
 * TourController / RecentPlans pattern; the view model keeps one line per hook): the overview of an opened tour (its days
 * worked out for each route), the map question at the first opening (§7), "Tag n fahren" (the picked day becomes the
 * plan), "‹ Alle Tage", every change of a followed day written back into the whole tour, and Save of the whole tour while
 * a day is the plan. The state itself is [flow] ([MultiDayFlow]: days, night marks by value, the followed day – kept in
 * the prefs).
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
    private val camera: Camera,
    private val host: MultiDayHost,
    /** The view model's one undoable plan edit. */
    private val change: (MutableList<GeoPoint>.() -> Unit) -> Unit,
    /** The view model's "saved as" (it also tells the running tour's plan copy). */
    private val setSaved: (String?, Long, List<GeoPoint>?) -> Unit,
    private val rememberPlaceName: (GeoPoint, String) -> Unit,
    private val nearbyNames: () -> Map<GeoPoint, String>,
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
    private fun pinnedIndices() = plan.pinnedIndices()

    /** The map question at the first opening of a multi-day tour (§7): sizes of the whole tour and of one day. */
    data class TourMapsAsk(val tourUuid: String, val wholeBytes: Long, val day: Int, val dayBytes: Long, val onWifi: Boolean,
                           val whole: List<CellId>, val dayCells: List<CellId>, val line: List<GeoPoint>, val dayLine: List<GeoPoint>)
    enum class TourMapsAnswer { WHOLE, DAY, LATER }

    var tourMapsAsk by mutableStateOf<TourMapsAsk?>(null)
        private set
    /** The picked day's maps are not all on the phone (§7: said before the start): about how many bytes; null = all there. */
    var dayMapsMissing by mutableStateOf<Long?>(null)
        private set
    private var dayMapsCells: Pair<List<CellId>, List<GeoPoint>>? = null
    /** The whole tour's save runs: a second tap on Save makes no second write. */
    private var savingWhole = false

    /** Called once at the view model's start (every property is set by then). */
    fun start() {
        // the plan's saved link and the router's snap reach the night marks (PlanDocument)
        plan.onLinked = flow::linked
        plan.onSnapped = flow::snapped
        // the kept multi-day map areas stay asked for after a restart (MapCells.tourCells)
        runCatching {
            host.cells.tourCells = prefs.multiDayMapCells.mapNotNull(CellId::parse).toSet()
            host.cells.tourWifiCells = prefs.multiDayWifiCells.mapNotNull(CellId::parse).toSet()
        }.onFailure { Problems.warn("cells", "Kept multi-day map areas not restored – a plan change may drop their requests", it) }
        // every change of a followed day's plan goes back into the whole tour (any edit, Undo, the router's snap)
        scope.launchSafe("app", "multiDayWriteBack") {
            snapshotFlow { waypoints.toList() }.collect { pts -> flow.pointsChanged(pts, plan.pinned.toSet(), ::stopName) }
        }
        // a day of a tour whose followed-day state is gone: the whole stored tour again, never a day saved over it
        reloadIfDayLost()
        // the overview's days, worked out off the main thread for each route of the tour
        scope.launchSafe("app", "multiDayDays") {
            snapshotFlow { Triple(route, flow.overview, flow.marks) }.collectLatest { (r, on, _) ->
                if (!on || r == null || r.points.size < 2) { if (!on) flow.show(null); return@collectLatest }
                val plan = wholePlan() ?: return@collectLatest
                val f = planRoute.planFactors()
                val v = withContext(default) { flow.compute(plan, r.points, f, r) } ?: return@collectLatest
                flow.show(v)
                askMapsOnce(v)
            }
        }
    }

    /**
     * Robust first: after a restart the plan belongs to a saved multi-day tour but no followed day is known – its state was
     * unreadable ([MultiDayFlow.dayLost]) or is missing while the plan was opened as other points than the tour's
     * (a day's). Then the stored tour is loaded as the plan again (the plan on the map goes under Zuletzt when it has
     * changes, as for every route opened) – a Save never writes a day's points over the whole tour. Not during a
     * recording (the followed plan stays; Save's "changed elsewhere" check writes a copy then).
     */
    private fun reloadIfDayLost() {
        val id = plan.linkId
        if (flow.following || id < 0 || plan.points.size < 2) return
        val opened = plan.savedPoints?.map { it.lat to it.lon } ?: return
        scope.launchSafe("app", "multiDayReload") {
            val t = withContext(io) {
                runCatching { tours.getTour(id) }.onFailure { Problems.warn("db", "Saved route not read at the start – no multi-day check", it) }.getOrNull()
            } ?: return@launchSafe
            if (t.deletedMs != null || !reloadsTour(flow.dayLost, flow.following, t.days != null, opened, t.waypoints.map { it.lat to it.lon })) return@launchSafe
            if (host.touring) {
                Problems.warn("plan", "Multi-day tour: the followed day is unknown after the restart, a tour records – plan kept, Save writes a copy"); return@launchSafe
            }
            Problems.warn("plan", "Multi-day tour: the followed day is unknown after the restart – the whole tour \"${t.name}\" is loaded again (never a day saved over it)")
            host.reopenTour(t)
        }
    }

    /** A saved route was opened (the view model's openTour): a multi-day tour shows its overview. */
    fun opened(t: Tour, pts: List<GeoPoint>) = flow.opened(t, pts)

    /** The overview shows (a multi-day tour is the plan, no day picked): its corridor waits for the map question. */
    val overview: Boolean get() = flow.overview

    private fun names(pts: List<GeoPoint>) = pts.indices.mapNotNull { i -> stopName(pts[i])?.let { i to it } }.toMap()
    private fun wholePlan() = waypoints.toList().let { pts -> flow.plan(pts, activity, sac, pinnedIndices(), names(pts)) }

    /** What Save stores as the days of [points] (the overview); null for a normal plan or a followed day. */
    fun daysFor(points: List<GeoPoint>): MultiDay? = flow.daysFor(points)

    // ---- the maps (§7) --------------------------------------------------------------------------------------------

    private suspend fun askMapsOnce(v: MultiDayFlow.DaysView) {
        val t = flow.tour ?: return
        if (t.uuid.isEmpty() || t.uuid in prefs.multiDayMapsAsked || tourMapsAsk?.tourUuid == t.uuid) return
        val n = flow.selected?.first ?: 1
        val d = v.days.days.getOrNull(n - 1) ?: return
        val dayLine = TourCorridor.stretch(v.line, d.fromM, d.toM)
        val (whole, day) = withContext(default) { host.tourMaps(v.line) to host.tourMaps(dayLine) }
        if (whole == null || day == null) return
        if (whole.missing.isEmpty()) { prefs.multiDayMapsAsked = prefs.multiDayMapsAsked + t.uuid; return }
        Problems.trace("cells", "Multi-day tour opened the first time: ${whole.missing.size} map area(s) missing (≈ ${whole.bytes / 1_000_000} MB) – asking once")
        tourMapsAsk = TourMapsAsk(t.uuid, whole.bytes, n, day.bytes, host.cells.unmetered(), whole.missing, day.missing, v.line, dayLine)
    }

    /** The map question answered: the whole tour, only its day, or later (picking a day checks again). */
    fun answerTourMaps(what: TourMapsAnswer) {
        val a = tourMapsAsk ?: return
        tourMapsAsk = null
        prefs.multiDayMapsAsked = prefs.multiDayMapsAsked + a.tourUuid
        Problems.trace("cells", "Multi-day tour maps: ${what.name.lowercase()}" + if (!a.onWifi) " (mobile data: wait for Wi-Fi)" else "")
        when (what) {
            TourMapsAnswer.WHOLE -> loadMaps(a.whole, a.line, wifiWait = !a.onWifi)
            TourMapsAnswer.DAY -> loadMaps(a.dayCells, a.dayLine, wifiWait = !a.onWifi)
            TourMapsAnswer.LATER -> Unit
        }
    }

    private fun loadMaps(want: List<CellId>, line: List<GeoPoint>, wifiWait: Boolean) {
        if (want.isEmpty()) return
        host.loadTourMaps(want, line, wifiWait)
        prefs.multiDayMapCells = prefs.multiDayMapCells + want.map { it.id }
        if (wifiWait) prefs.multiDayWifiCells = prefs.multiDayWifiCells + want.map { it.id }
    }

    /** The picked day's missing maps – "Laden" in the day's sheet: now, also on mobile data (the user asked). */
    fun loadDayMaps() {
        val (want, line) = dayMapsCells ?: return
        loadMaps(want, line, wifiWait = false)
        dayMapsMissing = null
    }

    /** My routes (blocking, IO): "Karten fehlen" on a multi-day tour while map areas of its corridor are not on the phone. */
    fun withMaps(t: Tour): Tour = if (t.kind != TourKind.PLANNED || t.days == null || t.deletedMs != null) t
        else t.copy(mapsMissing = runCatching { host.tourMaps(tours.loadSegments(t.id).flatten().map { it.toGeo() })?.missing?.isNotEmpty() }
            .onFailure { Problems.warn("routes", "Maps of the multi-day tour ${t.id} not checked – no tag", it) }.getOrNull())

    // ---- picking a day ---------------------------------------------------------------------------------------------

    fun tapDay(n: Int) = flow.tapDay(n)
    fun holdDay(n: Int) = flow.holdDay(n)
    fun dayLabel(): String? = flow.dayLabel()

    /** The overview: "Tag n fahren" – the picked day(s) become the plan (§3 Phone 3); "Los geht's" then follows them. */
    fun rideDays() {
        if (host.touring || !flow.overview) return
        val whole = wholePlan() ?: return
        val tourId = plan.linkId; val tourName = plan.linkName
        val v = flow.view ?: return
        val part = flow.pickDay(whole, plan.savedPoints) ?: return
        plan.pin(part.pinned.map { part.points[it] })
        part.names.forEach { (i, n) -> rememberPlaceName(part.points[i], n) }
        change { clear(); addAll(part.points) }
        plan.forgetHistory()
        setSaved(tourName, tourId, part.points)
        val dayLine = v.line.subList(part.startLine.coerceIn(0, v.line.lastIndex), part.endLine.coerceIn(0, v.line.lastIndex) + 1)
        camera.fit(dayLine)
        // §7: picking a day always checks that day's maps before the start
        dayMapsMissing = null; dayMapsCells = null
        scope.launchSafe("app", "dayMaps") {
            val m = withContext(default) { host.tourMaps(dayLine) } ?: return@launchSafe
            if (m.missing.isNotEmpty() && flow.following) { dayMapsCells = m.missing to dayLine; dayMapsMissing = m.bytes }
        }
    }

    /** "‹ Alle Tage" (not during a tour): the whole tour – with the day's changes – is the plan again. */
    fun allDays() {
        if (host.touring) return
        val tourName = plan.linkName
        val (whole, opened) = flow.leaveDay() ?: return
        plan.pin(whole.pinned.mapNotNull { whole.points.getOrNull(it) })
        whole.names.forEach { (i, n) -> whole.points.getOrNull(i)?.let { rememberPlaceName(it, n) } }
        dayMapsMissing = null; dayMapsCells = null
        change { clear(); addAll(whole.points) }
        plan.forgetHistory()
        setSaved(tourName, flow.tour?.id ?: plan.linkId, opened ?: whole.points)
        Problems.trace("plan", "Multi-day tour: back to all days (${whole.points.size} points)")
    }

    // ---- saving the whole tour while a day is the plan (#14) ------------------------------------------------------

    /**
     * ☰ Speichern / the tour start's save while a day is followed: the whole tour with the day's change is written over
     * the saved tour (its line = the stored line with the day's new line spliced in), with its days. False when no day is
     * followed – the view model saves as always.
     */
    fun overwriteWhole(name: String, then: (() -> Unit)?): Boolean {
        val pts = waypoints.toList()
        flow.pointsChanged(pts, plan.pinned.toSet(), ::stopName) // the newest names and pins in too
        val c = flow.day?.takeIf { flow.following } ?: return false
        val w = c.whole
        val days = MultiDay.of(w) ?: return false
        if (savingWhole) return true
        savingWhole = true
        val act = activity; val level = sac; val f = planRoute.planFactors(act)
        val dayLine = route?.points?.takeIf { it.size >= 2 } ?: pts
        val stops = com.onpath.app.gpx.PlanGpx.stops(w.points) { w.names[it] }
        scope.launchSafe("app", "saveWholeTour") {
            try {
                val ok = withContext(io) {
                    runCatching {
                        val (line, n) = wholeLine(c.tour.id, w.points, c.startAt, c.endAt, dayLine, act, f)
                        tours.replacePlanned(c.tour.id, PlannedRow(act.name, level, n[0], n[1], n[2], n[3], listOf(line), stops, w.pinned, null, days))
                            .also { if (it && name != c.tour.name) tours.rename(c.tour.id, name) }
                    }.onFailure { Problems.error("db", "Saving the multi-day tour with the day's change failed", it) }
                }
                if (ok.getOrNull() != true) {
                    host.showSaveProblem(com.onpath.strings.Txt.s(com.onpath.strings.Res.string.msg_route_save_failed)); return@launchSafe
                }
                Problems.trace("plan", "Multi-day tour saved with the change of day ${c.part.first}–${c.part.last} (${w.points.size} points)")
                host.tourChanged(c.tour.id); host.syncChanged("multi-day tour saved")
                flow.wholeSaved(w.points, dayLine, name)
                setSaved(name, c.tour.id, pts)
                host.reloadTourList(); then?.invoke()
            } finally { savingWhole = false }
        }
        return true
    }

    /**
     * The plan leaves the map while a followed day has unsaved changes: the whole tour with them goes under Zuletzt (with
     * its days) instead of the day alone. False when no day is followed / nothing changed.
     */
    fun keepWhole(): Boolean {
        if (!plan.unsaved) return false
        val pts = waypoints.toList()
        flow.pointsChanged(pts, plan.pinned.toSet(), ::stopName)
        val c = flow.day?.takeIf { flow.following } ?: return false
        val w = c.whole
        val days = MultiDay.of(w) ?: return false
        val act = activity
        return runCatching {
            val (line, n) = wholeLine(c.tour.id, w.points, c.startAt, c.endAt, route?.points ?: pts, act, planRoute.planFactors(act))
            tours.insertDraftPlanned(c.tour.name, PlannedRow(act.name, sac, n[0], n[1], n[2], n[3], listOf(line),
                com.onpath.app.gpx.PlanGpx.stops(w.points) { w.names[it] }, w.pinned, null, days))
            Problems.trace("plan", "Multi-day tour with the day's change kept under Zuletzt")
            host.reloadTourList()
            true
        }.onFailure { Problems.error("db", "The multi-day tour with the day's change could not be kept under Zuletzt", it) }.getOrDefault(false)
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

    /** The plan sheet's overview (rows, profile, day borders); null for a normal plan. */
    fun daysUi(): DaysUi? {
        if (!flow.overview) return null
        val t = flow.tour ?: return null
        val v = flow.view ?: return null
        val total = v.days.days.lastOrNull()?.toM?.takeIf { it > 0 } ?: return null
        val pts = waypoints.toList()
        // a place name or null; a night's hotel counts as its end's place before the nearby lookup
        fun nameAt(p: GeoPoint): String? = (stopName(p) ?: flow.marks[p]?.hotel?.let { h -> h.name ?: h.address }
            ?: nearbyNames()[p] ?: endNames[p.lat to p.lon])?.trim()?.takeIf { it.isNotEmpty() }
        val ends = listOf(pts.firstOrNull()) + v.days.days.map { d -> d.end?.let { e -> e.planIndex?.let { pts.getOrNull(it) } ?: e.point } ?: pts.lastOrNull() }
        val rows = v.days.days.mapIndexed { i, d ->
            val places = com.onpath.app.routing.DayRowText.places(ends[i]?.let(::nameAt), ends[i + 1]?.let(::nameAt))
            DayRowUi(d.number, places, d.distanceM, d.ascentM, d.end?.kind, d.over.isNotEmpty())
        }
        lookUpEndNames(ends.filterNotNull())
        return DaysUi(t.name, v.days.days.size, v.line, v.days.days.mapNotNull { d -> d.end?.let { (it.alongM / total).toFloat() to it.kind } },
            rows, flow.selected)
    }

    /** The followed day's head line ("Tag 4 von 6"); null when no day is followed. */
    fun dayHeadUi(): DayHeadUi? = flow.dayLabel()?.let { DayHeadUi(it, !host.touring, dayMapsMissing) }

    /** The map in Plan: the overview's day ends on the line and the picked day(s) as a band; none otherwise. */
    fun dayEndsOnMap(): Pair<List<Pair<GeoPoint, DayEndKind>>, List<GeoPoint>?> {
        val v = flow.view
        if (host.mode != AppMode.PLAN || !flow.overview || v == null) return emptyList<Pair<GeoPoint, DayEndKind>>() to null
        val ends = v.days.days.mapNotNull { d -> d.end?.let { it.point to it.kind } }
        val day = flow.selected?.let { s ->
            val a = if (s.first == 1) 0 else v.days.days.getOrNull(s.first - 2)?.end?.lineIndex ?: return@let null
            val b = v.days.days.getOrNull(s.last - 1)?.let { it.end?.lineIndex ?: v.line.lastIndex } ?: return@let null
            v.line.subList(a.coerceIn(0, v.line.lastIndex), b.coerceIn(a, v.line.lastIndex) + 1)
        }
        return ends to day
    }

    /** Place names of the grey day ends (the offline index), by position. */
    private var endNames by mutableStateOf<Map<Pair<Double, Double>, String>>(emptyMap())
    private var endNamesJob: Job? = null
    private fun lookUpEndNames(points: List<GeoPoint>) {
        val todo = points.filter { (it.lat to it.lon) !in endNames && stopName(it) == null && it !in nearbyNames() }.distinct()
        if (todo.isEmpty() || endNamesJob?.isActive == true) return
        endNamesJob = scope.launchSafe("app", "dayEndNames") {
            val namer = try { host.endNamer() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for the day ends; their rows without places", e); return@launchSafe }
            val names = withContext(io) {
                todo.associate { p -> (p.lat to p.lon) to (namer.label(p) ?: "") }
            }
            endNames = endNames + names
        }
    }

    companion object {
        /**
         * The plan must be the stored multi-day tour again ([reloadIfDayLost]): it belongs to a saved route with days
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
