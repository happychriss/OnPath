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
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingService
import com.onpath.app.routing.Activity
import com.onpath.app.routing.EngineProvider
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.WaypointInsertion
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

enum class Mode { BROWSE, PLAN }

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

/** Short message after adding a point, with one alternative ("Make it the destination"). */
data class PlanNotice(val text: String, val actionLabel: String?, val action: NoticeAction?, val id: Long = System.nanoTime())

enum class NoticeAction { MAKE_DESTINATION, MAKE_STOP }

/** One-off things the UI must do with an Activity context. */
sealed interface UiEvent {
    data class Share(val intent: Intent) : UiEvent
    data class Fit(val points: List<GeoPoint>) : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val dataFiles = DataFiles(app)
    private val prefs = Prefs(app)
    private val db = TourDb.get(app)
    val tracker = LocationTracker(app)

    // --- data check ---
    var missing by mutableStateOf(dataFiles.check().filter { !it.present })
        private set
    var dataScreenDismissed by mutableStateOf(false)

    // --- navigation ---
    var showTours by mutableStateOf(false)
    var mode by mutableStateOf(Mode.BROWSE)
        private set
    /** POI whose details sheet is open (F-MAP-09). */
    var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    // --- activity ---
    var activity by mutableStateOf(prefs.activity)
        private set
    var sac by mutableStateOf(prefs.sac)
        private set
    // --- settings (Settings page) ---
    var settings by mutableStateOf(prefs.settings)
        private set
    var showSettings by mutableStateOf(false)
    val overlayOn: Boolean get() = settings.routeOverlay

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
    /** Eye button: planned line, markers and panel details temporarily hidden. */
    var routeHidden by mutableStateOf(false)
    /** Plan panel size (full / compact / mini), kept while the app runs. */
    var planSize by mutableStateOf(PlanSize.FULL)
    var notice by mutableStateOf<PlanNotice?>(null)
    /** Waypoint whose small menu (Remove / Make start / Make destination) is open. */
    var pointMenu by mutableStateOf<Int?>(null)
    private var lastAdded: Int = -1
    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    var routing by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    var highlight by mutableStateOf<GeoPoint?>(null)

    // --- shown track / follow ---
    var shown by mutableStateOf<ShownTrack?>(null)
        private set
    var followName by mutableStateOf(prefs.loadFollow()?.first)
        private set
    /** Line currently followed (drawn on the map while browsing). */
    var followLine by mutableStateOf(prefs.loadFollow()?.second)
        private set

    // --- position / camera ---
    var followMe by mutableStateOf(false)

    // --- dialogs / messages ---
    var message by mutableStateOf<String?>(null)
    var unfinished by mutableStateOf<Tour?>(null)
    var finishedTour by mutableStateOf<Tour?>(null)
    var tours by mutableStateOf<List<Tour>>(emptyList())
        private set
    var events = mutableStateListOf<UiEvent>()
        private set

    val recState: StateFlow<RecState?> = RecordingService.state

    private val routingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: RoutingEngine? = null
    private var routeJob: Job? = null
    private val requests = RouteRequests()
    /** The followed line was taken from this plan ("Follow & record"), so Clear removes it too. */
    private var followFromPlan = false

    init {
        if (waypoints.isNotEmpty()) mode = Mode.PLAN
        recalc()
        viewModelScope.launch(Dispatchers.IO) {
            val active = db.findActiveRecording()
            if (active != null && RecordingService.state.value == null) {
                delay(1500) // the service may be restarting by itself (sticky)
                if (RecordingService.state.value == null) withContext(Dispatchers.Main) { unfinished = active }
            }
        }
        viewModelScope.launch {
            RecordingService.finished.collect { id ->
                if (id != null) {
                    RecordingService.finished.value = null
                    finishedTour = withContext(Dispatchers.IO) { db.getTour(id) }
                    clearFollow()
                }
            }
        }
    }

    fun initialCamera(): Triple<Double, Double, Double> = prefs.camera ?: Triple(46.67, 11.16, 12.0)
    fun saveCamera(lat: Double, lon: Double, zoom: Double) { prefs.camera = Triple(lat, lon, zoom) }

    var batteryHintShown: Boolean
        get() = getApplication<Application>().getSharedPreferences("onpath", 0).getBoolean("batteryHint", false)
        set(v) { getApplication<Application>().getSharedPreferences("onpath", 0).edit().putBoolean("batteryHint", v).apply() }

    fun recheckData() { missing = dataFiles.check().filter { !it.present } }

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
                withContext(Dispatchers.Main) {
                    recheckData()
                    styleVersion++
                }
                r.summary
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

    fun startPlanning() { mode = Mode.PLAN; shown = null; showTours = false }

    fun closePlanning() { mode = Mode.BROWSE; highlight = null; planPoi = null; routeHidden = false; notice = null; pointMenu = null }

