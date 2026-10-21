package com.onpath.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onpath.app.cells.label
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Geo
import com.onpath.app.data.Prefs
import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.backup.Backups
import com.onpath.app.data.TourKind
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.LocationTracker
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.NavSessionStore
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingService
import com.onpath.app.routing.Activity
import com.onpath.app.routing.EngineProvider
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.WaypointSnap
import com.onpath.app.routing.RouteProgress
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.WaypointInsertion
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.onpath.app.routing.ArrivalGate
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** A track shown on the map (imported GPX, recorded or saved tour). */
data class ShownTrack(
    val tourId: Long?,
    val name: String,
    val segments: List<List<GeoPoint>>,
    val waypoints: List<GpxWaypoint>,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
) {
    val points: List<GeoPoint> get() = segments.flatten()
}

/** One-off things the UI must do with an Activity context. */
sealed interface UiEvent {
    data class Share(val intent: Intent) : UiEvent
    data class Fit(val points: List<GeoPoint>) : UiEvent
    /** Fly to one place at [zoom] (search results). */
    data class CenterOn(val point: GeoPoint, val zoom: Double) : UiEvent
    /** Move the map to [point] at once, same zoom (profile scrubbing in Navigate). */
    data class PanTo(val point: GeoPoint) : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val dataFiles = DataFiles(app)
    private val prefs = Prefs(app)
    private val db = TourDb.get(app)
    // time estimate state, declared before init {} uses it (recalc / refreshTours)
    /** Pace learned from earlier tours (Prefs JSON, written by the recording service when a tour ends). */
    private var paceStore = com.onpath.app.time.PaceStore.fromJson(prefs.paceStore)

    /** Model times of saved planned routes, by tour id: (factors, stored distance) they were computed for → seconds. */
    private val plannedTimes = java.util.concurrent.ConcurrentHashMap<Long, Pair<Pair<com.onpath.app.time.Factors, Double>, Double>>()
    val tracker = LocationTracker(app)

