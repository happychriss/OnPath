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
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Planner
import com.onpath.app.routing.RoutingEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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
    var overlayOn by mutableStateOf(true)

    // --- planning ---
    val waypoints = mutableStateListOf<GeoPoint>().apply { addAll(prefs.planWaypoints) }
    private val undo = ArrayDeque<List<GeoPoint>>()
    var canUndo by mutableStateOf(false)
        private set
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
    private var routeGen = 0

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

    // ---------------- activity ----------------

    fun selectActivity(a: Activity) {
        activity = a; prefs.activity = a; recalc()
    }

    fun selectSac(level: Int) {
        sac = level.coerceIn(1, 5); prefs.sac = sac; recalc()
    }

    // ---------------- planning ----------------

    fun startPlanning() { mode = Mode.PLAN; shown = null; showTours = false }

    fun closePlanning() { mode = Mode.BROWSE; highlight = null }

    private fun change(block: MutableList<GeoPoint>.() -> Unit) {
        undo.addLast(waypoints.toList())
        if (undo.size > 100) undo.removeFirst()
        waypoints.block()
        canUndo = true
        prefs.planWaypoints = waypoints.toList()
        recalc()
    }

    fun addPoint(p: GeoPoint) = change { add(p) }

    fun removePoint(index: Int) { if (index in waypoints.indices) change { removeAt(index) } }

    fun startAtMyPosition() {
        val loc = tracker.location.value
        if (loc == null) { message = "No position yet – waiting for GPS."; return }
        val p = GeoPoint(loc.latitude, loc.longitude)
        change { if (isEmpty()) add(p) else add(0, p) }
    }

    fun undoStep() {
        val prev = undo.removeLastOrNull() ?: return
        waypoints.clear(); waypoints.addAll(prev)
        canUndo = undo.isNotEmpty()
        prefs.planWaypoints = waypoints.toList()
        recalc()
    }

    fun clearPlan() { if (waypoints.isNotEmpty()) change { clear() } }

    private fun recalc() {
        routeJob?.cancel()
        highlight = null
        val pts = waypoints.toList()
        if (pts.size < 2) { route = null; routeError = null; routing = false; return }
        val act = activity; val level = sac
        val gen = ++routeGen
        routing = true
        routeJob = viewModelScope.launch {
            delay(150) // debounce quick taps
            val result = withContext(routingDispatcher) {
                runCatching {
                    val eng = engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir)
                        .also { if (EngineProvider.isRealRouter) engine = it } // retry later if it failed
                    Planner(eng).plan(pts, act, level)
                }
            }
            if (gen != routeGen) return@launch // a newer request superseded this one
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
    }

    fun followShown() {
        val s = shown ?: return
        setFollow(s.name, s.points)
    }

    private fun setFollow(name: String, points: List<GeoPoint>) {
        prefs.saveFollow(name, points)
        followName = name
        followLine = points
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED)
    }

    fun clearFollow() {
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
