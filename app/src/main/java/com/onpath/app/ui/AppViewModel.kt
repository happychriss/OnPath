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
import com.onpath.app.data.RoutingDataNotice
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.SportType
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
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.WaypointInsertion
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.onpath.app.routing.ArrivalGate
import com.onpath.strings.*
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
    /** Rides not counted (vehicle detection): drawn grey dashed (bus / car) or purple dashed (lift) beside the track. */
    val rides: List<Pair<List<GeoPoint>, com.onpath.app.rides.RideKind>> = emptyList(),
    /**
     * Grey INFO line in the track panel when opening the track changed the activity ("Activity set to MTB from the
     * file"); stays until the track is closed / replaced or the user picks an activity.
     */
    val note: String? = null,
    /** Grey INFO line after a GPX import that skipped parts of the file (the details are in Diagnostics); stays with the track. */
    val skippedNote: String? = null,
) {
    val points: List<GeoPoint> get() = segments.flatten()
}

/** What "End tour" asks: the short question, or the ride summary when a bus / car / lift was found. */
sealed interface EndAsk {
    data object Plain : EndAsk
    data class Rides(val tourId: Long, val summary: com.onpath.app.rides.RideView.Summary) : EndAsk
}

/** What "Replace current route?" / "Save first" loads afterwards: a saved route or a plan from a link (A-005). */
sealed interface PlanToOpen {
    data class Saved(val tour: Tour) : PlanToOpen
    data class Shared(val plan: com.onpath.app.routing.SharedPlan) : PlanToOpen
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
    val prefs = Prefs(app)
    private val db = TourDb.get(app)
    // time estimate state, declared before init {} uses it (recalc / refreshTours)
    init {
        // pace is learned per tour only (owner): drop the cross-tour pace older installs stored
        try { if (prefs.dropOldLearnedPace()) Problems.info("time", "Pace learned across tours removed (learning per tour only)") }
        catch (e: Exception) { Problems.warn("time", "Old learned pace could not be removed; it is not used", e) }
    }

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
            withContext(Dispatchers.Main) { packageCheck = p; if (note.isNotEmpty() && errorCard == null) sayError(note.trim(), NoticePlace.BACKGROUND) }
        }
    }
    var dataScreenDismissed by mutableStateOf(false)

    /** The APK ships the overview map's base map (map/Overview.kt) – a fresh install can open on the map. */
    private val overviewBundled: Boolean by lazy { "base.pmtiles" in com.onpath.app.map.Overview.bundled(getApplication()) }

    /**
     * Missing files that keep the app on the missing-data screen: none when the overview is in the APK and map cells can
     * be downloaded (the map comes where the user is or plans); otherwise [missing] (DataFiles.blocking).
     */
    val blockingMissing: List<DataFiles.Check> get() = DataFiles.blocking(missing, overviewBundled, cells.downloadsAvailable)

    // --- screens and mode ---
    // messages (declared before routesOpen / showSettings, whose setters clear their lines) – see [say]
    /** The light error card over the map (⚠, red edge, OK); null = none. Never cleared by itself. */
    var errorCard by mutableStateOf<String?>(null)
    /** The line at the top of My routes; goes when My routes is left. */
    var routesNote by mutableStateOf<Notice?>(null)
    /** The line at the top of the open Settings page; goes when the page changes or Settings closes. */
    var settingsNote by mutableStateOf<Notice?>(null)
    private var routesShown by mutableStateOf(false)
    /** My routes list (☰ → My routes) is open. */
    var routesOpen: Boolean
        get() = routesShown
        set(v) { if (!v) routesNote = null; routesShown = v } // the line lasts until My routes is left
    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes.md. */
    var mode by mutableStateOf(AppMode.PLAN)
        private set
    /** Navigate → ⚙: the navigation settings sheet is open. */
    var navSettingsOpen by mutableStateOf(false)
    /** Zoom the map takes when Navigate starts ([AppMode.navStartZoom]); null once taken (then the user's zoom stays). */
    var navStartZoom by mutableStateOf<Double?>(null)
    /** Map taps may add, move or insert points (Plan with no track shown). */
    val canEditMap: Boolean get() = mode == AppMode.PLAN && shown == null
    /** "You have arrived – end tour?" is open. */
    var arrivalAsk by mutableStateOf(false)
    /** When to ask "You have arrived" (round trips, poor fixes, ask again after leaving); see [ArrivalGate]. */
    private val arrivalGate = ArrivalGate()
    /** POI whose details sheet is open (F-MAP-09). */
    var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    // --- activity ---
    // Every app start begins with the last chosen activity and hiking level (owner; fresh install Hike T2).
    var activity by mutableStateOf(prefs.activity)
        private set
    var sac by mutableStateOf(prefs.sac)
        private set
    // --- settings (Settings page) ---
    var settings by mutableStateOf(prefs.settings)
        private set
    private var settingsShown by mutableStateOf(false)
    var showSettings: Boolean
        get() = settingsShown
        set(v) { if (!v) settingsNote = null; settingsShown = v } // the line lasts until Settings is left
    /** Open topic of Settings (list of topics → pages); null = the list. */
    private var page by mutableStateOf<SettingsPage?>(null)
    var settingsPage: SettingsPage?
        get() = page
        set(v) {
            if (v != SettingsPage.OFFLINE_MAPS) offlineResult = null // the result line lasts until the page is left
            if (v != page) settingsNote = null
            if (v == SettingsPage.DIAGNOSTICS || page == SettingsPage.DIAGNOSTICS) markDiagnosticsSeen()
            page = v
        }

    /** Developer tools in Diagnostics (owner): debug builds always, else after 7 taps on the version. */
    var devToolsShown by mutableStateOf(DevToolsUnlock.shown(com.onpath.app.BuildConfig.DEBUG, prefs.devToolsUnlocked))
        private set

    fun unlockDevTools() {
        prefs.devToolsUnlocked = true
        devToolsShown = true
        Problems.info("ui", "Developer tools unlocked (7 taps on the version)")
    }

    /** Last visit to Diagnostics: a serious problem after it shows red on the Settings list (owner). */
    var diagnosticsSeenMs by mutableStateOf(prefs.diagnosticsSeenMs)
        private set

    private fun markDiagnosticsSeen() {
        diagnosticsSeenMs = System.currentTimeMillis()
        prefs.diagnosticsSeenMs = diagnosticsSeenMs
    }

    /** Opens Settings, at [page] if given (map credits → About). */
    fun openSettings(page: SettingsPage? = null) { settingsPage = page; showSettings = true }

    /** Back in Settings: a topic page returns to the list, the list closes Settings. */
    fun settingsBack() { if (settingsPage != null) settingsPage = null else showSettings = false }

    // --- Navigate orientation + next turn (see NavigateScreen, MapController.setCamera) ---
    /** Navigate: heading-up (walking direction up) or north-up; persisted in settings. */
    val navHeadingUp: Boolean get() = settings.navHeadingUp

    /** Walking direction up – one persisted setting for Plan and Navigate (owner: switching modes keeps the compass choice). */
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
            mode == AppMode.NAVIGATE && headingUp && !followMe && hasFix -> { followMe = true; recentreTick++ }
            turningWithMe || mapTurned -> {
                setHeadingUp(false)
                // Following north up turns the map to north itself; an extra north-up camera move from the app would
                // make MapLibre drop the following again (it ends tracking on every app camera move).
                if (mode == AppMode.NAVIGATE && hasFix) followMe = true else northTick++
            }
            !hasFix -> sayError(Txt.s(Res.string.msg_no_position_turn), NoticePlace.MAP) // a tap must not do nothing
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
        if (followMe) { scrubWasFollowing = true; followMe = false; Problems.trace("map", "Following paused: finger on the elevation profile") }
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
        appVisible = true; appOnScreen = true
        cells.setForeground(true)
        sync.request("back in the app")
        if (mode == AppMode.PLAN) t3Pending = true // T3: the next usable fix checks the own area
    }

    /**
     * The app left the screen. Map areas still on their way keep loading through MapLoadService (owner),
     * started when they began; otherwise nothing is polled in the background (map-cells.md §8).
     */
    fun onLeaveApp() { appVisible = false; appOnScreen = false; cells.setForeground(false) }

    /** The app is on screen (Compose state: MapHost catches up with newer map data on return). */
    var appOnScreen by mutableStateOf(true)
        private set

    /** The app is on screen (Android allows starting the map loading service only then). */
    private var appVisible = true

    // ---------------- map cells (docs/design/map-cells.md §9, §19) ----------------

    /** Downloaded map cells: requests, states, installed list (UI: card, Settings list – see map-cells.md §19). */
    val cells: com.onpath.app.cells.MapCells by lazy { com.onpath.app.cells.AppCells.get(getApplication()) }

    init {
        // map areas start loading → MapLoadService keeps it going with the screen off / app left (owner)
        viewModelScope.launch {
            var wasLoading = false
            cells.states.collect { st ->
                val loading = st.values.any(com.onpath.app.cells.MapLoadService::loading)
                if (loading && !wasLoading && appVisible) com.onpath.app.cells.MapLoadService.ensure(getApplication())
                wasLoading = loading
            }
        }
    }

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

    // ---------------- demo location (Developer tools → Demo, recording/DemoLocation) ----------------

    /** What "Demo: ride" would ride: the planned route, else the shown track (a GPX file / tour); null = nothing. */
    fun demoLine(): Pair<String, List<GeoPoint>>? {
        route?.points?.takeIf { it.size >= 2 }?.let { return "planned route" to it }
        shown?.let { t -> t.segments.flatten().takeIf { it.size >= 2 }?.let { return "track \"${t.name}\"" to it } }
        return null
    }

    /** Rides [demoLine] from its start at the activity's modelled speed (user's pace); real GPS is ignored meanwhile. */
    fun startDemoRide() {
        val (label, line) = demoLine() ?: return
        val path = com.onpath.app.recording.DemoPath.timed(line, activity, planFactors())
        if (path == null) { sayInfo("This line is too short for a demo ride.", NoticePlace.SETTINGS); return }
        com.onpath.app.recording.DemoLocation.startRide(path, label)
    }

    /** Stands at the map centre (non-moving shots). */
    fun startDemoAtMapCentre() {
        val (lat, lon, _) = initialCamera()
        com.onpath.app.recording.DemoLocation.jumpTo(lat, lon, null, "map centre")
    }

    /** Settings → Diagnostics → "Map cells (debug)": request the cell under the map centre (own-point rule). */
    fun requestCellAtMapCentre() {
        val (lat, lon, _) = initialCamera()
        val c = com.onpath.app.cells.CellId.at(lat, lon) ?: return
        val added = cells.request(listOf(c), com.onpath.app.cells.CellReason.POINT)
        sayInfo(if (added.isEmpty()) "Cell ${c.id} is already on the phone or requested." else "Cell ${c.id} requested – see the list below.",
            NoticePlace.SETTINGS)
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

    /** "Detailed map for here?" – asked in the blue strip when the app opens where there is no detailed map. */
    data class HereAsk(val at: GeoPoint, val accuracyM: Float?, val own: com.onpath.app.cells.CellId, val regions: Int, val bytes: Long,
                       /** The server must build it first (a few minutes); false = status unknown. */
                       val mustBuild: Boolean = false)
    var hereAsk by mutableStateOf<HereAsk?>(null)
        private set
    /** Areas the user said "Not now" to (this app run): not asked again until a point is set there. */
    private val hereDeclined = HashSet<com.onpath.app.cells.CellId>()

    /**
     * The answer to [hereAsk]: Load = the map for here in the background (no big loading window – the user only opened
     * the app); Not now = this area is not asked again in this run (setting a point there loads it as always).
     */
    fun answerHereAsk(load: Boolean) {
        val a = hereAsk ?: return
        hereAsk = null
        Problems.trace("cells", "Map for here: " + if (load) "load" else "not now")
        if (!load) { hereDeclined += a.own; return }
        loadHere(a.at, a.accuracyM)
    }

    /** The map for here in the background (no loading window – the user only opened the app). */
    private fun loadHere(at: GeoPoint, accuracyM: Float?) {
        safeCells("position") { showCellJob(cellTriggers.appOpened(at, accuracyM)?.copy(own = null)?.tagged(LoadTag(LoadWhy.POINT))) }
        routingFor(listOf(at), wait = false)
    }
    /** The first-start card ("Allow location to get the detailed map …") was hidden with × (this app run). */
    private var firstStartHidden by mutableStateOf(false)
    /** "Map downloads not available in this build" was shown in this app run (once is enough). */
    private var notAvailableShown = false

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
        routingForCells(j.cells, wait = j.own != null || viewWithoutDetail) // routing data comes with the map
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
            if (loadModalFor.isEmpty()) { loadProgress.clear(); loadHeld = 0; loadBySearch = false }
            if (j.why == com.onpath.app.cells.CellWhy.SEARCHED) loadBySearch = true
            loadModalFor = loadModalFor + j.cells
        }
    }

    // ---------------- the modal while the user waits for the map of their area ----------------

    /** Regions the modal waits for; empty = no modal (closed with Cancel / Close, or all there). */
    private var loadModalFor by mutableStateOf<Set<com.onpath.app.cells.CellId>>(emptySet())
    /** The open card's job was waited for in the modal: when it is all there it simply goes – no "Map ready" card. */
    private var jobWaited by mutableStateOf(false)
    private val loadProgress = mutableMapOf<com.onpath.app.cells.CellId, MapLoadProgress>()
    /** The loading window's time-based % (one per window, reset when it closes). */
    private val loadSmoother = LoadSmoother()
    /** Last % shown (never backwards). */
    private var loadHeld = 0
    /** A search / shared place opened (or joined) the modal: Cancel also removes its ring ([MapLoadCancel]). */
    private var loadBySearch = false
    /** A large part of the visible map has no detailed map (MapScreen, any zoom) – a route computed now is waited for. */
    var viewWithoutDetail = false

    /**
     * What the modal shows now ([now]: uptime ms; called about every second): in Plan, while some of the regions the user
     * waits for are not there yet. Null = no modal (and it is over when everything is there).
     */
    fun mapLoadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, now: Long): MapLoadUi? {
        if (mode != AppMode.PLAN) return null
        val focus = loadModalFor
        val map = if (focus.isEmpty() || !cells.downloadsAvailable) null else
            MapLoads.aggregate(focus, states, cells.installed.value.associate { it.id to it.totalBytes }, loadProgress, loadHeld, now)
                .also { if (it == null) loadModalFor = emptySet() else loadHeld = it.percent } // null: the map is there
        // routing data in the same modal: one % over both, closes when both are there
        val r = if (routingModal) routingData.status.also { if (it == null) routingModal = false } else null
        val raw = RoutingLoads.combine(map, r, combinedHeld)
        // the % follows the time, not the bytes (owner: quick routing data would race to 50 % and stand)
        val ui = when {
            raw == null -> { loadSmoother.reset(); null }
            raw.kind == MapLoadUi.Kind.LOADING || raw.kind == MapLoadUi.Kind.PREPARING ->
                raw.copy(percent = maxOf(combinedHeld, loadSmoother.next(now, raw.secondsLeft)))
            else -> raw.copy(percent = combinedHeld) // offline / Wi-Fi / failed: the bar stays where it was
        }
        combinedHeld = ui?.percent ?: 0
        return ui
    }

    /** Modal (failed) → Close: the modal goes; the card shows the failure with Retry. */
    fun hideLoadModal() { loadModalFor = emptySet(); routingModal = false }

    // ---------------- routing data (loaded by the app itself, also on a fresh install) ----------------

    /** Loads missing routing data tiles from brouter.de (mobile data too unless "Maps only on Wi-Fi"). */
    private val routingData: com.onpath.app.data.RoutingDataLoader by lazy {
        com.onpath.app.data.AppRouting.listen(routingListener)
        com.onpath.app.data.AppRouting.get(getApplication())
    }
    /** The shared loader's events (also parts the recording service loaded on a tour) → the UI. */
    private val routingListener = object : com.onpath.app.data.AppRouting.Listener {
        override fun installed(names: List<String>) { viewModelScope.launch(Dispatchers.Main) { routingDataArrived(names) } }
        override fun changed() { viewModelScope.launch(Dispatchers.Main) { routingDataChanged() } }
    }
    /** The loading modal waits for routing data too (point set, first start, search). */
    private var routingModal by mutableStateOf(false)
    /** Last % of the combined modal (never backwards). */
    private var combinedHeld = 0
    /** Settings → "Download routing data" tapped: its progress shows in the data line. */
    private var manualRouting: String? = null

    /** Routing data for [points] (a point, a position, a route): missing tiles load; [wait] = in the modal. */
    private fun routingFor(points: List<GeoPoint>, wait: Boolean) = safeRouting("trigger") {
        val miss = routingData.ensure(com.onpath.app.data.RoutingTiles.forPoints(points))
        if (wait && miss.isNotEmpty() && routingData.status != null && mode == AppMode.PLAN) routingModal = true
    }

    /** Routing data in the corridor of a route (the map's own T4 trigger covers the regions). Never in the modal. */
    private fun routingForLine(keys: List<String>) = safeRouting("route") {
        routingData.keepCorridor(keys) // parts of an older route's corridor go
        routingData.ensure(keys, corridor = true)
    }

    /**
     * What a computed route needs for its corridor – pure geometry and file checks, computed off the main thread (several
     * corridor walks per long route would hitch the UI). Null parts = that check failed (reported); the route stays.
     */
    private class PostRoute(val routingKeys: List<String>?, val tilesMissing: Boolean, val cells: List<com.onpath.app.cells.CellId>?)

    private fun postRoute(r: com.onpath.app.routing.PlannedRoute, pts: List<GeoPoint>): PostRoute {
        val line = if (r.points.size >= 2) r.points else pts
        val keys = try { com.onpath.app.data.RoutingTiles.forLine(line) } catch (e: Exception) {
            Problems.error("routing", "Routing data corridor of the route unknown; nothing loads for it", e); null
        }
        val missing = try { routingData.missing(com.onpath.app.data.RoutingTiles.forPoints(pts, 0.0)).isNotEmpty() } catch (e: Exception) {
            Problems.warn("routing", "Routing data check failed; notice not adjusted", e); false
        }
        val corridor = try { cellTriggers.corridorOf(r.points) } catch (e: Exception) {
            Problems.error("cells", "Map corridor of the route unknown; computed on the main thread", e); null
        }
        return PostRoute(keys, missing, corridor)
    }

    /**
     * The plan's route settled without a corridor ([line] null: cleared, long-route question, Cancel, not routed, routing
     * failed): corridor map regions and routing parts of older routes are dropped, nothing new loads. A computed route does this through T4 ([routingForLine], CellTriggers.routeComputed).
     */
    private fun corridorFollowsPlan(line: List<GeoPoint>?) {
        safeCells("corridor") { cellTriggers.planRoute(line) }
        safeRouting("corridor") {
            routingData.keepCorridor(if (line == null || line.size < 2) emptyList() else com.onpath.app.data.RoutingTiles.forLine(line))
        }
    }

    /** Routing data under these map regions (a region load is a trigger too). */
    private fun routingForCells(c: Collection<com.onpath.app.cells.CellId>, wait: Boolean) = safeRouting("regions") {
        val miss = routingData.ensure(com.onpath.app.data.RoutingTiles.forCells(c))
        if (wait && miss.isNotEmpty() && routingData.status != null && mode == AppMode.PLAN) routingModal = true
    }

    /** Routing data triggers never break planning. */
    private inline fun safeRouting(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { Problems.error("routing", "Routing data download ($what) failed; planning goes on", e) }
    }

    /** Main thread: progress / phase changed – the plan's notice follows (loading ↔ not yet). */
    private fun routingDataChanged() {
        val s = routingData.status
        if (RoutingDataNotice.isNotice(routeError)) routeError = RoutingDataNotice.text(s?.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING)
        val name = manualRouting ?: return
        when {
            s == null || name !in s.names -> {
                manualRouting = null; dataBusy = null
                if (routingData.exists(name)) sayInfo(Txt.s(Res.string.msg_routing_data_ready), NoticePlace.SETTINGS)
                else sayError(Txt.s(Res.string.msg_routing_data_stopped), NoticePlace.SETTINGS)
            }
            s.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING ->
                dataBusy = Txt.s(Res.string.msg_routing_data_progress, s.percent, mb(s.totalBytes))
            else -> {
                manualRouting = null; dataBusy = null
                sayError(Txt.s(if (s.phase == com.onpath.app.data.RoutingDataLoader.Phase.OFFLINE) Res.string.msg_routing_data_offline
                    else Res.string.msg_routing_data_stopped), NoticePlace.SETTINGS)
            }
        }
    }

    /**
     * Main thread: routing data arrived – the plan is computed again at once (no question: this is planning, not
     * re-routing a tour), so the straight line becomes a route with elevation.
     */
    private fun routingDataArrived(names: List<String>) {
        Problems.info("routing", "Routing data installed (${names.joinToString()}); plan computed again")
        recheckData()
        engine = null // a new engine reads the new tile
        // once after the batch, not per arriving part (a recalc can take up to a minute on a long plan)
        arrivedNames += names
        arrivalGateJob = gated(routingArrivalGate, arrivalGateJob, busy = { routingData.loading }) {
            val n = arrivedNames.size; arrivedNames.clear()
            if (RoutingLoads.recalcOnArrival(mode, recState.value != null, waypoints.size)) {
                Problems.trace("routing", "Plan computed again after $n routing part(s) arrived")
                recalc()
            }
        }
    }

    /**
     * [gate] got a change: a coroutine waits until it is due ([QuietGate]) and runs [action] once (main thread). An
     * already waiting coroutine ([running]) picks the change up. Returns the waiting coroutine.
     */
    private fun gated(gate: QuietGate, running: kotlinx.coroutines.Job?, busy: () -> Boolean, action: () -> Unit): kotlinx.coroutines.Job {
        gate.changed(android.os.SystemClock.elapsedRealtime())
        if (running?.isActive == true) return running
        return viewModelScope.launch {
            while (true) {
                val w = try { gate.waitMs(android.os.SystemClock.elapsedRealtime(), busy()) } catch (e: Exception) {
                    Problems.warn("ui", "Busy check failed; acting now", e); 0L
                } ?: break
                if (w == 0L) {
                    gate.reset()
                    try { action() } catch (e: Exception) { Problems.error("ui", "Delayed update failed", e) }
                    break
                }
                delay(w)
            }
        }
    }

    /**
     * Modal → Cancel (owner: "Cancel = stop", [MapLoadCancel]): the waited-for regions stop loading, no card
     * follows, a searched place's ring goes, points set stay, the camera stays.
     */
    fun cancelLoadModal() {
        val job = cellJob
        val o = MapLoadCancel.decide(loadModalFor, job, jobWaited, cells.installed.value.map { it.id }.toSet(), loadBySearch)
        Problems.trace("cells", "Map loading cancelled: ${o.stop.size} region(s) stopped" +
            (job?.let { ", job ${it.why.name.lowercase()}" } ?: "") + (if (o.clearSearchMark) ", search ring removed" else ""))
        if (o.stop.isNotEmpty()) safeCells("cancel") { cells.cancel(o.stop) }
        o.stop.forEach { loadProgress.remove(it) }
        loadModalFor = emptySet(); loadBySearch = false
        // routing data waited for in the same modal: one Cancel stops it too (the partial download stays for resuming)
        if (routingModal) { routingModal = false; safeRouting("cancel") { routingData.cancel() } }
        if (o.closeCard) { cellJob = null; mobileDeclined = false; jobWaited = false; firstStartHidden = true }
        if (o.clearSearchMark) { searchMark = null; searchPlace = null }
    }

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
        if (load) safeCells("tap") { showCellJob(cellTriggers.pointSet(a.at, com.onpath.app.cells.CellWhy.TAPPED).tagged(LoadTag(LoadWhy.POINT, a.at))) }
    }

    /** T1: the user set these points (hold → Start here / Go here, a stop, a moved point). */
    private fun cellsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) {
        // a point that makes the plan very long (owner) loads nothing until the
        // long-plan question is answered: Plan anyway → it loads then; Cancel → nothing was loaded
        if (after.size >= 2 && after.toList() != longRouteOk && com.onpath.app.routing.LongRoute.needsAsk(after.toList(), activity)) {
            longRouteDeferred = before.toList()
            Problems.trace("cells", "Very long plan: maps and routing data for the new point wait for the answer")
            return
        }
        longRouteDeferred = null
        val fresh = com.onpath.app.cells.CellTriggers.newPoints(before, after)
        if (fresh.isNotEmpty()) routingFor(fresh, wait = fresh.size <= 3)
        if (fresh.size > 3) return // a whole route loaded / restored: T4 covers its corridor
        for (p in fresh) safeCells("point") {
            val j = cellTriggers.pointSet(p)
            // a point set there answers "map for here?" and lifts an earlier "Not now" for those areas
            j?.cells?.let { c -> hereDeclined.removeAll(c.toSet()); if (hereAsk?.own in c) hereAsk = null }
            showCellJob(j.tagged(LoadTag(LoadWhy.POINT, p)))
        }
    }

    /**
     * Strip → "Load now over mobile data" (owner): the waiting cells of this card load now, once – the
     * "Maps only on Wi-Fi" setting stays.
     */
    fun cellCardMobileYes() {
        val job = cellJob ?: return
        val waiting = cells.waitingForMobileApproval().map { it.first }.filter { it in job.cells }
        Problems.trace("cells", "Load now over mobile data: ${waiting.size} waiting region(s) of the card")
        safeCells("mobile") { cells.approveMobile(waiting) }
    }

    /**
     * Loading modal → "Load now over mobile data" (owner): what the modal waits for (map regions and
     * routing data) loads once over mobile data; the setting stays.
     */
    fun loadModalOnMobile() {
        val waiting = cells.waitingForMobileApproval().map { it.first }.filter { it in loadModalFor }
        Problems.trace("cells", "Load now over mobile data: ${waiting.size} waiting region(s) of the loading modal" +
            if (routingModal) " + routing data" else "")
        if (waiting.isNotEmpty()) safeCells("mobile") { cells.approveMobile(waiting) }
        if (routingModal) safeRouting("mobile") { routingData.loadOnMobileOnce() }
    }

    /** Card → "Only on Wi-Fi": the question goes, the card says the areas wait for Wi-Fi. */
    fun cellCardWifiOnly() { mobileDeclined = true }

    /** Card → "Retry": ask again for the cells that failed. */
    fun cellCardRetry() {
        safeRouting("retry") { routingData.retry(force = true) }
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

    /** Offline maps → "Clear all" is not offered while a tour runs (its map must not change under the user's feet). */
    val mapsLocked: Boolean get() = recState.value != null || mode == AppMode.NAVIGATE

    /**
     * Settings → Offline maps → "Clear all" (owner): every downloaded
     * map area and every waiting request goes, the world overview stays; the area of the position (T3) and the next
     * point set load again as on a new install. Not during a tour.
     */
    fun clearAllMaps() = removeAreas("clear all") {
        if (mapsLocked) { Problems.info("cells", "Clear all refused: a tour runs"); return@removeAreas }
        val bytes = offlineBytes(cells.installed.value.map { it.id })
        val all = cells.clearAll()
        if (all.isNotEmpty()) removeRoutingParts(all, emptyList())
        cleanupPreview = null
        closeCellCard(); t3Pending = true
        // the user confirmed it on the Offline maps page: the result is a plain line there, nothing on the map (owner)
        offlineResult = OfflineResult.removed(all.size, bytes, afterTour = false)
    }

    /**
     * Settings → Offline maps → "Clear all" / "Clean up" remove: a failure keeps a persistent message (the line on the
     * page is only for success).
     */
    private inline fun removeAreas(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            Problems.error("cells", "Offline maps: $what failed", e)
            offlineResult = null
            sayError(Txt.s(Res.string.msg_maps_remove_failed), NoticePlace.SETTINGS)
        }
    }

    /** Bytes of [ids] on the phone (map + routing data), as Offline maps lists them. */
    private fun offlineBytes(ids: List<com.onpath.app.cells.CellId>): Long {
        val routing = routingBytesCache?.second.orEmpty()
        val set = ids.toSet()
        return cells.installed.value.filter { it.id in set }.sumOf { it.totalBytes + (routing[it.id] ?: 0L) }
    }

    /** Offline maps: what the last Clean up / Clear all removed ("37 areas removed · 450 MB freed"); until the page is left. */
    var offlineResult by mutableStateOf<String?>(null)

    /** Settings → Offline maps → "Clean up": what would go, shown before anything is removed ([confirmCleanup]). */
    fun prepareCleanup() {
        val installed = cells.installed.value
        val removing = cells.removing()
        val planLine = route?.points?.takeIf { it.size >= 2 } ?: waypoints.toList()
        val rec = recState.value
        val tour = rec?.track.orEmpty()
        val followed = rec?.nav?.line.orEmpty()
        val pos = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
        val routingBytes = routingBytesCache?.second.orEmpty()
        viewModelScope.launch {
            val preview = try {
                withContext(Dispatchers.IO) {
                    val saved = db.listTours().filter { it.kind != com.onpath.app.data.TourKind.RECORDED }
                        .map { t -> db.loadSegments(t.id).flatten().map { GeoPoint(it.lat, it.lon) } }
                        .filter { it.isNotEmpty() }
                    val inputs = com.onpath.app.cells.MapCleanup.Inputs(planLine, saved, tour, followed, pos)
                    val sizes = installed.filter { it.id !in removing }.map { it.id to it.totalBytes + (routingBytes[it.id] ?: 0L) }
                    val plan = com.onpath.app.cells.MapCleanup.plan(sizes, inputs)
                    CleanupPreview(plan.remove, plan.removeBytes, buildList {
                        if (planLine.isNotEmpty()) add(Txt.s(Res.string.msg_cleanup_keeps_plan))
                        if (saved.isNotEmpty()) add(Txt.s(Res.string.msg_cleanup_keeps_saved))
                        if (rec != null) add(Txt.s(Res.string.msg_cleanup_keeps_tour))
                        if (pos != null) add(Txt.s(Res.string.msg_cleanup_keeps_position))
                    })
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Problems.error("cells", "Clean up could not work out which map areas are needed; nothing removed", e)
                sayError(Txt.s(Res.string.msg_cleanup_not_possible), NoticePlace.SETTINGS)
                return@launch
            }
            Problems.trace("cells", "Clean up preview: ${preview.remove.size} region(s) could go (${preview.bytes / 1_048_576} MB); keeps " +
                preview.keeps.joinToString().ifEmpty { "nothing in use" })
            cleanupPreview = preview
        }
    }

    /** "Clean up" → Remove: the areas of [cleanupPreview] go (after the tour if one runs); routing data they alone need too. */
    fun confirmCleanup() {
        val p = cleanupPreview ?: return
        cleanupPreview = null
        if (p.remove.isEmpty()) return
        removeAreas("clean up") {
            val kept = cells.installed.value.map { it.id }.filter { it !in p.remove }
            cells.delete(p.remove, "Clean up in Settings")
            removeRoutingParts(p.remove, kept)
            offlineResult = OfflineResult.removed(p.remove.size, p.bytes, afterTour = mapsLocked)
        }
    }

    /** Areas with the detailed map (installed cells, region package) for the style's overview cover. */
    fun detailedAreas(): List<DoubleArray> = cells.detailedAreas()

    /** Boxes of the installed region packages (z7–9, map-cells.md §20): the style's region view there. */
    fun packageAreas(): List<DoubleArray> = cells.packageAreas()

    /** True when a large part of the visible box has no detailed map ([OverviewHintRule], any zoom). */
    fun overviewOnly(south: Double, west: Double, north: Double, east: Double): Boolean = try {
        OverviewHintRule.show(OverviewHintRule.MIN_ZOOM,
            OverviewHintRule.samples(south, west, north, east).map { (lat, lon) -> cells.hasDetailedMap(GeoPoint(lat, lon)) })
    } catch (e: Exception) {
        Problems.warn("cells", "Detailed-map check failed; overview hint hidden", e); false
    }

    /** Every fix: T3 (first usable fix after start / return in Plan). */
    private fun cellsForFix(loc: android.location.Location) {
        val p = GeoPoint(loc.latitude, loc.longitude)
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val onTour = recState.value != null || mode == AppMode.NAVIGATE
        if (t3Pending && startModeKnown && !onTour && (acc == null || acc <= com.onpath.app.cells.CellTriggers.T3_MAX_ACCURACY_M)) {
            t3Pending = false
            // owner (e.g. in a car): opening the app loads the map for here silently only when the server
            // has it ready (seconds); when it must be built first (minutes) the blue strip asks. No loading window.
            safeCells("position") {
                val want = cellTriggers.preview(p)
                val own = want.firstOrNull()
                if (own == null) { hereAsk = null; return@safeCells }
                if (!cells.downloadsAvailable || own in hereDeclined) return@safeCells
                viewModelScope.launch {
                    val built = withContext(Dispatchers.IO) { cells.builtOnServer(want) }
                    if (built != null && built.containsAll(want)) {
                        Problems.trace("cells", "Map for here ready on the server (${want.size} area(s)) – loading it quietly")
                        loadHere(p, acc)
                    } else {
                        hereAsk = HereAsk(p, acc, own, want.size,
                            MapLoads.estimate(want, cells.states.value, cells.installed.value.associate { it.id to it.totalBytes }),
                            mustBuild = built != null)
                        Problems.trace("cells", "Asking whether to load the map for here (${want.size} area(s), " +
                            (if (built == null) "server status unknown" else "${want.size - built.size} to be built") + ")")
                    }
                }
            }
        }
        // T5 (on tour, near the edge of the map) runs in the recording service – also with the screen off
    }

    /**
     * After a route was computed in Plan (owner: "at the moment I plan, I want a recent map"; nothing is updated while
     * the app is idle): newer versions of the installed map areas along it (quiet; the map is veiled for
     * the seconds a ready one loads) and newer routing data (then the plan is computed again).
     */
    private fun freshenAfterRoute(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?) {
        safeCells("fresh") {
            val asked = cellTriggers.freshenAlong(line, corridor)
            tagLoads(asked, LoadTag(LoadWhy.REFRESH))
            if (asked.isNotEmpty()) Problems.info("cells", "Checking ${asked.size} map area(s) along the route for a newer version")
        }
        checkRoutingData(line)
    }

    // ---------------- background downloads: visible and stoppable (owner) ----------------

    private val bgProgress = BackgroundProgress()
    /** Per map region of the strip: its time-based % while the server prepares it ([MapLoadProgress]). */
    private val bgCellProgress = HashMap<com.onpath.app.cells.CellId, MapLoadProgress>()
    /** The strip / chip was tapped: what loads in the background, with "Cancel all". */
    var downloadDetailsOpen by mutableStateOf(false)
    /** The Plan strip is on screen (hints below the search bar move down). */
    var backgroundStripShown by mutableStateOf(false)
    /** Height of that strip in dp (measured); the error card goes below it ([MessagePlacement]). */
    var backgroundStripHeightDp by mutableStateOf(0)

    /**
     * What loads in the background now (map regions, routing data the user does not wait for in the modal); null =
     * nothing, or the loading modal is open. Called about every second by the strip / chip.
     */
    fun backgroundDownloadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>): BackgroundDownloadUi? = try {
        val modal = loadModalFor.isNotEmpty() || routingModal
        // newer versions of installed areas: told in the strip / its details (no second indicator on the map)
        val installed = if (modal) emptySet() else cells.installed.value.map { it.id }.toSet()
        // the server's preparing time moves the % too (else it stands at 0 % for the minutes an area is prepared)
        val cellItems = if (modal) emptyList() else BackgroundProgress.cellItems(states, emptySet(), installed, bgCellProgress,
            android.os.SystemClock.elapsedRealtime(), cells::knownSize)
        val r = if (modal) null else routingData.status?.takeIf { it.phase == com.onpath.app.data.RoutingDataLoader.Phase.LOADING }
        val items = cellItems + listOfNotNull(r?.let { BackgroundProgress.Item("routing", it.doneBytes, it.totalBytes) })
        val newer = if (modal) 0 else states.count { (c, s) ->
            c in installed && BackgroundProgress.loading(s) && s != com.onpath.app.cells.CellState.Requested
        }
        val eta = (MapUpdates.of(states, installed) as? MapUpdateUi.Preparing)?.minutes
        val groups = if (modal) emptyList() else loadGroups(states, cellItems, installed, r)
        bgProgress.update(items, cellItems.size, r?.names?.size ?: 0, newer, eta, groups)
    } catch (e: Exception) {
        Problems.warn("cells", "Background download status unknown; strip hidden", e); null
    }

    // ---- names of what loads (owner: "⬇ Bad Waldsee · 34 %"; map areas only have grid ids) ----

    /** Why each map area was asked for, set where the app asks (point, search, route, newer map); main thread only. */
    private val loadTags = HashMap<com.onpath.app.cells.CellId, LoadTag>()
    /** Larger towns of the world overview (read once off the main thread; null = not read yet). */
    @Volatile private var overviewTowns: List<com.onpath.app.map.OverviewTowns.Town>? = null
    private var overviewTownsAsked = false
    /** Nearest town per area ("" = none near). */
    private val townOfCell = HashMap<com.onpath.app.cells.CellId, String>()

    /** Remembers why [cells] load; a more important reason (route > point > newer) is kept. */
    private fun tagLoads(cells: Collection<com.onpath.app.cells.CellId>, tag: LoadTag) {
        val t = tag.copy(sinceMs = android.os.SystemClock.uptimeMillis())
        for (c in cells) { val old = loadTags[c]; if (old == null || t.why.ordinal <= old.why.ordinal) loadTags[c] = t }
    }

    private fun com.onpath.app.cells.CellJob?.tagged(tag: LoadTag): com.onpath.app.cells.CellJob? = also { j -> j?.let { tagLoads(it.cells, tag) } }

    /** The overview town nearest to [c]'s centre; null while the towns are read or none is near. */
    private fun townNear(c: com.onpath.app.cells.CellId): String? {
        val towns = overviewTowns
        if (towns == null) {
            if (!overviewTownsAsked) {
                overviewTownsAsked = true
                viewModelScope.launch(Dispatchers.IO) {
                    overviewTowns = try { com.onpath.app.map.OverviewTowns.load(getApplication()) } catch (e: Exception) {
                        Problems.warn("cells", "Town names of the world overview not readable; loading areas named \"map area\"", e); emptyList()
                    }
                }
            }
            return null
        }
        return townOfCell.getOrPut(c) { com.onpath.app.map.OverviewTowns.nearest(towns, c.center)?.name.orEmpty() }.ifEmpty { null }
    }

    /** What loads, by name: the reason the app knows ([loadTags]), else the nearest overview town. */
    private fun loadGroups(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, items: List<BackgroundProgress.Item>,
                           installed: Set<com.onpath.app.cells.CellId>, paths: com.onpath.app.data.RoutingDataLoader.Status?): List<LoadGroup> {
        val bytes = items.associate { it.key to it.total }
        val now = android.os.SystemClock.uptimeMillis()
        // reasons of areas no longer loading go (a moment of grace: a fresh request may not show as loading yet)
        loadTags.entries.removeAll { (c, t) -> !(states[c]?.let(BackgroundProgress::loading) ?: false) && now - t.sinceMs > 10_000 }
        val job = cellJob?.takeIf { it.own != null }
        val touring = recState.value != null
        val entries = states.filter { (c, s) -> "cell:${c.id}" in bytes && BackgroundProgress.loading(s) }.map { (c, s) ->
            val tag = loadTags[c]
            val why = tag?.why ?: when { c in installed -> LoadWhy.REFRESH; touring -> LoadWhy.AHEAD; else -> null }
            val name = tag?.name ?: tag?.at?.let { stopName(it) ?: nearbyNames[it] }
            LoadEntry(why, name, townNear(c), waiting = job != null && c in job.cells, bytes = bytes["cell:${c.id}"] ?: 0L,
                phase = LoadNames.phaseOf(s))
        }
        return LoadNames.groups(entries + listOfNotNull(paths?.let { LoadEntry(null, paths = true, bytes = it.totalBytes) }))
    }

    /** Settings → Offline maps / the strip's details → "Cancel all": every waiting download goes; installed maps stay. */
    fun cancelAllDownloads() {
        safeCells("cancel all") { cells.cancelAll() }
        safeRouting("cancel all") { routingData.cancel() }
        loadModalFor = emptySet(); routingModal = false
        cellJob = null
        downloadDetailsOpen = false
    }

    /** The user drags the map (true) / the map came to rest (false): new map areas are not joined meanwhile. */
    fun mapGesture(on: Boolean) { safeCells("gesture") { cells.userBusy = on } }

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
            val index = try { search.offlineIndex() } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { Problems.info("cells", "No place index for map area names; coordinates shown", e); return@launch }
            val names = withContext(Dispatchers.IO) {
                todo.associateWith { c ->
                    com.onpath.app.search.RouteNamer.label(c.center, com.onpath.app.search.RouteNamer.placesNear(index, listOf(c.center)))
                }.filterValues { it != null }.mapValues { it.value!! }
            }
            areaNames = areaNames + names
        }
    }

    fun offlineMapsUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                      installed: List<com.onpath.app.cells.CellStore.InstalledCell>): OfflineMapsUi {
        val fmt = SimpleDateFormat(Txt.s(Res.string.msg_date_pattern_area), Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val sorted = installed.sortedBy { areaNames[it.id] ?: it.id.id }
        // the map of a region includes its routing data (owner); a part shared by regions counts once.
        // Computed once per change of the regions or the routing data – not on every progress tick
        val key = sorted.map { it.id } to routingData.store.version
        val routingBytes = routingBytesCache?.takeIf { it.first == key }?.second ?: try {
            com.onpath.app.data.RoutingTiles.bytesPerCell(sorted.map { it.id }) { k ->
                com.onpath.app.routing.Rd5Parts.Part.parse(k)?.let { routingData.store.sizeOnPhone(it) } ?: 0L
            }.also { routingBytesCache = key to it }
        } catch (e: Exception) {
            Problems.warn("routing", "Routing data sizes for Offline maps not readable; map sizes only", e); emptyMap()
        }
        val removing = try { cells.removing() } catch (e: Exception) { Problems.warn("cells", "Areas being removed unknown", e); emptySet() }
        val areas = sorted.map { c ->
            val built = c.built?.take(10)?.let { b -> runCatching { iso.parse(b) }.getOrNull() }
            val st = states[c.id]
            OfflineArea(c.id.id, areaNames[c.id] ?: coordName(c.id), c.totalBytes + (routingBytes[c.id] ?: 0L),
                fmt.format(built ?: Date(c.installedAt)),
                if (c.id in removing) Txt.s(Res.string.msg_area_removed_after_tour) else st?.takeIf { it !is com.onpath.app.cells.CellState.Ready && it != com.onpath.app.cells.CellState.None }?.label())
        }
        val ids = installed.map { it.id }.toSet()
        val loading = states.count { (c, s) -> c !in ids && s !is com.onpath.app.cells.CellState.Failed && s != com.onpath.app.cells.CellState.None }
        // what still waits to download – visible and stoppable (owner)
        val w = try { cells.waiting() } catch (e: Exception) { Problems.warn("cells", "Waiting downloads unknown", e); null }
        val r = try { routingData.status } catch (e: Exception) { Problems.warn("routing", "Routing downloads unknown", e); null }
        val waiting = WaitingDownloads(w?.regions ?: 0, r?.names?.size ?: 0,
            (w?.knownBytes ?: 0L) + (w?.unknownSize ?: 0) * BackgroundProgress.ESTIMATE_BYTES + (r?.let { it.totalBytes - it.doneBytes } ?: 0L))
        return OfflineMapsUi(cells.downloadsAvailable, areas, settings.mapsWifiOnly, loading, waiting.takeIf { it.any },
            cleanup = cleanupPreview, result = offlineResult, clearAllBlocked = if (mapsLocked) Txt.s(Res.string.msg_clear_all_blocked) else null)
    }

    private fun coordName(c: com.onpath.app.cells.CellId): String = c.center.let {
        "%.2f° %s, %.2f° %s".format(Locale.ROOT, kotlin.math.abs(it.lat), if (it.lat >= 0) "N" else "S",
            kotlin.math.abs(it.lon), if (it.lon >= 0) "E" else "W")
    }

    /** Settings → "Update maps now": ask for newer versions of every installed area. */
    fun updateMapsNow() = safeCells("update") {
        val all = cells.installed.value.map { it.id }
        val asked = cells.request(all, com.onpath.app.cells.CellReason.REFRESH, ownPoint = false)
        // the page's own result line (like a removal), not a message
        offlineResult = if (asked.isEmpty()) Txt.s(Res.string.msg_maps_already_checking)
            else Txt.p(if (settings.mapsWifiOnly) Res.plurals.msg_maps_checking_wifi else Res.plurals.msg_maps_checking, asked.size, asked.size)
    }

    fun deleteMapArea(id: String) {
        val c = com.onpath.app.cells.CellId.parse(id) ?: return
        val kept = cells.installed.value.map { it.id }.filter { it != c }
        safeCells("delete") { cells.delete(listOf(c), "deleted in Settings: ${areaNames[c] ?: c.id}") }
        removeRoutingParts(listOf(c), kept)
    }

    /**
     * Routing parts under deleted regions that no remaining region needs go too (owner: routing data is part
     * of a region's map). Parts loaded for a point or a route outside every region stay. Background, never during a tour.
     */
    private fun removeRoutingParts(deleted: List<com.onpath.app.cells.CellId>, kept: List<com.onpath.app.cells.CellId>) {
        if (recState.value != null) return // the tour's router may need them; they go with the next delete
        viewModelScope.launch(Dispatchers.IO) {
            safeRouting("delete") {
                val parts = com.onpath.app.data.RoutingTiles.unneeded(deleted, kept).mapNotNull { com.onpath.app.routing.Rd5Parts.Part.parse(it) }
                val freed = routingData.store.remove(parts)
                if (freed > 0) {
                    Problems.info("routing", "Routing data of deleted map areas removed (${freed / 1_048_576} MB, ${parts.joinToString()})")
                    withContext(Dispatchers.Main) { engine = null }
                }
            }
        }
    }
    /** Next turn ahead in Navigate (arrow on the map); null = none / off route / Plan mode. */
    var navTurn by mutableStateOf<com.onpath.app.routing.Turn?>(null)
    /** Navigate while cycling: the rest view covers the map, which gets no fixes meanwhile (ui/RestView.kt, SmartScreen). */
    var screenRest by mutableStateOf(false)

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
    /** Settings → Offline maps → "Clean up": what would be removed, shown until Remove / Cancel; null = no question. */
    var cleanupPreview by mutableStateOf<CleanupPreview?>(null)
    /**
     * Plan points the route does not reach (owner, [PlanGaps]): grey dashed piece + hollow point on the map,
     * one red line in the plan sheet – while it holds, never dismissed. Derived from the route of exactly these points.
     */
    val planGaps: List<PlanGap>
        get() = route?.takeIf { !routing }?.let { PlanGaps.of(waypoints.toList(), it.snapped) } ?: emptyList()

    // route warnings (RouteWarnings): declared before init {} – recalc() touches them (ViewModelInitOrderTest)
    /**
     * "Route changed for gravel bike – please check": the activity / T level changed and the new route differs
     * noticeably ([RouteWarnings.changedNoticeably]). Stays until the amber line is tapped or the plan changes – never
     * dismissed by time.
     */
    var routeChangedNotice by mutableStateOf<String?>(null)
        private set
    /** The route before the activity / T level change whose result is still pending (null = none). */
    private var activityBefore: PlannedRoute? = null
    /** The next [recalc] runs for an activity / T level change. */
    private var activityRecalc = false
    /** The list of spots (tap on the amber line) is open. */
    var spotsOpen by mutableStateOf(false)
        private set
    /** The piece of route of the spot tapped in that list, drawn amber on the map; null = none. */
    var spotLine by mutableStateOf<List<GeoPoint>?>(null)
        private set
    private var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>? = null

    // Bursts → one action; declared before init {} (ViewModelInitOrderTest).
    /** Map style reload after new map store generations: once per quiet period, never while the user drags. */
    private val styleGate = QuietGate(STYLE_QUIET_MS, STYLE_MAX_WAIT_MS)
    private var styleGateJob: kotlinx.coroutines.Job? = null
    /** The plan recomputed after routing data arrived: once after a batch, not per part. */
    private val routingArrivalGate = QuietGate(ARRIVAL_QUIET_MS, ARRIVAL_MAX_WAIT_MS, maxBusyMs = ARRIVAL_MAX_WAIT_MS)
    private var arrivalGateJob: kotlinx.coroutines.Job? = null
    private val arrivedNames = ArrayList<String>()
    /** Settings → Offline maps: routing bytes per region for (regions, routing data version) – see [offlineMapsUi]. */
    private var routingBytesCache: Pair<Pair<List<com.onpath.app.cells.CellId>, Int>, Map<com.onpath.app.cells.CellId, Long>>? = null

    // declared before init {}: init restores the saved plan and routes it at once (declared below, they are still null then)
    /** Cancel signal of the route being calculated (Cancel button; a newer request stops the older one). */
    private var routeCancel: com.onpath.app.routing.RouteCancel? = null
    private val ROUTING_SLOW_MS = 2_500L
    /** The route has been calculating for a while: the plan sheet offers Cancel. */
    var routingSlow by mutableStateOf(false)
        private set
    /** Estimated duration (s) of a plan longer than a day: "Plan it anyway?" (null = no question). */
    var longRouteAsk by mutableStateOf<Double?>(null)
        private set
    /** The very long plan the user said yes to (not asked again for the same points). */
    private var longRouteOk: List<GeoPoint>? = null
    /** The points before the edit that made the plan very long: its maps load only after "Plan anyway". */
    private var longRouteDeferred: List<GeoPoint>? = null
    /** The plan is left without a route on purpose (Cancel with nothing to take back); the sheet offers to calculate. */
    var notRouted by mutableStateOf<NotRouted?>(null)
        private set
    /** What the next [recalc] is for (set just before it): Cancel undoes only that. */
    private var nextCause: PlanStatus.Cause = PlanStatus.Cause.Other
    /** What the current / last routing run (or long-route question) was started by. */
    private var runCause: PlanStatus.Cause = PlanStatus.Cause.Other


    // --- saved state of the plan (menus / Manage routes) ---
    /** Name, tour id and points the plan was last saved or loaded as; the plan is unsaved when its points differ. */
    private var linkName by mutableStateOf(prefs.planSavedName)
    private var linkId = prefs.planSavedId
    private var savedPoints by mutableStateOf(prefs.planSavedPoints)
    /**
     * The plan still belongs to that saved route (RouteRules.linkedToSaved): after Clear or with every point replaced
     * it is a new plan – no saved name, Save asks for a new name; Undo back to the old points restores the link.
     */
    private val planLinked: Boolean get() = RouteRules.linkedToSaved(waypoints, savedPoints)
    /** Name the plan was saved / loaded as, while it still belongs to that route. */
    val savedName: String? get() = linkName.takeIf { planLinked }
    private val savedId: Long get() = if (planLinked) linkId else -1L
    /** The plan has a route worth keeping that differs from the last save / load (RouteRules.isUnsaved). */
    val planUnsaved: Boolean get() = RouteRules.isUnsaved(waypoints, savedPoints)
    /** "Save route" name dialog is open (always a new entry). */
    var saveDialog by mutableStateOf(false)
    /**
     * "Save route" choice is open: Save (overwrite the loaded / last saved route) or Save as new…; [SaveChoice.thenOpen]
     * is the route to load afterwards ("Save first" before loading), else null.
     */
    var saveChoice by mutableStateOf<SaveChoice?>(null)
    class SaveChoice(val thenOpen: PlanToOpen?)
    /** "Replace current plan?" is open for this route (a saved one or one from a plan link). */
    var replaceAsk by mutableStateOf<PlanToOpen?>(null)
    /** "Save first" was chosen: the name dialog saves the plan, then loads this route. */
    var saveFirst by mutableStateOf<PlanToOpen?>(null)
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

    /**
     * Navigate: where the user turned / moved the map by hand (free look, e.g. to match a mountain range; owner); after
     * walking on [FREE_LOOK_BACK_M] the map follows again by itself, compass / locate at once. null = not looking around.
     */
    private var freeLookAt: GeoPoint? = null

    /** Last finger-down on the map (MapHost); a "gesture" without a recent touch is not one. */
    var lastMapTouchMs = 0L

    /**
     * Finger down on the map. Plan with walking direction up: stop turning / following at once, so a route point stays
     * exactly under the finger (a point cannot be held to delete it while the map keeps turning).
     */
    fun onMapTouch() {
        lastMapTouchMs = android.os.SystemClock.uptimeMillis()
        if (mode == AppMode.PLAN && followMe && headingUp) followMe = false
    }

    /**
     * MapLibre stopped following. It does that for finger gestures and also for every camera move of the app itself
     * (fly to a search result, show a route, profile scrub) – both mean "not following any more". Only a stop right
     * after a real touch starts free look (back to following after walking on); a stop without a touch is not ignored –
     * that would undo the app's own camera moves.
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
    /**
     * Says [text] where it belongs (owner: no dark message bar): a line in My routes / the open Settings page where the
     * user acted ([place]); an error without such a place on the light [errorCard] (stays until OK); an INFO without a
     * place only in the problem log. Routing: [NoticeRouting].
     */
    fun say(text: String, kind: NoticeKind, place: NoticePlace) {
        when (NoticeRouting.surface(kind, place, routesOpen, showSettings)) {
            NoticeSurface.ROUTES_LINE -> routesNote = Notice(text, kind)
            NoticeSurface.SETTINGS_LINE -> settingsNote = Notice(text, kind)
            NoticeSurface.ERROR_CARD -> errorCard = text
            NoticeSurface.LOG_ONLY -> Problems.trace("ui", "${kind.name} for ${place.name.lowercase()} not shown (place closed): $text")
        }
    }
    fun sayError(text: String, place: NoticePlace) = say(text, NoticeKind.ERROR, place)
    fun sayInfo(text: String, place: NoticePlace) = say(text, NoticeKind.INFO, place)
    /** Settings → Updates, or the update banner on the map. */
    private fun updatePlace() = if (showSettings) NoticePlace.SETTINGS else NoticePlace.MAP

    /** Work the user started that takes a moment: a spinner where it was started. */
    var busy by mutableStateOf<Busy?>(null)
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

    /** The router (on [routingDispatcher]); only a real one is kept, so a failed start is retried next time. */
    private fun routingEngine(): RoutingEngine =
        engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir).also { if (EngineProvider.isRealRouter) engine = it }

    private var routeJob: Job? = null
    private val requests = RouteRequests()
    /** The followed line was taken from this plan ("Start navigation"), so Clear removes it too. */
    private var followFromPlan = false
    /** All plan points were deleted during the tour, which ended the guidance; points back → followed again (PlanGuidance). */
    private var guidanceEndedByEmptyPlan = false
    /** The followed line is a shown track: "Continue navigation" keeps it instead of re-planning. */
    private var followIsTrack = prefs.followIsTrack
    /** The plan's points when a track was taken to follow ([PlanGuidance.planReplacesTrack]); null = no track / unknown. */
    private var planAtTrackFollow: List<GeoPoint>? = null
    /** "Continue navigation" re-plans: follow the next route result. */
    private var followNextRoute = false
    /** The plan's points were edited (add, delete, round trip, reverse, undo …): a running tour follows the result. */
    private var planEditPending = false

    // `by lazy` used from init (also from its IO jobs: refreshTours → withModelTime → planTimes) is declared here,
    // above that init – below it the delegate is still null (ViewModelInitOrderTest)
    /** Model times of saved planned routes (with way attributes), stored at save. */
    private val planTimes by lazy {
        com.onpath.app.time.PlanTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.PlanTimesStore.FILE))
    }
    private val searchStore by lazy { com.onpath.app.search.SearchStore(getApplication()) }
    val search by lazy {
        com.onpath.app.ui.search.SearchSession(viewModelScope, dataFiles.dir, getApplication<Application>().filesDir, searchStore,
            com.onpath.app.search.PhotonProvider(com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME)),
            areaPlan = { com.onpath.app.search.AreaPlan.of(dataFiles.dir) }) {
            val loc = tracker.location.value
            val (cLat, cLon, _) = initialCamera()
            // Position when known and near what the user looks at, else the map centre.
            val usePos = loc != null && Geo.distance(loc.latitude, loc.longitude, cLat, cLon) < 30_000
            com.onpath.app.ui.search.SearchContext(if (usePos) loc!!.latitude else cLat, if (usePos) loc!!.longitude else cLon,
                if (shown == null) route?.points else null)
        }
    }

    init {
        recalc()
        // every plan change is traced, so the diagnostics show what the user did
        Problems.trace("plan", "Plan restored at app start: ${waypoints.size} points" +
            (followName?.let { ", following \"$it\" (${followLine?.size ?: 0} line points)" } ?: ", nothing followed"))
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
                if (why != null) { RecordingService.startFailed.value = null; sayError(why, NoticePlace.BACKGROUND); if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN) }
            }
        }
        // Re-routing (service): follow the line the service navigates now (new route taken / original again).
        viewModelScope.launch {
            var saveFailedShown = false
            var recordingSeen: Long? = null
            RecordingService.state.collect { st ->
                if (st?.tourId != recordingSeen) {
                    if (st != null) Problems.trace("tour", "Tour ${st.tourId} recording (plan ${waypoints.size} points, " +
                        (if (followLine != null) "following ${followName ?: "a route"}" else "nothing followed") + ")")
                    else { Problems.trace("tour", "Tour $recordingSeen recording ended"); guidanceEndedByEmptyPlan = false }
                    recordingSeen = st?.tourId
                }
                if (st?.saveFailed == true && !saveFailedShown) {
                    saveFailedShown = true
                    sayError(Txt.s(Res.string.msg_track_save_failing), NoticePlace.BACKGROUND)
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
                    val discard = deleteFinished; deleteFinished = false
                    if (discard) {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { db.setDeleted(id, System.currentTimeMillis()) }
                                .onFailure { Problems.error("db", "The ended tour could not be moved to the recycle bin; it stays in My routes", it) }
                                .getOrDefault(false).also { if (it) Backups.tourChanged(getApplication(), id) }
                        }
                        finishedTour = null; finishedTimes = null
                        if (!ok) sayError(Txt.s(Res.string.msg_track_delete_failed_here), NoticePlace.MAP)
                    } else {
                        finishedTour = withContext(Dispatchers.IO) { db.getTour(id) }
                        finishedTimes = withContext(Dispatchers.IO) { finishedTour?.let { tourTimes(it) } }
                        finishedTour?.let { nameRecordedTour(it) }
                        // "Split into two tours": the parts after the rides get a place name too
                        val parts = RecordingService.finishedParts; RecordingService.finishedParts = emptyList()
                        parts.forEach { p -> withContext(Dispatchers.IO) { db.getTour(p) }?.let { nameRecordedTour(it) } }
                    }
                    refreshTours()
                    clearFollow()
                    arrivalAsk = false
                    // owner: after Save or Delete the tour is done – the plan leaves the map, saved or not
                    // (one Undo step brings it back)
                    if (waypoints.isNotEmpty()) clearPlan()
                    enter(AppMode.PLAN)
                }
            }
        }
    }

    fun initialCamera(): Triple<Double, Double, Double> = prefs.camera ?: Triple(46.67, 11.16, 12.0)
    fun saveCamera(lat: Double, lon: Double, zoom: Double) { prefs.camera = Triple(lat, lon, zoom) }

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
            .put("phone", com.onpath.app.setup.PhoneCheck.describe(phone))
            .put("demo", com.onpath.app.recording.DemoLocation.state.value?.let { d -> org.json.JSONObject().put("kind", d.kind.name)
                .put("label", d.label).put("factor", d.factor).put("paused", d.paused).put("alongM", d.alongM.toInt())
                .put("lengthM", d.lengthM.toInt()).put("ended", d.ended) } ?: org.json.JSONObject.NULL)
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
        return if (p.ok) "" else " " + Txt.s(Res.string.msg_package_mixed, p.mismatched.joinToString())
    }

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
                if (manual) UpdateState.Failed(Txt.s(Res.string.msg_update_check_failed)) else UpdateState.Idle
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
            sayInfo(Txt.s(Res.string.msg_update_allow_install), updatePlace())
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
                    sayError(Txt.s(Res.string.msg_update_download_failed), updatePlace())
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

    /** Settings → Diagnostics → Developer tools → "Reload map style" (after tools/dev/push_style.sh). */
    fun reloadMapStyle() {
        Problems.info("map", "Map style reloaded by hand (developer tools)")
        styleVersion++
    }

    /** Developer tools → test style "Remove": deletes dev/style.json, the normal style loads again. */
    fun removeTestStyle() {
        com.onpath.app.map.StyleLoader.removeDevStyle(dataFiles.dir)
        styleVersion++
    }

    /** Imports the onpath-data zip or a single data/style file picked by the user. */
    fun importData(uri: Uri) {
        if (dataBusy != null) return
        val app = getApplication<Application>()
        dataBusy = Txt.s(Res.string.msg_import_busy)
        viewModelScope.launch(Dispatchers.IO) {
            val msg: Pair<String, Boolean> = try {
                val name = displayName(uri) ?: "import.zip"
                val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open the file.")
                val r = com.onpath.app.data.DataImporter.import(input, name, dataFiles.dir) { f ->
                    dataBusy = Txt.s(Res.string.msg_import_busy_file, f)
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
                (r.summary + packageNote() +
                    if (newTiles && recState.value != null) " " + Txt.s(Res.string.msg_import_restart_after_tour) else "") to false
            } catch (e: com.onpath.app.data.DataImporter.ImportException) {
                Problems.warn("data", "Map data import rejected", e)
                com.onpath.app.debug.UserText.of(e, Txt.s(Res.string.msg_import_failed_unchanged)) to true
            } catch (e: Exception) {
                Problems.error("data", "Map data import failed; existing data kept", e)
                Txt.s(Res.string.msg_import_failed_damaged) to true
            }
            withContext(Dispatchers.Main) {
                dataBusy = null
                say(msg.first, if (msg.second) NoticeKind.ERROR else NoticeKind.INFO, NoticePlace.SETTINGS)
            }
        }
    }

    /**
     * Settings → Diagnostics → "Download routing data": the 1° parts at the map centre from brouter.de (normally they
     * come by themselves with the map – owner: no separate "routing data" for the user).
     */
    fun downloadRoutingData() {
        if (dataBusy != null) return
        val (lat, lon, _) = initialCamera()
        val keys = com.onpath.app.data.RoutingTiles.forPoints(listOf(GeoPoint(lat, lon)))
        val miss = try { routingData.missing(keys) } catch (e: Exception) {
            Problems.warn("routing", "Routing data check failed", e); keys
        }
        if (miss.isEmpty()) { sayInfo(Txt.s(Res.string.msg_routing_data_present), NoticePlace.SETTINGS); return }
        // the same loader as the automatic download (one writer per file); an explicit tap loads on mobile data too
        manualRouting = miss.first()
        dataBusy = Txt.s(Res.string.msg_routing_data_busy)
        safeRouting("manual") { routingData.ensure(miss, ignoreWifiOnly = true) }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        Problems.warn("data", "File name of the picked file unreadable; using the URI name", e)
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    // ---------------- activity ----------------

    /**
     * The activity chosen in the picker: the route is planned again; Cancel while it is calculated puts the previous
     * activity back. [userChange] false: taken over from elsewhere (the service switched, the switch sheet) – Cancel
     * then only stops.
     */
    fun selectActivity(a: Activity, userChange: Boolean = true) {
        noteActivityChange()
        nextCause = if (userChange) activityCause() else PlanStatus.Cause.Other
        activity = a; prefs.activity = a; recalc()
        clearShownNote()
    }

    /** A stored activity name; null for none or an unknown / old one (expected: the caller falls back). */
    private fun activityOrNull(name: String?): Activity? = name?.let { runCatching { Activity.valueOf(it) }.getOrNull() }

    /** "Activity set to … from the file" is over once the activity / T level changes again. */
    private fun clearShownNote() { shown?.let { if (it.note != null) shown = it.copy(note = null) } }

    /** Cause of an activity / T level change; a change still being calculated keeps the activity from before it. */
    private fun activityCause(): PlanStatus.Cause =
        (runCause as? PlanStatus.Cause.ActivityChange)?.takeIf { routing || longRouteAsk != null }
            ?: PlanStatus.Cause.ActivityChange(activity, sac)

    /** T level from the activity picker (Hike): remembered, the route is planned again at once. */
    fun selectSac(level: Int) {
        val l = level.coerceIn(1, 5)
        if (l == sac) return
        if (activity == Activity.HIKE) { noteActivityChange(); nextCause = activityCause() }
        sac = l; prefs.sac = l; if (activity == Activity.HIKE) recalc()
        clearShownNote()
    }

    // ---------------- planning ----------------

    fun updateSettings(s: AppSettings) {
        val paceChanged = s.hikePace != settings.hikePace || s.bikePace != settings.bikePace
        settings = s; prefs.settings = s
        cells.wifiOnly = s.mapsWifiOnly
        if (paceChanged) retime()
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_SETTINGS)
    }

    /**
     * One undoable change of the planned points. No vibration here: the plan vibrates only at the moment of a map hold
     * (PlanHaptics, owner), never after an edit.
     */
    private fun change(block: MutableList<GeoPoint>.() -> Unit) {
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
        routeBackAsk = RouteBack.asks(before, waypoints, recState.value != null, followLine != null, followIsTrack, myPosition())
        if (routeBackAsk) Problems.trace("plan", "Goal of the 2-point plan deleted during the tour: asking \"Route back to the start?\"")
        planChanged()
        endGuidanceIfPlanEmpty()
        nextCause = PlanStatus.Cause.PointEdit
        recalc()
    }

    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    private fun myPosition(): GeoPoint? =
        tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: recState.value?.track?.lastOrNull()?.lastOrNull()

    /**
     * The goal of a start → goal plan was deleted during a tour (owner, [RouteBack]): "Route back to the start?" stays
     * until answered (never self-dismissing). The followed line is unchanged while it is open.
     */
    var routeBackAsk by mutableStateOf(false)
        private set

    /**
     * Yes: the plan becomes position → start and the tour follows it (followEditedPlan) – no extra Undo step, so one
     * Undo brings the plan from before the delete. No (or Yes without a position): plain recording, the route guidance
     * ends as for an empty plan; Undo brings the plan and the guidance back.
     */
    fun answerRouteBack(yes: Boolean) {
        if (!routeBackAsk) return
        routeBackAsk = false
        if (recState.value == null) return
        val pos = myPosition()
        val back = if (yes) RouteBack.plan(waypoints, pos) else null
        if (back != null) {
            Problems.trace("plan", "Route back to the start: yes – plan = position → start")
            val before = waypoints.toList()
            waypoints.clear(); waypoints.addAll(back)
            cellsForNewPoints(before, waypoints)
            prefs.planWaypoints = waypoints.toList()
            prunePinned()
            planEditPending = true
            recalc()
            return
        }
        if (yes) sayError(Txt.s(Res.string.msg_route_back_no_position), NoticePlace.MAP)
        Problems.trace("plan", "Route back to the start: " + (if (yes) "yes, but no position" else "no") +
            " – route guidance ended, recording continues (Undo brings it back)")
        if (followLine != null && !followIsTrack) { clearFollow(); guidanceEndedByEmptyPlan = true }
    }

    /**
     * Owner rule ([PlanGuidance]): what Plan shows = what Navigate follows. An empty plan during a tour ends the
     * route guidance – no line, no off-route warning, no junction vibration, no arrival; the recording goes on. Undo
     * (points back) follows the plan again ([followEditedPlan]).
     */
    private fun endGuidanceIfPlanEmpty() {
        if (!PlanGuidance.endsGuidance(waypoints.size, recState.value != null, followLine != null, followIsTrack)) return
        Problems.trace("plan", "All points deleted during the tour: route guidance ended, recording continues (Undo brings it back)")
        clearFollow()
        guidanceEndedByEmptyPlan = true
    }

    /** Forget pinned points that are neither planned nor reachable by undo/redo. */
    private fun prunePinned() {
        if (pinned.isEmpty()) return
        val alive = HashSet(waypoints).apply { addAll(history.allPoints()) }
        if (pinned.retainAll(alive)) prefs.planPinned = pinned
    }

    private fun set(list: List<GeoPoint>) = change { clear(); addAll(list) }

    /**
     * Adds a held point where it most likely belongs (WaypointInsertion, F-PLAN-13), silently: the hold's vibration and
     * the new line confirm it; a wrong guess is fixed with Undo or by dragging ≡ in the Points list.
     * @param lineSnapM "on the line" distance, ~24 dp in metres at the current zoom.
     */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) {
        if (onPoi) { pinned += p; prefs.planPinned = pinned }
        val before = waypoints.toList()
        // While a reroute runs, the shown line belongs to older waypoints: use best-fit only.
        val d = WaypointInsertion.decide(before, p, if (routing) null else route?.points, lineSnapM)
        change { add(d.index.coerceIn(0, size), p) }
    }

    fun removePoint(index: Int) { if (index in waypoints.indices) change { removeAt(index) } }

    /** Search result shown in the place card (search, ui/search); map-held POIs use [poiMenu]. */
    var planPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)
    /** Eye button held: route lines and markers are hidden (position and recorded track stay). */
    var routePeek by mutableStateOf(false)

    // ---------------- map hold menus (MapGestures, ui/MapOverlays) ----------------
    /** POI held on the map in Plan: its menu "Add to route · Open in Google Maps". */
    var poiMenu by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    /** First hold on an empty plan: the held spot, its menu "Start here · Go here (from my location)" is open. */
    var firstPointMenu by mutableStateOf<GeoPoint?>(null)

    val mapOverlayOpen: Boolean get() =
        poiMenu != null || pointMenu != null || firstPointMenu != null || planPoi != null

    /** Closes every map menu; [why] goes into the gesture log (Diagnostics) when a point menu was open. */
    fun closeMapOverlays(why: String = "closed") {
        // The search mark lives only until its menu is done (owner): closing that menu – by an action or by
        // tapping elsewhere – removes the orange ring too.
        if (poiMenu != null) searchMark = null // the menu may belong to the place's own map icon a few metres away
        closePointMenu(why)
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
        if (waypoints.isEmpty()) change { add(GeoPoint(loc.latitude, loc.longitude)); add(p) } else addPoint(p)
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
        change { add(GeoPoint(loc.latitude, loc.longitude)); add(p) }
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
    /** The POI menu's add item, saying what it does ("Set as destination", "Add as stop 2", …) – same rule as [addPoint]. */
    fun poiAddLabel(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0): String {
        val w = waypoints.toList()
        return com.onpath.app.search.AddPreview.label(
            WaypointInsertion.decide(w, GeoPoint(poi.lat, poi.lon), if (routing) null else route?.points, lineSnapM), w.size)
    }

    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        closeMapOverlays()
        val idx = waypoints.indexOfFirst { sameSpot(it, poi.lat, poi.lon) }
        if (idx >= 0) { openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        clearSearchMarkAt(poi.lat, poi.lon)
        val before = waypoints.size
        addPoint(p, lineSnapM, onPoi = true)
        Problems.trace("plan", "POI menu: \"${poi.name ?: poi.kind}\" added to the route (plan $before → ${waypoints.size} points)")
    }

    /** Point menu → "Start here" (drops earlier points) / round trip "Start & finish here" (rotates the loop). */
    fun makeStartFromMenu() {
        val index = menuAction("Start here") ?: return
        startAtPoint(index)
    }

    /**
     * "Start here" on planned point [index] – the map's point menu and the search card's "As start" (owner:
     * the same word does the same, [PlanEdits.startHere]); one Undo step brings the plan back.
     */
    private fun startAtPoint(index: Int) {
        val w = waypoints.toList()
        val next = PlanEdits.startHere(w, index)
        if (next != w) set(next)
    }

    fun isRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints)

    /**
     * A point menu action was tapped: closes the menu and returns the index of the point it was opened for in the
     * plan as it is NOW (the plan can change under an open menu – PointMenuTarget), or null when that point is gone.
     * Logged either way, so a "tap did nothing" report can be traced.
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
            // only the places near each point are read from the index file (off the main thread)
            val index = try { search.offlineIndex() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for point names; \"Point N\" shown", e); return@launch }
            val names = withContext(Dispatchers.IO) {
                todo.mapNotNull { p ->
                    com.onpath.app.search.RouteNamer.label(p, com.onpath.app.search.RouteNamer.placesNear(index, listOf(p)))?.let { p to it }
                }.toMap()
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
        set(after)
    }

    /** Points list → ✕: deleted at once (Undo brings it back); a round trip stays closed at its start. */
    fun deletePointFromList(index: Int) {
        val after = com.onpath.app.routing.PlanPoints.delete(waypoints, index)
        if (after.size == waypoints.size) return
        Problems.trace("plan", "Points list: point ${index + 1} deleted (plan ${waypoints.size} → ${after.size})")
        set(after)
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

    fun isStop(poi: com.onpath.app.poi.Poi): Boolean = waypoints.any { sameSpot(it, poi.lat, poi.lon) }

    /** [p] is at [lat] / [lon] (a POI, place or search mark that became a route point keeps its exact coordinate). */
    private fun sameSpot(p: GeoPoint, lat: Double, lon: Double) =
        kotlin.math.abs(p.lat - lat) < 1e-7 && kotlin.math.abs(p.lon - lon) < 1e-7

    // ---------------- search + place card (UI in ui/search, logic in search/) ----------------
    // Open/close the search view, the picked place, and "add as role" actions of the shared place card.
    // The card is keyed by planPoi (so MapHost's tap rules – a map tap closes it – apply unchanged).

    enum class PlaceRole { SMART, START, DESTINATION }

    // searchStore / search: declared above the init that calls recalc() / refreshTours() (ViewModelInitOrderTest)
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
        flyToPlace(p)
    }

    /**
     * Flies to a searched / shared place at a zoom that fits what it is (a city needs the overview, a restaurant the
     * close-up); without a detailed map there its regions and routing data load in the modal (owner: a trigger).
     */
    private fun flyToPlace(p: com.onpath.app.search.Place) {
        val zoom = when (p.sub) { "city" -> 12.0; "town" -> 13.0; "village", "suburb" -> 14.0; else -> 16.0 }
        events += UiEvent.CenterOn(GeoPoint(p.lat, p.lon), zoom)
        if (mode == AppMode.PLAN) safeCells("search") {
            showCellJob(cellTriggers.pointSet(GeoPoint(p.lat, p.lon), com.onpath.app.cells.CellWhy.SEARCHED)
                .tagged(LoadTag(LoadWhy.POINT, GeoPoint(p.lat, p.lon), p.title())))
        }
        if (mode == AppMode.PLAN) routingFor(listOf(GeoPoint(p.lat, p.lon)), wait = true)
    }

    /** Search result marked on the map (pin); see [pickSearchResult]. */
    var searchMark by mutableStateOf<com.onpath.app.search.Place?>(null)

    private fun clearSearchMarkAt(lat: Double, lon: Double) {
        val m = searchMark ?: return
        if (sameSpot(GeoPoint(m.lat, m.lon), lat, lon)) searchMark = null
    }

    /** Place shown in the card: the search result, or the tapped POI. */
    val cardPlace: com.onpath.app.search.Place?
        get() = planPoi?.let { poi -> searchPlace?.takeIf { it.id == poi.id } ?: com.onpath.app.search.Place.fromPoi(poi) }

    fun closePlaceCard() { planPoi = null; searchPlace = null }

    private fun routeIndexOf(place: com.onpath.app.search.Place) =
        waypoints.indexOfFirst { sameSpot(it, place.lat, place.lon) }.takeIf { it >= 0 }

    /** Card content incl. what "Add to route" will do (same WaypointInsertion as a map hold). */
    fun placeCardUi(place: com.onpath.app.search.Place): com.onpath.app.ui.search.PlaceCardUi {
        val idx = routeIndexOf(place)
        val r = route
        if (idx != null) return com.onpath.app.ui.search.PlaceCardUi(place, idx, waypoints.size,
            com.onpath.app.search.AddPreview.existingRole(idx, waypoints.size))
        val pv = com.onpath.app.search.AddPreview.of(waypoints.toList(), GeoPoint(place.lat, place.lon), if (routing) null else r?.points,
            r?.takeIf { it.durationS > 0 }?.let { it.distanceM / it.durationS })
        val k = pv.decision.kind
        return com.onpath.app.ui.search.PlaceCardUi(place, null, waypoints.size, pv.subtitle, pv.label,
            addIsStart = k == WaypointInsertion.Kind.START,
            addIsDestination = k == WaypointInsertion.Kind.DESTINATION && waypoints.size <= 1)
    }

    /** Adds the place as a pinned stop (passed exactly), named after the place. Moves it when already planned. */
    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) {
        val p = GeoPoint(place.lat, place.lon)
        val name = place.title()
        val idx = routeIndexOf(place)
        closePlaceCard()
        rememberPlaceName(p, name)
        if (idx != null) {
            // "As start" = the point menu's "Start here" (owner); "As destination" moves it to the end
            when (role) { PlaceRole.START -> startAtPoint(idx); PlaceRole.DESTINATION -> makeDestination(idx); PlaceRole.SMART -> Unit }
            return
        }
        pinned += p; prefs.planPinned = pinned
        when (role) {
            // No timed notices (owner rule): the named marker and the new line show what happened.
            PlaceRole.SMART -> addPoint(p, onPoi = true)
            PlaceRole.START -> change { add(0, p) }
            PlaceRole.DESTINATION -> {
                val loc = tracker.location.value
                if (waypoints.isEmpty() && loc != null) change { add(GeoPoint(loc.latitude, loc.longitude)); add(p) }
                else change { add(p) }
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
        routesOpen = false; showSettings = false; offlineResult = null; timeDetails = null; routeDetailsOpen = false; searchOpen = false
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
                // For speed (owner): when the redirect already names the address, search that and skip the Google
                // page; the page is only read when the address is not found.
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
                        showSharedPlace(hit.first.copy(name = shownName,
                            note = com.onpath.app.search.SharedPosition.of(point = false, fromPage = false, addressWon = false).note))
                        return@launch
                    }
                    r = withContext(Dispatchers.IO) { com.onpath.app.search.ShortLinkResolver(http).resolve(link) }
                }
                r.place?.let { rp -> p = p.copy(lat = rp.lat, lon = rp.lon, name = p.name ?: rp.name, address = p.address ?: rp.address, query = p.query ?: rp.query) }
                fromPage = r.fromPage
                if (!p.hasPoint) Problems.info("search", "Shared map link gave no position (${r.reason}); searching the address instead")
            }
            var lat = p.lat; var lon = p.lon
            var addressWon = false
            // A point read from the downloaded Google page (the link itself had none) is checked against the address
            // in the link: the page can carry other places' markers. More than SHARED_PAGE_MAX_OFF_M apart → the address wins.
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
                        addressWon = true
                    }
                }
            }
            if (lat != null && lon != null) {
                Problems.info("search", "Shared place at %.5f, %.5f (%s)".format(java.util.Locale.ROOT, lat, lon,
                    if (fromPage) "from the map page" else if (shared.hasPoint) "in the shared text / link" else "from the resolved link"))
                // "Gelateria Costantin, Passeggiata …, Italien" (Google's place path) → the name only on the ring
                val shownName = p.name?.substringBefore(',')?.trim()?.ifEmpty { null } ?: p.name
                showSharedPlace(com.onpath.app.search.Place("shared:$lat,$lon", shownName, "other", lat, lon, address = p.address,
                    note = com.onpath.app.search.SharedPosition.of(point = true, fromPage = fromPage, addressWon = addressWon).note))
                return@launch
            }
            val found = search.findOne(p.searchTexts())
            // Stays until OK (owner rule: no message disappears by itself).
            if (found == null) { sayError(Txt.s(Res.string.msg_shared_place_not_found), NoticePlace.MAP); return@launch }
            val (place, from) = found
            Problems.info("search", "Shared place positioned from its address (${from.name.lowercase()} search)")
            showSharedPlace(place.copy(name = p.name ?: place.name,
                note = com.onpath.app.search.SharedPosition.of(point = false, fromPage = false, addressWon = false).note))
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
        // outside the downloaded map the ring still shows (on an empty map) – the overview zoom makes that clear
        flyToPlace(p)
    }

    private fun holdCameraForSharedPlace() {
        startCameraDone = true; userMovedMap = true
        keepCameraUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
    }
    // ---------------- end shared places ----------------

    fun makeDestination(index: Int) {
        if (index in waypoints.indices && index != waypoints.lastIndex) set(PlanEdits.move(waypoints, index, waypoints.size - 1))
    }
    fun reverseRoute() { if (waypoints.size >= 2) set(PlanEdits.reverse(waypoints)) }
    fun roundTrip() {
        val w = PlanEdits.roundTrip(waypoints)
        // the sheet offers Round trip only when the route does not end at the start yet (PlanPanel canRoundTrip)
        if (w.size != waypoints.size) set(w) else Problems.trace("plan", "Round trip: the route already ends at the start")
    }

    fun undoStep() = step(history.undo(waypoints), "Undo")
    fun redoStep() = step(history.redo(waypoints), "Redo")

    private fun step(prev: List<GeoPoint>?, what: String) {
        if (prev == null) { Problems.trace("plan", "$what: nothing to ${what.lowercase()}"); return }
        Problems.trace("plan", "$what: plan ${waypoints.size} → ${prev.size} points")
        waypoints.clear(); waypoints.addAll(prev)
        canUndo = history.canUndo; canRedo = history.canRedo
        routeBackAsk = false // the plan the question was about is gone
        closePointMenu("undo / redo"); firstPointMenu = null
        prefs.planWaypoints = waypoints.toList()
        planEditPending = true
        planChanged()
        endGuidanceIfPlanEmpty()
        nextCause = if (what == "Undo") PlanStatus.Cause.Undo else PlanStatus.Cause.Redo
        recalc()
    }

    /** Clear the plan right away (owner): one Undo step brings it back, so no question. */
    fun requestClear() = clearPlan()

    /**
     * Resets the whole plan in one undoable step: points, route line, pinned marks (kept while
     * undo can bring them back), error, highlight, notice and point menu. A routing job still
     * running is cancelled and its result ignored (recalc → RouteRequests.invalidate).
     * A followed copy of this plan goes too, unless a recording is using it right now.
     */
    fun clearPlan() {
        Problems.trace("plan", "Clear route: plan ${waypoints.size} → 0 points" + if (recState.value != null) " (tour running)" else "")
        val planLine = route?.points
        if (waypoints.isNotEmpty()) change { clear() } else recalc()
        closePointMenu("plan cleared"); firstPointMenu = null; highlight = null
        val fromPlan = followFromPlan || (planLine != null && followLine == planLine)
        if (fromPlan && followLine != null && recState.value == null) clearFollow()
    }

    fun answerLongRoute(plan: Boolean) {
        longRouteAsk = null
        val deferred = longRouteDeferred; longRouteDeferred = null
        if (plan) {
            longRouteOk = waypoints.toList()
            deferred?.let { cellsForNewPoints(it, waypoints) } // now the new point's maps load
            nextCause = runCause; recalc(); return
        }
        if (deferred != null) Problems.trace("cells", "Very long plan cancelled: nothing was loaded for it")
        longRouteDeferred = deferred // points kept unrouted: "Plan anyway" later loads them (an undo replaces it)
        // only the change that made it so long is taken back (Redo brings it again); nothing to take back (a plan
        // restored at start or loaded, newer routing data): the points stay without a route – never "Calculating…"
        // with nothing running
        cancelRun(NotRouted.LONG)
    }

    /**
     * Cancel of the long-route question or of a running calculation: a point edit is
     * undone (Undo / Redo the other way), an activity / T level change gets the previous activity back, anything else
     * just stops and leaves the points unrouted.
     */
    private fun cancelRun(why: NotRouted) {
        val cause = runCause
        runCause = PlanStatus.Cause.Other
        val what = PlanStatus.onCancel(cause, canUndo, canRedo)
        Problems.trace("routing", "Cancel ($why) of a run for ${cause::class.simpleName}: ${what.name.lowercase()}")
        leaveUnrouted(why)
        when (what) {
            PlanStatus.OnCancel.UNDO -> undoStep()
            PlanStatus.OnCancel.REDO -> redoStep()
            PlanStatus.OnCancel.RESTORE_ACTIVITY -> (cause as? PlanStatus.Cause.ActivityChange)?.let { restoreActivity(it) }
            PlanStatus.OnCancel.KEEP_POINTS -> Unit
        }
    }

    /** The activity and T level from before the cancelled change; the route is planned for them again (no undo step). */
    private fun restoreActivity(c: PlanStatus.Cause.ActivityChange) {
        activity = c.activity; prefs.activity = c.activity
        sac = c.sac; prefs.sac = c.sac
        routeChangedNotice = null; activityBefore = null; activityRecalc = false
        nextCause = PlanStatus.Cause.Other
        recalc()
    }

    /** "Plan anyway" / "Calculate" of the not-routed notice. */
    fun planAnyway() {
        if (waypoints.size < 2) return
        if (notRouted == NotRouted.LONG) {
            longRouteOk = waypoints.toList()
            longRouteDeferred?.let { longRouteDeferred = null; cellsForNewPoints(it, waypoints) }
        }
        recalc()
    }

    private fun leaveUnrouted(why: NotRouted) {
        routeCancel?.cancel(); routeCancel = null
        routeJob?.cancel(); routeJob = null
        requests.invalidate()
        route = null; routeError = null; routing = false; routingSlow = false; longRouteAsk = null
        followNextRoute = false; planEditPending = false
        notRouted = why
        corridorFollowsPlan(null)
        Problems.info("routing", "Plan left without a route (${why.name.lowercase()}): ${waypoints.size} points")
    }

    /**
     * Cancel while a route is calculated (owner): the router stops at once and the change that
     * started this calculation is taken back ([cancelRun]). Nothing to take back: the points stay, without a route.
     */
    fun cancelRouting() {
        if (!routing) return
        Problems.info("routing", "Route calculation cancelled by the user")
        cancelRun(NotRouted.STOPPED)
    }

    private fun recalc() {
        runCause = nextCause; nextCause = PlanStatus.Cause.Other
        routeJob?.cancel(); routeJob = null
        val viaData = routingDataReroute; routingDataReroute = false // this run: newer routing data arrived
        highlight = null; spotLine = null; spotsOpen = false
        // the route before an activity / T level change, compared with this run's result ("Route changed for …")
        val changeBefore = activityBefore.takeIf { activityRecalc }; activityRecalc = false
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        routeCancel?.cancel(); routeCancel = null; routingSlow = false // an older request stops at once
        notRouted = null
        if (pts.size < 2) {
            requests.invalidate(); route = null; routeError = null; routing = false; followNextRoute = false
            corridorFollowsPlan(null); return
        }
        val act = activity; val level = sac
        // owner: a plan far beyond a day (a point in another country by mistake) is asked about first –
        // nothing is routed or loaded until the answer
        if (pts != longRouteOk && com.onpath.app.routing.LongRoute.needsAsk(pts, act)) {
            requests.invalidate(); route = null; routeError = null; routing = false
            longRouteAsk = com.onpath.app.routing.LongRoute.estimateS(pts, act)
            Problems.trace("routing", "Very long plan (${com.onpath.app.routing.LongRoute.describe(longRouteAsk!!)}) – asking first")
            corridorFollowsPlan(null) // nothing loads for a plan that is not routed
            return
        }
        longRouteAsk = null
        val f = planFactors(act)
        val gen = requests.next()
        routing = true
        val started = android.os.SystemClock.elapsedRealtime()
        val cancel = com.onpath.app.routing.RouteCancel().also { routeCancel = it }
        routeJob = viewModelScope.launch {
            delay(150) // debounce quick taps
            // the Cancel button appears only when it takes a while (no flicker for normal routes)
            val slow = launch { delay(ROUTING_SLOW_MS); if (requests.isCurrent(gen)) routingSlow = true }
            var planner: Planner? = null
            val result = withContext(routingDispatcher) {
                runCatching {
                    val eng = routingEngine()
                    withModelTime(Planner(eng, cancel).also { planner = it }.plan(pts, act, level, pins), act, f)
                }
            }
            slow.cancel()
            // the corridor work of a result off the main thread
            val post = result.getOrNull()?.let { r -> withContext(Dispatchers.Default) { postRoute(r, pts) } }
            if (requests.isCurrent(gen)) routingSlow = false
            if (!requests.isCurrent(gen) || !isActive) { // superseded or cleared
                Problems.trace("routing", "Route #$gen for ${pts.size} points superseded – result ignored")
                return@launch
            }
            // one line per route: distance, size, profile, each try's time and outcome
            val done = result.getOrNull()
            Problems.trace("routing", "Route #$gen for ${pts.size} points " + (if (result.isSuccess) "ready" else "failed") +
                " after ${android.os.SystemClock.elapsedRealtime() - started} ms (plan now ${waypoints.size} points): " +
                "${act.name}, ${"%.1f".format(Locale.ROOT, com.onpath.app.routing.RouteTimeLimit.straightKm(pts))} km straight" +
                (done?.let { ", ${"%.1f".format(Locale.ROOT, it.distanceM / 1000)} km routed, ${it.points.size} line points, " +
                    "${it.legs.count { l -> l.straight }} straight legs" } ?: "") +
                (planner?.describeTries()?.takeIf { it.isNotEmpty() }?.let { "; tries: $it" } ?: ""))
            var baseline = false
            result.onSuccess {
                baseline = adoptPlanBaseline
                route = it; snapToRoute(pts, pins, it)
                if (changeBefore != null) {
                    activityBefore = null
                    if (RouteWarnings.changedNoticeably(changeBefore.points, changeBefore.distanceM, changeBefore.ascentM,
                            it.points, it.distanceM, it.ascentM)) routeChangedNotice = RouteWarnings.changedText(act, level)
                }
                // routing data missing in the route's corridor (~10 km): it loads now; the notice (plain words, no path)
                // only when a part under a planned point is missing – those make the straight line
                post?.routingKeys?.let { k -> routingForLine(k) }
                routeError = RoutingDataNotice.forRoute(it.error, post?.tilesMissing == true, routingData.loading)
                // T4: the corridor – named after the plan's destination (round trip: its start) in the loading chip
                val dest = if (com.onpath.app.routing.PlanEdits.isRoundTrip(pts)) pts.firstOrNull() else pts.lastOrNull()
                safeCells("route") { showCellJob(cellTriggers.routeComputed(it.points, post?.cells).tagged(LoadTag(LoadWhy.ROUTE, dest))) }
                if (mode == AppMode.PLAN && !followNextRoute && !planEditPending && !viaData) freshenAfterRoute(it.points, post?.cells)
                if (viaData) routingDataRouteChanged(it)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (adoptPlanBaseline) { adoptPlanBaseline = false; planBaseline = it.points }
            }
                .onFailure {
                    Problems.error("routing", "Route calculation failed", it); route = null; routeError = it.message ?: Txt.s(Res.string.msg_routing_failed)
                    corridorFollowsPlan(null)
                }
            if (followNextRoute) {
                followNextRoute = false
                if (route != null) followPlanned(checkNow = true) else sayError(Txt.s(Res.string.msg_no_new_route), NoticePlace.MAP)
            } else if (planEditPending && result.isSuccess) {
                // an edit of the plan during a tour: the tour follows the new plan (owner rule, no question)
                planEditPending = false
                if (!baseline) followEditedPlan()
            }
            routing = false
        }
    }

    // ---------------- time estimate (docs/plan-time-estimate.md) ----------------

    /** The pace setting for planning with [a] (learning per tour only: nothing from earlier tours). */
    fun planFactors(a: Activity = activity): com.onpath.app.time.Factors =
        com.onpath.app.time.TimeEstimate.planFactors(a, settings)

    /** Route sheet ⋯ → Time details (debug view of the estimate); null = closed. */
    var timeDetails by mutableStateOf<TimeDetailsUi?>(null)

    /** [technical]: Settings → Diagnostics → Developer tools (formulas, tables); else the plain lines of sheet ⋯. */
    fun openTimeDetails(technical: Boolean = false) {
        val r = route ?: return
        val pace = com.onpath.app.time.TimeEstimate.pace(activity, settings)
        timeDetails = TimeDetailsUi(
            com.onpath.app.time.TimeBreakdown.of(r.points, activity, planFactors()),
            pace.label, pace.factor, technical,
        )
    }

    /**
     * The plan's time from OnPath's own model (time/RouteTimes), not the router's: the same number as the route list
     * and Navigate "to go" at the start. Moving time, rounded for display.
     */
    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute =
        r.copy(durationS = com.onpath.app.time.TimeEstimate.planSeconds(r.points, a, f))

    /** Times of a finished tour: the service's live summary, else recomputed from the stored points. */
    private fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = try {
        // rides taken out (vehicle detection): moving time / breaks of what is left, not the service's live summary
        if (t.rides != null && t.rides.choice != com.onpath.app.rides.RideChoice.AS_RECORDED)
            com.onpath.app.time.TourTimes.of(t.durationS, com.onpath.app.time.BreakDetector.breaksIn(
                com.onpath.app.rides.RideView.keptSegments(db.loadSegments(t.id), t.rides)))
        else com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).get(t.id)
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

    /** A planned route was saved as [id]: keep its model time per group (with way attributes) for the route list. */
    private fun storePlanTimes(id: Long, r: PlannedRoute?, a: Activity) {
        if (r == null || r.points.size < 2) return
        try { planTimes.put(id, r.distanceM, com.onpath.app.time.RouteTimes.of(r.points, a).groupTotals()) } catch (e: Exception) {
            Problems.warn("time", "Plan times not stored for a saved route; list time falls back to the stored points", e)
        }
    }

    /**
     * Planned routes and imported tracks without timestamps show the model time with the current pace (recomputed, the
     * stored time is ignored: one time model); recorded tours and timed imports as stored.
     */
    private fun withModelTime(t: Tour): Tour {
        if (t.kind != TourKind.PLANNED && t.kind != TourKind.IMPORTED) return t
        val a = activityOrNull(t.activity) ?: Activity.HIKE
        val f = planFactors(a)
        val key = f to t.distanceM // an overwritten route has a new distance
        plannedTimes[t.id]?.takeIf { it.first == key }?.let { return t.copy(durationS = it.second) }
        if (t.kind == TourKind.IMPORTED) return try {
            val s = com.onpath.app.time.TimeEstimate.importedSeconds(db.loadSegments(t.id), a, f)
            plannedTimes[t.id] = key to s
            t.copy(durationS = s)
        } catch (e: Exception) {
            Problems.warn("time", "Model time of an imported track could not be computed; stored time shown", e)
            t
        }
        // stored at save with the way attributes; the stored points alone have none
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
        // a point the route does not reach keeps its place: the gap is shown (planGaps), not hidden by moving the point
        val far = PlanGaps.farIndices(routed, r.snapped)
        if (far.isNotEmpty()) Problems.trace("routing", "Route does not reach point(s) ${far.sorted().joinToString { "${it + 1}" }}")
        val keep = pins + far + routed.indices.filter { stopName(routed[it]) != null }
        val snapped = WaypointSnap.apply(routed, r.snapped, keep) ?: return
        for (i in snapped.indices) if (snapped[i] != routed[i]) waypoints[i] = snapped[i]
        if (routed.size >= 2 && !RouteRules.isUnsaved(routed, savedPoints)) setSaved(linkName, linkId, snapped)
        linkTitle?.let { (title, at) -> if (at == routed) linkTitle = title to snapped }
        linkPoints?.let { at -> if (at.map { it.lat to it.lon } == routed.map { it.lat to it.lon }) linkPoints = snapped }
        val moves = WaypointSnap.moves(routed, snapped)
        history.replacePoints(moves)
        // an open menu follows its point to the snapped position (it must still find it for Delete)
        pointMenuTarget = pointMenuTarget?.afterSnap(moves)
        prefs.planWaypoints = waypoints.toList()
    }

    /**
     * The router reaches a point only by its nearest allowed way (up to 1 km away): a summit above the T level, a
     * via ferrata, a point off any path. The plan sheet says so in one red line (owner: no message box), or
     * the hiker trusts a line that stops short of where they want to go. Null = every point is reached.
     */
    fun planGapText(gaps: List<PlanGap> = planGaps): String? {
        if (gaps.isEmpty()) return null
        val pts = waypoints.toList()
        return PlanGaps.text(gaps, activity) { i -> pts.getOrNull(i)?.let { stopName(it) ?: nearbyNames[it] } }
    }

    // ---------------- route warnings ----------------

    private fun noteActivityChange() {
        routeChangedNotice = null
        if (activityBefore == null) activityBefore = route?.takeIf { !routing }
        activityRecalc = activityBefore != null
    }

    /** A plan edit (points changed): the "route changed" notice and its pending comparison go. */
    private fun planChanged() {
        routeChangedNotice = null; activityBefore = null; activityRecalc = false
    }

    /** Spots worth knowing on the current route ([RouteWarnings.detect]); none while it is being calculated. */
    val routeSpots: List<RouteSpot>
        get() {
            val r = route?.takeIf { !routing } ?: return emptyList()
            val key = Triple(r.points, activity, sac)
            spotsMemo?.let { (k, v) -> if (k.first === key.first && k.second == key.second && k.third == key.third) return v }
            val v = try { RouteWarnings.detect(r.points, activity, sac) } catch (e: Exception) {
                Problems.warn("routing", "Route warnings could not be worked out; none shown", e); emptyList()
            }
            spotsMemo = key to v
            return v
        }

    /** The amber line of the plan sheet: spots on the way only (pushing, steps, above T, steep). */
    fun warningLineText(): String? = RouteWarnings.text(routeSpots, sac)

    /** Grey INFO line "New route for … – check it" (grey: the user changed the activity themselves). */
    fun routeChangedLineText(): String? = routeChangedNotice

    /** Tap on the "New route for …" line: it goes, the map shows the whole route. */
    fun tapRouteChangedLine() {
        routeChangedNotice = null
        val r = route ?: return
        followMe = false
        events += UiEvent.Fit(r.points)
    }

    /** Tap on the amber line: the notice goes; the list of spots opens (none: the map shows the whole route). */
    fun tapWarningLine() {
        routeChangedNotice = null
        if (routeSpots.isNotEmpty()) { spotsOpen = true; return }
        val r = route ?: return
        followMe = false
        events += UiEvent.Fit(r.points)
    }

    fun closeSpots() { spotsOpen = false }

    /** A spot in the list tapped: the list closes, following stops, the map shows that piece (drawn amber). */
    fun showSpot(s: RouteSpot) {
        spotsOpen = false
        val pts = route?.points ?: return
        val piece = RouteWarnings.showPoints(pts, s)
        followMe = false
        spotLine = piece
        val c = RouteWarnings.centerFor(pts, s)
        events += if (c != null) UiEvent.CenterOn(c.first, c.second) else UiEvent.Fit(piece)
    }

    /** "Show" of that line: stop following, then the map shows the missing piece of the first gap. */
    fun showPlanGap() {
        val g = planGaps.firstOrNull() ?: return
        followMe = false
        val (at, zoom) = PlanGaps.showTarget(g)
        events += UiEvent.CenterOn(at, zoom)
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
        val title = name.trim().ifBlank { defaultName(Txt.s(Res.string.msg_name_route)) }
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
            if (id == null) { routesProblem = Txt.s(Res.string.msg_route_save_failed); return@launch }
            Backups.tourChanged(getApplication(), id)
            sync.changed("route saved")
            setSaved(title, id, pts)
            refreshTours()
            then?.invoke()
        }
    }

    /** The plan's stops as saved: with their place names (backup / restore keeps them). */
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
        val then: (() -> Unit)? = choice.thenOpen?.let { t -> { open(t) } }
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
                if (replaced) { Backups.tourChanged(getApplication(), id); sync.changed("route overwritten"); setSaved(name, id, pts); refreshTours(); then?.invoke() }
                else saveRoute(name, then) // deleted meanwhile: keep the work as a new route with the same name
            }.onFailure {
                Problems.error("db", "Overwriting a saved route failed", it)
                routesProblem = Txt.s(Res.string.msg_route_save_failed)
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
            if (!ok) { routesProblem = Txt.s(Res.string.msg_route_rename_failed); return@launch }
            Backups.tourChanged(getApplication(), t.id)
            sync.changed("renamed")
            if (linkId == t.id) setSaved(name, linkId, savedPoints)
            shown?.let { if (it.tourId == t.id) shown = it.copy(name = name) }
            refreshTours()
        }
    }

    private fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) {
        linkName = name; linkId = id; savedPoints = points
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
        if (RouteRules.askBeforeLoading(t.kind, t.waypoints.size, waypoints, savedPoints)) replaceAsk = PlanToOpen.Saved(t) else openTour(t)
    }

    fun replaceConfirmed() { val t = replaceAsk ?: return; replaceAsk = null; open(t) }

    /** Loads what "Replace current plan?" / "Save first" was about. */
    private fun open(t: PlanToOpen) = when (t) {
        is PlanToOpen.Saved -> openTour(t.tour)
        is PlanToOpen.Shared -> openSharedPlan(t.plan)
    }

    /** "Save first": name dialog, then save and load [replaceAsk]. */
    fun replaceSaveFirst() {
        val t = replaceAsk ?: return
        replaceAsk = null
        if (currentSaveMode() == SaveMode.OVERWRITE_OR_NEW) saveChoice = SaveChoice(t) else { suggestName(); saveFirst = t }
    }

    fun saveFirstDone(name: String) {
        val t = saveFirst ?: return
        saveFirst = null
        saveRoute(name) { open(t) }
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
        // a plan from a link brings its name while its points are unchanged
        linkTitle?.let { (title, at) -> if (at == pts) { suggestedName = title; return } }
        val line = route?.points?.takeIf { it.size >= 2 } ?: pts
        val startName = stopName(pts.first()); val endName = stopName(pts.last())
        val via = pts.subList(1, pts.size - 1).mapNotNull { stopName(it) }
        nameJob = viewModelScope.launch {
            val index = try { search.offlineIndex() } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { Problems.warn("search", "Place index unavailable; route name from stop names only", e); null }
            suggestedName = withContext(Dispatchers.IO) {
                try {
                    val places = index?.let { com.onpath.app.search.RouteNamer.placesNear(it, line) }.orEmpty()
                    com.onpath.app.search.RouteNamer.name(line, places, startName, endName, via)
                } catch (e: Exception) {
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
        // the date name in the language it was made in (EN "Tour 20 Oct…", the German pattern starts the same today)
        val datePrefix = Txt.s(Res.string.nav_tour_date_name, "\u0000").substringBefore("\u0000")
        // a demo tour (Developer tools → Demo) gets a place name too and keeps its "Demo · " mark
        val demo = t.name.startsWith(com.onpath.app.recording.DemoLocation.TOUR_PREFIX)
        val dateName = t.name.removePrefix(com.onpath.app.recording.DemoLocation.TOUR_PREFIX)
        if (!dateName.startsWith("Tour ") && !dateName.startsWith(datePrefix)) return
        viewModelScope.launch {
            val name = try {
                val index = search.offlineIndex()
                withContext(Dispatchers.IO) {
                    // "Save only until …": named after the part that is the tour
                    val line = com.onpath.app.rides.RideView.keptSegments(db.loadSegments(t.id), t.rides).flatten().map { GeoPoint(it.lat, it.lon) }
                    com.onpath.app.search.RouteNamer.name(line, com.onpath.app.search.RouteNamer.placesNear(index, line))
                }?.let { if (demo) com.onpath.app.recording.DemoLocation.TOUR_PREFIX + it else it }
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

    /** "Route 26 Oct, 10:12" – [prefix] in the app's language (msg_name_route …), the date as the language writes it. */
    fun defaultName(prefix: String) =
        "$prefix " + SimpleDateFormat(Txt.s(Res.string.msg_name_date_pattern), Locale.getDefault()).format(Date())

    // ---------------- modes: Plan <-> Navigate ----------------

    /** Switches mode; the recording goes on either way, only off-route checks pause in Plan. */
    private fun enter(m: AppMode) {
        val was = mode
        mode = m
        // the start zoom of Navigate, once per start (owner); the map takes it when it follows
        if (m == AppMode.NAVIGATE && was != AppMode.NAVIGATE) navStartZoom = AppMode.navStartZoom(activity)
        else if (m != AppMode.NAVIGATE) navStartZoom = null
        if (m == AppMode.NAVIGATE) { planPoi = null; poiMenu = null; closePointMenu("mode change to Navigate"); firstPointMenu = null; highlight = null; followMe = true }
        // Plan keeps the compass choice: walking direction up stays turned and following (until you edit or move the
        // map); north up stops following
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
            if (started) enter(AppMode.NAVIGATE)
            else if (errorCard == null) sayError(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)
        }
    }

    private var startJob: Job? = null

    /** How long Start waits for the service's first state before saying that recording did not start. */
    private val START_TIMEOUT_MS = 6_000L

    /** A plan with legs the router could not find (drawn dashed): "Navigate anyway?" is open. */
    var straightAsk by mutableStateOf(false)

    private fun planHasStraightLegs(): Boolean =
        (!followIsTrack || planReplacesTrack()) && waypoints.size >= 2 && route?.legs?.any { it.straight } == true

    /** A followed track and a plan edited since: Continue follows the plan, the track is drawn grey (owner). */
    private fun planReplacesTrack(): Boolean = PlanGuidance.planReplacesTrack(followIsTrack, waypoints.toList(), planAtTrackFollow)

    /** The user wants to navigate the plan although parts of it are straight lines. */
    fun straightConfirmed() { straightAsk = false; startNavigation(force = true) }

    /**
     * Back to Navigate while a tour runs (no new recording). The plan's waypoints are **not** changed (owner):
     * when the plan was edited, its route becomes the new navigation session; otherwise the running session (and a
     * new route already taken) stays. Either way the service checks the next fix at once and, when the user is away
     * from the line, asks the same "Take new route / Keep original" question as when leaving the route.
     */
    fun continueNavigation(force: Boolean = false) {
        if ((!followIsTrack || planReplacesTrack()) && waypoints.size >= 2) {
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
            mode == AppMode.PLAN && (!followIsTrack || planReplacesTrack()) && waypoints.size >= 2 && planLine != null &&
                !planFollowed(planLine) ->
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
                note = Txt.s(Res.string.msg_check_no_route_followed))
            return
        }
        activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Checking) },
            note = if (tracker.location.value == null) Txt.s(Res.string.msg_check_waiting_gps) else null)
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
                updateCheck(null) { ActivityRowState.Failed(Txt.s(Res.string.msg_check_nothing_left)) }
                return@launch
            }
            updateCheck(null) { it }
            for (a in others) {
                if (!isActive || cancel.isCancelled) return@launch
                val st = withContext(routingDispatcher) {
                    try {
                        val eng = routingEngine()
                        if (eng is com.onpath.app.routing.StraightLineEngine) {
                            ActivityRowState.Failed(Txt.s(Res.string.msg_check_cannot_route))
                        } else {
                            val r = eng.route(rest.waypoints, a, sacFor(a),
                                com.onpath.app.routing.RouteLimits(com.onpath.app.navigation.ActivityCheck.TIMEOUT_MS, cancel))
                            if (r.points.size < 2) ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))
                            else ActivityRowState.Ready(com.onpath.app.navigation.ActivityCheck.evaluate(rest.line, a, sacFor(a), r, planFactors(a)))
                        }
                    } catch (e: Exception) {
                        Problems.warn("routing", "Activity check: routing for another activity failed", e)
                        val none = Txt.s(Res.string.msg_check_no_route)
                        ActivityRowState.Failed(com.onpath.app.debug.UserText.of(e, none).takeIf { it.length < 90 } ?: none)
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
            selectActivity(a, userChange = false)
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
        // chosen in Plan during the tour: the Take new route / Keep question is asked in Navigate
        if (mode == AppMode.PLAN) enter(AppMode.NAVIGATE)
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
            selectActivity(a, userChange = false)
        }
    }

    /** The slide switch in Navigate. Recording continues. */
    fun switchToPlan() = enter(AppMode.PLAN)

    /** "End tour" in Plan (after confirmation) or "End & save" on arrival: stop and save. */
    fun endTour() { Problems.trace("tour", "End tour (save)"); endAsk = null; arrivalAsk = false; deleteFinished = false; stopRecording() }

    /** What "End tour" shows now (null = nothing): see [askEndTour]. */
    var endAsk by mutableStateOf<EndAsk?>(null)

    /**
     * "End tour" tapped: rides in the track so far (docs/design/vehicle-detection.md) → the summary with four choices,
     * otherwise the short Cancel · Delete · Save question. Detection failing = the short question (reported).
     * [saveIfNoRides]: Save was already chosen ("You have arrived" → Save) – with
     * rides the summary shows, without them the tour is saved at once.
     */
    fun askEndTour(saveIfNoRides: Boolean = false) {
        arrivalAsk = false
        val id = recState.value?.tourId ?: run { if (saveIfNoRides) endTour() else endAsk = EndAsk.Plain; return }
        val navActivity = recState.value?.nav?.session?.activity
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val segs = db.loadSegments(id)
                    val a = navActivity ?: activityOrNull(db.getTour(id)?.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check at End tour failed – asked without it", it) }.getOrNull()
            }
            if (recState.value?.tourId != id) return@launch // ended meanwhile (notification, arrival)
            when {
                s != null -> endAsk = EndAsk.Rides(id, s)
                saveIfNoRides -> endTour()
                else -> endAsk = EndAsk.Plain
            }
        }
    }

    /** Save from the ride summary: the choice is stored on the tour first, the recording service applies it at the end. */
    fun endTourWith(a: EndAsk.Rides, choice: com.onpath.app.rides.RideChoice) {
        endAsk = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { com.onpath.app.rides.RideApply.choose(com.onpath.app.rides.TourDbRides(db), a.tourId, a.summary.rides, choice) }
                    .onFailure { Problems.error("rides", "The choice for the ride could not be stored – the tour is saved as recorded", it) }
            }
            endTour()
        }
    }

    /** My routes → ⋮ → Edit rides: the same summary for a saved tour (no split, no delete there). */
    var editRides by mutableStateOf<Pair<Tour, com.onpath.app.rides.RideView.Summary>?>(null)

    fun openEditRides(t: Tour) {
        val rec = t.rides ?: return
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val onFoot = (t.activity ?: Activity.HIKE.name) == Activity.HIKE.name
                    com.onpath.app.rides.RideView.summary(db.loadSegments(t.id), rec.rides, onFoot, allowSplit = false)
                }.onFailure { Problems.warn("rides", "Rides of a tour could not be shown", it) }.getOrNull()
            }
            if (s == null) { sayError(Txt.s(Res.string.msg_rides_read_failed), NoticePlace.ROUTES); return@launch }
            editRides = t to s
        }
    }

    fun applyEditRides(t: Tour, choice: com.onpath.app.rides.RideChoice) {
        editRides = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { com.onpath.app.rides.RideApply.edit(com.onpath.app.rides.TourDbRides(db), t.id, choice) }
                    .onFailure { Problems.error("rides", "Changing the rides of a tour failed; it stays as it was", it) }.isSuccess
                    .also { if (it) Backups.tourChanged(getApplication(), t.id) }
            }
            if (!ok) sayError(Txt.s(Res.string.msg_rides_change_failed), NoticePlace.ROUTES)
            refreshTours()
            if (shown?.tourId == t.id) withContext(Dispatchers.IO) { db.getTour(t.id) }?.let { openTour(it) }
        }
    }

    /** End tour → Delete (owner): stop, then the tour goes straight to the recycle bin, no summary. */
    fun endTourAndDelete() { Problems.trace("tour", "End tour (delete)"); endAsk = null; arrivalAsk = false; deleteFinished = true; stopRecording() }
    private var deleteFinished = false

    /** Called with each fix in Navigate: asks when the end of the followed line is reached ([ArrivalGate]). */
    fun checkArrival(position: GeoPoint, remainingAlongM: Double?) {
        if (mode != AppMode.NAVIGATE || recState.value == null) return
        val acc = tracker.location.value?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
        if (arrivalGate.onFix(position, followLine?.lastOrNull(), remainingAlongM, acc)) arrivalAsk = true
    }

    // ---------------- follow / record ----------------

    /**
     * The plan was edited while a tour follows it (round trip, Undo, points added / removed, reverse): the tour follows
     * the new plan at once – followed line, target and arrival – without a question (owner rule). A
     * re-computed line that is the same route (Undo back to it) keeps the running session. A followed GPX track or a
     * free recording are not touched; a plan with fewer than two points changes nothing.
     */
    private fun followEditedPlan() {
        val r = route ?: return
        if (PlanGuidance.resumes(guidanceEndedByEmptyPlan, recState.value != null, followLine != null, r.points.size)) {
            Problems.trace("plan", "Plan points back during the tour: following the plan again")
            followPlanned(checkNow = true)
            return
        }
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
        setFollow(defaultName(Txt.s(Res.string.msg_name_route)), r.points, isTrack = false, checkNow = checkNow,
            stops = w, names = w.map { stopName(it) }, pinned = pinnedIndices())
        followFromPlan = true
    }

    fun followShown() {
        val s = shown ?: return
        setFollow(s.name, s.points, isTrack = true)
        followFromPlan = false
        planAtTrackFollow = waypoints.toList()
    }

    /**
     * Starts a navigation session for [points] (nav-session.json for the service: original line, stops for re-routing)
     * and the plain follow line. [checkNow]: the service checks the next fix at once ("Continue navigation").
     */
    private fun setFollow(name: String, points: List<GeoPoint>, isTrack: Boolean, checkNow: Boolean = false,
                          stops: List<GeoPoint> = emptyList(), names: List<String?> = emptyList(), pinned: Set<Int> = emptySet()) {
        guidanceEndedByEmptyPlan = false
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
        planAtTrackFollow = null
        arrivalGate.reset()
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED) {
            putExtra(RecordingService.EXTRA_CHECK_NOW, checkNow)
        }
    }

    fun clearFollow() {
        followFromPlan = false
        guidanceEndedByEmptyPlan = false
        followIsTrack = false; prefs.followIsTrack = false
        planAtTrackFollow = null
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
        Problems.trace("tour", "Tour start (${activity.name}, plan ${waypoints.size} points, " +
            (if (followLine != null) "following ${followName ?: "a route"}" else "nothing followed") + ")")
        RecordingService.send(getApplication(), RecordingService.ACTION_START) {
            putExtra(RecordingService.EXTRA_ACTIVITY, activity.name)
            putExtra(RecordingService.EXTRA_SAC, sac)
        }
        followMe = true
    }

    fun continueRecording(t: Tour) {
        unfinished = null
        RecordingService.send(getApplication(), RecordingService.ACTION_START) {
            putExtra(RecordingService.EXTRA_CONTINUE_ID, t.id)
        }
    }

    /** "Unfinished tour" → Save with a bus / car / lift found: the ride summary for it (null = none). */
    var unfinishedRides by mutableStateOf<Pair<Tour, com.onpath.app.rides.RideView.Summary>?>(null)

    /**
     * "Unfinished tour" → Save: like End tour – rides found → the summary with the four
     * choices ([unfinishedRides]), else saved at once. Detection failing = saved as recorded (reported).
     */
    fun askSaveUnfinished(t: Tour) {
        unfinished = null
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val segs = db.loadSegments(t.id)
                    val a = activityOrNull(t.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check of the unfinished tour failed – saved without it", it) }.getOrNull()
            }
            if (s != null) unfinishedRides = t to s else saveUnfinished(t)
        }
    }

    /** Ride summary of the unfinished tour → Cancel: back to its question (Continue · Delete · Save). */
    fun backToUnfinished() { unfinishedRides?.let { unfinished = it.first }; unfinishedRides = null }

    /**
     * Saves the interrupted tour; [choice] from the ride summary is applied like at the end of a recording
     * (RideApply.finish – without a choice the rides are only marked, Edit rides can change it later).
     */
    fun saveUnfinished(t: Tour, choice: com.onpath.app.rides.RideChoice? = null) {
        val rides = unfinishedRides?.second?.rides
        unfinished = null; unfinishedRides = null
        // the sticky service came back meanwhile and records this tour: it is not unfinished, leave it alone
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        enter(AppMode.PLAN)
        viewModelScope.launch(Dispatchers.IO) {
            db.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
            val store = com.onpath.app.rides.TourDbRides(db)
            if (choice != null && rides != null) runCatching { com.onpath.app.rides.RideApply.choose(store, t.id, rides, choice) }
                .onFailure { Problems.error("rides", "The choice for the ride could not be stored – the tour is saved as recorded", it) }
            val a = activityOrNull(t.activity) ?: activity
            val parts = com.onpath.app.rides.RideApply.finish(store, t.id,
                com.onpath.app.rides.TourStats(t.distanceM, t.ascentM, t.descentM, t.durationS), System.currentTimeMillis(), a)
            Backups.tourChanged(getApplication(), t.id)
            parts.forEach { Backups.tourChanged(getApplication(), it) }
            // the track is in My routes – no message (owner: no "Track saved" bar)
            withContext(Dispatchers.Main) { refreshTours() }
        }
    }

    /** "Unfinished tour" → Delete (asked again in the dialog): saved as done, then into the recycle bin (30 days). */
    fun deleteUnfinished(t: Tour) {
        unfinished = null; unfinishedRides = null
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        Problems.trace("tour", "Unfinished tour ${t.id} deleted (recycle bin)")
        enter(AppMode.PLAN)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                // "done" first: restored from the recycle bin it is a normal tour, never "unfinished" again
                db.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
                db.setDeleted(t.id, System.currentTimeMillis())
            }.onFailure { Problems.error("db", "The unfinished tour could not be moved to the recycle bin", it) }.getOrDefault(false)
            if (ok) Backups.tourChanged(getApplication(), t.id)
            withContext(Dispatchers.Main) {
                // the dialog closing is the result; only a failure is said
                if (!ok) sayError(Txt.s(Res.string.msg_track_delete_failed), NoticePlace.MAP)
                refreshTours()
            }
        }
    }

    fun pauseRecording(pause: Boolean) =
        RecordingService.send(getApplication(), if (pause) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME)

    fun stopRecording() {
        routeBackAsk = false // a question about this tour's plan never reaches the next tour
        RecordingService.send(getApplication(), RecordingService.ACTION_STOP)
    }

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
                activityOrNull(t.activity)?.let { activity = it; prefs.activity = it } // unknown: the current one stays
                sac = t.sac
                shown = null
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                // stop names and POI pins saved with the plan come back too – the pins
                // before the points, so the first route already passes them exactly (recalc reads them at once)
                t.pinned.mapNotNull { pts.getOrNull(it) }.takeIf { it.isNotEmpty() }?.let { pinned += it; prefs.planPinned = pinned }
                change { clear(); addAll(pts) }
                t.waypoints.forEachIndexed { i, w -> w.name?.let { rememberPlaceName(pts[i], it) } }
                setSaved(t.name, t.id, pts)
                events += UiEvent.Fit(segs.flatten().map { it.toGeo() })
            } else {
                // a recorded or imported tour brings its activity, like the import: the T level stays inside the
                // tour's band, not during a tour (the followed route keeps its activity), a change is said in the
                // track panel; an unknown / missing activity keeps the current one
                val choice = SportType.openChoice(t.activity, t.sac, activity, sac, touring = recState.value != null)
                // before the track is shown: an activity change clears the panel's line, this one sets it
                if (choice.switch) {
                    if (choice.pick.activity == Activity.HIKE) { sac = choice.pick.sac.coerceIn(1, 5); prefs.sac = sac }
                    selectActivity(choice.pick.activity, userChange = false)
                }
                // rides (vehicle detection): the tour as chosen; rides not counted beside it, dropped parts not drawn
                val lines = com.onpath.app.rides.RideView.lines(segs, t.rides).filter { it.part != com.onpath.app.rides.RideView.Part.DROPPED }
                val kept = lines.filter { it.part == com.onpath.app.rides.RideView.Part.KEPT }.map { l -> l.points.map { it.toGeo() } }
                val rides = lines.filter { it.part != com.onpath.app.rides.RideView.Part.KEPT }.map { l ->
                    l.points.map { it.toGeo() } to (if (l.part == com.onpath.app.rides.RideView.Part.LIFT) com.onpath.app.rides.RideKind.LIFT
                        else com.onpath.app.rides.RideKind.VEHICLE)
                }
                showTrack(ShownTrack(t.id, t.name, kept, t.waypoints, t.distanceM, t.ascentM, t.descentM, t.durationS, rides,
                    note = choice.note))
            }
        }
    }

    // ---------------- plan sync with the browser (A-007: plansync/, docs/design/web-sync.md) ----------------

    /**
     * Saved routes and imported tracks ⇄ the paired browser. Runs in the background (plansync/PlanSync): at start,
     * back in the app, after a change here, when the network is back – never during a recording (after the tour).
     */
    val sync = com.onpath.app.plansync.PlanSync(app, com.onpath.app.plansync.DbTourStore(app, db) { a -> planFactors(a) },
        recording = { recState.value != null }, onReport = ::syncReport)

    /** A pairing link was opened: the secret, until Pair / Cancel in Settings → Browser sync; null = no question. */
    var pairAsk by mutableStateOf<ByteArray?>(null)
        private set

    init {
        sync.start()
        // a tour ended: what waited for it comes now
        viewModelScope.launch {
            var was = recState.value != null
            recState.collect { s -> if (was && s == null) sync.request("tour ended"); was = s != null }
        }
    }

    /** A pairing link (camera / QR code, a tapped link): Settings → Browser sync shows the check digits and asks. */
    private fun receivePairing(secret: ByteArray, via: String) {
        openSettings(SettingsPage.SYNC)
        viewModelScope.launch {
            val current = withContext(Dispatchers.IO) {
                try { sync.pairedMailbox() } catch (e: Exception) { Problems.warn("sync", "Stored pairing not read", e); null }
            }
            val incoming = com.onpath.app.sync.SyncKeys(secret).mailbox
            Problems.info("sync", "Pairing link ($via): " + when (current) { null -> "asking"; incoming -> "already paired"; else -> "paired with another browser" })
            when (current) {
                null -> pairAsk = secret
                incoming -> sayInfo(Txt.s(Res.string.sync_already_paired), NoticePlace.SETTINGS)
                else -> sayError(Txt.s(Res.string.sync_unpair_first), NoticePlace.SETTINGS)
            }
        }
    }

    /** "Pair": store the pairing, say hello, sync at once (the page shows "Paired with …" when the browser answered). */
    fun confirmPair() { val s = pairAsk ?: return; pairAsk = null; sync.pair(s) }

    fun cancelPair() { pairAsk = null; Problems.info("sync", "Pairing cancelled") }

    /** Settings → Sync now. */
    fun syncNow() {
        if (recState.value != null) { sayInfo(Txt.s(Res.string.sync_during_tour), NoticePlace.SETTINGS); return }
        sync.request("Sync now", manual = true)
    }

    /** Settings → Unpair (after the question): the mailbox ends for both sides; the routes stay everywhere. */
    fun unpair() {
        sync.unpair { status ->
            when (status) {
                200, 404, 410 -> sayInfo(Txt.s(Res.string.sync_unpaired), NoticePlace.SETTINGS)
                -2 -> sayError(Txt.s(Res.string.sync_unpair_failed), NoticePlace.SETTINGS)
                else -> sayInfo(Txt.s(Res.string.sync_unpaired_offline), NoticePlace.SETTINGS)
            }
        }
    }

    /** A sync pass ended (main thread): new / changed routes into My routes, plans from the browser routed. */
    private fun syncReport(r: com.onpath.app.plansync.PlanSync.Report) {
        val res = r.result
        if (r.ended) sayInfo(Txt.s(Res.string.sync_ended), NoticePlace.SETTINGS)
        // Pair / Sync now during a tour: nothing ran – it comes after the tour
        if (r.manual && res == null && !r.error && recState.value != null) sayInfo(Txt.s(Res.string.sync_during_tour), NoticePlace.SETTINGS)
        if (res?.toursChanged == true) {
            refreshTours()
            followSyncedSavedPlan()
            routeSyncedPlans(res.toRoute)
        }
    }

    /** The open plan's saved route was renamed or deleted from the browser: "Saved as …" follows (like Rename / Delete). */
    private fun followSyncedSavedPlan() {
        val id = linkId.takeIf { it >= 0 } ?: return
        viewModelScope.launch {
            val t = withContext(Dispatchers.IO) { runCatching { db.getTour(id) }.getOrNull() }
            if (linkId != id) return@launch
            if (t == null || t.deletedMs != null) setSaved(null, -1, null) else if (t.name != linkName) setSaved(t.name, id, savedPoints)
        }
    }

    /**
     * Routes from the browser are saved with their points as the line; here they get the real route (the router, the
     * pace) like a route saved on the phone. No routing data there yet: the points stay – opening the route routes it.
     */
    private fun routeSyncedPlans(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            for (id in ids.distinct()) {
                val t = withContext(Dispatchers.IO) { runCatching { db.getTour(id) }.getOrNull() } ?: continue
                if (t.kind != TourKind.PLANNED || t.waypoints.size < 2 || t.deletedMs != null) continue
                val act = activityOrNull(t.activity) ?: Activity.HIKE
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                val f = planFactors(act)
                val r = withContext(routingDispatcher) {
                    try {
                        val eng = routingEngine()
                        if (eng is com.onpath.app.routing.StraightLineEngine) null
                        else withModelTime(Planner(eng, com.onpath.app.routing.RouteCancel()).plan(pts, act, t.sac, t.pinned), act, f)
                    } catch (e: Exception) {
                        Problems.warn("sync", "A route from the browser could not be routed; it keeps its points until opened", e); null
                    }
                } ?: continue
                val ok = withContext(Dispatchers.IO) {
                    try {
                        // changed again meanwhile (a newer sync): leave it to that one's routing
                        val now = db.getTour(id)
                        now != null && now.waypoints == t.waypoints && db.replacePlanned(id, act.name, t.sac, r.distanceM, r.ascentM, r.descentM,
                            r.durationS, listOf(r.points.map { TrackPoint(it.lat, it.lon, it.ele) }), t.waypoints, t.pinned)
                            .also { if (it) { plannedTimes.remove(id); storePlanTimes(id, r, act) } }
                    } catch (e: Exception) { Problems.error("db", "A routed route from the browser could not be saved", e); false }
                }
                if (ok) Backups.tourChanged(getApplication(), id)
            }
            refreshTours()
        }
    }

    // ---------------- plan links (A-005: routing/PlanLinks, docs/core-features.md C15) ----------------

    /** Name a plan link brought, for the Save dialog while the plan still has the link's points. */
    private var linkTitle: Pair<String, List<GeoPoint>>? = null
    /** The points the last plan link brought (after snapping): while the plan is unchanged, the next link needs no question. */
    private var linkPoints: List<GeoPoint>? = prefs.planLinkPoints
        set(v) { field = v; prefs.planLinkPoints = v }

    /**
     * A plan link opened in OnPath (camera / QR code, a tapped link, shared text, pasted into the search box; [via] for
     * the log): back to the map in Plan and loaded like a saved route – "Replace current route?" first when the plan has
     * unsaved changes. During a tour it is not loaded (the followed route stays) and a grey line says so. A broken link
     * changes nothing and says so in one red line.
     */
    fun receivePlanLink(found: com.onpath.app.routing.PlanLinks.Found, via: String) {
        routesOpen = false; showSettings = false; offlineResult = null; timeDetails = null; routeDetailsOpen = false; searchOpen = false
        closeMapOverlays()
        val plan = when (found) {
            is com.onpath.app.routing.PlanLinks.Found.Broken -> {
                Problems.warn("plan", "Plan link ($via) could not be read: cut off, damaged or a newer format")
                sayError(Txt.s(Res.string.msg_plan_link_unreadable), NoticePlace.MAP)
                return
            }
            is com.onpath.app.routing.PlanLinks.Found.Plan -> found.plan
            // a pairing link of the web planner (☰ → Pair phone): Settings → Browser sync asks first (A-007)
            is com.onpath.app.routing.PlanLinks.Found.Pairing -> { receivePairing(found.secret, via); return }
            is com.onpath.app.routing.PlanLinks.Found.BrokenPairing -> {
                Problems.warn("sync", "Pairing link ($via) could not be read: cut off or a newer version")
                openSettings(SettingsPage.SYNC)
                sayError(Txt.s(Res.string.sync_link_broken), NoticePlace.SETTINGS)
                return
            }
        }
        viewModelScope.launch {
            // Cold start: the start mode (Plan / Navigate, an unfinished tour) is decided after a database check
            if (!startModeKnown) androidx.compose.runtime.snapshotFlow { startModeKnown }.first { it }
            when (RouteRules.linkOpen(navigating(recState.value != null), waypoints.toList(), savedPoints, plan.points, linkPoints)) {
                LinkOpen.AFTER_TOUR -> {
                    Problems.info("plan", "Plan link ($via, ${plan.points.size} points) not opened: a tour is running")
                    sayError(Txt.s(Res.string.msg_plan_link_after_tour), NoticePlace.MAP)
                }
                LinkOpen.ASK_FIRST -> {
                    if (shown != null) closeShown()
                    Problems.trace("plan", "Plan link ($via, ${plan.points.size} points): asking to replace the unsaved plan first")
                    replaceAsk = PlanToOpen.Shared(plan)
                }
                LinkOpen.NOW -> openSharedPlan(plan, via)
            }
        }
    }

    /** Text pasted into the search box: a plan link opens the plan (true); anything else is a search (false). */
    fun pastedPlanLink(text: String): Boolean {
        val found = com.onpath.app.routing.PlanLinks.fromText(text) ?: return false
        search.clear()
        receivePlanLink(found, "pasted")
        return true
    }

    /** Puts a plan from a link into the plan: points, activity, T level, point names, pinned points – a new, unsaved plan. */
    private fun openSharedPlan(plan: com.onpath.app.routing.SharedPlan, via: String = "after asking") {
        val load = com.onpath.app.routing.PlanLinks.load(plan)
        if (mode != AppMode.PLAN) enter(AppMode.PLAN)
        shown = null
        activity = plan.activity; prefs.activity = plan.activity
        sac = plan.sac.coerceIn(1, 5); prefs.sac = sac
        // pins before the points: the first route already passes them exactly (recalc reads them at once)
        if (load.pinned.isNotEmpty()) { pinned += load.pinned; prefs.planPinned = pinned }
        change { clear(); addAll(load.points) }
        load.names.forEach { (p, n) -> rememberPlaceName(p, n) }
        setSaved(null, -1, null) // not in My routes yet: Save asks for a name
        linkTitle = plan.title?.let { it to load.points }
        linkPoints = load.points
        Problems.info("plan", "Plan link opened ($via): ${load.points.size} points, ${plan.activity.name}" +
            (if (plan.activity == Activity.HIKE) " T${plan.sac}" else "") + ", ${load.pinned.size} pinned, ${load.names.size} names")
        events += UiEvent.Fit(load.points)
    }

    /** Sheet ⋯ → Share plan: the plan as a plan.onpath.earth link (PlanLinks) in the share sheet; from two points. */
    fun sharePlan() {
        if (waypoints.size < 2) return
        val pts = waypoints.toList()
        // the route's name: as saved, else the name a link brought while its points are unchanged
        val title = savedName ?: linkTitle?.takeIf { it.second == pts }?.first
        val plan = com.onpath.app.routing.PlanLinks.share(pts, activity, sac, pinnedIndices(), ::stopName, title)
        val link = com.onpath.app.routing.PlanLinks.link(plan)
        Problems.trace("plan", "Plan shared as a link: ${pts.size} points, ${link.length} characters")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
            title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.plan_share_plan)))
    }

    private fun showTrack(s: ShownTrack) {
        // A GPX opened from another app while navigating: show it in Plan (the recording goes on).
        if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN)
        shown = s
        events += UiEvent.Fit(s.points)
    }

    fun closeShown() { shown = null; highlight = null }

    /** A track is being turned into a plan ([planFromShown]): spinner on its button. */
    var planFromTrackBusy by mutableStateOf(false)
        private set

    /**
     * Shown track → a plannable route with few route points (owner; routing/TrackToPlan): our router and the
     * current activity re-trace the track; the result replaces the plan (Undo brings the old one back).
     */
    fun planFromShown() {
        val sh = shown ?: return
        if (planFromTrackBusy) return
        val track = sh.segments.flatten()
        if (track.size < 2) return
        val act = activity; val level = sac
        // routing data along the track first: without it every leg fails
        val missing = try { routingData.ensure(com.onpath.app.data.RoutingTiles.forLine(track)) } catch (e: Exception) {
            Problems.warn("routing", "Routing data along the track not checked; planning from the track anyway", e); emptyList()
        }
        if (missing.isNotEmpty()) {
            sayError(Txt.s(Res.string.plan_from_track_wait), NoticePlace.MAP)
            return
        }
        planFromTrackBusy = true
        viewModelScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = withContext(routingDispatcher) {
                try {
                    val eng = routingEngine()
                    if (eng is com.onpath.app.routing.StraightLineEngine) null
                    else com.onpath.app.routing.TrackToPlan.fit(track, eng, act, level)
                } catch (e: Exception) {
                    Problems.error("routing", "Track could not be turned into a plan", e); null
                }
            }
            planFromTrackBusy = false
            if (r == null || r.points.size < 2) { sayError(Txt.s(Res.string.plan_from_track_failed), NoticePlace.MAP); return@launch }
            Problems.trace("plan", "Track → plan: ${track.size} track points → ${r.points.size} route points, follows " +
                "${(r.follows * 100).toInt()} %, ${r.calls} routing calls, ${r.failedLegs} legs without a route, " +
                "${android.os.SystemClock.elapsedRealtime() - t0} ms")
            closeShown()
            change { clear(); addAll(r.points) }
        }
    }

    fun deleteTour(t: Tour) {
        // never the tour being recorded: the service keeps writing into it (the list hides Delete for it too)
        if (recState.value?.tourId == t.id) { sayError(Txt.s(Res.string.msg_track_recording_now), NoticePlace.ROUTES); return }
        viewModelScope.launch(Dispatchers.IO) {
            // into the recycle bin (30 days, My routes → ⋮ → Recycle bin); its backup file moves to Deleted/
            val ok = runCatching { db.setDeleted(t.id, System.currentTimeMillis()) }
                .onFailure { Problems.error("db", "Moving a tour to the recycle bin failed", it) }.getOrDefault(false)
            if (ok) { Backups.tourChanged(getApplication(), t.id); sync.changed("moved to the recycle bin") }
            withContext(Dispatchers.Main) {
                if (!ok) { routesProblem = Txt.s(Res.string.msg_tour_delete_failed, t.name); return@withContext }
                if (shown?.tourId == t.id) shown = null
                if (linkId == t.id) setSaved(null, -1, null) // the plan is no longer saved anywhere
                if (finishedTour?.id == t.id) finishedTour = null
                refreshTours()
            }
        }
    }

    // ---------------- recycle bin + backup (docs/core-features.md C11) ----------------

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
            if (ok) { Backups.tourChanged(getApplication(), t.id); sync.changed("restored from the recycle bin") }
            else routesProblem = Txt.s(Res.string.msg_tour_restore_failed, t.name)
            openRecycleBin(); refreshTours()
        }
    }

    /** Recycle bin → Delete now (asked first): gone for good, its backup file too. */
    fun deleteForever(t: Tour) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.deleteTour(t.id) }.onFailure { Problems.error("db", "Deleting a tour for good failed", it) }.isSuccess
            }
            if (ok) { Backups.purge(getApplication(), listOf(t.uuid)); sync.changed("deleted for good") }
            openRecycleBin()
        }
    }

    /** "Some parts of the file were skipped." – a line in the imported track's panel (the parser's warnings are in Diagnostics). */
    internal fun importedNote(warnings: List<String>): String? = if (warnings.isEmpty()) null else Txt.s(Res.string.msg_gpx_parts_skipped)

    /** "Export all": one ZIP with every tour and plan as GPX, handed to the share sheet (Files, Drive, mail …). */
    fun exportAll() {
        busy = Busy.EXPORT
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val uri = withContext(Dispatchers.IO) {
                    val f = Backups.exportZip(app, File(app.cacheDir, "export"), SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()))
                    FileProvider.getUriForFile(app, app.packageName + ".files", f) to f.name
                }
                busy = null
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri.first)
                    putExtra(Intent.EXTRA_SUBJECT, uri.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.msg_export_all_chooser)))
            } catch (e: com.onpath.app.backup.BackupException) {
                Problems.info("backup", "Export all: ${e.reason}", e); busy = null; sayError(Txt.s(Res.string.msg_export_reason, e.reason), NoticePlace.ROUTES)
            } catch (e: Exception) {
                Problems.error("backup", "Export all failed", e)
                busy = null
                sayError(Txt.s(Res.string.msg_export_failed), NoticePlace.ROUTES)
            }
        }
    }

    /** First start with an empty database: "Earlier tours found? Restore from Documents/OnPath" (asked once). */
    var restoreOffer by mutableStateOf(false)
    /** The system's location permission request is open (MainActivity); the restore offer waits ([RestoreOfferRule]). */
    var locationAsking by mutableStateOf(false)
    /** The loading modal waits for the map / routing data of the user's area. */
    val loadModalOpen: Boolean get() = loadModalFor.isNotEmpty() || routingModal
    // ---------------- Phone setup (docs/design/onboarding-phone-check.md) ----------------
    /** The phone as last read by MainActivity (every return to the app, every system answer). */
    var phone by mutableStateOf(com.onpath.app.setup.PhoneState(sdk = android.os.Build.VERSION.SDK_INT))
        private set
    /** Android's unused-app reset for OnPath (read off the main thread at start). */
    var unusedReset = com.onpath.app.setup.ResetStatus.UNKNOWN
    /** What the phone or the user removed since the last use (the guide and Settings say so calmly). */
    var phoneTaken by mutableStateOf<Map<com.onpath.app.setup.CheckId, com.onpath.app.setup.Taken>>(emptyMap())
    /** The first-start welcome ("Set up now / Later"). */
    var setupWelcome by mutableStateOf(false)
    /** The open step-by-step guide; null = none. */
    var setupGuide by mutableStateOf<com.onpath.app.setup.SetupGuide?>(null)
        private set
    private var setupThen: (() -> Unit)? = null
    val setupOpen: Boolean get() = setupWelcome || setupGuide != null
    /** The tour whose "Notifications off" chip was confirmed with OK (in memory: the next tour shows it again). */
    var notificationsOkTour by mutableStateOf<Long?>(null)

    /** Navigation vibrations in use: the vibration step applies only then. */
    val vibrationUsed: Boolean get() = settings.junctionVibration != com.onpath.app.data.JunctionVibration.OFF ||
        (settings.offRouteWarning && settings.offRouteVibrate)

    fun updatePhone(s: com.onpath.app.setup.PhoneState) {
        phone = s
        setupGuide = setupGuide?.refresh(s, vibrationUsed)
        finishSetupIfDone()
    }

    /**
     * Opens the guide for [purpose] with what is missing; nothing missing → [then] runs at once. Before a tour and for
     * the locate button [then] runs when the guide ends and what it needs is there.
     */
    fun openSetup(purpose: com.onpath.app.setup.Purpose, then: (() -> Unit)? = null) {
        val g = com.onpath.app.setup.SetupGuide.of(purpose, phone, vibrationUsed, prefs.snoozed(System.currentTimeMillis()))
            ?.refresh(phone, vibrationUsed)
        if (g == null || g.finished && then != null && setupReady(purpose)) { then?.invoke(); return }
        Problems.trace("setup", "Phone setup guide ($purpose): ${g.steps.joinToString()}")
        setupThen = then
        setupGuide = g
    }

    /** Settings → Phone setup → a row: that one step. */
    fun openSetupStep(id: com.onpath.app.setup.CheckId) {
        setupThen = null
        setupGuide = com.onpath.app.setup.SetupGuide.single(id)
    }

    fun setupWelcomeNow() {
        setupWelcome = false; prefs.setupWelcomeSeen = true
        openSetup(com.onpath.app.setup.Purpose.FIRST_SETUP)
    }

    /** "Later": before a tour a recommended step stays quiet for a week. */
    fun setupLater() {
        val g = setupGuide ?: return
        val id = g.current
        if (id != null && g.purpose == com.onpath.app.setup.Purpose.BEFORE_TOUR &&
            com.onpath.app.setup.PhoneCheck.need(id) != com.onpath.app.setup.Need.REQUIRED) prefs.snooze(id, System.currentTimeMillis())
        setupGuide = g.putOff().refresh(phone, vibrationUsed)
        finishSetupIfDone()
    }

    /** The system dialog for [id] came back without it. */
    fun setupDenied(id: com.onpath.app.setup.CheckId) {
        val g = setupGuide ?: return
        if (g.current == id && !com.onpath.app.setup.PhoneCheck.isOk(id, phone)) setupGuide = g.denied(id)
    }

    fun setupVibrationPlayed() { setupGuide = setupGuide?.vibrationPlayed() }

    fun setupVibrationAnswer(f: com.onpath.app.setup.Felt) {
        prefs.vibrationFelt = f
        Problems.trace("setup", "Vibration test: felt = $f")
        setupGuide = setupGuide?.vibrationAnswer(f)
        updatePhone(phone.copy(vibrationFelt = f))
    }

    fun setupBrandDone() {
        prefs.brandStepsDone = true
        updatePhone(phone.copy(brandDone = true))
    }

    /** Closes the welcome / guide (Back, Close, Later on the welcome). */
    fun closeSetup() {
        if (setupWelcome) { setupWelcome = false; prefs.setupWelcomeSeen = true }
        setupGuide = null
        setupThen = null
    }

    private fun setupReady(purpose: com.onpath.app.setup.Purpose) = when (purpose) {
        com.onpath.app.setup.Purpose.BEFORE_TOUR -> com.onpath.app.setup.PhoneCheck.tourReady(phone)
        com.onpath.app.setup.Purpose.LOCATE -> com.onpath.app.setup.PhoneCheck.isOk(com.onpath.app.setup.CheckId.LOCATION, phone)
        else -> true
    }

    /** At its end a guide for a tour / the locate button closes by itself and goes on; the others show their end page. */
    private fun finishSetupIfDone() {
        val g = setupGuide ?: return
        if (!g.finished) return
        val goOn = g.purpose == com.onpath.app.setup.Purpose.BEFORE_TOUR || g.purpose == com.onpath.app.setup.Purpose.LOCATE
        val single = g.purpose == com.onpath.app.setup.Purpose.SETTINGS && g.steps.size == 1
        if (goOn && setupReady(g.purpose) || single) {
            val then = setupThen
            setupGuide = null; setupThen = null
            then?.invoke()
        }
    }

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
                while (errorCard != null) delay(500) // after the current error card
                sayError(Txt.s(Res.string.msg_backup_failed, text), NoticePlace.BACKGROUND)
                Backups.failureSeen(getApplication())
            }
        }
    }

    fun restoreOfferAnswered() { restoreOffer = false; Backups.setRestoreOffered(getApplication()) }

    /** The folder the user confirmed in the picker: import every OnPath backup in it (off the main thread). */
    fun restoreFrom(tree: Uri) {
        if (restoreProgress != null) return
        restoreProgress = Txt.s(Res.string.msg_restore_looking)
        viewModelScope.launch {
            val app = getApplication<Application>()
            val r = withContext(Dispatchers.IO) {
                try { Backups.restore(app, tree) { line -> viewModelScope.launch(Dispatchers.Main) { if (restoreProgress != null) restoreProgress = line } } }
                catch (e: Exception) { Problems.error("backup", "Restore from the backup folder failed", e); null }
            }
            restoreProgress = null
            if (r != null) sync.changed("restored from the backup folder")
            // the result where it was started (Settings → Backup), else where the tracks are now: My routes (restore
            // offered at the first start); a failure there → the error card
            if (r != null) sayInfo(com.onpath.app.backup.BackupRestore.summary(r),
                if (showSettings) NoticePlace.SETTINGS else { routesOpen = true; NoticePlace.ROUTES })
            else sayError(Txt.s(Res.string.msg_restore_folder_unreadable), if (showSettings) NoticePlace.SETTINGS else NoticePlace.MAP)
            refreshTours()
        }
    }

    fun exportTour(t: Tour) {
        viewModelScope.launch {
            // as chosen at End tour: rides not counted are their own segments, a dropped ride is not exported
            val segs = withContext(Dispatchers.IO) { com.onpath.app.rides.RideView.layout(db.loadSegments(t.id), t.rides).trk.map { it.first } }
            // a planned route carries its route points as <wpt> (start, stops, goal – with their names): other apps
            // show them, and a re-import can rebuild the plan from them
            share(t.name, segs, t.waypoints, SportType.typeOf(t.activity, t.sac), t.kind)
        }
    }

    /** [sport] = the sport as `<trk><type>` (Komoot's string, so Komoot and OnPath read the activity back). */
    private suspend fun share(name: String, segs: List<List<TrackPoint>>, wpts: List<GpxWaypoint>, sport: String?,
                              kind: TourKind? = null) {
        try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "export").apply { mkdirs() }
                // the same in every language (owner): "Hilden - plan.gpx" / "Hilden - rec.gpx"
                val suffix = GpxWriter.kindSuffix(kind == TourKind.PLANNED, kind == TourKind.RECORDED)
                val file = File(dir, GpxWriter.fileName(name, suffix))
                file.writeText(GpxWriter.write(name, segs, wpts, type = sport))
                FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", file)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.common_export_gpx)))
        } catch (e: Exception) {
            Problems.error("gpx", "GPX export failed", e)
            sayError(Txt.s(Res.string.msg_export_failed), NoticePlace.ROUTES)
        }
    }

    // ---------------- GPX import ----------------

    fun importGpx(uri: Uri) {
        busy = Busy.IMPORT
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
                // Komoot's sport in <trk><type> preselects the activity (owner); unknown → the current one;
                // the user's T level stays when it fits the type's band, and a change is said
                val choice = SportType.importChoice(data.type, activity, sac, touring = recState.value != null)
                val pick = choice.pick
                val name = data.name ?: fileName?.removeSuffix(".gpx")?.removeSuffix(".GPX") ?: defaultName(Txt.s(Res.string.msg_name_import))
                val geo = segs.map { s -> s.map { it.toGeo() } }
                val dist = geo.sumOf { Geo.length(it) }
                var up = 0.0; var down = 0.0
                segs.forEach { s -> val (u, d) = Geo.climb(s.map { it.ele }); up += u; down += d }
                // the file's own time, else the one time model with the pace level (the same as this line planned)
                val dur = com.onpath.app.time.TimeEstimate.importedSeconds(segs, pick.activity, planFactors(pick.activity))
                val id = withContext(Dispatchers.IO) {
                    db.insertTour(TourKind.IMPORTED, name, pick.activity.name, pick.sac, dist, up, down, dur, segs, data.waypoints)
                }
                Backups.tourChanged(app, id)
                sync.changed("GPX imported")
                val pts = geo.flatten().ifEmpty { data.waypoints.map { GeoPoint(it.lat, it.lon) } }
                routesOpen = false // show the imported track on the map
                refreshTours()
                // like opening a recorded tour: its activity is chosen (not during a tour – the followed route keeps its own);
                // before the track is shown: an activity change clears the panel's line, this one sets it
                if (choice.switch) {
                    if (pick.activity == Activity.HIKE) { sac = pick.sac.coerceIn(1, 5); prefs.sac = sac }
                    selectActivity(pick.activity, userChange = false)
                }
                // something odd in the file: a grey line in the track panel, never a message (owner)
                showTrack(ShownTrack(id, name, geo, data.waypoints, dist, up, down, dur, note = choice.note,
                    skippedNote = importedNote(data.warnings)))
                if (geo.isEmpty()) events += UiEvent.Fit(pts)
                // Diagnostics: the type is the file's own text – cut short, and say when it was not one we know
                data.type?.let { t ->
                    val to = if (SportType.activityOf(t) == null) "unknown, kept ${pick.activity.name}"
                        else pick.activity.name + if (pick.activity == Activity.HIKE) " T${pick.sac}" else ""
                    Problems.info("gpx", "GPX import “$name”: type “${t.take(40)}” → $to")
                }
                // The track panel with its name is the feedback (+ its "parts skipped" line); the details go to Diagnostics
                busy = null
                if (data.warnings.isNotEmpty()) {
                    Problems.info("gpx", "GPX import “$name”: ${data.pointCount} points, ${data.waypoints.size} waypoints; " +
                        data.warnings.joinToString("; "))
                }
            } catch (e: Exception) {
                busy = null
                Problems.error("gpx", "GPX import failed", e)
                // GpxException: our own plain reason ("No track, route or waypoint found in this file.")
                val own = com.onpath.app.debug.UserText.of(e, "")
                // My routes when the import was started there; a file opened from another app → the error card
                sayError(if (own.isNotEmpty()) Txt.s(Res.string.msg_gpx_import_failed_reason, own)
                    else Txt.s(Res.string.msg_gpx_import_failed), NoticePlace.ROUTES)
            }
        }
    }

    // ---------------- routing data freshness (owner: routing matters most – a changed way must reroute) ----

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
        val names = listOf(line.first(), line.last()).map { com.onpath.app.routing.Rd5Parts.tileName(it.lat, it.lon) }.distinct()
        val due = names.map { java.io.File(dataFiles.segmentsDir, it) }
            .filter { it.isFile && t - it.lastModified() > ROUTING_MAX_AGE_DAYS * 86_400_000L }
        prefs.routingDataCheckedAt = t
        if (due.isEmpty() || (settings.mapsWifiOnly && !com.onpath.app.cells.AndroidNetworkStatus(getApplication()).unmetered())) return
        routingDataBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            var got = 0
            for (f in due) {
                try {
                    if (routingData.store.refreshIfNewer(f.name)) got++ // only the 1° parts on the phone
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
        // the plan sheet's grey "changed" line (a tap shows the whole route), not a message (owner)
        if (com.onpath.app.data.RoutingDataCheck.differs(before, r.points)) routeChangedNotice = Txt.s(Res.string.msg_route_updated_newer_data)
    }

    /** This ViewModel's [com.onpath.app.cells.MapCells.relevance] – onCleared removes only its own (two screens can exist at start). */
    private var ownRelevance: com.onpath.app.cells.MapCells.Relevance? = null

    // Last in the class: every property it touches is initialised by now.
    init {
        // a new cell store generation (cell added / replaced / deleted, region re-joined) → reload the map style
        // (new file paths; MapLibre keeps no stale PMTiles index) and recheck the data. The search index notices by itself.
        viewModelScope.launch {
            // what THIS ViewModel's map was asked to show (start: the data now – its map's first load takes it). Not the
            // process-wide StyleLoader.lastMapDir: with two activities alive the other one's map may have loaded the new
            // data, and this one would never ask its own map to reload (MapReload, StyleReloadRule).
            var asked: java.io.File? = dataFiles.mapDir()
            cells.generation.collect {
                val onMap = com.onpath.app.map.StyleLoader.lastMapDir
                if (onMap == null) { recheckData(); asked = dataFiles.mapDir() } // no map shown yet: its first load takes it
                else if (StyleReloadRule.ask(dataFiles.mapDir(), asked)) {
                    // once per burst, never while the map / sheet is dragged (a reload per region makes native memory
                    // grow); the camera and location tracking stay as they are
                    styleGateJob = gated(styleGate, styleGateJob, busy = { cells.userBusy }) {
                        val dir = dataFiles.mapDir()
                        if (StyleReloadRule.ask(dir, asked)) {
                            Problems.trace("map", "Map style reload asked for ${dir.name} (version ${styleVersion + 1})")
                            asked = dir
                            recheckData(); styleVersion++
                        }
                    }
                }
            }
        }
        // a joined / removed map area → the offline search index follows in the background (only changed areas are read;
        // not at start – an unchanged index needs nothing), so the next search or route name does not wait for it
        viewModelScope.launch {
            cells.generation.drop(1).collectLatest { delay(5_000); search.prepare() }
        }
        // map cells (map-cells.md §9): Wi-Fi setting, cells still loading from the last run on the card, T3 on fixes
        cells.wifiOnly = settings.mapsWifiOnly
        // asked right before each download (owner): does a point request still belong to the user?
        cells.relevance = object : com.onpath.app.cells.MapCells.Relevance {
            override fun waiting(): Set<com.onpath.app.cells.CellId> =
                loadModalFor + (cellJob?.takeIf { it.why == com.onpath.app.cells.CellWhy.TAPPED }?.cells ?: emptyList())
            override fun stillNear(c: com.onpath.app.cells.CellId): Boolean {
                if (recState.value != null || mode == AppMode.NAVIGATE) return true // on tour: the map ahead (T5)
                val pts = waypoints.toList() + listOfNotNull(searchMark?.let { GeoPoint(it.lat, it.lon) },
                    tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) })
                return com.onpath.app.cells.CellTriggers.nearAny(c, pts)
            }
        }.also { ownRelevance = it }
        safeCells("start") {
            val inst = cells.installed.value.map { it.id }.toSet()
            val loading = cells.states.value.filter { (c, s) -> c !in inst && s != com.onpath.app.cells.CellState.None &&
                s !is com.onpath.app.cells.CellState.Ready && s !is com.onpath.app.cells.CellState.Failed }.keys.sorted()
            if (loading.isNotEmpty() && cells.downloadsAvailable) cellJob = com.onpath.app.cells.CellJob(loading, null, com.onpath.app.cells.CellWhy.PLANNED)
        }
        viewModelScope.launch { tracker.location.filterNotNull().collect { cellsForFix(it) } }
        // a tour runs (Navigate): newer map versions wait – the map never switches under the user's feet
        viewModelScope.launch { androidx.compose.runtime.snapshotFlow { mode }.collect { cells.holdUpdates = it == AppMode.NAVIGATE } }
        // a tour started indoors (owner): the fused position until its first GPS fix – off with that fix, a pause, the end
        viewModelScope.launch {
            var asked = false
            recState.collect { r ->
                val ask = com.onpath.app.navigation.Provisional.asksPosition(r != null, r?.paused == true, r?.hadGpsFix == true)
                if (ask != asked) {
                    asked = ask
                    try { tracker.setFusedWanted(ask) } catch (e: Exception) {
                        Problems.warn("gps", "Provisional position request not ${if (ask) "started" else "stopped"}", e)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        com.onpath.app.data.AppRouting.unlisten(routingListener)
        // the screen's state is gone: only the age / corridor rules apply – unless a newer screen set its own already
        ownRelevance?.let { cells.releaseRelevance(it) }
        sync.stop()
        routingDispatcher.close()
        super.onCleared()
    }
}

/** Map style reload after a new map store generation: this quiet, at most this long after the first (ms). */
private const val STYLE_QUIET_MS = 2_000L
private const val STYLE_MAX_WAIT_MS = 15_000L
/** Plan recomputed after routing data arrived: this quiet (and the queue empty), at most this long (ms). */
private const val ARRIVAL_QUIET_MS = 1_500L
private const val ARRIVAL_MAX_WAIT_MS = 20_000L

/** Navigate profile scrubbing: how long the dot and the map stay at the spot after the finger leaves (ms). */
private const val SCRUB_HOLD_MS = 4000L
/** A shared place's point from the Google page may be this far from its address before the address wins. */
private const val SHARED_PAGE_MAX_OFF_M = 3_000.0
/** Routing data: checked at most this often after a route (ms), and refreshed when older than this (days). */
private const val ROUTING_CHECK_MS = 12 * 3600_000L
private const val ROUTING_MAX_AGE_DAYS = 35
/** Free look in Navigate ends after walking this far from where the map was turned / moved by hand. */
private const val FREE_LOOK_BACK_M = 25.0