    // --- data check ---
    var missing by mutableStateOf(dataFiles.check().filter { !it.present })
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val note = packageNote()
            val p = dataFiles.packageCheck()
            withContext(Dispatchers.Main) { packageCheck = p; if (note.isNotEmpty() && message == null) message = note.trim() }
        }
    }
    var dataScreenDismissed by mutableStateOf(false)

    /** The APK ships the overview map's base map (map/Overview.kt) – a fresh install can open on the map. */
    private val overviewBundled: Boolean by lazy { "base.pmtiles" in com.onpath.app.map.Overview.bundled(getApplication()) }

    /**
     * Missing files that keep the app on the missing-data screen: none when the overview is in the APK and map cells can
     * be downloaded (the map comes where the user is or plans); otherwise [missing], as before cells (DataFiles.blocking).
     */
    val blockingMissing: List<DataFiles.Check> get() = DataFiles.blocking(missing, overviewBundled, cells.downloadsAvailable)

    // --- screens and mode ---
    /** My routes list (☰ → My routes) is open. */
    var routesOpen by mutableStateOf(false)
    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes.md. */
    var mode by mutableStateOf(AppMode.PLAN)
        private set
    /** Map taps may add, move or insert points (Plan with no track shown). */
    val canEditMap: Boolean get() = mode == AppMode.PLAN && shown == null
    /** "You have arrived – end tour?" is open. */
    var arrivalAsk by mutableStateOf(false)
    /** Asked once per followed line; reset when the line or the tour changes. */
    /** When to ask "You have arrived" (round trips, poor fixes, ask again after leaving); see [ArrivalGate]. */
    private val arrivalGate = ArrivalGate()
    /** POI whose details sheet is open (F-MAP-09). */
    var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    // --- activity ---
    // Every app start begins with the default activity from Settings (owner: no need to pick it each time).
    var activity by mutableStateOf(prefs.settings.defaultActivity)
        private set
    var sac by mutableStateOf(prefs.settings.defaultSac)
        private set
    // --- settings (Settings page) ---
    var settings by mutableStateOf(prefs.settings)
        private set
    var showSettings by mutableStateOf(false)

    // --- Navigate orientation + next turn (navigation agent; see NavigateScreen, MapController.setCamera) ---
    /** Navigate: heading-up (walking direction up) or north-up; persisted in settings. */
    val navHeadingUp: Boolean get() = settings.navHeadingUp

    /**
     * Walking direction up – one setting for Plan and Navigate (owner 2026-10-20: switching modes must keep the compass
     * choice, anything else is confusing). Persisted.
     */
    val headingUp: Boolean get() = navHeadingUp
    /** Bumped to turn the map north (MapScreen → MapController.northUp). */
    var northTick by mutableIntStateOf(0)
    /** Bumped when the camera must follow the position again although [followMe] did not change. */
    var recentreTick by mutableIntStateOf(0)

    private fun setHeadingUp(on: Boolean) {
        if (settings.navHeadingUp != on) { settings = settings.copy(navHeadingUp = on); prefs.settings = settings }
    }

    /**
     * Compass button, same in both modes (owner): map turned by hand → north up; north up → walking direction up
     * (follows the position); walking direction up → north up. Without a position fix the third state is skipped.
     */
    fun onCompass(mapTurned: Boolean) {
        val turningWithMe = headingUp && followMe
        val hasFix = tracker.location.value != null
        when {
            // Navigate, looked around by hand (free look) with walking direction chosen: back to it, not to north
            // (requirements review 2026-10-20)
            mode == AppMode.NAVIGATE && headingUp && !followMe && hasFix -> { followMe = true; recentreTick++ }
            turningWithMe || mapTurned -> {
                setHeadingUp(false)
                // Following north up turns the map to north itself; an extra north-up camera move from the app would
                // make MapLibre drop the following again (it ends tracking on every app camera move).
                if (mode == AppMode.NAVIGATE && hasFix) followMe = true else northTick++
            }
            !hasFix -> message = "No position yet – the map can turn with your walking direction once GPS has a fix."
            else -> { setHeadingUp(true); followMe = true; recentreTick++ }
        }
    }

    private var scrubWasFollowing = false
    private var scrubEndJob: kotlinx.coroutines.Job? = null

    /**
     * Navigate: a finger on the elevation profile (owner): a dot marks that spot on the route and the map shows it
     * (following pauses). After the finger leaves, the dot stays [SCRUB_HOLD_MS], then the map returns to the position.
     */
    fun navScrub(p: GeoPoint) {
        scrubEndJob?.cancel(); scrubEndJob = null
        if (followMe) { scrubWasFollowing = true; followMe = false; Problems.info("map", "Following paused: finger on the elevation profile") }
        highlight = p
        events += UiEvent.PanTo(p)
    }

    fun navScrubEnd() {
        scrubEndJob?.cancel()
        scrubEndJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SCRUB_HOLD_MS)
            highlight = null
            if (scrubWasFollowing && mode == AppMode.NAVIGATE) { followMe = true; recentreTick++ }
            scrubWasFollowing = false
        }
    }

    /** The app is on screen again (e.g. taken out of the pocket): in Navigate jump back to the own position (owner). */
    fun onReturnToApp() {
        if (mode == AppMode.NAVIGATE && tracker.location.value != null) { followMe = true; recentreTick++ }
        cells.setForeground(true)
        if (mode == AppMode.PLAN) t3Pending = true // T3: the next usable fix checks the own area
    }

    /** The app left the screen: map cell status is not polled in the background (map-cells.md §8). */
    fun onLeaveApp() = cells.setForeground(false)

    // ---------------- map cells (docs/design/map-cells.md §9, §19) ----------------

    /** Downloaded map cells: requests, states, installed list (UI: card, Settings list – see map-cells.md §19). */
    val cells: com.onpath.app.cells.MapCells by lazy { com.onpath.app.cells.AppCells.get(getApplication()) }

    /** The map style loaded from [dir]: when it is a cell store generation, older generations can go (R-16). */
    fun onMapStyleLoaded(dir: java.io.File?) {
        val gen = com.onpath.app.cells.CellStore.generationOf(dir) ?: return
        viewModelScope.launch(Dispatchers.IO) { cells.confirmLoaded(gen) }
    }

    /** The map could not load from [dir]: if that is a cell store generation, go back to the previous one. */
    fun rollbackMapCells(dir: java.io.File?): Boolean {
        val gen = com.onpath.app.cells.CellStore.generationOf(dir) ?: return false
        return cells.rollback(gen)
    }

    /** Settings → Diagnostics → "Map cells (debug)": request the cell under the map centre (own-point rule). */
    fun requestCellAtMapCentre() {
        val (lat, lon, _) = initialCamera()
        val c = com.onpath.app.cells.CellId.at(lat, lon) ?: return
        val added = cells.request(listOf(c), com.onpath.app.cells.CellReason.POINT)
        message = if (added.isEmpty()) "Cell ${c.id} is already on the phone or requested." else "Cell ${c.id} requested – see Settings → Diagnostics."
    }

    // ---------------- map cell triggers + card (map-cells.md §9: the map comes where you plan or where you are) ----

    private val cellTriggers by lazy { com.onpath.app.cells.CellTriggers(cells) }
    /** Cells the open map card is about (T1/T3/T4); null = no card. Closed only by the user (OK / Not now / ×). */
    var cellJob by mutableStateOf<com.onpath.app.cells.CellJob?>(null)
        private set
    /** The card's mobile-data question was answered "Only on Wi-Fi" (for this card). */
    private var mobileDeclined by mutableStateOf(false)
    /** T3 waits for a usable fix after app start / return in Plan. */
    private var t3Pending by mutableStateOf(true)
    /** The first-start card ("Allow location to get the detailed map …") was hidden with × (this app run). */
    private var firstStartHidden by mutableStateOf(false)
    /** "Map downloads not available in this build" was shown in this app run (once is enough). */
    private var notAvailableShown = false
    /** T5: position of the last check, so the check runs every [T5_STEP_M], not on every fix. */
    private var t5LastAt: GeoPoint? = null

    /** The card for the current cell states (MapScreen collects [com.onpath.app.cells.MapCells.states] / installed). */
    fun cellCardUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                   installed: List<com.onpath.app.cells.CellStore.InstalledCell>): CellCardUi? =
        builtCard(states, installed)
            // the user waited in the modal for their area: when all is there it simply goes – no "done" message
            ?.takeIf { !(it is CellCardUi.Done && jobWaited) }
            ?: CellCards.firstStart(cellJob, cells.downloadsAvailable,
                noDetailedMap = installed.isEmpty() && missing.any { !it.name.startsWith("segments") },
                waitingForPosition = t3Pending && mode == AppMode.PLAN, hidden = firstStartHidden,
                permission = tracker.hasPermission())

    private fun builtCard(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                          installed: List<com.onpath.app.cells.CellStore.InstalledCell>): CellCardUi? =
        CellCards.build(cellJob, states, installed.associate { it.id to it.totalBytes }, cells.downloadsAvailable,
            settings.mapsWifiOnly, mobileDeclined)

    private fun showCellJob(j: com.onpath.app.cells.CellJob?) {
        j ?: return
        if (!cells.downloadsAvailable) {
            if (notAvailableShown) return
            notAvailableShown = true; cellJob = j; return
        }
        val done = builtCard(cells.states.value, cells.installed.value) is CellCardUi.Done
        val open = cellJob
        cellJob = com.onpath.app.cells.CellTriggers.merge(open, j, done)
        if (open == null || done) { mobileDeclined = false; jobWaited = false }
        // the user waits for this map (a point / position / search / tap without detailed map, or a route while the
        // visible map has none): the modal shows (again), over all regions asked for so far
        if (j.own != null || viewWithoutDetail) {
            jobWaited = true
            if (loadModalFor.isEmpty()) { loadProgress.clear(); loadHeld = 0 }
            loadModalFor = loadModalFor + j.cells
        }
    }

    // ---------------- the modal while the user waits for the map of their area (owner 2026-10-21) ----------------

    /** Regions the modal waits for; empty = no modal (closed with Cancel / Close, or all there). */
    private var loadModalFor by mutableStateOf<Set<com.onpath.app.cells.CellId>>(emptySet())
    /** The open card's job was waited for in the modal: when it is all there it simply goes – no "Map ready" card. */
    private var jobWaited by mutableStateOf(false)
    private val loadProgress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
    /** Last % shown (never backwards). */
    private var loadHeld = 0
    /** A large part of the visible map has no detailed map (MapScreen, any zoom) – a route computed now is waited for. */
    var viewWithoutDetail = false

    /**
     * What the modal shows now ([now]: uptime ms; called about every second): in Plan, while some of the regions the user
     * waits for are not there yet. Null = no modal (and it is over when everything is there).
     */
    fun mapLoadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, now: Long): MapLoadUi? {
        val focus = loadModalFor
        if (focus.isEmpty() || !cells.downloadsAvailable || mode != AppMode.PLAN) return null
        val ui = MapLoads.aggregate(focus, states, cells.installed.value.associate { it.id to it.totalBytes }, loadProgress, loadHeld, now)
        if (ui == null) { loadModalFor = emptySet(); return null } // the map is there: the situation is over
        loadHeld = ui.percent
        return ui
    }

    /** Modal → Cancel / Close: the modal goes, the map keeps loading in the background (the plan sheet's card shows it). */
    fun hideLoadModal() { loadModalFor = emptySet() }

    /** A tap on the world map where there is no detailed map: "Load the map for this area? N regions · ≈ X MB". */
    data class LoadAsk(val at: GeoPoint, val regions: Int, val bytes: Long)
    var loadAsk by mutableStateOf<LoadAsk?>(null)
        private set

    /** Short tap on the map (nothing else under the finger): asks before loading – never a silent download from a tap. */
    fun mapTapped(p: GeoPoint) {
        if (mode != AppMode.PLAN || !cells.downloadsAvailable) return
        safeCells("tap") {
            val want = cellTriggers.preview(p)
            if (want.isNotEmpty()) loadAsk = LoadAsk(p, want.size,
                MapLoads.estimate(want, cells.states.value, cells.installed.value.associate { it.id to it.totalBytes }))
        }
    }

    /** The tap question answered: Load → the regions load (modal), Not now → nothing. */
    fun answerLoadAsk(load: Boolean) {
        val a = loadAsk ?: return
        loadAsk = null
        if (load) safeCells("tap") { showCellJob(cellTriggers.pointSet(a.at, com.onpath.app.cells.CellWhy.TAPPED)) }
    }

    /** T1: the user set these points (hold → Start here / Go here, a stop, a moved point). */
    private fun cellsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) {
        val fresh = com.onpath.app.cells.CellTriggers.newPoints(before, after)
        if (fresh.size > 3) return // a whole route loaded / restored: T4 covers its corridor
        for (p in fresh) safeCells("point") { showCellJob(cellTriggers.pointSet(p)) }
    }

    /** Card → "Yes" to mobile data: the waiting cells of this card load now. */
    fun cellCardMobileYes() {
        val job = cellJob ?: return
        val waiting = cells.waitingForMobileApproval().map { it.first }.filter { it in job.cells }
        cells.approveMobile(waiting)
    }

    /** Card → "Only on Wi-Fi": the question goes, the card says the areas wait for Wi-Fi. */
    fun cellCardWifiOnly() { mobileDeclined = true }

    /** Card → "Retry": ask again for the cells that failed. */
    fun cellCardRetry() {
        val job = cellJob ?: return
        val failed = job.cells.filter { cells.state(it) is com.onpath.app.cells.CellState.Failed }
        val own = failed.filter { it == job.own }
        failed.forEach { loadProgress.remove(it) } // the modal's bar for them starts again
        if (jobWaited) loadModalFor = loadModalFor + failed
        safeCells("retry") {
            if (own.isNotEmpty()) cells.request(own, com.onpath.app.cells.CellReason.POINT)
            val rest = failed - own.toSet()
            if (rest.isNotEmpty()) cells.request(rest, com.onpath.app.cells.CellReason.NEAR, ownPoint = false)
        }
    }

    /** Card → OK / Not now / ×: the card goes; cells still loading go on (Settings → Offline maps shows them). */
    fun closeCellCard() { if (cellJob == null) firstStartHidden = true; cellJob = null; mobileDeclined = false }

    /** Overlay → Cancel: stop loading the areas of this job (installed ones stay; setting a point there asks again). */
    fun cancelCellLoading() {
        val job = cellJob ?: return
        val inst = cells.installed.value.map { it.id }.toSet()
        safeCells("cancel") { cells.cancel(job.cells.filter { it !in inst || job.why == com.onpath.app.cells.CellWhy.UPDATE }) }
        closeCellCard()
    }

    /**
     * Settings → Diagnostics → "Reset maps": deletes every downloaded map area and the queue – the app is like freshly
     * installed for maps; the own position (T3) and the next point set fetch them again (owner: test a fresh install).
     */
    fun resetMapsLikeFreshInstall() = safeCells("reset") {
        val all = cells.installed.value.map { it.id }
        cells.cancel(cells.states.value.keys.toList())
        if (all.isNotEmpty()) cells.delete(all)
        closeCellCard(); t3Pending = true; t5LastAt = null
        message = "Map areas deleted (${all.size}). The map for your position loads again as on a new install."
        Problems.info("cells", "Maps reset like a fresh install (${all.size} area(s) deleted)")
    }

    /** "Overview only" hint: Plan, zoom ≥ 10 and no detailed map at the map centre. Reads a file header – call off main. */
    /** Areas with the detailed map (installed cells, region package) for the style's overview cover. */
    fun detailedAreas(): List<DoubleArray> = cells.detailedAreas()

    /** True when a large part of the visible box has no detailed map ([OverviewHintRule], any zoom). */
    fun overviewOnly(south: Double, west: Double, north: Double, east: Double): Boolean = try {
        OverviewHintRule.show(OverviewHintRule.MIN_ZOOM,
            OverviewHintRule.samples(south, west, north, east).map { (lat, lon) -> cells.hasDetailedMap(GeoPoint(lat, lon)) })
    } catch (e: Exception) {
        Problems.warn("cells", "Detailed-map check failed; overview hint hidden", e); false
    }

    /** Every fix: T3 (first usable fix after start / return in Plan) and T5 (on tour, near the edge of the map). */
    private fun cellsForFix(loc: android.location.Location) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val onTour = recState.value != null || mode == AppMode.NAVIGATE
        if (t3Pending && startModeKnown && !onTour && (acc == null || acc <= com.onpath.app.cells.CellTriggers.T3_MAX_ACCURACY_M)) {
            t3Pending = false
            safeCells("position") { showCellJob(cellTriggers.appOpened(p, acc)) }
        }
        if (onTour) {
            val last = t5LastAt
            if (last == null || Geo.distance(last, p) >= T5_STEP_M) {
                t5LastAt = p
                safeCells("tour") {
                    val asked = cellTriggers.onTour(p)
                    if (asked.isNotEmpty()) Problems.info("cells", "On tour near the edge of the map: asked for ${asked.joinToString()}")
                }
            }
        }
    }

    /** T6: once a day, newer versions of used cells (quiet, Wi-Fi only). */
    /**
     * After a route was computed in Plan (owner 2026-10-21: "at the moment I plan, I want a recent map"; nothing is
     * updated while the app is idle): newer versions of the installed map areas along it (quiet; the map is veiled for
     * the seconds a ready one loads) and newer routing data (then the plan is computed again).
     */
    private fun freshenAfterRoute(line: List<GeoPoint>) {
        safeCells("fresh") {
            val asked = cellTriggers.freshenAlong(line)
            if (asked.isNotEmpty()) Problems.info("cells", "Checking ${asked.size} map area(s) along the route for a newer version")
        }
        checkRoutingData(line)
    }

    /** Map cell triggers never break planning: a failure is logged, planning goes on. */
    private inline fun safeCells(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.error("cells", "Map download trigger ($what) failed; planning goes on", e) }
    }

    // ---------------- Settings → Offline maps ----------------

    /** Place names of installed cells ("Meran", "near Lana") from the offline index; filled by [loadAreaNames]. */
    var areaNames by mutableStateOf<Map<com.onpath.app.cells.CellId, String>>(emptyMap())
        private set

    /** Settings opened: name the installed areas (offline index, built once; coordinates until then / without). */
    fun loadAreaNames() {
        val todo = cells.installed.value.map { it.id }.filter { it !in areaNames }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            val places = try { search.offlineIndex().entries.map { it.place } } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { Problems.info("cells", "No place index for map area names; coordinates shown", e); return@launch }
            val names = withContext(Dispatchers.Default) {
                todo.associateWith { c -> com.onpath.app.search.RouteNamer.label(c.center, places) }.filterValues { it != null }
                    .mapValues { it.value!! }
            }
            areaNames = areaNames + names
        }
    }

    fun offlineMapsUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                      installed: List<com.onpath.app.cells.CellStore.InstalledCell>): OfflineMapsUi {
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val areas = installed.sortedBy { areaNames[it.id] ?: it.id.id }.map { c ->
            val built = c.built?.take(10)?.let { b -> runCatching { iso.parse(b) }.getOrNull() }
            val st = states[c.id]
            OfflineArea(c.id.id, areaNames[c.id] ?: coordName(c.id), c.totalBytes,
                fmt.format(built ?: Date(c.installedAt)),
                st?.takeIf { it !is com.onpath.app.cells.CellState.Ready && it != com.onpath.app.cells.CellState.None }?.label())
        }
        val ids = installed.map { it.id }.toSet()
        val loading = states.count { (c, s) -> c !in ids && s !is com.onpath.app.cells.CellState.Failed && s != com.onpath.app.cells.CellState.None }
        return OfflineMapsUi(cells.downloadsAvailable, areas, settings.mapsWifiOnly, loading)
    }

    private fun coordName(c: com.onpath.app.cells.CellId): String = c.center.let {
        "%.2f° %s, %.2f° %s".format(Locale.ROOT, kotlin.math.abs(it.lat), if (it.lat >= 0) "N" else "S",
            kotlin.math.abs(it.lon), if (it.lon >= 0) "E" else "W")
    }

    /** Settings → "Update maps now": ask for newer versions of every installed area. */
    fun updateMapsNow() = safeCells("update") {
        val all = cells.installed.value.map { it.id }
        val asked = cells.request(all, com.onpath.app.cells.CellReason.REFRESH, ownPoint = false)
        message = if (asked.isEmpty()) "The map areas are already being checked."
            else "Checking ${asked.size} map ${if (asked.size == 1) "area" else "areas"} for a newer version." +
                if (settings.mapsWifiOnly) " Settings: maps only on Wi-Fi." else ""
    }

    fun deleteMapArea(id: String) {
        val c = com.onpath.app.cells.CellId.parse(id) ?: return
        safeCells("delete") { cells.delete(listOf(c)) }
    }
    /** Next turn ahead in Navigate (arrow on the map); null = none / off route / Plan mode. */
    var navTurn by mutableStateOf<com.onpath.app.routing.Turn?>(null)

    // --- planning ---
    val waypoints = mutableStateListOf<GeoPoint>().apply { addAll(prefs.planWaypoints) }
    /**
     * Points added by tapping a POI (hut, viewpoint …), kept by value so reorder/reverse/undo keep
     * them; a dragged point is a new value and so no longer pinned. Routed exactly (Planner).
     */
    private val pinned = prefs.planPinned.toMutableSet()
    fun pinnedIndices(): Set<Int> = waypoints.indices.filter { waypoints[it] in pinned }.toSet()
    private val history = PlanHistory()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** Plan panel size (full / compact / mini), kept while the app runs. */
    var planSize by mutableStateOf(PlanSize.COMPACT)
    /** The route point whose point menu is open (hold on its marker, MapGestures): index when opened + position. */
    var pointMenuTarget by mutableStateOf<PointMenuTarget?>(null)
        private set
    /** Index of that point in the plan now, or null (no menu, or its point is gone). */
    val pointMenu: Int? get() = pointMenuTarget?.indexIn(waypoints)
    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    var routing by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    var highlight by mutableStateOf<GeoPoint?>(null)

    // --- saved state of the plan (menus / Manage routes) ---
    /** Name, tour id and points the plan was last saved or loaded as; the plan is unsaved when its points differ. */
    var savedName by mutableStateOf(prefs.planSavedName)
        private set
    private var savedId = prefs.planSavedId
    private var savedPoints by mutableStateOf(prefs.planSavedPoints)
    /** The plan has a route worth keeping that differs from the last save / load (RouteRules.isUnsaved). */
    val planUnsaved: Boolean get() = RouteRules.isUnsaved(waypoints, savedPoints)
    /** "Save route" name dialog is open (always a new entry). */
    var saveDialog by mutableStateOf(false)
    /**
     * "Save route" choice is open: Save (overwrite the loaded / last saved route) or Save as new…; [SaveChoice.thenOpen]
     * is the route to load afterwards ("Save first" before loading), else null.
     */
    var saveChoice by mutableStateOf<SaveChoice?>(null)
    class SaveChoice(val thenOpen: Tour?)
    /** "Replace current plan?" is open for this route. */
    var replaceAsk by mutableStateOf<Tour?>(null)
    /** "Save first" was chosen: the name dialog saves the plan, then loads this route. */
    var saveFirst by mutableStateOf<Tour?>(null)
    /** A save failed: shown in a dialog until OK (no message that disappears by itself). */
    var routesProblem by mutableStateOf<String?>(null)

    // --- shown track / follow ---
    var shown by mutableStateOf<ShownTrack?>(null)
        private set
    var followName by mutableStateOf(prefs.loadFollow()?.first)
        private set
    /** Line currently followed (drawn on the map while browsing); after a re-route the new line (from the service). */
    var followLine by mutableStateOf(prefs.loadFollow()?.second)
        private set
    /** Navigation session file shared with the service (re-routing, docs/plan-auto-reroute.md). */
    private val navStore = NavSessionStore(java.io.File(app.filesDir, NavSession.FILE))
    /** The followed line as it was started (plan/track) – re-routing never changes it. */
    private var followOriginal: List<GeoPoint>? = navStore.load()?.original ?: followLine
    /** Last navigation line version seen from the service. */
    private var navVersion = -1

    // --- position / camera ---
    var followMe by mutableStateOf(false)

    /** Navigate: where the user turned / moved the map by hand (free look); null = not looking around. */
    private var freeLookAt: GeoPoint? = null

    /**
     * The map stopped following because of a finger (turn, move). Navigate (owner 2026-10-20): look around freely –
     * e.g. turn the map to match a mountain range – and once you walk on ([FREE_LOOK_BACK_M]) the map comes back to
     * your position and the chosen orientation by itself. Compass / locate bring it back at once, as before.
     */
    /** Last finger-down on the map (MapHost); a "gesture" without a recent touch is not one. */
    var lastMapTouchMs = 0L

    /**
     * Finger down on the map. Plan with walking direction up: stop turning / following at once, so a route point stays
     * exactly under the finger (field test 2026-10-20: points could no longer be held to delete them while the map
     * kept turning).
     */
    fun onMapTouch() {
        lastMapTouchMs = android.os.SystemClock.uptimeMillis()
        if (mode == AppMode.PLAN && followMe && headingUp) followMe = false
    }

    /**
     * MapLibre stopped following. It does that for finger gestures and also for every camera move of the app itself
     * (fly to a search result, show a route, profile scrub) – both mean "not following any more". Only a stop right
     * after a real touch starts free look (back to following after walking on); the 0.7.20 "ignore without touch"
     * guard is gone – it undid the app's own camera moves (requirements review 2026-10-20).
     */
    fun onMapGesture() {
        followMe = false
        val touched = android.os.SystemClock.uptimeMillis() - lastMapTouchMs <= 1_500
        val loc = tracker.location.value
        freeLookAt = if (touched && mode == AppMode.NAVIGATE && loc != null) GeoPoint(loc.latitude, loc.longitude) else null
    }

    /** Every fix: back to following after free look once the walker moved on. */
    fun checkFreeLook(lat: Double, lon: Double) {
        val at = freeLookAt ?: return
        if (followMe || mode != AppMode.NAVIGATE) { freeLookAt = null; return }
        if (Geo.distance(at.lat, at.lon, lat, lon) < FREE_LOOK_BACK_M) return
        freeLookAt = null
        followMe = true
        recentreTick++
    }
    /** The start mode (Plan / Navigate) is decided (database checked for an unfinished tour). */
    var startModeKnown by mutableStateOf(false)
        private set
    /** The user moved or held the map since the app started (StartCamera never jumps after that). */
    var userMovedMap = false
    /** StartCamera ran (centred or gave up) – reset each time the app comes to the front ([onAppStart]). */
    var startCameraDone = false
    /** Bumped by [onAppStart]: restarts the start-camera check in MapScreen. */
    var startCameraGen by mutableStateOf(0)

    private var backgroundSince = 0L

    /** App goes to the background (Activity.onStop). */
    fun onAppStop() { backgroundSince = android.os.SystemClock.elapsedRealtime() }

    /**
     * App comes to the front (Activity.onStart): centre on the position again unless something is running – on a
     * real start, or after more than 10 min away (a quick look at Google Maps must not move the map).
     */
    fun onAppStart() {
        val away = if (backgroundSince == 0L) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() - backgroundSince
        if (away < 10 * 60_000L || recState.value != null || mode != AppMode.PLAN) return
        // A place was just shared into the app: the map shows it, not the position.
        if (android.os.SystemClock.elapsedRealtime() < keepCameraUntil) return
        startCameraDone = false; userMovedMap = false; followMe = false; startCameraGen++
    }

    // --- dialogs / messages ---
    var message by mutableStateOf<String?>(null)
    var unfinished by mutableStateOf<Tour?>(null)
    var finishedTour by mutableStateOf<Tour?>(null)
    /** Moving time / breaks / planned vs. needed of [finishedTour] (null = not known). */
    var finishedTimes by mutableStateOf<com.onpath.app.time.TourTimes?>(null)
        private set
    var tours by mutableStateOf<List<Tour>>(emptyList())
        private set
    var events = mutableStateListOf<UiEvent>()
        private set

    val recState: StateFlow<RecState?> = RecordingService.state

    private val routingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: RoutingEngine? = null
    private var routeJob: Job? = null
    private val requests = RouteRequests()
    /** The followed line was taken from this plan ("Start navigation"), so Clear removes it too. */
    private var followFromPlan = false
    /** The followed line is a shown track: "Continue navigation" keeps it instead of re-planning. */
    private var followIsTrack = prefs.followIsTrack
    /** "Continue navigation" re-plans: follow the next route result. */
    private var followNextRoute = false
    /** The plan's points were edited (add, delete, round trip, reverse, undo …): a running tour follows the result. */
    private var planEditPending = false

    init {
        recalc()
        refreshTours() // "Load route" is enabled only when something is saved
        // Set directly (enter() touches properties declared below); the check after the DB read
        // calls enter() and so also tells a running service which mode we are in.
        if (RecordingService.state.value != null) mode = AppMode.NAVIGATE
        viewModelScope.launch(Dispatchers.IO) {
            val active = db.findActiveRecording()
            val start = AppMode.onStart(RecordingService.state.value != null, active != null)
            withContext(Dispatchers.Main) { enter(start); startModeKnown = true }
            if (active != null && RecordingService.state.value == null) {
                delay(1500) // the service may be restarting by itself (sticky)
                if (RecordingService.state.value == null) withContext(Dispatchers.Main) { unfinished = active }
            }
        }
        // The service stopped by itself (permission gone, foreground refused, database broken): say why, in the app too.
        viewModelScope.launch {
            RecordingService.startFailed.collect { why ->
                if (why != null) { RecordingService.startFailed.value = null; message = why; if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN) }
            }
        }
        // Re-routing (service): follow the line the service navigates now (new route taken / original again).
        viewModelScope.launch {
            var saveFailedShown = false
            RecordingService.state.collect { st ->
                if (st?.saveFailed == true && !saveFailedShown) {
                    saveFailedShown = true
                    message = "The track cannot be saved any more (storage full or database broken). Free up space – recording keeps trying."
                } else if (st?.saveFailed == false) saveFailedShown = false
                val n = st?.nav ?: run { navActivitySeen = null; return@collect }
                if (n.version != navVersion) { navVersion = n.version; if (followLine != n.line) followLine = n.line }
                syncNavActivity(n.session)
            }
        }
        viewModelScope.launch {
            RecordingService.finished.collect { id ->
                if (id != null) {
                    RecordingService.finished.value = null
                    finishedTour = withContext(Dispatchers.IO) { db.getTour(id) }
                    finishedTimes = withContext(Dispatchers.IO) { finishedTour?.let { tourTimes(it) } }
                    finishedTour?.let { nameRecordedTour(it) }
                    reloadPace()
                    refreshTours()
                    clearFollow()
                    arrivalAsk = false
                    // owner: a saved plan is safe in My routes – take it off the map after the tour (Undo brings it
                    // back); an unsaved plan stays so nothing is lost
                    if (waypoints.size >= 2 && !planUnsaved) clearPlan()
                    enter(AppMode.PLAN)
                }
            }
        }
    }

    fun initialCamera(): Triple<Double, Double, Double> = prefs.camera ?: Triple(46.67, 11.16, 12.0)
    fun saveCamera(lat: Double, lon: Double, zoom: Double) { prefs.camera = Triple(lat, lon, zoom) }

    var batteryHintShown: Boolean
        get() = getApplication<Application>().getSharedPreferences("onpath", 0).getBoolean("batteryHint", false)
        set(v) { getApplication<Application>().getSharedPreferences("onpath", 0).edit().putBoolean("batteryHint", v).apply() }

    /** App state for the diagnostics export (Settings → Diagnostics); never throws. */
    fun diagnosticsState(): org.json.JSONObject = try {
        val rec = recState.value
        val loc = tracker.location.value
        org.json.JSONObject()
            .put("mode", mode.name).put("activity", activity.name).put("sac", sac)
            .put("waypoints", waypoints.size).put("routeMeters", route?.distanceM)
            .put("routeLegsStraight", route?.legs?.count { it.straight })
            .put("routeError", routeError)
            .put("followName", followName).put("followPoints", followLine?.size)
            .put("recording", rec?.let { org.json.JSONObject().put("tourId", it.tourId).put("paused", it.paused)
                .put("distanceM", it.distanceM).put("elapsedS", it.elapsedS).put("gpsLost", it.gpsLost)
                .put("offRouteM", it.offRouteM).put("saveFailed", it.saveFailed)
                .put("toGoS", it.time?.toGoS).put("arrivalMs", it.time?.arrivalMs) } ?: org.json.JSONObject.NULL)
            .put("gps", tracker.status().name)
            .put("location", loc?.let { org.json.JSONObject().put("lat", "%.5f".format(java.util.Locale.ROOT, it.latitude))
                .put("lon", "%.5f".format(java.util.Locale.ROOT, it.longitude)).put("accuracy", it.accuracy)
                .put("provider", it.provider).put("ageS", com.onpath.app.recording.LocationTracker.ageSeconds(it)) } ?: org.json.JSONObject.NULL)
            .put("styleVersion", styleVersion).put("missingData", missing.map { it.name })
            .put("mapDir", dataFiles.mapDir().path)
            .put("mapCells", org.json.JSONObject().put("generation", cells.generation.value)
                .put("installed", org.json.JSONArray(cells.installed.value.map { "${it.id} ${it.version} ${it.totalBytes}" }))
                .put("states", org.json.JSONObject(cells.states.value.entries.associate { (c, s) -> c.id to s.toString() } as Map<*, *>)))
    } catch (e: Throwable) {
        Problems.warn("ui", "Diagnostics app state could not be collected", e)
        org.json.JSONObject().put("error", e.toString())
    }

    fun recheckData() {
        missing = dataFiles.check().filter { !it.present }
        packageCheck = dataFiles.packageCheck()
    }

    /** Installed map package and files that do not belong to it (Settings → About; message when mixed). */
    var packageCheck by mutableStateOf<com.onpath.app.data.DataFiles.PackageCheck?>(null)
        private set

    /** After importing new .pmtiles: MainActivity restarts the app so MapLibre opens the new files. */
    var restartForData by mutableStateOf(false)

    /** " Mixed map files: …" when files from different packages are on the phone, else "". */
    private fun packageNote(): String {
        val p = dataFiles.packageCheck() ?: return ""
        return if (p.ok) "" else " Map files from another package: ${p.mismatched.joinToString()} – the map has gaps there. " +
            "Import every part of the map package (for South Tyrol: both zips)."
    }

    // ---------------- map data on the phone (no adb) ----------------

    // ---------------- app updates from GitHub Releases (F-OFF-06) ----------------

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val release: com.onpath.app.update.AppUpdater.Release) : UpdateState
        data class Downloading(val release: com.onpath.app.update.AppUpdater.Release, val percent: Int) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    var update by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    /** Release the user dismissed on the home screen (no nagging; still visible in Settings). */
    var updateDismissed by mutableStateOf<String?>(null)

    /** Hidden when Google Play installed the app – Play handles updates then. */
    val updatesEnabled: Boolean by lazy { !com.onpath.app.update.AppUpdater.installedFromPlay(getApplication()) }

    fun checkForUpdates(manual: Boolean) {
        if (!updatesEnabled || update is UpdateState.Checking || update is UpdateState.Downloading) return
        val sp = getApplication<Application>().getSharedPreferences("onpath", 0)
        val now = System.currentTimeMillis()
        if (!manual && !com.onpath.app.update.AppUpdater.dueForAutoCheck(sp.getLong("updateCheck", 0), now)) return
        if (manual) update = UpdateState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val r = com.onpath.app.update.AppUpdater.fetchLatest(com.onpath.app.BuildConfig.UPDATE_REPO)
                sp.edit().putLong("updateCheck", now).apply()
                if (r != null && com.onpath.app.update.AppUpdater.isNewer(r.version, com.onpath.app.BuildConfig.VERSION_NAME))
                    UpdateState.Available(r) else UpdateState.UpToDate
            } catch (e: Exception) {
                // Offline or GitHub unreachable: no user message for the automatic check, only the problem log.
                Problems.info("update", "Update check failed (offline or GitHub unreachable)", e)
                if (manual) UpdateState.Failed("Could not check for updates (${e.message ?: "no connection"}).") else UpdateState.Idle
            }
            withContext(Dispatchers.Main) { update = result }
        }
    }

    /** A tour is recording and the user tapped Install: ask first (installing closes OnPath, recording stops). */
    var askUpdateDuringTour by mutableStateOf(false)

    /** Downloads the update and opens the Android installer (the user confirms there). */
    fun installUpdate(duringTourOk: Boolean = false) {
        if (!duringTourOk && recState.value != null) { askUpdateDuringTour = true; return }
        askUpdateDuringTour = false
        val app = getApplication<Application>()
        val rel = (update as? UpdateState.Available)?.release ?: return
        if (!com.onpath.app.update.AppUpdater.canInstall(app)) {
            message = "Allow OnPath to install updates, then tap Install again."
            try { app.startActivity(com.onpath.app.update.AppUpdater.permissionIntent(app)) } catch (e: Exception) {
                Problems.warn("update", "Install-permission settings screen could not be opened", e)
            }
            return
        }
        update = UpdateState.Downloading(rel, 0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = com.onpath.app.update.AppUpdater.download(app, rel) { p ->
                    viewModelScope.launch(Dispatchers.Main) { if (update is UpdateState.Downloading) update = UpdateState.Downloading(rel, p) }
                }
                withContext(Dispatchers.Main) {
                    update = UpdateState.Available(rel)
                    app.startActivity(com.onpath.app.update.AppUpdater.installIntent(app, apk))
                }
            } catch (e: Exception) {
                Problems.warn("update", "Update download or installer start failed; current version kept", e)
                withContext(Dispatchers.Main) {
                    update = UpdateState.Available(rel)
                    message = "Update download failed: ${e.message ?: "no connection"}. Your current version keeps working."
                }
            }
        }
    }

    /** Progress text while importing / downloading map data, null when idle. */
    var dataBusy by mutableStateOf<String?>(null)
        private set
    /** Bumped when map style files changed, so the map reloads its style. */
    var styleVersion by mutableStateOf(0)
        private set

    /** Reload the map style as after a map update (emulator test of the core features, CorePlanE2ETest). */
    @androidx.annotation.VisibleForTesting
    fun reloadMapStyleForTest() { styleVersion++ }

    /** Imports the onpath-data zip or a single data/style file picked by the user. */
    fun importData(uri: Uri) {
        if (dataBusy != null) return
        val app = getApplication<Application>()
        dataBusy = "Importing map data…"
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val name = displayName(uri) ?: "import.zip"
                val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open the file.")
                val r = com.onpath.app.data.DataImporter.import(input, name, dataFiles.dir) { f ->
                    dataBusy = "Importing $f…"
                }
                // MapLibre keeps an opened .pmtiles file's index in memory: a replaced file would be read with the
                // old index (gaps, wrong tiles) until the process restarts. So after new .pmtiles: restart the app
                // (not during a tour – then the user is asked to restart after it).
                val newTiles = r.imported.any { it.endsWith(".pmtiles") }
                // downloaded map cells are joined with the region package: join the new one
                if (newTiles || r.imported.contains("pois.geojson")) cells.regionChanged()
                withContext(Dispatchers.Main) {
                    recheckData()
                    if (newTiles && recState.value == null) restartForData = true else styleVersion++
                }
                r.summary + packageNote() +
                    if (newTiles && recState.value != null) " Restart OnPath after the tour to see the new map." else ""
            } catch (e: com.onpath.app.data.DataImporter.ImportException) {
                Problems.warn("data", "Map data import rejected", e)
                e.message ?: "Import failed."
            } catch (e: Exception) {
                Problems.error("data", "Map data import failed; existing data kept", e)
                "Import failed: ${e.message ?: e.javaClass.simpleName}. Your existing map data is unchanged."
            }
            withContext(Dispatchers.Main) { dataBusy = null; message = msg }
        }
    }

    /** Downloads the routing data tile for the map area from brouter.de (needs internet once). */
    fun downloadRoutingData() {
        if (dataBusy != null) return
        val (lat, lon, _) = initialCamera()
        val name = com.onpath.app.data.SegmentDownloader.tileName(lat, lon)
        dataBusy = "Downloading routing data $name…"
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                com.onpath.app.data.SegmentDownloader.download(name, dataFiles.segmentsDir) { done, total ->
                    dataBusy = if (total > 0) "Downloading routing data: ${done * 100 / total} % of ${total / 1_048_576} MB"
                    else "Downloading routing data: ${done / 1_048_576} MB"
                }
                withContext(Dispatchers.Main) { recheckData() }
                "Routing data $name ready."
            } catch (e: Exception) {
                Problems.warn("data", "Routing data download stopped", e)
                "Routing data download stopped: ${e.message ?: e.javaClass.simpleName} Tap again to resume."
            }
            withContext(Dispatchers.Main) { dataBusy = null; message = msg }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        Problems.warn("data", "File name of the picked file unreadable; using the URI name", e)
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---------------- activity ----------------

    fun selectActivity(a: Activity) {
        activity = a; prefs.activity = a; recalc()
    }

    fun selectSac(level: Int) {
        sac = level.coerceIn(1, 5); prefs.sac = sac; recalc()
    }

    // ---------------- planning ----------------

    fun updateSettings(s: AppSettings) {
        val sacChanged = s.defaultSac != sac
        val paceChanged = s.hikePace != settings.hikePace || s.bikePace != settings.bikePace
        settings = s; prefs.settings = s
        cells.wifiOnly = s.mapsWifiOnly
        if (paceChanged) retime()
        // The SAC level is set in Settings only (owner) – it applies right away to the current hiking route.
        if (sacChanged) { sac = s.defaultSac.coerceIn(1, 5); prefs.sac = sac; if (activity == Activity.HIKE) recalc() }
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_SETTINGS)
    }

    /**
     * One undoable change of the planned points; [edit] = what it was, for the vibration (PlanHaptics: 1× a point set,
     * 2× deleted, 1× reordered, nothing else) – played here so map, menus and the Points list all feel the same.
     */
    private fun change(edit: PlanEdit = PlanEdit.OTHER, block: MutableList<GeoPoint>.() -> Unit) {
        // editing on a map that keeps turning is confusing: stop following, the map stays at its angle (owner)
        if (mode == AppMode.PLAN && headingUp && followMe) followMe = false
        history.record(waypoints)
        val before = waypoints.toList()
        waypoints.block()
        cellsForNewPoints(before, waypoints)
        canUndo = history.canUndo; canRedo = history.canRedo
        prefs.planWaypoints = waypoints.toList()
        prunePinned()
        planEditPending = true
        Haptics.play(getApplication(), PlanHaptics.feel(edit))
        recalc()
    }

    /** Forget pinned points that are neither planned nor reachable by undo/redo. */
    private fun prunePinned() {
        if (pinned.isEmpty()) return
        val alive = HashSet(waypoints).apply { addAll(history.allPoints()) }
        if (pinned.retainAll(alive)) prefs.planPinned = pinned
    }

    private fun set(list: List<GeoPoint>, edit: PlanEdit = PlanEdit.OTHER) = change(edit) { clear(); addAll(list) }

    /**
     * Adds a held point where it most likely belongs (WaypointInsertion, F-PLAN-13), silently: the vibration and
     * the new line confirm it; a wrong guess is fixed in the point menu ("End route here") or with Undo.
     * @param lineSnapM "on the line" distance, ~24 dp in metres at the current zoom.
     */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) {
        if (onPoi) { pinned += p; prefs.planPinned = pinned }
        val before = waypoints.toList()
        // While a reroute runs, the shown line belongs to older waypoints: use best-fit only.
        val d = WaypointInsertion.decide(before, p, if (routing) null else route?.points, lineSnapM)
        change(PlanEdit.POINT_SET) { add(d.index.coerceIn(0, size), p) }
    }

    fun removePoint(index: Int) { if (index in waypoints.indices) change(PlanEdit.POINT_DELETED) { removeAt(index) } }

    /** Search result shown in the place card (search, ui/search); map-held POIs use [poiMenu]. */
    var planPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)
    /** Eye button held: route lines and markers are hidden (position and recorded track stay). */
    var routePeek by mutableStateOf(false)

    // ---------------- map hold menus (MapGestures, ui/MapOverlays) ----------------
    /** POI held on the map in Plan: its menu "Add to route · Open in Google Maps". */
    var poiMenu by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    /** First hold on an empty plan: the held spot, its menu "Start here · Go here (from my location)" is open. */
    var firstPointMenu by mutableStateOf<GeoPoint?>(null)

    /** Hold 48–80 dp from a route point: "Point N: options" · "New point here" is open. */
    var nearChoice by mutableStateOf<NearPointChoice?>(null)
        private set

    val mapOverlayOpen: Boolean get() =
        poiMenu != null || pointMenu != null || firstPointMenu != null || planPoi != null || nearChoice != null

    /** Closes every map menu; [why] goes into the gesture log (Diagnostics) when a point menu was open. */
    fun closeMapOverlays(why: String = "closed") {
        // The search mark lives only until its menu is done (owner): closing that menu – by an action or by
        // tapping elsewhere – removes the orange ring too.
        if (poiMenu != null) searchMark = null // the menu may belong to the place's own map icon a few metres away
        closePointMenu(why)
        if (nearChoice != null) { Problems.trace("plan", "Near-point menu closed: $why"); nearChoice = null }
        if (firstPointMenu != null) Problems.trace("plan", "First-point menu closed: $why")
        if (poiMenu != null) Problems.trace("plan", "POI menu closed: $why")
        poiMenu = null; firstPointMenu = null; if (planPoi != null) closePlaceCard()
    }

    /** Closes the point menu (if open) and logs by what (core feature C1 diagnostics). */
    fun closePointMenu(why: String) {
        val t = pointMenuTarget ?: return
        pointMenuTarget = null
        Problems.trace("plan", "Point menu closed (point ${t.index + 1}): $why")
    }

    /**
     * Hold 48–80 dp from route point [index] ([distDp] from the finger): ask "Point N: options" · "New point here".
     * [at] = the held spot, [lineSnapM] its "on the line" distance for a new point.
     */
    fun openNearChoice(index: Int, at: GeoPoint, lineSnapM: Double, distDp: Int?) {
        closeMapOverlays("hold near point ${index + 1}")
        val p = waypoints.getOrNull(index) ?: return
        nearChoice = NearPointChoice(PointMenuTarget(index, p), at, lineSnapM)
        Problems.trace("plan", "Near-point menu opened: point ${index + 1} of ${waypoints.size} at ${distDp ?: "?"} dp")
    }

    /** Near-point menu → "Point N: options": that point's menu. */
    fun nearChoiceOptions() {
        val c = nearChoice ?: return
        nearChoice = null
        val i = c.target.indexIn(waypoints)
        Problems.trace("plan", "Near-point menu: options of point ${c.target.index + 1} (now ${i?.plus(1)})")
        if (i != null) openPointMenu(i, via = "near-point menu")
    }

    /** Near-point menu → "New point here": the held spot is added like any hold on the map. */
    fun nearChoiceNewPoint() {
        val c = nearChoice ?: return
        nearChoice = null
        Problems.trace("plan", "Near-point menu: new point here (plan ${waypoints.size} → ${waypoints.size + 1})")
        addPoint(c.at, c.lineSnapM)
    }
    fun openFirstPointMenu(p: GeoPoint) {
        closeMapOverlays("first-point menu opened")
        if (waypoints.isEmpty()) { firstPointMenu = p; Problems.trace("plan", "First-point menu opened") }
    }

    /** First-point menu → "Start here": the held spot is the start; later holds add points as usual. */
    fun startHere(p: GeoPoint) { firstPointMenu = null; addPoint(p) }

    /** First-point menu → "Go here": start = current position, the held spot = destination. */
    fun routeFromMyLocation(p: GeoPoint) {
        firstPointMenu = null
        val loc = tracker.location.value ?: return // the menu only offers this with a position
        if (waypoints.isEmpty()) change(PlanEdit.POINT_SET) { add(GeoPoint(loc.latitude, loc.longitude)); add(p) } else addPoint(p)
    }
    fun openPoiMenu(poi: com.onpath.app.poi.Poi) { closeMapOverlays("POI menu opened"); poiMenu = poi }

    /** POI menu on an empty plan → "Go here": start = position, the POI (pinned, named) = destination. */
    fun poiFromMyLocation(poi: com.onpath.app.poi.Poi) {
        closeMapOverlays()
        val loc = tracker.location.value ?: return // the menu only offers this with a position
        if (waypoints.isNotEmpty()) { addPoiPoint(poi); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        clearSearchMarkAt(poi.lat, poi.lon)
        pinned += p; prefs.planPinned = pinned
        change(PlanEdit.POINT_SET) { add(GeoPoint(loc.latitude, loc.longitude)); add(p) }
    }
    /**
     * Opens the menu of route point [index]; [distDp] = finger distance from the marker, [via] = what opened it
     * (both only for the gesture log in Diagnostics).
     */
    fun openPointMenu(index: Int, distDp: Int? = null, via: String = "hold") {
        closeMapOverlays("another menu opened ($via)")
        val p = waypoints.getOrNull(index)
        if (p == null) { Problems.trace("plan", "Point menu NOT opened: point ${index + 1} not in the plan of ${waypoints.size}"); return }
        pointMenuTarget = PointMenuTarget(index, p)
        Problems.trace("plan", "Point menu opened ($via): point ${index + 1} of ${waypoints.size}" +
            (distDp?.let { ", $it dp from the finger" } ?: ""))
    }

    /** POI menu → "Add to route": a pinned, named route point; when it already is a stop, its point menu opens. */
    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        closeMapOverlays()
        val idx = waypoints.indexOfFirst { kotlin.math.abs(it.lat - poi.lat) < 1e-7 && kotlin.math.abs(it.lon - poi.lon) < 1e-7 }
        if (idx >= 0) { openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        clearSearchMarkAt(poi.lat, poi.lon)
        addPoint(p, lineSnapM, onPoi = true)
    }

    /** Point menu → "Make starting point" (not on the start). */
    /** Point menu → "Start here" (drops earlier points) / round trip "Start & finish here" (rotates the loop). */
    fun makeStartFromMenu() {
        val index = menuAction("Start here") ?: return
        set(if (isRoundTrip()) PlanEdits.rotateLoop(waypoints, index) else PlanEdits.startAt(waypoints, index))
    }

    fun isRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints)

    /** Point menu → "End route here" (middle points only): that point becomes the destination. */
    fun endRouteAt() { val index = menuAction("End here") ?: return; set(PlanEdits.endAt(waypoints, index)) }

    /**
     * A point menu action was tapped: closes the menu and returns the index of the point it was opened for in the
     * plan as it is NOW (the plan can change under an open menu – PointMenuTarget), or null when that point is gone.
     * Logged either way (field 2026-10-21: "Delete did nothing", no trace of why).
     */
    private fun menuAction(what: String): Int? {
        val t = pointMenuTarget
        val i = t?.indexIn(waypoints)
        pointMenuTarget = null
        Problems.trace("plan", "Point menu: $what tapped – menu of point ${t?.index?.plus(1)}, now point ${i?.plus(1)}, " +
            "plan ${waypoints.size} points, route ${if (routing) "calculating" else "ready"}")
        return i
    }

    // ---------------- Points section of the full plan sheet (PointsSection, routing/PlanPoints) ----------------

    /** Nearest place / hut / peak for route points without a name of their own (offline index, RouteNamer.label). */
    private var nearbyNames by mutableStateOf<Map<GeoPoint, String>>(emptyMap())
    private var namePlaces: List<com.onpath.app.search.Place>? = null
    private var namesJob: Job? = null

    /** Rows of the Points section: names (pinned POI / search first, then the nearest place), leg distances. */
    fun planPointRows(): List<com.onpath.app.routing.PlanPointRow> {
        val pts = waypoints.toList()
        val line = route?.takeIf { !routing }?.points
        return com.onpath.app.routing.PlanPoints.rows(pts, line) { i -> pts.getOrNull(i)?.let { stopName(it) ?: nearbyNames[it] } }
    }

    /** Names the route points from the offline place index (once per point, off the main thread). */
    fun refreshPointNames() {
        val todo = waypoints.toList().filter { it !in nearbyNames && stopName(it) == null }.distinct()
        if (todo.isEmpty() || namesJob?.isActive == true) return
        namesJob = viewModelScope.launch {
            val places = namePlaces ?: try { search.offlineIndex().entries.map { it.place }.also { namePlaces = it } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for point names; \"Point N\" shown", e); return@launch }
            val names = withContext(Dispatchers.Default) {
                todo.mapNotNull { p -> com.onpath.app.search.RouteNamer.label(p, places)?.let { p to it } }.toMap()
            }
            nearbyNames = nearbyNames + names
        }
    }

    /** Route details (way types, surfaces, difficulty, full profile) – opened from the sheet's profile or badge. */
    var routeDetailsOpen by mutableStateOf(false)

    /** Points list: row [from] dropped at row [to] (hold ≡ and drag); to the top = the new start. Undo reverts. */
    fun movePointInList(from: Int, to: Int) {
        val after = com.onpath.app.routing.PlanPoints.move(waypoints, from, to)
        if (after == waypoints.toList()) return
        Problems.trace("plan", "Points list: point ${from + 1} moved to ${to + 1} (plan ${waypoints.size} points)")
        set(after, PlanEdit.REORDERED)
    }

    /** Points list → ✕: deleted at once (Undo brings it back); a round trip stays closed at its start. */
    fun deletePointFromList(index: Int) {
        val after = com.onpath.app.routing.PlanPoints.delete(waypoints, index)
        if (after.size == waypoints.size) return
        Problems.trace("plan", "Points list: point ${index + 1} deleted (plan ${waypoints.size} → ${after.size})")
        set(after, PlanEdit.POINT_DELETED)
    }

    /**
     * Points list → row tapped: the map shows that point (following stopped first – CLAUDE.md map camera rule) with a
     * short highlight; the sheet goes to half size so list and map are both visible.
     */
    fun showPointFromList(index: Int) {
        val p = waypoints.getOrNull(index) ?: return
        followMe = false
        planSize = PlanSize.COMPACT
        highlight = p
        events += UiEvent.PanTo(p)
        viewModelScope.launch { delay(3_000); if (highlight == p) highlight = null }
    }

    /** Point menu → "Delete" (Undo brings it back). */
    fun deletePointFromMenu() {
        val i = menuAction("Delete") ?: return
        val before = waypoints.size
        val gone = waypoints[i]
        removePoint(i)
        // another marker on the same spot still looks like "the point stayed" (a point snapped next to it)
        val twin = waypoints.withIndex().minByOrNull { com.onpath.app.data.Geo.distance(it.value, gone) }
            ?.takeIf { com.onpath.app.data.Geo.distance(it.value, gone) < 100.0 }
        Problems.trace("plan", "Point ${i + 1} deleted: plan $before → ${waypoints.size}, route recalculation " +
            (if (routing) "started" else "not needed") +
            (twin?.let { ", point ${it.index + 1} is only ${com.onpath.app.data.Geo.distance(it.value, gone).toInt()} m away" } ?: ""))
    }

    /** Point menu → "Open in Google Maps" (closes the menu; the caller opens the link). */
    fun googleFromMenu(): GeoPoint? = menuAction("Open in Google Maps")?.let { waypoints[it] }
    // ---------------- end map hold menus ----------------

    fun isStop(poi: com.onpath.app.poi.Poi): Boolean =
        waypoints.any { kotlin.math.abs(it.lat - poi.lat) < 1e-7 && kotlin.math.abs(it.lon - poi.lon) < 1e-7 }

    // ---------------- search + place card (search agent; UI in ui/search, logic in search/) ----------------
    // Open/close the search view, the picked place, and "add as role" actions of the shared place card.
    // The card is keyed by planPoi (so MapHost's tap rules – a map tap closes it – apply unchanged).

    enum class PlaceRole { SMART, START, DESTINATION }

    private val searchStore by lazy { com.onpath.app.search.SearchStore(getApplication()) }
    val search by lazy {
        com.onpath.app.ui.search.SearchSession(viewModelScope, dataFiles.dir, getApplication<Application>().filesDir, searchStore,
            com.onpath.app.search.PhotonProvider(com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME)),
            mapDir = { dataFiles.mapDir() }) {
            val loc = tracker.location.value
            val (cLat, cLon, _) = initialCamera()
            // Position when known and near what the user looks at, else the map centre.
            val usePos = loc != null && Geo.distance(loc.latitude, loc.longitude, cLat, cLon) < 30_000
            com.onpath.app.ui.search.SearchContext(if (usePos) loc!!.latitude else cLat, if (usePos) loc!!.longitude else cLon,
                if (shown == null) route?.points else null)
        }
    }
    var searchOpen by mutableStateOf(false)
        private set
    /** The picked search result (address, kind) behind planPoi; null for a POI tapped on the map. */
    private var searchPlace by mutableStateOf<com.onpath.app.search.Place?>(null)
    /** Names of route points added from a place ("Leiter Alm"), by position – for stop lists. */
    private val placeNames = HashMap<GeoPoint, String>()
    private var namesLoaded = false

    fun openSearch() { closeMapOverlays("search opened"); searchOpen = true; search.onOpen() }
    fun closeSearch() { searchOpen = false }

    /**
     * Result tapped (owner): close the search, fly the map there and mark the place with a pin – no card. Holding the
     * pin opens the usual POI menu (Add to route / Start here … / Google Maps). The mark stays until the next search
     * pick or until the place is added to the route.
     */
    fun pickSearchResult(place: com.onpath.app.search.Place) {
        val p = search.resolve(place)
        search.remember(p)
        searchOpen = false
        closeMapOverlays()
        searchPlace = p
        searchMark = p
        // Zoom to fit what it is: a city needs the overview, a single restaurant the close-up.
        val zoom = when (p.sub) { "city" -> 12.0; "town" -> 13.0; "village", "suburb" -> 14.0; else -> 16.0 }
        events += UiEvent.CenterOn(GeoPoint(p.lat, p.lon), zoom)
        // no detailed map there: its regions load (owner 2026-10-21 – a search / shared place is a trigger, modal)
        if (mode == AppMode.PLAN) safeCells("search") { showCellJob(cellTriggers.pointSet(GeoPoint(p.lat, p.lon), com.onpath.app.cells.CellWhy.SEARCHED)) }
    }

    /** Search result marked on the map (pin); see [pickSearchResult]. */
    var searchMark by mutableStateOf<com.onpath.app.search.Place?>(null)

    private fun clearSearchMarkAt(lat: Double, lon: Double) {
        val m = searchMark ?: return
        if (kotlin.math.abs(m.lat - lat) < 1e-7 && kotlin.math.abs(m.lon - lon) < 1e-7) searchMark = null
    }

    /** Place shown in the card: the search result, or the tapped POI. */
    val cardPlace: com.onpath.app.search.Place?
        get() = planPoi?.let { poi -> searchPlace?.takeIf { it.id == poi.id } ?: com.onpath.app.search.Place.fromPoi(poi) }

    fun closePlaceCard() { planPoi = null; searchPlace = null }

    private fun routeIndexOf(place: com.onpath.app.search.Place) =
        waypoints.indexOfFirst { kotlin.math.abs(it.lat - place.lat) < 1e-7 && kotlin.math.abs(it.lon - place.lon) < 1e-7 }.takeIf { it >= 0 }

    /** Card content incl. what "Add to route" will do (same WaypointInsertion as a map hold). */
    fun placeCardUi(place: com.onpath.app.search.Place): com.onpath.app.ui.search.PlaceCardUi {
        val idx = routeIndexOf(place)
        val r = route
        val subtitle = if (idx != null) com.onpath.app.search.AddPreview.existingRole(idx, waypoints.size)
        else com.onpath.app.search.AddPreview.of(waypoints.toList(), GeoPoint(place.lat, place.lon), if (routing) null else r?.points,
            r?.takeIf { it.durationS > 0 }?.let { it.distanceM / it.durationS }).subtitle
        return com.onpath.app.ui.search.PlaceCardUi(place, idx, waypoints.size, subtitle)
    }

    /** Adds the place as a pinned stop (passed exactly), named after the place. Moves it when already planned. */
    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) {
        val p = GeoPoint(place.lat, place.lon)
        val name = place.title()
        val idx = routeIndexOf(place)
        closePlaceCard()
        rememberPlaceName(p, name)
        if (idx != null) {
            when (role) { PlaceRole.START -> makeStart(idx); PlaceRole.DESTINATION -> makeDestination(idx); PlaceRole.SMART -> Unit }
            return
        }
        pinned += p; prefs.planPinned = pinned
        when (role) {
            // No timed notices (owner rule): the named marker and the new line show what happened.
            PlaceRole.SMART -> addPoint(p, onPoi = true)
            PlaceRole.START -> change(PlanEdit.POINT_SET) { add(0, p) }
            PlaceRole.DESTINATION -> {
                val loc = tracker.location.value
                if (waypoints.isEmpty() && loc != null) change(PlanEdit.POINT_SET) { add(GeoPoint(loc.latitude, loc.longitude)); add(p) }
                else change(PlanEdit.POINT_SET) { add(p) }
            }
        }
    }

    fun removePlace(place: com.onpath.app.search.Place) { routeIndexOf(place)?.let { removePoint(it) }; closePlaceCard() }

    /** Name of a route point added from a place, or null (UI: "Leiter Alm" instead of "Point 3"). */
    fun stopName(p: GeoPoint): String? { loadNames(); return placeNames[p] }

    private fun loadNames() { if (!namesLoaded) { namesLoaded = true; placeNames.putAll(searchStore.names()) } }

    private fun rememberPlaceName(p: GeoPoint, name: String) {
        loadNames(); placeNames[p] = name
        val alive = HashSet(waypoints).apply { add(p); addAll(history.allPoints()) }
        placeNames.keys.retainAll(alive)
        searchStore.saveNames(placeNames)
    }
    // ---------------- end search ----------------

    // ---------------- shared places (another app → OnPath; parsing in search/SharedPlace) ----------------
    private var sharedJob: Job? = null
    /** A shared place is being looked up (short link, address search): the map shows "Finding the shared place…". */
    var sharedLookup by mutableStateOf(false)
    /** Until then (elapsedRealtime) the start camera must not move the map away from a shared place. */
    private var keepCameraUntil = 0L

    /**
     * A place shared from Google Maps, a `geo:` link … (MainActivity): back to the map in Plan (a running tour goes on
     * untouched), fly there and show the orange ring like a search result – nothing is added to the route. Without a
     * coordinate (short link offline, address only) the address is searched offline, then online, with a note.
     */
    fun receiveSharedPlace(shared: com.onpath.app.search.SharedPlace) {
        routesOpen = false; showSettings = false; timeDetails = null; routeDetailsOpen = false; searchOpen = false
        closeMapOverlays()
        // A shown track hides the plan sheet and blocks map holds – the ring's menu must work.
        if (shown != null) closeShown()
        holdCameraForSharedPlace()
        sharedJob?.cancel()
        sharedLookup = true
        sharedJob = viewModelScope.launch {
          try {
            // Cold start: the start mode (Plan / Navigate) is decided after a database check – switch after that.
            if (!startModeKnown) androidx.compose.runtime.snapshotFlow { startModeKnown }.first { it }
            if (mode != AppMode.PLAN) enter(AppMode.PLAN)
            var p = shared
            var fromPage = false
            if (!p.hasPoint && p.link != null) {
                val link = p.link!!
                val http = com.onpath.app.search.ShortLinkResolver.http(
                    com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME))
                // Faster (owner: 2–3 s before anything moved): when the redirect already names the address, search that
                // and skip the Google page; the page is only read when the address is not found.
                var r = withContext(Dispatchers.IO) { com.onpath.app.search.ShortLinkResolver(http, stopAtAddress = true).resolve(link) }
                val quick = r.place?.takeIf { !it.hasPoint && it.address != null }
                if (quick != null) {
                    val q = p.copy(name = p.name ?: quick.name, address = quick.address, query = p.query ?: quick.query)
                    val hit = try { search.findOne(q.searchTexts()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        Problems.info("search", "Address search for a shared place failed", e); null
                    }
                    if (hit != null) {
                        Problems.info("search", "Shared place found by its address (${hit.second.name.lowercase()} search)")
                        val shownName = q.name?.substringBefore(',')?.trim()?.ifEmpty { null } ?: hit.first.name
                        showSharedPlace(hit.first.copy(name = shownName))
                        return@launch
                    }
                    r = withContext(Dispatchers.IO) { com.onpath.app.search.ShortLinkResolver(http).resolve(link) }
                }
                r.place?.let { rp -> p = p.copy(lat = rp.lat, lon = rp.lon, name = p.name ?: rp.name, address = p.address ?: rp.address, query = p.query ?: rp.query) }
                fromPage = r.fromPage
                if (!p.hasPoint) Problems.info("search", "Shared map link gave no position (${r.reason}); searching the address instead")
            }
            var lat = p.lat; var lon = p.lon
            // A point read from the downloaded Google page (the link itself had none) is checked against the address
            // in the link: the page can carry other places' markers (field test 2026-10-20: Gelateria Costantin in
            // Meran landed far away). More than SHARED_PAGE_MAX_OFF_M apart → the address wins.
            if (fromPage && lat != null && lon != null && p.searchTexts().isNotEmpty()) {
                val byAddress = try { search.findOne(p.searchTexts()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    Problems.info("search", "Address check for a shared place failed", e); null
                }
                if (byAddress != null) {
                    val off = Geo.distance(lat, lon, byAddress.first.lat, byAddress.first.lon)
                    if (off > SHARED_PAGE_MAX_OFF_M) {
                        Problems.warn("search", "Shared place: the map page pointed ${(off / 1000).toInt()} km away from the " +
                            "address – using the address (${byAddress.second.name.lowercase()} search)")
                        lat = byAddress.first.lat; lon = byAddress.first.lon
                    }
                }
            }
            if (lat != null && lon != null) {
                Problems.info("search", "Shared place at %.5f, %.5f (%s)".format(java.util.Locale.ROOT, lat, lon,
                    if (fromPage) "from the map page" else if (shared.hasPoint) "in the shared text / link" else "from the resolved link"))
                // "Gelateria Costantin, Passeggiata …, Italien" (Google's place path) → the name only on the ring
                val shownName = p.name?.substringBefore(',')?.trim()?.ifEmpty { null } ?: p.name
                showSharedPlace(com.onpath.app.search.Place("shared:$lat,$lon", shownName, "other", lat, lon, address = p.address))
                return@launch
            }
            val found = search.findOne(p.searchTexts())
            // Stays until OK (owner rule: no message disappears by itself).
            if (found == null) { message = "Couldn't find this place – try again with internet"; return@launch }
            val (place, from) = found
            Problems.info("search", "Shared place positioned from its address (${from.name.lowercase()} search)")
            showSharedPlace(place.copy(name = p.name ?: place.name))
          } finally { sharedLookup = false }
        }
    }

    private fun showSharedPlace(p: com.onpath.app.search.Place) {
        if (mode != AppMode.PLAN) enter(AppMode.PLAN)
        closeMapOverlays()
        holdCameraForSharedPlace()
        searchPlace = p
        searchMark = p
        followMe = false
        // Outside the downloaded map the ring still shows (on an empty map) – the overview zoom makes that clear.
        val zoom = when (p.sub) { "city" -> 12.0; "town" -> 13.0; "village", "suburb" -> 14.0; else -> 16.0 }
        events += UiEvent.CenterOn(GeoPoint(p.lat, p.lon), zoom)
        // no detailed map there: its regions load (owner 2026-10-21 – a search / shared place is a trigger, modal)
        if (mode == AppMode.PLAN) safeCells("search") { showCellJob(cellTriggers.pointSet(GeoPoint(p.lat, p.lon), com.onpath.app.cells.CellWhy.SEARCHED)) }
    }

    private fun holdCameraForSharedPlace() {
        startCameraDone = true; userMovedMap = true
        keepCameraUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
    }
    // ---------------- end shared places ----------------

    fun makeStart(index: Int) { if (index > 0 && index in waypoints.indices) set(PlanEdits.move(waypoints, index, 0)) }
    fun makeDestination(index: Int) {
        if (index in waypoints.indices && index != waypoints.lastIndex) set(PlanEdits.move(waypoints, index, waypoints.size - 1))
    }
    fun reverseRoute() { if (waypoints.size >= 2) set(PlanEdits.reverse(waypoints)) }
    fun roundTrip() {
        val w = PlanEdits.roundTrip(waypoints)
        if (w.size != waypoints.size) set(w) else if (waypoints.size >= 2) message = "The route already ends at the start."
    }

    fun undoStep() = step(history.undo(waypoints))
    fun redoStep() = step(history.redo(waypoints))

    private fun step(prev: List<GeoPoint>?) {
        prev ?: return
        waypoints.clear(); waypoints.addAll(prev)
        canUndo = history.canUndo; canRedo = history.canRedo
        closePointMenu("undo / redo"); nearChoice = null; firstPointMenu = null
        prefs.planWaypoints = waypoints.toList()
        planEditPending = true
        recalc()
    }

    /** Clear button; with two or more points the UI asks first (PlanClear.needsConfirm). */
    var confirmClear by mutableStateOf(false)

    /** Clear the plan right away (owner): one Undo step brings it back, so no question. */
    fun requestClear() = clearPlan()

    /**
     * Resets the whole plan in one undoable step: points, route line, pinned marks (kept while
     * undo can bring them back), error, highlight, notice and point menu. A routing job still
     * running is cancelled and its result ignored (recalc → RouteRequests.invalidate).
     * A followed copy of this plan goes too, unless a recording is using it right now.
     */
    fun clearPlan() {
        confirmClear = false
        val planLine = route?.points
        if (waypoints.isNotEmpty()) change { clear() } else recalc()
        closePointMenu("plan cleared"); nearChoice = null; firstPointMenu = null; highlight = null
        val fromPlan = followFromPlan || (planLine != null && followLine == planLine)
        if (fromPlan && followLine != null && recState.value == null) clearFollow()
    }

    private fun recalc() {
        routeJob?.cancel(); routeJob = null
        val viaData = routingDataReroute; routingDataReroute = false // this run: newer routing data arrived
        highlight = null
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        if (pts.size < 2) { requests.invalidate(); route = null; routeError = null; routing = false; followNextRoute = false; return }
        val act = activity; val level = sac
        val f = planFactors(act)
        val gen = requests.next()
        routing = true
        val started = android.os.SystemClock.elapsedRealtime()
        routeJob = viewModelScope.launch {
            delay(150) // debounce quick taps
            val result = withContext(routingDispatcher) {
                runCatching {
                    val eng = engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir)
                        .also { if (EngineProvider.isRealRouter) engine = it } // retry later if it failed
                    withModelTime(Planner(eng).plan(pts, act, level, pins), act, f)
                }
            }
            if (!requests.isCurrent(gen) || !isActive) { // superseded or cleared
                Problems.trace("routing", "Route #$gen for ${pts.size} points superseded – result ignored")
                return@launch
            }
            Problems.trace("routing", "Route #$gen for ${pts.size} points " + (if (result.isSuccess) "ready" else "failed") +
                " after ${android.os.SystemClock.elapsedRealtime() - started} ms (plan now ${waypoints.size} points)")
            var baseline = false
            result.onSuccess {
                baseline = adoptPlanBaseline
                route = it; routeError = it.error; snapToRoute(pts, pins, it)
                safeCells("route") { showCellJob(cellTriggers.routeComputed(it.points)) } // T4: the corridor
                if (mode == AppMode.PLAN && !followNextRoute && !planEditPending && !viaData) freshenAfterRoute(it.points)
                if (viaData) routingDataRouteChanged(it)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (adoptPlanBaseline) { adoptPlanBaseline = false; planBaseline = it.points }
            }
                .onFailure { Problems.error("routing", "Route calculation failed", it); route = null; routeError = it.message ?: "Routing failed." }
            if (followNextRoute) {
                followNextRoute = false
                if (route != null) followPlanned(checkNow = true) else message = "Could not re-route – still following the previous line."
            } else if (planEditPending && result.isSuccess) {
                // an edit of the plan during a tour: the tour follows the new plan (owner rule 2026-10-19, no question)
                planEditPending = false
                if (!baseline) followEditedPlan()
            }
            routing = false
        }
    }

    // ---------------- time estimate (docs/plan-time-estimate.md) ----------------

    /** Pace setting × learned factors for planning with [a]. */
    fun planFactors(a: Activity = activity): com.onpath.app.time.Factors =
        com.onpath.app.time.TimeEstimate.planFactors(a, settings, learnedFactors(a))

    private fun learnedFactors(a: Activity): com.onpath.app.time.Factors? = paceStore.factors(a)

    /** Route sheet ⋯ → Time details (debug view of the estimate); null = closed. */
    var timeDetails by mutableStateOf<TimeDetailsUi?>(null)

    fun openTimeDetails() {
        val r = route ?: return
        val pace = com.onpath.app.time.TimeEstimate.pace(activity, settings)
        val entry = paceStore.entry(activity)
        timeDetails = TimeDetailsUi(
            com.onpath.app.time.TimeBreakdown.of(r.points, activity, planFactors()),
            pace.label, pace.factor, entry?.factors, entry?.tours ?: 0,
        )
    }

    /**
     * The plan's time from OnPath's own model (time/RouteTimes), not the router's: the same number as the route list
     * and Navigate "to go" at the start. Moving time, rounded for display.
     */
    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute =
        r.copy(durationS = com.onpath.app.time.TimeEstimate.planSeconds(r.points, a, f))

    /** What was learned from earlier tours, per activity (Settings → Your pace). */
    var learnedPace by mutableStateOf(learnedList())
        private set

    private fun learnedList() = paceStore.learned().map { (a, e) -> LearnedPace(a, e.tours, e.factors) }

    /** Settings → Your pace → Reset (after a confirmation): times for [a] go back to the pace setting. */
    fun resetLearnedPace(a: Activity) {
        paceStore = paceStore.reset(a)
        prefs.paceStore = paceStore.toJson()
        learnedPace = learnedList()
        retime()
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_SETTINGS)
    }

    /** A tour ended: the service may have learned something; new plan times. */
    private fun reloadPace() {
        val fresh = com.onpath.app.time.PaceStore.fromJson(prefs.paceStore)
        if (fresh.toJson() != paceStore.toJson()) { paceStore = fresh; learnedPace = learnedList(); retime() }
    }

    /** Times of a finished tour: the service's live summary, else recomputed from the stored points. */
    private fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = try {
        com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).get(t.id)
            ?: com.onpath.app.time.TourTimes.of(t.durationS, com.onpath.app.time.BreakDetector.breaksIn(db.loadSegments(t.id)))
    } catch (e: Exception) {
        Problems.warn("time", "Times of a finished tour could not be computed; shown without", e)
        null
    }

    /** Pace or learned factors changed: new times for the plan and the route list (the route itself stays). */
    private fun retime() {
        route?.let { route = withModelTime(it, activity, planFactors()) }
        refreshTours()
    }


    private val planTimes by lazy {
        com.onpath.app.time.PlanTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.PlanTimesStore.FILE))
    }

    /** A planned route was saved as [id]: keep its model time per group (with way attributes) for the route list. */
    private fun storePlanTimes(id: Long, r: PlannedRoute?, a: Activity) {
        if (r == null || r.points.size < 2) return
        try { planTimes.put(id, r.distanceM, com.onpath.app.time.RouteTimes.of(r.points, a).groupTotals()) } catch (e: Exception) {
            Problems.warn("time", "Plan times not stored for a saved route; list time falls back to the stored points", e)
        }
    }

    /** Planned routes show the model time (recomputed, the stored router time is ignored); others as stored. */
    private fun withModelTime(t: Tour): Tour {
        if (t.kind != TourKind.PLANNED) return t
        // expected: unknown/old activity name in the DB falls back to Hike
        val a = t.activity?.let { runCatching { Activity.valueOf(it) }.getOrNull() } ?: Activity.HIKE
        val f = planFactors(a)
        val key = f to t.distanceM // an overwritten route has a new distance
        plannedTimes[t.id]?.takeIf { it.first == key }?.let { return t.copy(durationS = it.second) }
        // stored at save with the way attributes (P4); the stored points alone have none
        planTimes.get(t.id, t.distanceM)?.let { g ->
            val s = com.onpath.app.time.PlanTimesStore.seconds(g, f)
            plannedTimes[t.id] = key to s
            return t.copy(durationS = s)
        }
        return try {
            val pts = db.loadSegments(t.id).flatten().map { it.toGeo() }
            if (pts.size < 2) t else {
                val s = com.onpath.app.time.TimeEstimate.planSeconds(pts, a, f)
                plannedTimes[t.id] = key to s
                t.copy(durationS = s)
            }
        } catch (e: Exception) {
            Problems.warn("time", "Model time of a planned route could not be computed; stored time shown", e)
            t
        }
    }

    /**
     * Moves free points onto the way the router used (owner: no off-road guidance), so marker, stored
     * plan and route line agree. POI-pinned and named stops keep their coordinates. Amends the current
     * state (no undo step; history snapshots get the same values) and does not re-route: [route] already
     * belongs to the snapped points. Only for the newest result of exactly these points.
     */
    private fun snapToRoute(routed: List<GeoPoint>, pins: Set<Int>, r: PlannedRoute) {
        if (waypoints.toList() != routed) return
        warnFarSnap(routed, r)
        val keep = pins + routed.indices.filter { stopName(routed[it]) != null }
        val snapped = WaypointSnap.apply(routed, r.snapped, keep) ?: return
        for (i in snapped.indices) if (snapped[i] != routed[i]) waypoints[i] = snapped[i]
        if (!RouteRules.isUnsaved(routed, savedPoints)) setSaved(savedName, savedId, snapped)
        val moves = WaypointSnap.moves(routed, snapped)
        history.replacePoints(moves)
        // an open menu follows its point to the snapped position (it must still find it for Delete)
        pointMenuTarget = pointMenuTarget?.afterSnap(moves)
        nearChoice = nearChoice?.let { it.copy(target = it.target.afterSnap(moves)) }
        prefs.planWaypoints = waypoints.toList()
    }

    /**
     * The router reaches a point only by its nearest allowed way (up to 1 km away): a summit above the T level, a
     * via ferrata, a point off any path. Say so, or the hiker trusts a line that stops short of where they want to go.
     */
    private fun warnFarSnap(routed: List<GeoPoint>, r: PlannedRoute) {
        val far = routed.indices.mapNotNull { i ->
            r.snapped.getOrNull(i)?.let { i to com.onpath.app.data.Geo.distance(routed[i], it) }
        }.filter { it.second >= FAR_SNAP_M }.maxByOrNull { it.second } ?: return
        val (i, d) = far
        val what = when (i) { 0 -> "starts"; routed.lastIndex -> "ends"; else -> "passes" }
        message = "The route $what ${com.onpath.app.data.Format.distance(Math.round(d / 10.0) * 10.0)} away from point ${i + 1}: " +
            "the last part is too difficult for ${com.onpath.app.navigation.NavText.activityName(activity, sac)}, closed or has no path."
    }

    /**
     * Saves the plan as a new planned route (⋮ Save route, or "Save first" before loading). The sheet then shows
     * "Saved as …" (no message). Without a route line yet (still calculating / failed) the points are stored as
     * the line, so nothing is lost; opening it re-routes. [then] runs on success only.
     */
    fun saveRoute(name: String, then: (() -> Unit)? = null) {
        val pts = waypoints.toList()
        if (pts.size < 2) return
        val r = route
        val title = name.trim().ifBlank { defaultName("Route") }
        val act = activity; val level = sac
        val stops = savedStops(pts); val pins = pinnedIndices()
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    db.insertTour(
                        TourKind.PLANNED, title, act.name, level,
                        r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                        listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                        stops, pinned = pins,
                    ).also { storePlanTimes(it, r, act) }
                }.onFailure { Problems.error("db", "Saving a planned route failed", it) }.getOrNull()
            }
            if (id == null) { routesProblem = "The route could not be saved. Your plan is still here – please try again."; return@launch }
            Backups.tourChanged(getApplication(), id)
            setSaved(title, id, pts)
            refreshTours()
            then?.invoke()
        }
    }

    /** The plan's stops as saved: with their place names (backup / restore keeps them, owner 2026-10-21). */
    private fun savedStops(pts: List<GeoPoint>) = pts.map { GpxWaypoint(it.lat, it.lon, stopName(it)) }

    /** ⋮ → Save route: Save / Save as new… when the plan belongs to a saved route (RouteRules.saveMode), else the name dialog. */
    fun requestSave() {
        if (waypoints.size < 2) return
        when (currentSaveMode()) {
            SaveMode.OVERWRITE_OR_NEW -> saveChoice = SaveChoice(null)
            SaveMode.NAME_DIALOG -> { suggestName(); saveDialog = true }
        }
    }

    private fun currentSaveMode() =
        RouteRules.saveMode(savedId, savedName, tours.filter { it.kind == TourKind.PLANNED }.map { it.id })

    /** Choice "Save": overwrite the saved route in place (name stays). Falls back to a new entry if it is gone. */
    fun saveChoiceOverwrite() {
        val choice = saveChoice ?: return
        saveChoice = null
        val then: (() -> Unit)? = choice.thenOpen?.let { t -> { openTour(t) } }
        val pts = waypoints.toList()
        val name = savedName
        val id = savedId
        if (pts.size < 2 || name == null || id < 0) { saveDialog = true; return }
        val r = route
        val act = activity; val level = sac
        val stops = savedStops(pts); val pins = pinnedIndices()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    db.replacePlanned(
                        id, act.name, level,
                        r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                        listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                        stops, pins,
                    ).also { if (it) { plannedTimes.remove(id); storePlanTimes(id, r, act) } }
                }
            }
            ok.onSuccess { replaced ->
                if (replaced) { Backups.tourChanged(getApplication(), id); setSaved(name, id, pts); refreshTours(); then?.invoke() }
                else saveRoute(name, then) // deleted meanwhile: keep the work as a new route with the same name
            }.onFailure {
                Problems.error("db", "Overwriting a saved route failed", it)
                routesProblem = "The route could not be saved. Your plan is still here – please try again."
            }
        }
    }

    /** Choice "Save as new…": the name dialog (then loading the picked route when it came from "Save first"). */
    fun saveChoiceAsNew() {
        val choice = saveChoice ?: return
        saveChoice = null
        suggestName()
        if (choice.thenOpen != null) saveFirst = choice.thenOpen else saveDialog = true
    }

    /** Manage routes → Rename. The plan's "Saved as …" and a shown track follow the new name. */
    fun renameTour(t: Tour, input: String) {
        val name = RouteRules.cleanName(input, t.name)
        if (name == t.name) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { db.rename(t.id, name) }
                .onFailure { Problems.error("db", "Renaming a route failed", it) }.isSuccess }
            if (!ok) { routesProblem = "The route could not be renamed. Please try again."; return@launch }
            Backups.tourChanged(getApplication(), t.id)
            if (savedId == t.id) setSaved(name, savedId, savedPoints)
            shown?.let { if (it.tourId == t.id) shown = it.copy(name = name) }
            refreshTours()
        }
    }

    private fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) {
        savedName = name; savedId = id; savedPoints = points
        prefs.planSavedName = name; prefs.planSavedId = id; prefs.planSavedPoints = points
    }

    /** Navigate mode or a tour recording: no loading into the plan, no Clear. */
    fun navigating(recording: Boolean): Boolean = mode == AppMode.NAVIGATE || recording

    /** State of the ⋮ menu items (RouteRules.menu). */
    fun menuState(recording: Boolean): RouteMenuState =
        RouteRules.menu(route != null, waypoints.size, tours.size, navigating(recording), recording)

    fun openRoutes() { refreshTours(); routesOpen = true }

    /** Open / load from Manage routes or Load route: asks "Replace current plan?" when that would lose changes. */
    fun requestOpen(t: Tour) {
        if (!RouteRules.canOpen(t.kind, t.waypoints.size, navigating(recState.value != null))) return
        if (RouteRules.askBeforeLoading(t.kind, t.waypoints.size, waypoints, savedPoints)) replaceAsk = t else openTour(t)
    }

    fun replaceConfirmed() { val t = replaceAsk ?: return; replaceAsk = null; openTour(t) }

    /** "Save first": name dialog, then save and load [replaceAsk]. */
    fun replaceSaveFirst() {
        val t = replaceAsk ?: return
        replaceAsk = null
        if (currentSaveMode() == SaveMode.OVERWRITE_OR_NEW) saveChoice = SaveChoice(t) else { suggestName(); saveFirst = t }
    }

    fun saveFirstDone(name: String) {
        val t = saveFirst ?: return
        saveFirst = null
        saveRoute(name) { openTour(t) }
    }

    /**
     * Proposed name for the open "Save route" dialog ("Algund → Meran", search/RouteNamer), null until computed or
     * when nothing fits. The dialog opens at once with [defaultName] and takes this over while the user has not typed.
     */
    var suggestedName by mutableStateOf<String?>(null)
        private set
    private var nameJob: Job? = null

    /** Computes [suggestedName] off the main thread from the offline place index (built first if needed). */
    private fun suggestName() {
        suggestedName = null
        nameJob?.cancel()
        val pts = waypoints.toList()
        if (pts.size < 2) return
        val line = route?.points?.takeIf { it.size >= 2 } ?: pts
        val startName = stopName(pts.first()); val endName = stopName(pts.last())
        val via = pts.subList(1, pts.size - 1).mapNotNull { stopName(it) }
        nameJob = viewModelScope.launch {
            val places = try { search.offlineIndex().entries.map { it.place } } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { Problems.warn("search", "Place index unavailable; route name from stop names only", e); emptyList() }
            suggestedName = withContext(Dispatchers.Default) {
                try { com.onpath.app.search.RouteNamer.name(line, places, startName, endName, via) } catch (e: Exception) {
                    Problems.warn("search", "No route name proposed (naming failed)", e); null
                }
            }
        }
    }

    /**
     * A finished recording still called "Tour 20 Oct 2026, 10:12" gets a place name ("Algund → Meran", RouteNamer)
     * in the background; the "Tour saved" dialog follows. Nothing fits / index missing → the date name stays.
     * The date is still shown in My routes; Rename there changes it.
     */
    private fun nameRecordedTour(t: Tour) {
        if (!t.name.startsWith("Tour ")) return
        viewModelScope.launch {
            val name = try {
                val places = search.offlineIndex().entries.map { it.place }
                withContext(Dispatchers.IO) {
                    val line = db.loadSegments(t.id).flatten().map { GeoPoint(it.lat, it.lon) }
                    com.onpath.app.search.RouteNamer.name(line, places)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Problems.warn("search", "No name proposed for the recorded tour; it keeps its date name", e); null
            } ?: return@launch
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.rename(t.id, name) }.onFailure { Problems.warn("db", "Naming the recorded tour failed; it keeps its date name", it) }.isSuccess
            }
            if (!ok) return@launch
            Backups.tourChanged(getApplication(), t.id) // the file follows the new name
            finishedTour?.let { if (it.id == t.id) finishedTour = it.copy(name = name) }
            refreshTours()
        }
    }

    fun defaultName(prefix: String) =
        "$prefix " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())

    // ---------------- modes: Plan <-> Navigate ----------------

    /** Switches mode; the recording goes on either way, only off-route checks pause in Plan. */
    private fun enter(m: AppMode) {
        mode = m
        if (m == AppMode.NAVIGATE) { planPoi = null; poiMenu = null; closePointMenu("mode change to Navigate"); nearChoice = null; firstPointMenu = null; highlight = null; followMe = true }
        // Plan keeps the compass choice: walking direction up stays turned and following (until you edit or move the
        // map); north up stops following as before
        else followMe = headingUp && followMe && tracker.location.value != null
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_NAVIGATING) {
            putExtra(RecordingService.EXTRA_ON, m == AppMode.NAVIGATE)
        }
    }

    /**
     * Plan's primary action (location permission is checked by the caller). Follows the planned
     * route, else the shown track, else nothing (free recording), and starts recording.
     */
    fun startNavigation(force: Boolean = false) {
        if (recState.value != null) { continueNavigation(force); return }
        val s = shown
        if (s == null && !force && planHasStraightLegs()) { straightAsk = true; return }
        when {
            s != null -> { followShown(); shown = null }
            route != null && waypoints.size >= 2 -> followPlanned()
            else -> clearFollow()
        }
        startRecording()
        // Navigate only once the service really records (R-01): a refused foreground service, a missing
        // permission or a broken database must not leave the user in Navigate with nothing being recorded.
        startJob?.cancel()
        startJob = viewModelScope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { RecordingService.state.filterNotNull().first() } != null
            if (started) { enter(AppMode.NAVIGATE); maybeAskBackground() }
            else if (message == null) message = "Recording did not start. Check the location permission (precise) and notifications, then try again."
        }
    }

    private var startJob: Job? = null

    /** First tour: offer "Allow all the time" once, so a restart / update does not stop recording (0.7.8). */
    var askBackground by mutableStateOf(false)

    private fun maybeAskBackground() {
        if (android.os.Build.VERSION.SDK_INT < 29 || prefs.backgroundAsked) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(getApplication(),
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) askBackground = true
    }

    fun backgroundAnswered() { prefs.backgroundAsked = true; askBackground = false }
    /** How long Start waits for the service's first state before saying that recording did not start. */
    private val START_TIMEOUT_MS = 6_000L

    /** A plan with legs the router could not find (drawn dashed): "Navigate anyway?" is open. */
    var straightAsk by mutableStateOf(false)

    private fun planHasStraightLegs(): Boolean =
        !followIsTrack && waypoints.size >= 2 && route?.legs?.any { it.straight } == true

    /** The user wants to navigate the plan although parts of it are straight lines. */
    fun straightConfirmed() { straightAsk = false; startNavigation(force = true) }

    /**
     * Back to Navigate while a tour runs (no new recording). The plan's waypoints are **not** changed (owner Q5):
     * when the plan was edited, its route becomes the new navigation session; otherwise the running session (and a
     * new route already taken) stays. Either way the service checks the next fix at once and, when the user is away
     * from the line, asks the same "Take new route / Keep original" question as when leaving the route.
     */
    fun continueNavigation(force: Boolean = false) {
        if (!followIsTrack && waypoints.size >= 2) {
            val planLine = if (routing) null else route?.points
            // an edited plan with straight legs would become the navigated line: ask first (same as Start)
            if (!force && planLine != null && !planFollowed(planLine) && planHasStraightLegs()) { straightAsk = true; return }
            when {
                planLine != null && !planFollowed(planLine) -> followPlanned(checkNow = true)
                routing -> followNextRoute = true // follows the plan when the result is there
                else -> checkNow()
            }
        } else checkNow()
        enter(AppMode.NAVIGATE)
    }

    /**
     * True while a tour follows exactly the current plan (plan not edited): then the followed line (blue) is the
     * plan, and after a re-route the plan line must not be drawn as a second blue line (the original shows grey).
     */
    fun planIsFollowed(): Boolean =
        followLine != null && !followIsTrack && planFollowed(route?.points)

    /** Look of the followed / shown line on the map (see MapController.setTrackLook). */
    fun trackLook(): com.onpath.app.map.MapController.TrackLook {
        val planLine = route?.points
        return when {
            shown != null -> com.onpath.app.map.MapController.TrackLook.TRACK
            mode == AppMode.PLAN && !followIsTrack && waypoints.size >= 2 && planLine != null && !planFollowed(planLine) ->
                com.onpath.app.map.MapController.TrackLook.FOLLOW_BEHIND
            else -> com.onpath.app.map.MapController.TrackLook.FOLLOW
        }
    }

    private fun checkNow() {
        if (recState.value != null && followLine != null) RecordingService.send(getApplication(), RecordingService.ACTION_CHECK_NOW)
    }

    /** Off-route question in Navigate: take the new route the service found. */
    fun takeNewRoute(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_TAKE_ROUTE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** Off-route question in Navigate: keep following the original route. */
    fun keepOriginalRoute(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_KEEP_ROUTE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    // ---------------- activity check while navigating (owner: a wrong activity is a disaster) ----------------

    /** The check-and-switch sheet (Navigate → tap the activity icon); null = closed. Stays until a choice is made. */
    var activityCheck by mutableStateOf<ActivityCheckUi?>(null)
        private set
    private var checkJob: Job? = null
    private var checkCancel: com.onpath.app.routing.RouteCancel? = null
    private var checkSession: NavSession? = null
    private var checkRest: com.onpath.app.navigation.ActivityCheck.Rest? = null
    /** Session activity last seen from the service (switch taken → the app follows). */
    private var navActivitySeen: Activity? = null
    /** Plan line computed for an activity switched while navigating: counts as followed (not an edit). */
    private var planBaseline: List<GeoPoint>? = null
    private var adoptPlanBaseline = false

    private fun planFollowed(planLine: List<GeoPoint>?): Boolean =
        planLine != null && (planLine == followOriginal || planLine == planBaseline)

    private fun sacFor(a: Activity) = if (a == Activity.HIKE) sac else checkSession?.sac ?: sac

    /**
     * Opens the sheet: for every other activity the rest of the followed tour is routed offline (one after the other,
     * spinner per row) and checked ([com.onpath.app.navigation.ActivityCheck]). Free recording: plain choices.
     */
    fun openActivityCheck() {
        closeActivityCheck()
        val nav = recState.value?.nav
        val session = nav?.session?.takeIf { it.active.size >= 2 }
        val cur = session?.activity ?: activity
        val curSac = session?.sac ?: sac
        val others = Activity.entries.filter { it != cur }
        checkSession = session
        if (session == null) {
            activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Plain) },
                note = "No route is followed – the new activity applies to the recording and the plan.")
            return
        }
        activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Checking) },
            note = if (tracker.location.value == null) "Waiting for GPS…" else null)
        val cancel = com.onpath.app.routing.RouteCancel()
        checkCancel = cancel
        checkJob = viewModelScope.launch {
            val loc = tracker.location.value ?: tracker.location.filterNotNull().first()
            val pos = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
            val progress = recState.value?.nav?.progressM ?: nav.progressM
            val rest = withContext(Dispatchers.Default) {
                runCatching { com.onpath.app.navigation.ActivityCheck.rest(session, progress, pos) }
                    .onFailure { Problems.warn("navigation", "Activity check: rest of the route could not be computed", it) }.getOrNull()
            }
            checkRest = rest
            if (rest == null) {
                updateCheck(null) { ActivityRowState.Failed("Nothing left of the route to check") }
                return@launch
            }
            updateCheck(null) { it }
            for (a in others) {
                if (!isActive || cancel.isCancelled) return@launch
                val st = withContext(routingDispatcher) {
                    try {
                        val eng = engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir)
                            .also { if (EngineProvider.isRealRouter) engine = it }
                        if (eng is com.onpath.app.routing.StraightLineEngine) {
                            ActivityRowState.Failed("Offline routing not available")
                        } else {
                            val r = eng.route(rest.waypoints, a, sacFor(a),
                                com.onpath.app.routing.RouteLimits(com.onpath.app.navigation.ActivityCheck.TIMEOUT_MS, cancel))
                            if (r.points.size < 2) ActivityRowState.Failed("No route found for it")
                            else ActivityRowState.Ready(com.onpath.app.navigation.ActivityCheck.evaluate(rest.line, a, sacFor(a), r, planFactors(a)))
                        }
                    } catch (e: Exception) {
                        Problems.warn("routing", "Activity check: routing for another activity failed", e)
                        ActivityRowState.Failed(e.message?.takeIf { it.length < 90 } ?: "No route found for it")
                    }
                }
                if (!isActive || cancel.isCancelled) return@launch
                updateCheck(a) { st }
            }
        }
    }

    /** Clears the note; [a] null = every row, else only that row. */
    private fun updateCheck(a: Activity?, f: (ActivityRowState) -> ActivityRowState) {
        val ui = activityCheck ?: return
        activityCheck = ui.copy(note = null, rows = ui.rows.map { if (a == null || it.activity == a) it.copy(state = f(it.state)) else it })
    }

    /**
     * A row was chosen: free recording → switch at once; navigating → the rest of the tour for that activity becomes
     * the dashed proposal with the usual question (Take new route / Keep original), asked by the service.
     */
    fun chooseActivity(a: Activity) {
        val ui = activityCheck ?: return
        val row = ui.rows.firstOrNull { it.activity == a }?.takeIf { it.choosable } ?: return
        val st = row.state
        val session = checkSession
        val rest = checkRest
        closeActivityCheck()
        if (st == ActivityRowState.Plain || session == null) {
            selectActivity(a)
            recState.value?.tourId?.let { id -> viewModelScope.launch(Dispatchers.IO) {
                runCatching { db.updateActivity(id, a.name, sacFor(a)) }
                    .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
            } }
            return
        }
        val fit = (st as? ActivityRowState.Ready)?.fit ?: return
        val left = rest?.stops.orEmpty()
        val proposed = runCatching {
            if (session.kind == NavKind.PLAN && left.isNotEmpty()) {
                NavSession.start(NavKind.PLAN, session.name, a, fit.sac, fit.line,
                    stops = listOf(fit.line.first()) + left.map { it.point }, names = listOf(null) + left.map { it.name },
                    pinned = left.indices.filter { left[it].pinned }.map { it + 1 }.toSet())
            } else NavSession.start(session.kind, session.name, a, fit.sac, fit.line)
        }.onFailure { Problems.error("navigation", "Proposed route for the new activity could not be started", it) }.getOrNull() ?: return
        val app = getApplication<Application>()
        NavSessionStore(File(app.filesDir, com.onpath.app.navigation.ActivityCheck.PROPOSAL_FILE)).save(proposed)
        RecordingService.send(app, RecordingService.ACTION_PROPOSE_SWITCH) { putExtra(RecordingService.EXTRA_TIME_S, fit.timeS) }
    }

    /** "Keep <activity>" (or Back): close the sheet, stop the checks. Nothing changes. */
    fun closeActivityCheck() {
        checkCancel?.cancel(); checkCancel = null
        checkJob?.cancel(); checkJob = null
        activityCheck = null
    }

    /** The service navigates another activity now (switch taken, also from the notification): the app follows. */
    private fun syncNavActivity(s: NavSession?) {
        val a = s?.activity ?: return
        if (a == navActivitySeen) return
        navActivitySeen = a
        if (a != activity) {
            followOriginal = s.original
            adoptPlanBaseline = true
            selectActivity(a)
        }
    }

    /** The slide switch in Navigate. Recording continues. */
    fun switchToPlan() = enter(AppMode.PLAN)

    /** "End tour" in Plan (after confirmation) or "End & save" on arrival: stop and save. */
    fun endTour() { arrivalAsk = false; stopRecording() }

    /** Called with each fix in Navigate: asks when the end of the followed line is reached ([ArrivalGate]). */
    fun checkArrival(position: GeoPoint, remainingAlongM: Double?) {
        if (mode != AppMode.NAVIGATE || recState.value == null) return
        val acc = tracker.location.value?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        if (arrivalGate.onFix(position, followLine?.lastOrNull(), remainingAlongM, acc)) arrivalAsk = true
    }

    // ---------------- follow / record ----------------

    /**
     * The plan was edited while a tour follows it (round trip, Undo, points added / removed, reverse): the tour follows
     * the new plan at once – followed line, target and arrival – without a question (owner rule 2026-10-19). A
     * re-computed line that is the same route (Undo back to it) keeps the running session. A followed GPX track or a
     * free recording are not touched; a plan with fewer than two points changes nothing.
     */
    private fun followEditedPlan() {
        val r = route ?: return
        if (recState.value == null || followLine == null || followIsTrack) return
        if (r.points.size < 2) return // nothing to follow (e.g. all points on one spot): keep the running line
        val orig = followOriginal
        if (planFollowed(r.points)) return
        if (orig != null && NavLine.sameRoute(r.points, orig)) { planBaseline = r.points; return }
        followPlanned()
    }

    fun followPlanned(checkNow: Boolean = false) {
        val r = route ?: return
        val w = waypoints.toList()
        setFollow(defaultName("Route"), r.points, isTrack = false, checkNow = checkNow,
            stops = w, names = w.map { stopName(it) }, pinned = pinnedIndices())
        followFromPlan = true
    }

    fun followShown() {
        val s = shown ?: return
        setFollow(s.name, s.points, isTrack = true)
        followFromPlan = false
    }

    /**
     * Starts a navigation session for [points] (nav-session.json for the service: original line, stops for re-routing)
     * and the plain follow line. [checkNow]: the service checks the next fix at once ("Continue navigation").
     */
    private fun setFollow(name: String, points: List<GeoPoint>, isTrack: Boolean, checkNow: Boolean = false,
                          stops: List<GeoPoint> = emptyList(), names: List<String?> = emptyList(), pinned: Set<Int> = emptySet()) {
        val session = runCatching {
            NavSession.start(if (isTrack) NavKind.TRACK else NavKind.PLAN, name, activity, sac, points, stops, names, pinned)
        }.onFailure { Problems.error("navigation", "Navigation session could not be started; following without it", it) }.getOrNull()
        navStore.save(session)
        prefs.saveFollow(name, points)
        followName = name
        followLine = points
        followOriginal = points
        planBaseline = null
        followIsTrack = isTrack; prefs.followIsTrack = isTrack
        arrivalGate.reset()
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED) {
            putExtra(RecordingService.EXTRA_CHECK_NOW, checkNow)
        }
    }

    fun clearFollow() {
        followFromPlan = false
        followIsTrack = false; prefs.followIsTrack = false
        arrivalGate.reset()
        prefs.saveFollow("", null)
        navStore.save(null)
        followName = null
        followLine = null
        followOriginal = null
        planBaseline = null
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED)
    }

    fun startRecording() {
        RecordingService.send(getApplication(), RecordingService.ACTION_START) {
            putExtra(RecordingService.EXTRA_ACTIVITY, activity.name)
        }
        followMe = true
    }

    fun continueRecording(t: Tour) {
        unfinished = null
        RecordingService.send(getApplication(), RecordingService.ACTION_START) {
            putExtra(RecordingService.EXTRA_CONTINUE_ID, t.id)
        }
    }

    fun saveUnfinished(t: Tour) {
        unfinished = null
        // the sticky service came back meanwhile and records this tour: it is not unfinished, leave it alone
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        enter(AppMode.PLAN)
        viewModelScope.launch(Dispatchers.IO) {
            db.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
            Backups.tourChanged(getApplication(), t.id)
            withContext(Dispatchers.Main) { message = "Tour saved to My routes." }
        }
    }

    fun pauseRecording(pause: Boolean) =
        RecordingService.send(getApplication(), if (pause) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME)

    fun stopRecording() = RecordingService.send(getApplication(), RecordingService.ACTION_STOP)

    // ---------------- tours ----------------

    fun refreshTours() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.listTours().filter { it.state == "done" }.map { withModelTime(it) }
            withContext(Dispatchers.Main) { tours = list }
        }
    }

    fun openTour(t: Tour) {
        viewModelScope.launch {
            val segs = withContext(Dispatchers.IO) { db.loadSegments(t.id) }
            routesOpen = false
            if (RouteRules.replacesPlan(t.kind, t.waypoints.size)) {
                // Back into planning with its via points (recalculated with the current engine).
                // expected: unknown/old activity name keeps the current activity
                t.activity?.let { a -> runCatching { Activity.valueOf(a) }.getOrNull()?.let { activity = it; prefs.activity = it } }
                sac = t.sac
                shown = null
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                change { clear(); addAll(pts) }
                // stop names and POI pins saved with the plan (since the backup, 2026-10-21) come back too
                t.waypoints.forEachIndexed { i, w -> w.name?.let { rememberPlaceName(pts[i], it) } }
                t.pinned.mapNotNull { pts.getOrNull(it) }.takeIf { it.isNotEmpty() }?.let { pinned += it; prefs.planPinned = pinned }
                setSaved(t.name, t.id, pts)
                events += UiEvent.Fit(segs.flatten().map { it.toGeo() })
            } else {
                showTrack(ShownTrack(t.id, t.name, segs.map { s -> s.map { it.toGeo() } }, t.waypoints,
                    t.distanceM, t.ascentM, t.descentM, t.durationS))
            }
        }
    }

    private fun showTrack(s: ShownTrack) {
        // A GPX opened from another app while navigating: show it in Plan (the recording goes on).
        if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN)
        shown = s
        events += UiEvent.Fit(s.points)
    }

    fun closeShown() { shown = null; highlight = null }

    fun deleteTour(t: Tour) {
        // never the tour being recorded: the service keeps writing into it (the list hides Delete for it too)
        if (recState.value?.tourId == t.id) { message = "This tour is being recorded – end it first."; return }
        viewModelScope.launch(Dispatchers.IO) {
            // into the recycle bin (30 days, My routes → ⋮ → Recycle bin); its backup file moves to Deleted/
            val ok = runCatching { db.setDeleted(t.id, System.currentTimeMillis()) }
                .onFailure { Problems.error("db", "Moving a tour to the recycle bin failed", it) }.getOrDefault(false)
            if (ok) Backups.tourChanged(getApplication(), t.id)
            withContext(Dispatchers.Main) {
                if (!ok) { routesProblem = "“${t.name}” could not be deleted. Please try again."; return@withContext }
                if (shown?.tourId == t.id) shown = null
                if (savedId == t.id) setSaved(null, -1, null) // the plan is no longer saved anywhere
                if (finishedTour?.id == t.id) finishedTour = null
                refreshTours()
            }
        }
    }

    // ---------------- recycle bin + backup (owner 2026-10-21, docs/core-features.md C10) ----------------

    /** Recycle bin list (My routes → ⋮ → Recycle bin); null = closed. */
    var recycleBin by mutableStateOf<List<Tour>?>(null)

    fun openRecycleBin() {
        viewModelScope.launch {
            recycleBin = withContext(Dispatchers.IO) {
                runCatching { db.listDeleted() }.onFailure { Problems.error("db", "Recycle bin could not be read", it) }.getOrDefault(emptyList())
            }
        }
    }

    /** Recycle bin → Restore: back into My routes (and its file out of Deleted/). */
    fun restoreFromBin(t: Tour) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.setDeleted(t.id, null) }.onFailure { Problems.error("db", "Restoring from the recycle bin failed", it) }.getOrDefault(false)
            }
            if (ok) Backups.tourChanged(getApplication(), t.id)
            else routesProblem = "“${t.name}” could not be restored. Please try again."
            openRecycleBin(); refreshTours()
        }
    }

    /** Recycle bin → Delete now (asked first): gone for good, its backup file too. */
    fun deleteForever(t: Tour) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.deleteTour(t.id) }.onFailure { Problems.error("db", "Deleting a tour for good failed", it) }.isSuccess
            }
            if (ok) Backups.purge(getApplication(), listOf(t.uuid))
            openRecycleBin()
        }
    }

    /** "Export all": one ZIP with every tour and plan as GPX, handed to the share sheet (Files, Drive, mail …). */
    fun exportAll() {
        message = "Preparing the export…"
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    val f = Backups.exportZip(app, File(app.cacheDir, "export"), SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()))
                    FileProvider.getUriForFile(app, app.packageName + ".files", f) to f.name
                }
                message = null
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri.first)
                    putExtra(Intent.EXTRA_SUBJECT, uri.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                events += UiEvent.Share(Intent.createChooser(send, "Export all tours"))
            } catch (e: com.onpath.app.backup.BackupException) {
                Problems.info("backup", "Export all: ${e.reason}", e); message = "Export: ${e.reason}."
            } catch (e: Exception) {
                Problems.error("backup", "Export all failed", e)
                message = "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** First start with an empty database: "Earlier tours found? Restore from Documents/OnPath" (asked once). */
    var restoreOffer by mutableStateOf(false)
    /** A restore is running: its progress line ("Restoring 5 / 28"); null = none. */
    var restoreProgress by mutableStateOf<String?>(null)
        private set

    private fun checkRestoreOffer() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val offer = try { !Backups.restoreOffered(app) && db.countAll() == 0 } catch (e: Exception) {
                Problems.warn("backup", "Restore offer not checked", e); false
            }
            if (offer) withContext(Dispatchers.Main) { restoreOffer = true }
        }
    }

    init {
        Backups.init(app)
        Backups.purgeAndSync(app) // 30-day recycle bin, files for tours that have none yet
        checkRestoreOffer()
        // a failed backup is said until OK is tapped (no silent loss); then it counts as seen
        viewModelScope.launch {
            Backups.failure.collect { text ->
                if (text == null) return@collect
                while (message != null) delay(500) // after the current message
                message = "$text. Your tours are still on this phone – see Settings → Backup."
                Backups.failureSeen(getApplication())
            }
        }
    }

    fun restoreOfferAnswered() { restoreOffer = false; Backups.setRestoreOffered(getApplication()) }

    /** The folder the user confirmed in the picker: import every OnPath backup in it (off the main thread). */
    fun restoreFrom(tree: Uri) {
        if (restoreProgress != null) return
        restoreProgress = "Looking for backups…"
        viewModelScope.launch {
            val app = getApplication<Application>()
            val r = withContext(Dispatchers.IO) {
                try { Backups.restore(app, tree) { line -> viewModelScope.launch(Dispatchers.Main) { if (restoreProgress != null) restoreProgress = line } } }
                catch (e: Exception) { Problems.error("backup", "Restore from the backup folder failed", e); null }
            }
            restoreProgress = null
            message = r?.let { com.onpath.app.backup.BackupRestore.summary(it) } ?: "The backup folder could not be read. Please try again."
            refreshTours()
        }
    }

    fun exportTour(t: Tour) {
        viewModelScope.launch {
            val segs = withContext(Dispatchers.IO) { db.loadSegments(t.id) }
            share(t.name, segs, if (t.kind == TourKind.PLANNED) emptyList() else t.waypoints)
        }
    }

    fun exportShown() {
        val sh = shown ?: return
        viewModelScope.launch {
            share(sh.name, sh.segments.map { seg -> seg.map { TrackPoint(it.lat, it.lon, it.ele) } }, sh.waypoints)
        }
    }

    fun exportPlanned() {
        val r = route ?: return
        viewModelScope.launch {
            share(defaultName("OnPath route"), listOf(r.points.map { TrackPoint(it.lat, it.lon, it.ele) }), emptyList())
        }
    }

    private suspend fun share(name: String, segs: List<List<TrackPoint>>, wpts: List<GpxWaypoint>) {
        try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "export").apply { mkdirs() }
                val file = File(dir, GpxWriter.fileName(name))
                file.writeText(GpxWriter.write(name, segs, wpts))
                FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", file)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            events += UiEvent.Share(Intent.createChooser(send, "Export GPX"))
        } catch (e: Exception) {
            Problems.error("gpx", "GPX export failed", e)
            message = "Export failed: ${e.message}"
        }
    }

    // ---------------- GPX import ----------------

    fun importGpx(uri: Uri) {
        message = "Importing GPX…"
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val (data, fileName) = withContext(Dispatchers.IO) {
                    val name = runCatching {
                        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.onFailure { Problems.warn("gpx", "GPX import: file name unreadable; using the GPX name", it) }.getOrNull()
                    val stream = app.contentResolver.openInputStream(uri) ?: error("Cannot open the file.")
                    stream.use { GpxParser.parse(it) } to name
                }
                val segs = data.allSegments
                val name = data.name ?: fileName?.removeSuffix(".gpx")?.removeSuffix(".GPX") ?: defaultName("Import")
                val geo = segs.map { s -> s.map { it.toGeo() } }
                val dist = geo.sumOf { Geo.length(it) }
                var up = 0.0; var down = 0.0
                segs.forEach { s -> val (u, d) = Geo.climb(s.map { it.ele }); up += u; down += d }
                val times = segs.flatten().mapNotNull { it.timeMs }
                val dur = if (times.size > 1 && times.last() > times.first()) (times.last() - times.first()) / 1000.0
                else Geo.estimateSeconds(dist, up, down, activity)
                val id = withContext(Dispatchers.IO) {
                    db.insertTour(TourKind.IMPORTED, name, activity.name, sac, dist, up, down, dur, segs, data.waypoints)
                }
                Backups.tourChanged(app, id)
                val pts = geo.flatten().ifEmpty { data.waypoints.map { GeoPoint(it.lat, it.lon) } }
                routesOpen = false // show the imported track on the map
                refreshTours()
                showTrack(ShownTrack(id, name, geo, data.waypoints, dist, up, down, dur))
                if (geo.isEmpty()) events += UiEvent.Fit(pts)
                // The track panel with its name is the feedback; a message (until OK) only when something was odd.
                message = if (data.warnings.isEmpty()) null else buildString {
                    append("Imported “$name”: ${data.pointCount} points")
                    if (data.waypoints.isNotEmpty()) append(", ${data.waypoints.size} waypoints")
                    append(".")
                    data.warnings.forEach { append("\n").append(it) }
                }
            } catch (e: Exception) {
                Problems.error("gpx", "GPX import failed", e)
                message = "Could not import this file: ${e.message ?: "unknown error"}"
            }
        }
    }

    // ---------------- routing data freshness (owner 2026-10-21: routing matters most – a changed way must reroute) ----

    /** The plan is being computed again because newer routing data arrived. */
    private var routingDataReroute = false
    /** Route before that recalculation (to tell the user only when the way changed). */
    private var routingDataBefore: List<GeoPoint>? = null
    @Volatile private var routingDataBusy = false

    /**
     * Newer routing data (BRouter tiles from brouter.de, weekly) for the route's tiles: at most every
     * [ROUTING_CHECK_MS]; a tile older than [ROUTING_MAX_AGE_DAYS] days is downloaded again when the server has a
     * newer one – on mobile data too (owner), unless Settings says maps only on Wi-Fi. Never during a tour.
     */
    private fun checkRoutingData(line: List<GeoPoint>) {
        if (routingDataBusy || recState.value != null) return
        val t = System.currentTimeMillis()
        if (t - prefs.routingDataCheckedAt < ROUTING_CHECK_MS) return
        val names = listOf(line.first(), line.last()).map { com.onpath.app.data.SegmentDownloader.tileName(it.lat, it.lon) }.distinct()
        val due = names.map { java.io.File(dataFiles.segmentsDir, it) }
            .filter { it.isFile && t - it.lastModified() > ROUTING_MAX_AGE_DAYS * 86_400_000L }
        prefs.routingDataCheckedAt = t
        if (due.isEmpty() || (settings.mapsWifiOnly && !com.onpath.app.cells.AndroidNetworkStatus(getApplication()).unmetered())) return
        routingDataBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            var got = 0
            for (f in due) {
                try {
                    if (com.onpath.app.data.SegmentDownloader.refreshIfNewer(f.name, dataFiles.segmentsDir)) got++
                } catch (e: Exception) {
                    Problems.info("routing", "Routing data ${f.name} not refreshed now (${e.message}); tried again later", e)
                }
            }
            withContext(Dispatchers.Main) {
                routingDataBusy = false
                if (got > 0) {
                    Problems.info("routing", "Newer routing data installed ($got file(s)); plan computed again")
                    engine = null // new engine: no cached routes from the old data
                    if (mode == AppMode.PLAN && recState.value == null && waypoints.size >= 2) {
                        routingDataBefore = route?.points; routingDataReroute = true; recalc()
                    }
                }
            }
        }
    }

    /** The plan computed again on newer routing data: say so only when the way changed. */
    private fun routingDataRouteChanged(r: com.onpath.app.routing.PlannedRoute) {
        val before = routingDataBefore ?: return
        routingDataBefore = null
        if (com.onpath.app.data.RoutingDataCheck.differs(before, r.points)) message = "Route updated – newer way data."
    }

    // Last in the class: every property it touches is initialised by now.
    init {
        // a new cell store generation (cell added / replaced / deleted, region re-joined) → reload the map style
        // (new file paths; MapLibre keeps no stale PMTiles index) and recheck the data. The search index notices by itself.
        viewModelScope.launch {
            cells.generation.collect {
                val onMap = com.onpath.app.map.StyleLoader.lastMapDir
                if (onMap == null) recheckData() // no map shown yet (missing-data screen): the first cell may complete the data
                else if (dataFiles.mapDir() != onMap) { recheckData(); styleVersion++ }
            }
        }
        // map cells (map-cells.md §9): Wi-Fi setting, cells still loading from the last run on the card, T3 / T5 on fixes
        cells.wifiOnly = settings.mapsWifiOnly
        safeCells("start") {
            val inst = cells.installed.value.map { it.id }.toSet()
            val loading = cells.states.value.filter { (c, s) -> c !in inst && s != com.onpath.app.cells.CellState.None &&
                s !is com.onpath.app.cells.CellState.Ready && s !is com.onpath.app.cells.CellState.Failed }.keys.sorted()
            if (loading.isNotEmpty() && cells.downloadsAvailable) cellJob = com.onpath.app.cells.CellJob(loading, null, com.onpath.app.cells.CellWhy.PLANNED)
        }
        viewModelScope.launch { tracker.location.filterNotNull().collect { cellsForFix(it) } }
        // a tour runs (Navigate): newer map versions wait – the map never switches under the user's feet
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { mode }.collect { cells.holdUpdates = it == AppMode.NAVIGATE } }
    }

    override fun onCleared() {
        routingDispatcher.close()
        super.onCleared()
    }
}

/** A plan point the router reached only this far away (m) gets a message. */
private const val FAR_SNAP_M = 150.0

/** Navigate profile scrubbing: how long the dot and the map stay at the spot after the finger leaves (ms). */
private const val SCRUB_HOLD_MS = 4000L
/** A shared place's point from the Google page may be this far from its address before the address wins. */
private const val SHARED_PAGE_MAX_OFF_M = 3_000.0
/** Routing data: checked at most this often after a route (ms), and refreshed when older than this (days). */
private const val ROUTING_CHECK_MS = 12 * 3600_000L
private const val ROUTING_MAX_AGE_DAYS = 35
/** T5 (on tour, edge of the detailed map) is checked again after moving this far. */
private const val T5_STEP_M = 300.0
/** Free look in Navigate ends after walking this far from where the map was turned / moved by hand. */
private const val FREE_LOOK_BACK_M = 25.0
