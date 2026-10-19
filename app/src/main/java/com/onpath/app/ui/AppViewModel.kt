package com.onpath.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onpath.app.data.AppSettings
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Geo
import com.onpath.app.data.Prefs
import com.onpath.app.data.Tour
import com.onpath.app.data.TourDb
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxParser
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.LocationTracker
import com.onpath.app.navigation.NavKind
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
    /** Orientation button in Navigate: toggles and re-centres on the position (when there is a fix). */
    fun toggleNavOrientation() {
        settings = settings.copy(navHeadingUp = !settings.navHeadingUp); prefs.settings = settings
        if (tracker.location.value != null) followMe = true
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
    /** Waypoint whose point menu is open (hold on its marker, MapGestures). */
    var pointMenu by mutableStateOf<Int?>(null)
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
    /** The start mode (Plan / Navigate) is decided (database checked for an unfinished tour). */
    var startModeKnown by mutableStateOf(false)
        private set
    /** The user moved the map by a gesture since the app started (StartCamera never jumps after that). */
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
                    reloadPace()
                    refreshTours()
                    clearFollow()
                    arrivalAsk = false
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
                // Offline or GitHub unreachable: silent for the automatic check.
                if (manual) UpdateState.Failed("Could not check for updates (${e.message ?: "no connection"}).") else UpdateState.Idle
            }
            withContext(Dispatchers.Main) { update = result }
        }
    }

    /** Downloads the update and opens the Android installer (the user confirms there). */
    fun installUpdate() {
        val app = getApplication<Application>()
        val rel = (update as? UpdateState.Available)?.release ?: return
        if (!com.onpath.app.update.AppUpdater.canInstall(app)) {
            message = "Allow OnPath to install updates, then tap Install again."
            try { app.startActivity(com.onpath.app.update.AppUpdater.permissionIntent(app)) } catch (_: Exception) { }
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
                withContext(Dispatchers.Main) {
                    recheckData()
                    if (newTiles && recState.value == null) restartForData = true else styleVersion++
                }
                r.summary + packageNote() +
                    if (newTiles && recState.value != null) " Restart OnPath after the tour to see the new map." else ""
            } catch (e: com.onpath.app.data.DataImporter.ImportException) {
                e.message ?: "Import failed."
            } catch (e: Exception) {
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
                "Routing data download stopped: ${e.message ?: e.javaClass.simpleName} Tap again to resume."
            }
            withContext(Dispatchers.Main) { dataBusy = null; message = msg }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null } ?: uri.lastPathSegment?.substringAfterLast('/')

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
        if (paceChanged) retime()
        // The SAC level is set in Settings only (owner) – it applies right away to the current hiking route.
        if (sacChanged) { sac = s.defaultSac.coerceIn(1, 5); prefs.sac = sac; if (activity == Activity.HIKE) recalc() }
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_SETTINGS)
    }

    /** One undoable change of the planned points. */
    private fun change(block: MutableList<GeoPoint>.() -> Unit) {
        history.record(waypoints)
        waypoints.block()
        canUndo = history.canUndo; canRedo = history.canRedo
        prefs.planWaypoints = waypoints.toList()
        prunePinned()
        recalc()
    }

    /** Forget pinned points that are neither planned nor reachable by undo/redo. */
    private fun prunePinned() {
        if (pinned.isEmpty()) return
        val alive = HashSet(waypoints).apply { addAll(history.allPoints()) }
        if (pinned.retainAll(alive)) prefs.planPinned = pinned
    }

    private fun set(list: List<GeoPoint>) = change { clear(); addAll(list) }

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

    /** First hold on an empty plan: the held spot, its menu "Start here · Go here from my location" is open. */
    var firstPointMenu by mutableStateOf<GeoPoint?>(null)

    val mapOverlayOpen: Boolean get() = poiMenu != null || pointMenu != null || firstPointMenu != null || planPoi != null

    fun closeMapOverlays() {
        // The search mark lives only until its menu is done (owner): closing that menu – by an action or by
        // tapping elsewhere – removes the orange ring too.
        if (poiMenu != null) searchMark = null // the menu may belong to the place's own map icon a few metres away
        poiMenu = null; pointMenu = null; firstPointMenu = null; if (planPoi != null) closePlaceCard()
    }
    fun openFirstPointMenu(p: GeoPoint) { closeMapOverlays(); if (waypoints.isEmpty()) firstPointMenu = p }

    /** First-point menu → "Start here": the held spot is the start; later holds add points as usual. */
    fun startHere(p: GeoPoint) { firstPointMenu = null; addPoint(p) }

    /** First-point menu → "Go here from my location": start = current position, the held spot = destination. */
    fun routeFromMyLocation(p: GeoPoint) {
        firstPointMenu = null
        val loc = tracker.location.value ?: return // the menu only offers this with a position
        if (waypoints.isEmpty()) change { add(GeoPoint(loc.latitude, loc.longitude)); add(p) } else addPoint(p)
    }
    fun openPoiMenu(poi: com.onpath.app.poi.Poi) { closeMapOverlays(); poiMenu = poi }

    /** POI menu on an empty plan → "Go here from my location": start = position, the POI (pinned, named) = destination. */
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
    fun openPointMenu(index: Int) { closeMapOverlays(); if (index in waypoints.indices) pointMenu = index }

    /** POI menu → "Add to route": a pinned, named route point; when it already is a stop, its point menu opens. */
    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        closeMapOverlays()
        val idx = waypoints.indexOfFirst { kotlin.math.abs(it.lat - poi.lat) < 1e-7 && kotlin.math.abs(it.lon - poi.lon) < 1e-7 }
        if (idx >= 0) { pointMenu = idx; return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        clearSearchMarkAt(poi.lat, poi.lon)
        addPoint(p, lineSnapM, onPoi = true)
    }

    /** Point menu → "Make starting point" (not on the start). */
    /** Point menu → "Start here" (drops earlier points) / round trip "Start & finish here" (rotates the loop). */
    fun makeStartFromMenu(index: Int) {
        pointMenu = null
        if (index !in waypoints.indices) return
        set(if (isRoundTrip()) PlanEdits.rotateLoop(waypoints, index) else PlanEdits.startAt(waypoints, index))
    }

    fun isRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints)

    /** Point menu → "End route here" (middle points only): that point becomes the destination. */
    fun endRouteAt(index: Int) { pointMenu = null; if (index in waypoints.indices) set(PlanEdits.endAt(waypoints, index)) }

    /** Point menu → "Delete" (Undo brings it back). */
    fun deletePointFromMenu(index: Int) { pointMenu = null; removePoint(index) }
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
            com.onpath.app.search.PhotonProvider(com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME))) {
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

    fun openSearch() { closeMapOverlays(); searchOpen = true; search.onOpen() }
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
        pointMenu = null; firstPointMenu = null
        prefs.planWaypoints = waypoints.toList()
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
        pointMenu = null; firstPointMenu = null; highlight = null
        val fromPlan = followFromPlan || (planLine != null && followLine == planLine)
        if (fromPlan && followLine != null && recState.value == null) clearFollow()
    }

    private fun recalc() {
        routeJob?.cancel(); routeJob = null
        highlight = null
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        if (pts.size < 2) { requests.invalidate(); route = null; routeError = null; routing = false; followNextRoute = false; return }
        val act = activity; val level = sac
        val f = planFactors(act)
        val gen = requests.next()
        routing = true
        routeJob = viewModelScope.launch {
            delay(150) // debounce quick taps
            val result = withContext(routingDispatcher) {
                runCatching {
                    val eng = engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir)
                        .also { if (EngineProvider.isRealRouter) engine = it } // retry later if it failed
                    withModelTime(Planner(eng).plan(pts, act, level, pins), act, f)
                }
            }
            if (!requests.isCurrent(gen) || !isActive) return@launch // superseded or cleared
            result.onSuccess {
                route = it; routeError = it.error; snapToRoute(pts, pins, it)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (adoptPlanBaseline) { adoptPlanBaseline = false; planBaseline = it.points }
            }
                .onFailure { route = null; routeError = it.message ?: "Routing failed." }
            if (followNextRoute) {
                followNextRoute = false
                if (route != null) followPlanned(checkNow = true) else message = "Could not re-route – still following the previous line."
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
    } catch (_: Exception) { null }

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
        try { planTimes.put(id, r.distanceM, com.onpath.app.time.RouteTimes.of(r.points, a).groupTotals()) } catch (_: Exception) { }
    }

    /** Planned routes show the model time (recomputed, the stored router time is ignored); others as stored. */
    private fun withModelTime(t: Tour): Tour {
        if (t.kind != TourKind.PLANNED) return t
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
        } catch (_: Exception) { t }
    }

    /**
     * Moves free points onto the way the router used (owner: no off-road guidance), so marker, stored
     * plan and route line agree. POI-pinned and named stops keep their coordinates. Amends the current
     * state (no undo step; history snapshots get the same values) and does not re-route: [route] already
     * belongs to the snapped points. Only for the newest result of exactly these points.
     */
    private fun snapToRoute(routed: List<GeoPoint>, pins: Set<Int>, r: PlannedRoute) {
        if (waypoints.toList() != routed) return
        val keep = pins + routed.indices.filter { stopName(routed[it]) != null }
        val snapped = WaypointSnap.apply(routed, r.snapped, keep) ?: return
        for (i in snapped.indices) if (snapped[i] != routed[i]) waypoints[i] = snapped[i]
        if (!RouteRules.isUnsaved(routed, savedPoints)) setSaved(savedName, savedId, snapped)
        history.replacePoints(WaypointSnap.moves(routed, snapped))
        prefs.planWaypoints = waypoints.toList()
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
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    db.insertTour(
                        TourKind.PLANNED, title, act.name, level,
                        r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                        listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                        pts.map { GpxWaypoint(it.lat, it.lon, null) },
                    ).also { storePlanTimes(it, r, act) }
                }.getOrNull()
            }
            if (id == null) { routesProblem = "The route could not be saved. Your plan is still here – please try again."; return@launch }
            setSaved(title, id, pts)
            refreshTours()
            then?.invoke()
        }
    }

    /** ⋮ → Save route: Save / Save as new… when the plan belongs to a saved route (RouteRules.saveMode), else the name dialog. */
    fun requestSave() {
        if (waypoints.size < 2) return
        when (currentSaveMode()) {
            SaveMode.OVERWRITE_OR_NEW -> saveChoice = SaveChoice(null)
            SaveMode.NAME_DIALOG -> saveDialog = true
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
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    db.replacePlanned(
                        id, act.name, level,
                        r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                        listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                        pts.map { GpxWaypoint(it.lat, it.lon, null) },
                    ).also { if (it) { plannedTimes.remove(id); storePlanTimes(id, r, act) } }
                }
            }
            ok.onSuccess { replaced ->
                if (replaced) { setSaved(name, id, pts); refreshTours(); then?.invoke() }
                else saveRoute(name, then) // deleted meanwhile: keep the work as a new route with the same name
            }.onFailure {
                routesProblem = "The route could not be saved. Your plan is still here – please try again."
            }
        }
    }

    /** Choice "Save as new…": the name dialog (then loading the picked route when it came from "Save first"). */
    fun saveChoiceAsNew() {
        val choice = saveChoice ?: return
        saveChoice = null
        if (choice.thenOpen != null) saveFirst = choice.thenOpen else saveDialog = true
    }

    /** Manage routes → Rename. The plan's "Saved as …" and a shown track follow the new name. */
    fun renameTour(t: Tour, input: String) {
        val name = RouteRules.cleanName(input, t.name)
        if (name == t.name) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { db.rename(t.id, name) }.isSuccess }
            if (!ok) { routesProblem = "The route could not be renamed. Please try again."; return@launch }
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
        if (currentSaveMode() == SaveMode.OVERWRITE_OR_NEW) saveChoice = SaveChoice(t) else saveFirst = t
    }

    fun saveFirstDone(name: String) {
        val t = saveFirst ?: return
        saveFirst = null
        saveRoute(name) { openTour(t) }
    }

    fun defaultName(prefix: String) =
        "$prefix " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())

    // ---------------- modes: Plan <-> Navigate ----------------

    /** Switches mode; the recording goes on either way, only off-route checks pause in Plan. */
    private fun enter(m: AppMode) {
        mode = m
        if (m == AppMode.NAVIGATE) { planPoi = null; poiMenu = null; pointMenu = null; firstPointMenu = null; highlight = null; followMe = true }
        else followMe = false
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
            else if (message == null) message = "Recording did not start. Check the location permission (precise) and notifications, then try again."
        }
    }

    private var startJob: Job? = null
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
                runCatching { com.onpath.app.navigation.ActivityCheck.rest(session, progress, pos) }.getOrNull()
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
            recState.value?.tourId?.let { id -> viewModelScope.launch(Dispatchers.IO) { runCatching { db.updateActivity(id, a.name, sacFor(a)) } } }
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
        }.getOrNull() ?: return
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
        }.getOrNull()
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
                t.activity?.let { a -> runCatching { Activity.valueOf(a) }.getOrNull()?.let { activity = it; prefs.activity = it } }
                sac = t.sac
                shown = null
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                change { clear(); addAll(pts) }
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
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteTour(t.id)
            withContext(Dispatchers.Main) {
                if (shown?.tourId == t.id) shown = null
                if (savedId == t.id) setSaved(null, -1, null) // the plan is no longer saved anywhere
                if (finishedTour?.id == t.id) finishedTour = null
                refreshTours()
            }
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
                    }.getOrNull()
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
                message = "Could not import this file: ${e.message ?: "unknown error"}"
            }
        }
    }

    override fun onCleared() {
        routingDispatcher.close()
        super.onCleared()
    }
}