    fun updateSettings(s: AppSettings) {
        settings = s; prefs.settings = s
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED)
    }

    /** @param record false = amend the last step (notice actions), so one undo reverts both. */
    private fun change(record: Boolean = true, block: MutableList<GeoPoint>.() -> Unit) {
        if (record) { history.record(waypoints); notice = null } else history.dropRedo()
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

    private fun set(list: List<GeoPoint>, record: Boolean = true) = change(record) { clear(); addAll(list) }

    /**
     * Adds a tapped point where it most likely belongs (WaypointInsertion, F-PLAN-13) and tells
     * the user what happened, with one tap to choose the other option.
     * @param lineSnapM "on the line" distance, ~24 dp in metres at the current zoom.
     */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) {
        if (onPoi) { pinned += p; prefs.planPinned = pinned }
        val before = waypoints.toList()
        // While a reroute runs, the shown line belongs to older waypoints: use best-fit only.
        val d = WaypointInsertion.decide(before, p, if (routing) null else route?.points, lineSnapM)
        change { add(d.index.coerceIn(0, size), p) }
        lastAdded = d.index
        val n = waypoints.size
        notice = when {
            n <= 2 -> null // start and first destination are obvious
            d.kind == WaypointInsertion.Kind.DESTINATION ->
                PlanNotice("Added as new destination", "Insert as stop instead", NoticeAction.MAKE_STOP)
            else -> PlanNotice("Added as stop ${d.index + 1} of $n", "Make it the destination", NoticeAction.MAKE_DESTINATION)
        }
    }

    fun onNoticeAction(a: NoticeAction) {
        val i = lastAdded
        val w = waypoints.toList()
        if (i !in w.indices) { notice = null; return }
        val p = w[i]
        when (a) {
            NoticeAction.MAKE_DESTINATION -> {
                set(PlanEdits.move(w, i, w.size - 1), record = false); lastAdded = w.size - 1
                notice = PlanNotice("Now the destination", null, null)
            }
            NoticeAction.MAKE_STOP -> {
                val rest = w.toMutableList().apply { removeAt(i) }
                val at = PlanEdits.bestViaIndex(rest, p)
                set(rest.apply { add(at, p) }, record = false); lastAdded = at
                notice = PlanNotice("Now stop ${at + 1} of ${w.size}", null, null)
            }
        }
    }

    fun removePoint(index: Int) { if (index in waypoints.indices) change { removeAt(index) } }

    /** Plan mode tap on a POI: remove it if it already is a route point, otherwise add it (pinned). */
    fun togglePoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        val idx = waypoints.indexOfFirst { kotlin.math.abs(it.lat - poi.lat) < 1e-7 && kotlin.math.abs(it.lon - poi.lon) < 1e-7 }
        if (idx >= 0) removePoint(idx) else addPoint(GeoPoint(poi.lat, poi.lon), lineSnapM, onPoi = true)
    }

    /** POI tapped in plan mode: its card (add to route / Google Maps) is shown above the plan panel. */
    var planPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    fun isStop(poi: com.onpath.app.poi.Poi): Boolean =
        waypoints.any { kotlin.math.abs(it.lat - poi.lat) < 1e-7 && kotlin.math.abs(it.lon - poi.lon) < 1e-7 }

    /** Drag-to-move: the point keeps its position in the order. */
    fun movePoint(index: Int, p: GeoPoint) { if (index in waypoints.indices) set(PlanEdits.replace(waypoints, index, p)) }

    /** Dragging the route line: a new stop inserted at [index] (the leg it was dragged from). */
    fun insertPoint(index: Int, p: GeoPoint) {
        change { add(index.coerceIn(1, size), p) }
        notice = PlanNotice("Added as stop ${index.coerceIn(1, waypoints.size - 1) + 1} of ${waypoints.size}", null, null)
    }

    fun makeStart(index: Int) { if (index > 0 && index in waypoints.indices) set(PlanEdits.move(waypoints, index, 0)) }
    fun makeDestination(index: Int) {
        if (index in waypoints.indices && index != waypoints.lastIndex) set(PlanEdits.move(waypoints, index, waypoints.size - 1))
    }
    fun reverseRoute() { if (waypoints.size >= 2) set(PlanEdits.reverse(waypoints)) }
    fun roundTrip() {
        val w = PlanEdits.roundTrip(waypoints)
        if (w.size != waypoints.size) set(w) else if (waypoints.size >= 2) message = "The route already ends at the start."
    }

    fun startAtMyPosition() {
        val loc = tracker.location.value
        if (loc == null) { message = "No position yet – waiting for GPS."; return }
        val p = GeoPoint(loc.latitude, loc.longitude)
        change { if (isEmpty()) add(p) else add(0, p) }
    }

    fun undoStep() = step(history.undo(waypoints))
    fun redoStep() = step(history.redo(waypoints))

    private fun step(prev: List<GeoPoint>?) {
        prev ?: return
        waypoints.clear(); waypoints.addAll(prev)
        canUndo = history.canUndo; canRedo = history.canRedo
        notice = null; pointMenu = null
        prefs.planWaypoints = waypoints.toList()
        recalc()
    }

    /** Clear button; with two or more points the UI asks first (PlanClear.needsConfirm). */
    var confirmClear by mutableStateOf(false)

    fun requestClear() {
        if (PlanClear.needsConfirm(waypoints.size)) confirmClear = true else clearPlan()
    }

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
        notice = null; pointMenu = null; highlight = null; lastAdded = -1
        val fromPlan = followFromPlan || (planLine != null && followLine == planLine)
        if (fromPlan && followLine != null && recState.value == null) clearFollow()
    }

    private fun recalc() {
        routeJob?.cancel(); routeJob = null
        highlight = null
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        if (pts.size < 2) { requests.invalidate(); route = null; routeError = null; routing = false; return }
        val act = activity; val level = sac
        val gen = requests.next()
        routing = true
        routeJob = viewModelScope.launch {
            delay(150) // debounce quick taps
            val result = withContext(routingDispatcher) {
                runCatching {
                    val eng = engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir)
                        .also { if (EngineProvider.isRealRouter) engine = it } // retry later if it failed
                    Planner(eng).plan(pts, act, level, pins)
                }
            }
            if (!requests.isCurrent(gen) || !isActive) return@launch // superseded or cleared
            result.onSuccess { route = it; routeError = it.error }
                .onFailure { route = null; routeError = it.message ?: "Routing failed." }
            routing = false
        }
    }

    fun saveRoute(name: String) {
        val r = route ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.insertTour(
                TourKind.PLANNED, name.ifBlank { defaultName("Route") }, activity.name, sac,
                r.distanceM, r.ascentM, r.descentM, r.durationS,
                listOf(r.points.map { TrackPoint(it.lat, it.lon, it.ele) }),
                waypoints.map { GpxWaypoint(it.lat, it.lon, null) },
            )
            withContext(Dispatchers.Main) { message = "Route saved to My tours." }
        }
    }

    fun defaultName(prefix: String) =
        "$prefix " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())

    // ---------------- follow / record ----------------

    fun followPlanned() {
        val r = route ?: return
        setFollow(defaultName("Route"), r.points)
        followFromPlan = true
    }

    fun followShown() {
        val s = shown ?: return
        setFollow(s.name, s.points)
        followFromPlan = false
    }

    private fun setFollow(name: String, points: List<GeoPoint>) {
        prefs.saveFollow(name, points)
        followName = name
        followLine = points
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED)
    }

    fun clearFollow() {
        followFromPlan = false
        prefs.saveFollow("", null)
        followName = null
        followLine = null
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
        viewModelScope.launch(Dispatchers.IO) {
            db.updateStats(t.id, t.distanceM, t.ascentM, t.descentM, t.durationS, "done")
            withContext(Dispatchers.Main) { message = "Tour saved to My tours." }
        }
    }

    fun pauseRecording(pause: Boolean) =
        RecordingService.send(getApplication(), if (pause) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME)

    fun stopRecording() = RecordingService.send(getApplication(), RecordingService.ACTION_STOP)

    // ---------------- tours ----------------

    fun refreshTours() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.listTours().filter { it.state == "done" }
            withContext(Dispatchers.Main) { tours = list }
        }
    }

    fun openTour(t: Tour) {
        viewModelScope.launch {
            val segs = withContext(Dispatchers.IO) { db.loadSegments(t.id) }
            showTours = false
            if (t.kind == TourKind.PLANNED && t.waypoints.size >= 2) {
                // Back into planning with its via points (recalculated with the current engine).
                t.activity?.let { a -> runCatching { Activity.valueOf(a) }.getOrNull()?.let { activity = it; prefs.activity = it } }
                sac = t.sac
                shown = null
                mode = Mode.PLAN
                change { clear(); addAll(t.waypoints.map { GeoPoint(it.lat, it.lon) }) }
                events += UiEvent.Fit(segs.flatten().map { it.toGeo() })
            } else {
                showTrack(ShownTrack(t.id, t.name, segs.map { s -> s.map { it.toGeo() } }, t.waypoints,
                    t.distanceM, t.ascentM, t.descentM, t.durationS))
            }
        }
    }

    private fun showTrack(s: ShownTrack) {
        mode = Mode.BROWSE
        shown = s
        events += UiEvent.Fit(s.points)
    }

    fun closeShown() { shown = null; highlight = null }

    fun deleteTour(t: Tour) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteTour(t.id)
            withContext(Dispatchers.Main) {
                if (shown?.tourId == t.id) shown = null
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
                showTrack(ShownTrack(id, name, geo, data.waypoints, dist, up, down, dur))
                if (geo.isEmpty()) events += UiEvent.Fit(pts)
                message = buildString {
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
