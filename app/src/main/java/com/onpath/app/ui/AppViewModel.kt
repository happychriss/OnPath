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
import com.onpath.app.debug.launchSafe
import com.onpath.app.debug.reported
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
import com.onpath.app.navigation.TourSplit
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingService
import com.onpath.app.routing.Activity
import com.onpath.app.routing.EngineProvider
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.search.SharedPlaceLookup
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.onpath.strings.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.onpath.app.domain.plan.FollowHistory
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.plan.RouteBack
import com.onpath.app.domain.plan.RoutingSpot
import com.onpath.app.domain.plan.PlanSession
import com.onpath.app.domain.tour.QuietGate

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

/** One-off things the UI must do with an Activity context. */
sealed interface UiEvent {
    data class Share(val intent: Intent) : UiEvent
    /**
     * Show all [points] (MapController.fitTo). North up or at the current bearing by the session's one orientation
     * ([com.onpath.app.domain.map.MapOrientation.moveNorthUp]) – never decided per fit (owner 2026-11-07).
     */
    data class Fit(val points: List<GeoPoint>) : UiEvent
    /** Fly to one place at [zoom] (search results), in the free room of the fit padding (FitPaddingRule). */
    data class CenterOn(val point: GeoPoint, val zoom: Double) : UiEvent
    /** Move the map to [point] at once, same zoom (profile scrubbing in Navigate). */
    data class PanTo(val point: GeoPoint) : UiEvent
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val dataFiles = DataFiles(app)
    val prefs = Prefs(app)
    /**
     * viewModelScope for the helper classes (map data, search, updates, pairing): same job, plus a handler that reports
     * an unexpected error to the problem log instead of ending the app (owner 2026-11-07). The view model's own
     * launches use [launchSafe] with their function's name.
     */
    private val helperScope by lazy { viewModelScope.reported("app") }
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
        viewModelScope.launchSafe("app", "start-up work", Dispatchers.IO) {
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

    // --- the tour's lifecycle state (audit package 4 stage 2: a focused state holder, the VM forwards) ---
    /** End sheet, arrival box, saved box, interrupted tour, "Neuer Plan" line – see [TourController]. */
    val tour = com.onpath.app.ui.tour.TourController(
        object : com.onpath.app.ui.tour.TourRecorder {
            override fun start(activity: String, sac: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_START) {
                putExtra(RecordingService.EXTRA_ACTIVITY, activity)
                putExtra(RecordingService.EXTRA_SAC, sac)
            }
            override fun continueTour(tourId: Long) = RecordingService.send(getApplication(), RecordingService.ACTION_START) {
                putExtra(RecordingService.EXTRA_CONTINUE_ID, tourId)
            }
            override fun pause(pause: Boolean) =
                RecordingService.send(getApplication(), if (pause) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME)
            override fun stop() = RecordingService.send(getApplication(), RecordingService.ACTION_STOP)
        },
    )

    // --- screens and mode ---
    // messages (declared before routesOpen / showSettings, whose setters clear their lines) – see [say]
    /** The red error box (MessageCard HINWEIS_ERROR: ⚠, red edge, OK) on the screen shown; null = none. Never cleared by itself. */
    var errorCard by mutableStateOf<String?>(null)
    /** The line at the top of My routes; goes when My routes is left. */
    var routesNote by mutableStateOf<Notice?>(null)
    /**
     * A plan or a done tour was tapped in My routes during a tour and is followed now – no question (owner 2026-11-06
     * 22:05, "super verwirrend"): Navigate says "Neuer Plan – deine Aufnahme läuft weiter" at the bottom for a few
     * seconds ([NewPlanLine]), with "Rückgängig" when a plan was replaced ([undoNewPlan]). Null = no line.
     */
    val newPlanNote: NewPlanNote? get() = tour.newPlanNote
    fun dropNewPlanNote() = tour.dropNewPlanNote()

    /** The line at the top of the open Settings page; goes when the page changes or Settings closes. */
    var settingsNote by mutableStateOf<Notice?>(null)
    /**
     * My routes' state and decisions ([com.onpath.app.ui.routes.RoutesState], audit package 4 stage 3): open, tab, the
     * list, Zuletzt, the "✓ Gerade gespeichert" mark; the database behind a small store. The names below forward to it.
     */
    private val routes = com.onpath.app.ui.routes.RoutesState(object : com.onpath.app.ui.routes.RoutesStore {
        override fun listTours() = db.listTours()
        override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
        override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
        override fun pruneDrafts(max: Int) = db.pruneDrafts(max)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun deleteForGood(id: Long) { db.deleteTour(id) }
        override fun rename(id: Long, name: String) = db.rename(id, name)
    }).also { it.onClosed = { routesNote = null } } // the line at the top lasts until My routes is left
    /** My routes list (☰ → My routes) is open. */
    var routesOpen: Boolean
        get() = routes.open
        set(v) { routes.open = v }
    /** My routes' last chosen tab (Planned / Done); kept while the app runs, Planned after a restart. */
    var routesTab: RoutesTab
        get() = routes.tab
        set(v) { routes.tab = v }
    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes.md. */
    var mode by mutableStateOf(AppMode.PLAN)
        private set
    /**
     * Zoom the map takes when Navigate starts or locate is tapped in Navigate ([AppMode.navStartZoom],
     * [AppMode.locateZoom]); null once taken (then the user's zoom stays).
     */
    var navStartZoom by mutableStateOf<Double?>(null)
    /** Map taps may add, move or insert points (Plan with no track shown). */
    val canEditMap: Boolean get() = mode == AppMode.PLAN && shown == null
    /** "You have arrived – end tour?" is open. */
    var arrivalAsk: Boolean
        get() = tour.arrivalAsk
        set(v) { tour.arrivalAsk = v }
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
    /**
     * A pairing was just made (first browser or another one, A-017) or ended here: the Plan map shows "Paired. …" /
     * "Unpaired. …" with OK at the top (owner 2026-10-30) until OK or the next Pair / Unpair; never for "already
     * paired" or a failure ([PairLanding]).
     */
    var syncNote by mutableStateOf<SyncNote?>(null)
        private set

    private var settingsShown by mutableStateOf(false)
    var showSettings: Boolean
        get() = settingsShown
        set(v) { if (!v) { settingsNote = null; settingsOnTheWay = false }; settingsShown = v } // the line lasts until Settings is left
    /**
     * Settings opened from Navigate's ⚙ (owner 2026-11-07): the on-the-way list (Navigation page + the topics for the
     * way + "Alle Einstellungen"); the tour and the mode are not touched. False after "Alle Einstellungen" or closing.
     */
    var settingsOnTheWay by mutableStateOf(false)
        private set
    /** Open topic of Settings (list of topics → pages); null = the list. */
    private var page by mutableStateOf<SettingsPage?>(null)
    var settingsPage: SettingsPage?
        get() = page
        set(v) {
            if (v != SettingsPage.OFFLINE_MAPS) offlineResult = null // the result line lasts until the page is left
            if (v != page) settingsNote = null
            if (v == SettingsPage.DIAGNOSTICS || page == SettingsPage.DIAGNOSTICS) markDiagnosticsSeen()
            if (v == SettingsPage.SYNC) sync.endedSeen() // "ended in the browser" seen: the ☰ item is no longer amber
            if (v == SettingsPage.DIAGNOSTICS && v != page) loadRoutingInfo()
            page = v
        }

    /** Settings → Diagnostics: the routing fingerprint and the routing data's dates (RoutingFingerprint); null = not read yet. */
    var routingInfo by mutableStateOf<String?>(null)
        private set

    private val routingFingerprint by lazy {
        com.onpath.app.routing.RoutingFingerprint.of(read = { n -> getApplication<Application>().assets.open("brouter/$n").use { it.readBytes() } })
    }

    private fun loadRoutingInfo() {
        viewModelScope.launchSafe("app", "loadRoutingInfo") {
            routingInfo = withContext(Dispatchers.IO) {
                try {
                    val rd5 = dataFiles.segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty().map { f ->
                        f.name to try {
                            com.onpath.app.routing.Rd5Parts.readIndex(f)?.let { com.onpath.app.routing.Rd5Parts.creationTime(com.onpath.app.routing.Rd5Parts.readTail(f, it)) }
                        } catch (e: java.io.IOException) {
                            Problems.info("routing", "Diagnostics: date of routing data ${f.name} not read", e); null
                        }
                    }
                    com.onpath.app.routing.RoutingFingerprint.describe(routingFingerprint, rd5)
                } catch (e: Exception) {
                    Problems.warn("routing", "Diagnostics: routing fingerprint not computed", e); null
                }
            }
        }
    }

    /** Developer tools in Diagnostics (owner): after 7 taps on the version (remembered) – also in debug builds (Arch H1). */
    var devToolsShown by mutableStateOf(DevToolsUnlock.shown(prefs.devToolsUnlocked))
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

    /** Navigate → ⚙ (owner 2026-11-07): Settings in the on-the-way list; back returns to Navigate, the tour runs on. */
    fun openSettingsOnTheWay() {
        Problems.trace("ui", "Navigate ⚙: settings on the way")
        settingsPage = null; showSettings = true; settingsOnTheWay = true
    }

    /** On-the-way list → "Alle Einstellungen": the whole topic list (back from it closes Settings). */
    fun showAllSettings() { settingsOnTheWay = false; settingsPage = null }

    /** Back in Settings: a topic page returns to the list, the list closes Settings. */
    fun settingsBack() { if (settingsPage != null) settingsPage = null else showSettings = false }

    // --- Navigate orientation + next turn (see NavigateScreen, MapController.setCamera) ---
    /** Navigate: heading-up (walking direction up) or north-up; persisted in settings. */
    val navHeadingUp: Boolean get() = settings.navHeadingUp

    /**
     * The session's one map orientation (owner 2026-11-07, domain/map/MapOrientation): walking direction up or north up,
     * the same in Plan and Navigate; only the compass tap / the setting change it.
     */
    val headingUp: Boolean get() = navHeadingUp
    /** Bumped to turn the map north (MapScreen → MapController.northUp). */
    var northTick by mutableIntStateOf(0)
    /** Bumped when the camera must follow the position again although [followMe] did not change. */
    var recentreTick by mutableIntStateOf(0)

    /**
     * Locate button (GPS on, permission given): follow the position again. In Navigate also back to the standard
     * follow zoom of the activity (owner 2026-10-31, [AppMode.locateZoom]) – also when the map still follows but was
     * pinched; the map takes it while tracking (MapController.ensureStartZoom, no camera move – CLAUDE.md camera rule).
     * Plan: unchanged, following resumes at the user's zoom.
     */
    fun locate() {
        val zoom = AppMode.locateZoom(mode, activity)
        followMe = true
        if (zoom != null) { navStartZoom = zoom; recentreTick++ }
    }

    private fun setHeadingUp(on: Boolean) {
        if (settings.navHeadingUp != on) { settings = settings.copy(navHeadingUp = on); prefs.settings = settings }
    }

    /** Plan's neutral GPS note after a tap of the user's ([PlanGps]); null = none. [planGpsNoteTick] restarts its time. */
    var planGpsNote by mutableStateOf<PlanGps.Note?>(null)
    var planGpsNoteTick by mutableStateOf(0)
        private set

    /**
     * An edit during a tour needs my position and there is none: in Plan the neutral note (Plan is quiet about GPS, owner
     * 2026-11-06 23:35), elsewhere the error box as before.
     */
    private fun noPositionForEdit(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) {
        if (mode == AppMode.PLAN) showPlanGpsNote(note) else sayError(Txt.s(text), NoticePlace.MAP)
    }

    fun showPlanGpsNote(note: PlanGps.Note) {
        Problems.trace("gps", "Plan: neutral note ${note.name} after a tap (no GPS warning in Plan)")
        planGpsNote = note; planGpsNoteTick++
    }

    fun onCompass(mapTurned: Boolean) {
        // one orientation for the session (owner 2026-11-07, MapOrientation): only this tap changes it
        val o = com.onpath.app.domain.map.MapOrientation.of(headingUp)
        val hasFix = tracker.location.value != null
        val tap = com.onpath.app.domain.map.MapOrientation.tap(o, mapTurned, followMe, hasFix)
        Problems.trace("map", "Compass tap: $o → ${tap.orientation}" + (if (mapTurned) " (map was turned)" else ""))
        if (tap.noFix) {
            // a tap must not do nothing; Plan says it neutrally, no red box (owner 2026-11-06 23:35 – PlanGps)
            PlanGps.compassWithoutFix(mode == AppMode.PLAN)?.let { showPlanGpsNote(it) }
                ?: sayError(Txt.s(Res.string.msg_no_position_turn), NoticePlace.MAP)
            return
        }
        setHeadingUp(tap.orientation == com.onpath.app.domain.map.Orientation.TRAVEL)
        // following north up turns the map to north itself; an extra camera move from the app would make MapLibre drop
        // the tracking (it ends tracking on every app camera move) – so either follow again or turn north, never both
        if (tap.follow == true) { followMe = true; recentreTick++ }
        if (tap.turnNorth) northTick++
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
        scrubEndJob = viewModelScope.launchSafe("app", "navScrubEnd") {
            kotlinx.coroutines.delay(SCRUB_HOLD_MS)
            highlight = null
            if (scrubWasFollowing && mode == AppMode.NAVIGATE) { followMe = true; recentreTick++ }
            scrubWasFollowing = false
        }
    }

    /** The app is on screen again (e.g. taken out of the pocket): in Navigate jump back to the own position (owner). */
    fun onReturnToApp() {
        if (mode == AppMode.NAVIGATE && tracker.location.value != null) { followMe = true; recentreTick++ }
        appOnScreen = true
        mapData.onReturn(inPlan = mode == AppMode.PLAN) // T3 in Plan: the next usable fix checks the own area
        sync.request("back in the app")
    }

    /**
     * The app left the screen. Map areas still on their way keep loading through MapLoadService (owner),
     * started when they began; otherwise nothing is polled in the background (map-cells.md §8).
     */
    fun onLeaveApp() { appOnScreen = false; mapData.onLeave() }

    /** The app is on screen (Compose state: MapHost catches up with newer map data on return). */
    var appOnScreen by mutableStateOf(true)
        private set

    // ---------------- map cells (docs/design/map-cells.md §9, §19) ----------------

    /** Downloaded map cells: requests, states, installed list (UI: card, Settings list – see map-cells.md §19). */
    val cells: com.onpath.app.cells.MapCells by lazy { com.onpath.app.cells.AppCells.get(getApplication()) }

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

    /**
     * Navigate → "▶ Sim" (developer tools, [NavSimChip]): rides [line] – the line followed now – from where the tour is
     * on it ([NavSimStart]), else [demoLine] from its start; a ride already on goes first.
     */
    fun startNavSim(line: List<GeoPoint>?) {
        val followed = line?.takeIf { it.size >= 2 }
        val (label, l) = followed?.let { "followed route" to it } ?: demoLine()
            ?: run { sayError("Nothing to simulate: no followed route or plan.", NoticePlace.MAP); return }
        val path = com.onpath.app.recording.DemoPath.timed(l, activity, planFactors())
            ?: run { sayError("This line is too short for a demo ride.", NoticePlace.MAP); return }
        val nav = recState.value?.nav
        val fromM = NavSimStart.alongM(sameLine = followed != null && nav?.line == followed,
            reached = nav?.session?.reached == true, shownAlongM = nav?.shownAlongM, progressM = nav?.progressM)
        Problems.trace("demo", "Tour simulation started on the Navigate page ($label) at ${fromM.toInt()} m of ${path.lengthM.toInt()} m")
        com.onpath.app.recording.DemoLocation.startRide(path, label, startAlongM = fromM)
        navSimLine = l
    }

    /** Developer tools → Demo: the last recorded tours (not demo ones) to ride again at their recorded times. */
    fun recentRecordedTours(): List<Tour> = tours.filter {
        it.kind == TourKind.RECORDED && !it.name.startsWith(com.onpath.app.recording.DemoLocation.TOUR_PREFIX) &&
            it.distanceM >= 500.0   // not the empty / few-metre starts
    }.sortedByDescending { it.id }.take(8)

    fun demoTourLabel(t: Tour): String = "ride tour \"${t.name}\" (${"%.1f".format(Locale.ROOT, t.distanceM / 1000)} km, recorded times)"

    /** Rides recorded tour [t] again along its track at its own times (debugging a field tour, owner 2026-11-01). */
    fun startDemoTour(t: Tour) {
        viewModelScope.launchSafe("app", "startDemoTour") {
            val pts = withContext(Dispatchers.IO) {
                runCatching { db.loadSegments(t.id).flatten() }.onFailure { Problems.warn("demo", "Recorded tour not read for the demo", it) }
                    .getOrDefault(emptyList())
            }
            val path = com.onpath.app.recording.DemoPath.recorded(pts.map { GeoPoint(it.lat, it.lon, it.ele) }, pts.map { it.timeMs })
                ?: run { sayError("This tour has no track with times to ride.", NoticePlace.SETTINGS); return@launchSafe }
            navSimLine = null // a recorded tour is ridden as it was – never moved onto a changed line
            Problems.trace("demo", "Riding recorded tour ${t.id} \"${t.name}\" again at its recorded times (${pts.size} points)")
            com.onpath.app.recording.DemoLocation.startRide(path, "recorded tour \"${t.name}\"")
        }
    }

    /** The line the Navigate "Sim" rides; null = no Sim from the Navigate page. */
    private var navSimLine: List<GeoPoint>? = null

    /**
     * The followed line changed (plan edit, new route) while the Navigate "Sim" rides: it goes on along the new line from
     * where it is ([com.onpath.app.recording.DemoLocation.switchLine]) – else the voice speaks of one line while the
     * simulated position moves on another (owner 2026-11-01).
     */
    fun navSimFollow(line: List<GeoPoint>?) {
        val st = com.onpath.app.recording.DemoLocation.state.value
        if (st == null || st.kind != com.onpath.app.recording.DemoLocation.Kind.RIDE) { navSimLine = null; return }
        if (navSimLine == null || line == null || line.size < 2 || line == navSimLine) return
        val path = com.onpath.app.recording.DemoPath.timed(line, activity, planFactors()) ?: return
        navSimLine = line
        com.onpath.app.recording.DemoLocation.switchLine(path, "followed route")
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

    /** Map cells + routing data on the phone: [MapDataFlow], forwarded member by member (the UI and the E2E tests use these). */
    private val mapData by lazy {
        MapDataFlow(getApplication(), db, helperScope, { cells }, object : MapDataFlow.Host {
            override val mode: AppMode get() = this@AppViewModel.mode
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val waypoints: List<GeoPoint> get() = this@AppViewModel.waypoints
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val tracker: LocationTracker get() = this@AppViewModel.tracker
            override val settings: AppSettings get() = this@AppViewModel.settings
            override val missing: List<DataFiles.Check> get() = this@AppViewModel.missing
            override val startModeKnown: Boolean get() = this@AppViewModel.startModeKnown
            override val searchMark: com.onpath.app.search.Place? get() = this@AppViewModel.searchMark
            override val activity: com.onpath.app.routing.Activity get() = this@AppViewModel.activity
            override var routeError: String?
                get() = this@AppViewModel.routeError
                set(v) { this@AppViewModel.routeError = v }
            override var dataBusy: String?
                get() = this@AppViewModel.dataBusy
                set(v) { this@AppViewModel.dataBusy = v }
            override fun initialCamera() = this@AppViewModel.initialCamera()
            override fun clearSearchRing() { this@AppViewModel.searchMark = null; searchPlace = null }
            override fun placeName(at: GeoPoint): String? = stopName(at) ?: nearbyNames[at]
            override suspend fun offlineIndex() = search.offlineIndex()
            override fun invalidateEngine() { engine = null }
            override fun routingDataArrived(names: List<String>) = this@AppViewModel.routingDataArrived(names)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.say(text, kind, place)
        })
    }
    fun onMapStyleLoaded(dir: java.io.File?) = mapData.onMapStyleLoaded(dir)
    fun rollbackMapCells(dir: java.io.File?): Boolean = mapData.rollbackMapCells(dir)
    val cellJob: com.onpath.app.cells.CellJob? get() = mapData.cellJob
    val hereAsk: MapDataFlow.HereAsk? get() = mapData.hereAsk
    fun answerHereAsk(load: Boolean) = mapData.answerHereAsk(load)
    fun cellCardUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                   installed: List<com.onpath.app.cells.CellStore.InstalledCell>): CellCardUi? = mapData.cellCardUi(states, installed)
    var viewWithoutDetail: Boolean
        get() = mapData.viewWithoutDetail
        set(v) { mapData.viewWithoutDetail = v }
    fun mapLoadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, now: Long): MapLoadUi? = mapData.mapLoadUi(states, now)
    fun hideLoadModal() = mapData.hideLoadModal()
    fun cancelLoadModal() = mapData.cancelLoadModal()
    val loadAsk: MapDataFlow.LoadAsk? get() = mapData.loadAsk
    fun mapTapped(p: GeoPoint) = mapData.mapTapped(p)
    fun answerLoadAsk(load: Boolean) = mapData.answerLoadAsk(load)
    fun cellCardMobileYes() = mapData.cellCardMobileYes()
    fun loadModalOnMobile() = mapData.loadModalOnMobile()
    fun cellCardWifiOnly() = mapData.cellCardWifiOnly()
    fun cellCardRetry() = mapData.cellCardRetry()
    fun closeCellCard() = mapData.closeCellCard()
    fun cancelCellLoading() = mapData.cancelCellLoading()
    fun clearAllMaps() = mapData.clearAllMaps()
    var offlineResult: String?
        get() = mapData.offlineResult
        set(v) { mapData.offlineResult = v }
    var cleanupPreview: CleanupPreview?
        get() = mapData.cleanupPreview
        set(v) { mapData.cleanupPreview = v }
    fun prepareCleanup() = mapData.prepareCleanup()
    fun confirmCleanup() = mapData.confirmCleanup()
    fun detailedAreas(): List<DoubleArray> = mapData.detailedAreas()
    fun packageAreas(): List<DoubleArray> = mapData.packageAreas()
    fun overviewOnly(south: Double, west: Double, north: Double, east: Double): Boolean = mapData.overviewOnly(south, west, north, east)
    var downloadDetailsOpen: Boolean
        get() = mapData.downloadDetailsOpen
        set(v) { mapData.downloadDetailsOpen = v }
    var backgroundStripShown: Boolean
        get() = mapData.backgroundStripShown
        set(v) { mapData.backgroundStripShown = v }
    var backgroundStripBottomDp: Int
        get() = mapData.backgroundStripBottomDp
        set(v) { mapData.backgroundStripBottomDp = v }
    fun backgroundDownloadUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>): BackgroundDownloadUi? =
        mapData.backgroundDownloadUi(states)
    fun cancelAllDownloads() = mapData.cancelAllDownloads()
    fun loadAreaNames() = mapData.loadAreaNames()
    fun offlineMapsUi(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                      installed: List<com.onpath.app.cells.CellStore.InstalledCell>): OfflineMapsUi = mapData.offlineMapsUi(states, installed)
    fun updateMapsNow() = mapData.updateMapsNow()
    fun deleteMapArea(id: String) = mapData.deleteMapArea(id)
    /** Settings → Diagnostics → "Download routing data" (normally routing data comes by itself with the map). */
    fun downloadRoutingData() = mapData.downloadRoutingData()

    /**
     * T1: the user set these points – a point that makes the plan very long (owner) loads nothing until the long-plan
     * question is answered: Plan anyway → it loads then; Cancel → nothing was loaded.
     */
    private fun cellsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) {
        if (after.size >= 2 && after.toList() != longRouteOk && com.onpath.app.routing.LongRoute.needsAsk(after.toList(), activity)) {
            longRouteDeferred = before.toList()
            Problems.trace("cells", "Very long plan: maps and routing data for the new point wait for the answer")
            return
        }
        longRouteDeferred = null
        mapData.pointsSet(before, after)
    }

    /** After a route was computed in Plan: newer map areas along it ([MapDataFlow.freshenAlong]) and newer routing data. */
    private fun freshenAfterRoute(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, act: com.onpath.app.routing.Activity) {
        mapData.freshenAlong(line, corridor, act)
        checkRoutingData(line)
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
        arrivalGateJob = gated(routingArrivalGate, arrivalGateJob, busy = { mapData.routingLoading }) {
            val n = arrivedNames.size; arrivedNames.clear()
            if (syncRoutingLater.isNotEmpty()) { val later = syncRoutingLater.toList(); syncRoutingLater.clear(); routeSyncedPlans(later) }
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
        return viewModelScope.launchSafe("app", "gated") {
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

    /** The user drags the map (true) / the map came to rest (false): new map areas are not joined meanwhile. */
    fun mapGesture(on: Boolean) {
        // the map moved by hand during the flight to a shared place: its menu no longer opens by itself
        if (on) searchFlow.mapMovedByHand()
        mapData.userBusy(on)
    }

    /** Next turn ahead in Navigate (arrow on the map); null = none / off route / Plan mode. */
    var navTurn by mutableStateOf<com.onpath.app.routing.Turn?>(null)
    /** Navigate while cycling: the rest view covers the map, which gets no fixes meanwhile (ui/RestView.kt, SmartScreen). */
    var screenRest by mutableStateOf(false)

    // --- planning ---
    val waypoints = mutableStateListOf<GeoPoint>().apply { addAll(prefs.planWaypoints) }
    /** The plan being edited: points, Undo / Redo, pinned points, the one-point rule's start ([PlanSession]). */
    private val plan = PlanSession(waypoints, prefs.planPinned)
    /** Pinned (POI) points, kept by value ([PlanSession.pinned]). */
    private val pinned: MutableSet<GeoPoint> get() = plan.pinned
    fun pinnedIndices(): Set<Int> = plan.pinnedIndices()
    /** Followed routes replaced by plan edits during the tour (Undo takes them back exactly, Bug 02). */
    private val followHistory = FollowHistory()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /**
     * Plan panel size (full / compact / mini / rolled up), kept while the app runs: a plan opens at the summary step
     * (owner 2026-10-31); rolled up by the user it stays rolled up through route updates until opened again.
     */
    var planSize by mutableStateOf(PlanSize.MINI)

    /**
     * A tap on the open map in Plan (owner 2026-11-03, novice fix): the plan sheet says "Gedrückt halten, um hier einen
     * Punkt zu setzen." ([MapGestures.tapShowsHoldHint]). A line that stays until the user acts – a hold on the map, a
     * plan change, a sheet drag, a mode switch – never gone by itself (docs/design/ui-style.md, no flashing).
     */
    var tapHoldHint by mutableStateOf(false)
    fun showTapHoldHint() {
        if (!tapHoldHint) com.onpath.app.debug.Problems.trace("plan", "Tap on the map: 'hold to set a point' shown")
        tapHoldHint = true
        if (planSize == PlanSize.COLLAPSED) planSize = PlanSize.MINI // the line sits in the summary step
    }
    /**
     * The plan's route offline ("Ohne Netz ✓ / ⟳ / ⚠" in the mode pill, owner 2026-11-03, [OfflineReady]); null = no
     * route / unknown. Recomputed by MapScreen off the main thread when the route changes, and now and then while loading.
     */
    var offlineReady by mutableStateOf<OfflineReady.Result?>(null)
    /** The pill was tapped: the plan sheet explains the offline state in one line, until the plan changes / tapped again. */
    var offlineInfo by mutableStateOf(false)
    fun toggleOfflineInfo() {
        offlineInfo = !offlineInfo && offlineReady != null
        if (offlineInfo) {
            com.onpath.app.debug.Problems.trace("plan", "Offline state explained: ${offlineReady?.state}")
            if (planSize == PlanSize.COLLAPSED) planSize = PlanSize.MINI // the line sits in the summary step
        }
    }
    /** Blocking: what [OfflineReady] says for [line] (MapScreen calls it on Dispatchers.IO). */
    fun computeOfflineReady(line: List<GeoPoint>): OfflineReady.Result? = mapData.offlineReady(line, activity)

    /** The route point whose point menu is open (hold on its marker, MapGestures): index when opened + position. */
    var pointMenuTarget by mutableStateOf<PointMenuTarget?>(null)
        private set
    /** Index of that point in the plan now, or null (no menu, or its point is gone). */
    val pointMenu: Int? get() = pointMenuTarget?.indexIn(waypoints)
    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    var routing by mutableStateOf(false)
        private set
    /** The point the last edit added or moved: the map's spinner turns there while its route is computed ([RoutingSpot]). */
    var routingSpot by mutableStateOf<GeoPoint?>(null)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    var highlight by mutableStateOf<GeoPoint?>(null)
    /**
     * Plan points the route does not reach (owner, [PlanGaps]): grey dashed piece + hollow point on the map,
     * one red line in the plan sheet – while it holds, never dismissed. Derived from the route of exactly these points.
     */
    val planGaps: List<PlanGap>
        get() = route?.takeIf { !routing }?.let { r ->
            val w = waypoints.toList()
            // the gaps of the last plan result (routing/PlanResult, the web's too) while it still describes these points
            planResult?.takeIf { it.route.snapped === r.snapped && it.route.skipped === r.skipped && it.points == w }?.gaps
                ?: PlanGaps.of(w, r.snapped, r.skipped)
        } ?: emptyList()
    /**
     * What reaches the first plan point the route does not reach ([com.onpath.app.routing.PlanReach], owner
     * 2026-11-07): the red line says "② erst ab T2 erreichbar". Set with the route of exactly these points; null = all
     * reached, a skipped stop, or not known (then the line says the missing metres as before).
     */
    var planReach by mutableStateOf<com.onpath.app.routing.PlanReach?>(null)
        private set

    /** [com.onpath.app.routing.PlanReach.find] for [g] (routing thread); null when it could not be worked out. */
    private fun findReach(g: PlanGap, act: Activity, level: Int, cancel: com.onpath.app.routing.RouteCancel): com.onpath.app.routing.PlanReach? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            com.onpath.app.routing.PlanReach.find(routingEngine(), g, act, level, cancel).also { r ->
                Problems.trace("routing", "Point ${g.index + 1} not reached at ${act.name} T$level: " +
                    "${r?.fix ?: "search cancelled / not done"} after ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            }
        } catch (e: Exception) {
            Problems.warn("routing", "What reaches point ${g.index + 1} could not be worked out; the sheet says the missing metres", e)
            null
        }
    }

    /** The last route result applied ([recalc]; routing/PlanResult – the same computation as the web planner's). */
    private var planResult: com.onpath.app.routing.PlanResult? = null

    // route warnings (RouteWarnings): declared before init {} – recalc() touches them (ViewModelInitOrderTest)
    /**
     * "Route changed for gravel bike – please check": the activity / T level changed and the new route differs
     * noticeably ([RouteWarnings.changedNoticeably]). Stays until the amber line is tapped or the plan changes – never
     * dismissed by time.
     */
    var routeChangedNotice by mutableStateOf<String?>(null)
        private set
    /**
     * Grey INFO line in the plan sheet: the open plan came from the browser, routed there with another routing
     * fingerprint ("Planned in the browser with other routing – the route may differ slightly"). Nothing to do; it goes
     * with the next edit of the plan or another plan (docs/design/routing-fingerprint.md).
     */
    var syncRoutingNote by mutableStateOf<String?>(null)
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

    // declared before init {}: init restores the saved plan and routes it at once (declared below, they are still null then)
    /** Cancel signal of the route being calculated (Cancel button; a newer request stops the older one). */
    private var routeCancel: com.onpath.app.routing.RouteCancel? = null
    /**
     * The plan run reached its time limit (owner 2026-11-03, onpath.private#21 B, [com.onpath.app.routing.SlowPlan]):
     * the box "Die Route ist lang, die Berechnung dauert. Weiter warten?" – the periods of the run that asked; null = none.
     */
    var slowPlanAsk by mutableStateOf<Int?>(null)
        private set
    /** Time-limit periods for the next plan run: 1, or more after "Weiter warten" ([answerSlowPlan]). */
    private var planRounds = 1
    /** The next [recalc] keeps [planRounds] (a "Weiter warten" run), else it starts at one period again. */
    private var keepRounds = false

    private val ROUTING_SLOW_MS = 2_500L
    /** The route has been calculating for a while: the plan sheet offers Cancel. */
    var routingSlow by mutableStateOf(false)
        private set
    /**
     * The wait card's progress (owner 2026-11-05): legs done / legs to route in the running plan run – only legs the
     * router works on count (legs from the cache are there at once); null = none known yet (the bar runs).
     */
    var routeProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /**
     * The wait bar's share 0..1 (owner 2026-11-05: it moves within one long leg too): legs done + the running leg's
     * search ([com.onpath.app.routing.BRouterEngine.searchProgress]) over the legs to route, never back ([WaitBar]);
     * null = not known yet (the bar runs).
     */
    var routeShare by mutableStateOf<Float?>(null)
        private set
    /** How often the wait bar asks the router how far it is (ms). */
    private val WAIT_POLL_MS = 250L
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
    /** What started the routing run: Cancel takes back only that ([com.onpath.app.domain.plan.RunCause]). */
    private val runCauses = com.onpath.app.domain.plan.RunCause()


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
    /** Save's name dialog while open (owner 2026-10-31: Save always asks for the name; [requestSave], [requestCopyRoute]). */
    var saveAsk by mutableStateOf<SaveAsk?>(null)
        private set
    /** A save runs (the DB write): a second tap on Save does not make a second route. */
    private var saving = false
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
        if (away < 10 * 60_000L) return
        // back after a while with a plan: the plan in front, also during a tour (owner 2026-11-06, PlanCamera)
        if (backgroundSince != 0L && mode == AppMode.PLAN && waypoints.isNotEmpty()) {
            showPlanWhole(PlanCamera.Moment.BACK_IN_APP); return
        }
        if (recState.value != null || mode != AppMode.PLAN) return
        // A place was just shared into the app: the map shows it, not the position.
        if (android.os.SystemClock.elapsedRealtime() < searchFlow.keepCameraUntil) return
        startCameraDone = false; userMovedMap = false; followMe = false; startCameraGen++
    }

    // --- dialogs / messages ---
    /**
     * Says [text] where it belongs (owner: no dark message bar): a line in My routes / the open Settings page where the
     * user acted ([place]); an error without such a place in the red [errorCard] box (stays until OK); an INFO without a
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
    val unfinished: Tour? get() = tour.unfinished
    /** The track of [unfinished], drawn on the map while its question shows (no service runs then). */
    val unfinishedTrack: List<List<GeoPoint>> get() = tour.unfinishedTrack
    /**
     * The tour just saved by "Tour speichern": the slim box "Tour gespeichert" while it is set (owner 2026-11-06,
     * onpath.private#35: the box is clearer than a line in the plan sheet – name, numbers, "In Meine Touren ›", OK).
     */
    val finishedTour: Tour? get() = tour.finishedTour
    /** Moving time / breaks / planned vs. needed of [finishedTour] (null = not known). */
    val finishedTimes: com.onpath.app.time.TourTimes? get() = tour.finishedTimes

    /**
     * The box "Tour gespeichert" → "In Meine Touren ›": the box goes and My routes opens on Gemacht with the tour marked
     * "✓ Gerade gespeichert" – what the button says (field bug 2026-11-06 22:30: it opened the 0 m tour on the map, with
     * "Als Plan übernehmen" for a tour without any line).
     */
    fun openSavedTour() {
        val t = tour.closeFinished() ?: return
        Problems.trace("tour", "Saved box: In Meine Touren → My routes → Gemacht, tour ${t.id} marked")
        refreshTours()
        routes.showSaved(t.id)
    }

    /** The box "Tour gespeichert" → OK. */
    fun closeSavedTour() {
        tour.closeFinished()?.let { Problems.trace("tour", "Saved box: OK (tour ${it.id})") }
    }
    /** The tours My routes shows ([com.onpath.app.ui.routes.RoutesState.tours]). */
    val tours: List<Tour> get() = routes.tours
    var events = mutableStateListOf<UiEvent>()
        private set

    val recState: StateFlow<RecState?> = RecordingService.state

    /**
     * The copy of the followed plan kept with the recorded tour (owner 2026-11-07, [TourPlanCopy]): written on one serial
     * background thread, in the order the plan changed; a failure is loud in the problem log and tried again.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val tourPlanCopy = com.onpath.app.domain.tour.TourPlanCopy(
        write = { id, p ->
            runCatching { db.setTourPlan(id, p.copy(updatedMs = System.currentTimeMillis())) }
                .onFailure { Problems.error("db", "The followed plan could not be stored with tour $id – tried again with the next change", it) }
                .isSuccess
        },
        finalChanged = { id -> Backups.tourChanged(getApplication(), id) },
    )
    private fun planCopy(block: com.onpath.app.domain.tour.TourPlanCopy.() -> Unit) {
        viewModelScope.launchSafe("app", "tourPlanCopy", planCopyDispatcher) { tourPlanCopy.block() }
    }

    init {
        // the recording tour's id: a new tour takes the plan followed at its start, its end writes the last version
        viewModelScope.launchSafe("app", "tourPlanCopy") {
            recState.map { it?.tourId }.distinctUntilChanged()
                .collect { id -> planCopy { tour(id) } }
        }
    }

    private val routingDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    /**
     * Routing nobody waits for at the screen (browser plans after a pairing, "Plan a route from it"): its own thread, so
     * the user's own plan never queues behind ten of them (review routing H1). BRouter itself runs one search at a time
     * (BRouterEngine.LOCK), so a plan edit waits at most for one search of this work, not all of it.
     */
    private val backgroundRouting = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    /**
     * The activity check's one row request (Navigate → activity icon): its own thread, so a plan edit never queues behind
     * it (field bug 2026-11-07: six 15 s checks on [routingDispatcher] held the plan up to ~90 s). BRouter's LOCK still
     * runs one search at a time; a new tap or closing the sheet terminates the check's search.
     */
    private val checkRouting = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    @Volatile private var engine: RoutingEngine? = null

    /** The router (on [routingDispatcher]); only a real one is kept, so a failed start is retried next time. */
    @Synchronized // two routing threads ([backgroundRouting]): one engine
    private fun routingEngine(): RoutingEngine =
        engine ?: EngineProvider.create(getApplication(), dataFiles.segmentsDir).also {
            // a new engine = new routing data (or a failed start): the legs of the old one are routed again
            if (EngineProvider.isRealRouter) { engine = it; legCache.clear() }
        }

    /**
     * Legs of earlier plan runs (owner 2026-11-05, docs/design/long-bike-routes.md): an edit routes only the legs it
     * changed; the plan, the tour's follow line and browser plans share it. Cleared with the engine ([routingEngine]).
     */
    private val legCache = com.onpath.app.routing.LegCache()
    /** The tour's follow line being routed ([followAhead]): a newer edit stops it, so the plan never waits behind it. */
    @Volatile private var followCancel: com.onpath.app.routing.RouteCancel? = null

    private var routeJob: Job? = null
    private val requests = RouteRequests()
    /** The followed line was taken from this plan ("Start navigation"), so Clear removes it too. */
    private var followFromPlan = false
    /** All plan points were deleted during the tour, which ended the guidance; points back → followed again (PlanGuidance). */
    /** The route guidance was ended in the tour (all points deleted / "Just record"): the status says "Recording only". */
    var guidanceEndedByEmptyPlan by mutableStateOf(false)
        private set

    /**
     * What the last plan edit means for the running tour ([TourSplit], docs/design/tour-edit-position.md): the position
     * splits the plan into passed points and points ahead. Taken by [followEditedPlan] once the plan's route is ready;
     * null = no tour following the plan, or nothing passed (the plan is followed as a whole, as before).
     */
    private var pendingSplit: TourSplit.Outcome? = null
    /** The plan before that edit (FollowHistory key: Undo brings its followed route back exactly). */
    private var splitBefore: List<GeoPoint>? = null
    /** Newer position → ahead routes supersede older ones; any later edit / clear makes a running one stale. */
    private val splitRequests = SplitRequests()
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
        com.onpath.app.ui.search.SearchSession(helperScope, dataFiles.dir, getApplication<Application>().filesDir, searchStore,
            com.onpath.app.search.PhotonProvider(com.onpath.app.search.PhotonProvider.userAgent(com.onpath.app.BuildConfig.VERSION_NAME)),
            areaPlan = { com.onpath.app.search.AreaPlan.of(dataFiles.dir) },
            geocoder = com.onpath.app.search.AndroidPlaceGeocoder(getApplication())) {
            val loc = tracker.location.value
            val (cLat, cLon, _) = initialCamera()
            // Position when known and near what the user looks at, else the map centre.
            val usePos = loc != null && Geo.distance(loc.latitude, loc.longitude, cLat, cLon) < 30_000
            com.onpath.app.ui.search.SearchContext(if (usePos) loc!!.latitude else cLat, if (usePos) loc!!.longitude else cLon,
                if (shown == null) route?.points else null)
        }
    }
    /** Search + shared places: [SearchFlow], forwarded member by member below
     * (the UI and the E2E tests use these); declared above the init that calls recalc() (stopName). */
    private val searchFlow by lazy {
        SearchFlow(helperScope, { search }, { searchStore }, object : SearchFlow.Host {
            override val mode: AppMode get() = this@AppViewModel.mode
            override val startModeKnown: Boolean get() = this@AppViewModel.startModeKnown
            override val canEditMap: Boolean get() = this@AppViewModel.canEditMap
            override val mapOverlayOpen: Boolean get() = this@AppViewModel.mapOverlayOpen
            override val routesOpen: Boolean get() = this@AppViewModel.routesOpen
            override val showSettings: Boolean get() = this@AppViewModel.showSettings
            override val loadModalOpen: Boolean get() = this@AppViewModel.loadModalOpen
            override val waypoints: List<GeoPoint> get() = this@AppViewModel.waypoints
            override fun historyPoints(): Set<GeoPoint> = plan.historyPoints()
            override fun enter(m: AppMode) = this@AppViewModel.enter(m)
            override fun closeMapOverlays(why: String) = this@AppViewModel.closeMapOverlays(why)
            override fun backToMap() {
                this@AppViewModel.routesOpen = false; this@AppViewModel.showSettings = false
                offlineResult = null; timeDetails = null; routeDetailsOpen = false
            }
            override fun closeShownTrack() { if (shown != null) closeShown() }
            override fun flyTo(at: GeoPoint, zoom: Double) {
                followMe = false; freeLookAt = null
                // an intended fly to a found place: the start camera must not jump back to a late first fix afterwards
                // (field 2026-11-07, S10: the far destination left the screen once its map had loaded)
                startCameraDone = true; userMovedMap = true
                events += UiEvent.CenterOn(at, zoom)
            }
            override fun loadMapsFor(at: GeoPoint, name: String) = mapData.placeSearched(at, name)
            override fun stopFollowing() { followMe = false }
            override fun holdStartCamera() { startCameraDone = true; userMovedMap = true }
            override fun openPoiMenu(poi: com.onpath.app.poi.Poi) = this@AppViewModel.openPoiMenu(poi)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.say(text, kind, place)
        })
    }

    init {
        recalc()
        // every plan change is traced, so the diagnostics show what the user did
        Problems.trace("plan", "Plan restored at app start: ${waypoints.size} points" +
            (followName?.let { ", following \"$it\" (${followLine?.size ?: 0} line points)" } ?: ", nothing followed"))
        refreshTours() // "Load route" is enabled only when something is saved
        // an empty plan with a followed line left and no tour in the database (Pixel 2026-11-01: a blue line without points
        // that nothing could remove): the line goes – never while a tour is recording (the service may come back)
        if (waypoints.isEmpty() && followLine != null && !followIsTrack) viewModelScope.launchSafe("app", "start-up work") {
            val active = withContext(Dispatchers.IO) {
                runCatching { db.findActiveRecording() != null }
                    .onFailure { Problems.warn("db", "Active tour not checked at start; the followed line stays", it) }.getOrDefault(true)
            }
            if (!active && waypoints.isEmpty() && recState.value == null && followLine != null && !followIsTrack) {
                Problems.trace("plan", "Empty plan, no tour: the followed line left from before is removed")
                clearFollow()
            }
        }
        // Set directly (enter() touches properties declared below); the check after the DB read
        // calls enter() and so also tells a running service which mode we are in.
        if (RecordingService.state.value != null) mode = AppMode.NAVIGATE
        viewModelScope.launchSafe("app", "start-up work", Dispatchers.IO) {
            val active = db.findActiveRecording()
            val start = AppMode.onStart(RecordingService.state.value != null, active != null)
            withContext(Dispatchers.Main) { enter(start); startModeKnown = true }
            if (active != null && RecordingService.state.value == null) {
                delay(1500) // the service may be restarting by itself (sticky)
                if (RecordingService.state.value == null) {
                    // drawn behind the question, so the user sees which tour it is (field bug 2026-10-31: empty map)
                    val line = runCatching { db.loadSegments(active.id).map { seg -> seg.map { GeoPoint(it.lat, it.lon) } } }
                        .onFailure { Problems.warn("db", "Track of the unfinished tour could not be read – not drawn", it) }
                        .getOrDefault(emptyList())
                    withContext(Dispatchers.Main) { tour.showUnfinished(active, line) }
                }
            }
        }
        // The service stopped by itself (permission gone, foreground refused, database broken): say why, in the app too.
        viewModelScope.launchSafe("app", "start-up work") {
            RecordingService.startFailed.collect { why ->
                if (why != null) { RecordingService.startFailed.value = null; sayError(why, NoticePlace.BACKGROUND); if (mode == AppMode.NAVIGATE) enter(AppMode.PLAN) }
            }
        }
        // Re-routing (service): follow the line the service navigates now (new route taken / original again).
        viewModelScope.launchSafe("app", "start-up work") {
            var saveFailedShown = false
            var recordingSeen: Long? = null
            RecordingService.state.collect { st ->
                if (st?.tourId != recordingSeen) {
                    followHistory.clear() // Undo brings back a followed route only within its own tour
                    if (st != null) Problems.trace("tour", "Tour ${st.tourId} recording (plan ${waypoints.size} points, " +
                        (if (followLine != null) "following ${followName ?: "a route"}" else "nothing followed") + ")")
                    else { Problems.trace("tour", "Tour $recordingSeen recording ended"); guidanceEndedByEmptyPlan = false }
                    recordingSeen = st?.tourId
                }
                if (st?.saveFailed == true && !saveFailedShown) {
                    saveFailedShown = true
                    sayError(Txt.s(Res.string.msg_track_save_failing), NoticePlace.BACKGROUND)
                } else if (st?.saveFailed == false) saveFailedShown = false
                onArrived(st?.arrived == true)
                val n = st?.nav ?: run { navActivitySeen = null; return@collect }
                if (n.version != navVersion) { navVersion = n.version; if (followLine != n.line) followLine = n.line }
                syncNavActivity(n.session)
            }
        }
        viewModelScope.launchSafe("app", "start-up work") {
            RecordingService.finished.collect { id ->
                if (id != null) {
                    RecordingService.finished.value = null
                    val discard = tour.takeDeleteFinished()
                    if (discard) {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { db.setDeleted(id, System.currentTimeMillis()) }
                                .onFailure { Problems.error("db", "The ended tour could not be moved to the recycle bin; it stays in My routes", it) }
                                .getOrDefault(false).also { if (it) Backups.tourChanged(getApplication(), id) }
                        }
                        tour.closeFinished()
                        if (!ok) sayError(Txt.s(Res.string.msg_track_delete_failed_here), NoticePlace.MAP)
                    } else {
                        val done = withContext(Dispatchers.IO) { db.getTour(id) }
                        tour.showFinished(done, withContext(Dispatchers.IO) { done?.let { tourTimes(it) } })
                        done?.let { nameRecordedTour(it) }
                        // "Split into two tours": the parts after the rides get a place name too
                        val parts = RecordingService.finishedParts; RecordingService.finishedParts = emptyList()
                        parts.forEach { p -> withContext(Dispatchers.IO) { db.getTour(p) }?.let { nameRecordedTour(it) } }
                    }
                    refreshTours()
                    clearFollow()
                    // a demo ride ends with its tour: no "DEMO" / "Sim" left on the map (the recording is closed – no jump into it)
                    if (com.onpath.app.recording.DemoLocation.endWithTour()) navSimLine = null
                    enter(AppMode.PLAN)
                    tour.afterFinished() // no arrival box, no "Neuer Plan" line of the old tour
                    afterTourEnded()
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
            .put("voice", prefs.voiceDetail)
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

    /** App updates (F-OFF-06): [AppUpdates] – created on first use (nothing in the start-up reaches it). */
    private val updates by lazy {
        AppUpdates(getApplication(), helperScope, prefs, recording = { recState.value != null },
            say = { text, kind -> say(text, kind, updatePlace()) })
    }
    val update: UpdateState get() = updates.update
    /** Release the user dismissed on the home screen (no nagging; still visible in Settings). */
    var updateDismissed: String?
        get() = updates.updateDismissed
        set(v) { updates.updateDismissed = v }
    /** Hidden when Google Play installed the app – Play handles updates then. */
    val updatesEnabled: Boolean get() = updates.updatesEnabled
    /** A tour is recording and the user tapped Install: ask first (installing closes OnPath, recording stops). */
    var askUpdateDuringTour: Boolean
        get() = updates.askUpdateDuringTour
        set(v) { updates.askUpdateDuringTour = v }
    fun checkForUpdates(manual: Boolean) = updates.check(manual)
    /** Downloads the update and opens the Android installer (the user confirms there). */
    fun installUpdate(duringTourOk: Boolean = false) = updates.install(duringTourOk)

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
        viewModelScope.launchSafe("app", "importData", Dispatchers.IO) {
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
        if (userChange) runCauses.activity(activityCause()) else runCauses.other()
        activity = a; prefs.activity = a; recalc()
        clearShownNote()
    }

    /** A stored activity name; null for none or an unknown / old one (expected: the caller falls back). */
    private fun activityOrNull(name: String?): Activity? = com.onpath.app.debug.enumOrNull<Activity>(name, "app")

    /** "Activity set to … from the file" is over once the activity / T level changes again. */
    private fun clearShownNote() { shown?.let { if (it.note != null) shown = it.copy(note = null) } }

    /** Cause of an activity / T level change; a change still being calculated keeps the activity from before it. */
    private fun activityCause(): PlanStatus.Cause =
        runCauses.activityChange(PlanStatus.Cause.ActivityChange(activity, sac), previousStillOpen = routing || longRouteAsk != null)

    /** T level from the activity picker (Hike): remembered, the route is planned again at once. */
    fun selectSac(level: Int) {
        val l = level.coerceIn(1, 5)
        if (l == sac) return
        if (activity == Activity.HIKE) { noteActivityChange(); runCauses.activity(activityCause()) }
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
        pinTaken = null // a newer edit: only the add itself can bring its pin back (the add sets it after this)
        // editing on a map that keeps turning is confusing: stop following, the map stays at its angle (owner)
        if (mode == AppMode.PLAN && headingUp && followMe) followMe = false
        val before = plan.edit(block)
        if (WholeRoute.firstRoute(before.size, waypoints.size)) fitNextRoute = true
        routingSpot = RoutingSpot.of(before, waypoints)
        cellsForNewPoints(before, waypoints)
        canUndo = plan.canUndo; canRedo = plan.canRedo
        prefs.planWaypoints = waypoints.toList()
        prunePinned()
        planEditPending = true
        val split = splitOf(before, waypoints.toList())
        val pos = myPosition()
        // the one point left is where I stand (or only the start the one-point rule put in): nothing to ask about – the
        // plan empties in oneLeftGoesFromHere (field bug 2026-11-06; standing on point 1 also counts it as passed, so the
        // split says NothingAhead)
        val leftIsHere = plan.leftIsHere(pos)
        routeBackAsk = !leftIsHere && (split == TourSplit.Outcome.NothingAhead ||
            RouteBack.asks(before, waypoints, recState.value != null, followLine != null, followIsTrack, pos))
        if (routeBackAsk) {
            Problems.trace("plan", "Edit during the tour left no point ahead: asking \"No destination ahead\"")
            sayEvent(RecordingService.SAY_NO_DESTINATION)
        }
        oneLeftGoesFromHere(before, mayEmpty = true)
        planChanged()
        endGuidanceIfPlanEmpty()
        runCauses.pointEdit()
        recalc()
    }

    /** A tour event for the spoken directions (the service says it when they are on). */
    private fun sayEvent(what: String) {
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_SAY) {
            putExtra(RecordingService.EXTRA_SAY, what)
        }
    }

    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    private fun myPosition(): GeoPoint? =
        tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: recState.value?.track?.lastOrNull()?.lastOrNull()

    /**
     * The plan points passed in the running tour ([TourSplit.passed]) – empty without a tour that follows the plan.
     * Only the app's tour: the plan itself is edited exactly as in the web planner.
     */
    fun tourPassed(plan: List<GeoPoint> = waypoints.toList()): Set<Int> {
        val rec = recState.value ?: return emptySet()
        if (rec.tourId != passedTourId) { passedTourId = rec.tourId; passedPoints.clear() }
        if (followLine == null || followIsTrack || plan.isEmpty()) return emptySet()
        val s = rec.nav?.session?.takeIf { it.kind == NavKind.PLAN }
        return TourSplit.passed(plan, TourSplit.Tour(
            track = rec.track, position = myPosition(), reached = s?.reached == true, bike = activity != Activity.HIKE,
            // progress compares with the stops' places on the original line only (a taken re-route has its own)
            stops = s?.takeUnless { it.rerouted }?.stops.orEmpty(), progressM = rec.nav?.progressM ?: 0.0,
            visited = s?.visited?.mapNotNull { s.stops.getOrNull(it)?.point }?.toSet().orEmpty(),
        ), known = passedPoints).also { r -> r.forEach { i -> plan.getOrNull(i)?.let { passedPoints += it } } }
    }

    /**
     * The plan points found passed in this tour (fix-tour-edit-line.md cause 1): after an edit the tour follows
     * position → ahead and its session no longer knows them – they stay passed (matched by coordinate; a deleted point
     * simply no longer matches). Cleared with a new tour.
     */
    private val passedPoints = HashSet<GeoPoint>()
    private var passedTourId: Long? = null

    /** The next plan change loads a saved route during a tour ([openTour] duringTour): a whole new plan ([splitOf]). */
    private var loadedOnTheWay = false

    /** [TourSplit.outcome] of the edit [before] → [after] during a tour, kept for [followEditedPlan]. */
    private fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? {
        splitRequests.invalidate()
        // a route loaded on the way (a tap in My routes during a tour, owner 2026-11-03 / 2026-11-06) is a new plan, not an edit: followed as
        // a whole from its point ① (a way there / the mid-route question like at a tour start), nothing of it passed
        val fresh = loadedOnTheWay; loadedOnTheWay = false
        val passed = if (fresh) emptySet() else tourPassed(before)
        val o = if (passed.isEmpty()) null else TourSplit.outcome(before, after, passed)
        pendingSplit = o; splitBefore = before
        if (o != null) Problems.trace("plan", "Edit during the tour: ${passed.size} of ${before.size} points passed → " +
            (if (o is TourSplit.Outcome.Follow) "follow position → ${o.ahead.size} points ahead" else o.toString()))
        return o
    }

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
        splitRequests.invalidate()
        if (recState.value == null) return
        val pos = myPosition()
        val plan = waypoints.toList()
        // one point left: position → start (RouteBack); more: the plan's start is appended as its goal (a round trip),
        // the passed points stay, the tour follows position → start (TourSplit)
        val back = if (!yes) null else if (plan.size == 1) RouteBack.plan(plan, pos)
            else plan.takeIf { pos != null }?.let { it + it.first() }
        if (back != null && plan.size > 1) splitOf(plan, back) else pendingSplit = null
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
        if (yes) noPositionForEdit(PlanGps.Note.ROUTE_BACK_NO_POSITION, Res.string.msg_route_back_no_position)
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

    /**
     * One point left after an edit during a tour ([PlanGuidance.oneLeftGoesFromHere], owner 2026-11-06,
     * onpath.private#39): "Hierhin" – the plan becomes position → that point in the same Undo step (one Undo brings the
     * plan from before), the sheet shows that route and the tour follows it as a whole new plan ([followEditedPlan]).
     * Without a position the guidance ends as for an empty plan and a line says why; the recording goes on.
     */
    private fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean = false) {
        val left = waypoints.singleOrNull()
        val pos = myPosition()
        val auto = left != null && left == plan.oneLeftStart
        val outcome = plan.oneLeft(before.size, recState.value != null, followLine != null, followIsTrack, routeBackAsk, pos)
        if (outcome == PlanGuidance.OneLeft.NONE) return
        if (outcome == PlanGuidance.OneLeft.EMPTY) {
            if (!mayEmpty) {
                // Undo / Redo brings a plan back – it never empties it; but one point right here has no route of its
                // own, so the tour must not keep following the old line (audit 2026-11-07, PlanFlowPropertyTest rule 4):
                // the guidance ends as for an empty plan, the recording goes on, Redo / a new point bring it back
                Problems.trace("plan", "Undo / Redo left one point " +
                    (if (auto) "(the start the one-point rule put in)" else "right where I stand") +
                    " during the tour: route guidance ended, recording continues")
                pendingSplit = null
                clearFollow()
                guidanceEndedByEmptyPlan = true
                return
            }
            Problems.trace("plan", "One point left during the tour, " +
                (if (auto) "only the start this rule put in" else "right where I stand") + ": the plan empties (field bug 2026-11-06)")
            routeBackAsk = false
            plan.empty()
            prefs.planWaypoints = emptyList()
            return // endGuidanceIfPlanEmpty: the guidance ends, the recording goes on
        }
        val goal = left!!
        if (pos == null) {
            Problems.trace("plan", "One point left during the tour, no position: route guidance ended, recording continues")
            pendingSplit = null
            clearFollow()
            guidanceEndedByEmptyPlan = true
            noPositionForEdit(PlanGps.Note.ONE_POINT_NO_POSITION, Res.string.msg_one_point_no_position)
            return
        }
        pendingSplit = null // a whole new plan from here, nothing of it passed
        plan.fromHere(pos, goal)
        cellsForNewPoints(before, waypoints)
        prefs.planWaypoints = waypoints.toList()
        Problems.trace("plan", "One point left during the tour: plan = position → that point (like Hierhin)")
    }

    /** Forget pinned points that are neither planned nor reachable by undo/redo ([PlanSession.prunePinned]). */
    private fun prunePinned() {
        if (plan.prunePinned()) prefs.planPinned = pinned
    }

    private fun set(list: List<GeoPoint>) = change { clear(); addAll(list) }

    /**
     * Adds a held point where it most likely belongs (WaypointInsertion, F-PLAN-13), silently: the hold's vibration and
     * the new line confirm it; a wrong guess is fixed with Undo or by dragging ≡ in the Points list.
     * @param lineSnapM "on the line" distance, ~24 dp in metres at the current zoom.
     */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) {
        if (onPoi) { pinned += p; prefs.planPinned = pinned }
        val ins = insertionFor(p, lineSnapM)
        if (ins.decision.kind == WaypointInsertion.Kind.ON_THE_WAY)
            Problems.trace("plan", "New point on the way there (led to a route never reached): a stop before ①, the tour " +
                "is led via it (a round trip's loop stays, owner 2026-11-03)")
        change { add(ins.index.coerceIn(0, size), p) }
    }

    /**
     * Where a new point [p] goes ([addPoint]): [points] and [line] = what the decision was made on, [index] = the place in
     * the plan. [inTour]: a tour passed plan points – only the points ahead count, from the position.
     */
    private class Insertion(val points: List<GeoPoint>, val line: List<GeoPoint>?, val decision: WaypointInsertion.Decision,
                            val index: Int, val inTour: Boolean)

    private fun insertionFor(p: GeoPoint, lineSnapM: Double, stopOnly: Boolean = false): Insertion {
        val before = waypoints.toList()
        // during a tour a new point goes only among the points ahead, never behind the user (TourSplit)
        val passed = tourPassed(before)
        val pos = myPosition()
        if (passed.isNotEmpty() && pos != null) {
            val ahead = listOf(pos) + TourSplit.ahead(before, passed)
            // the followed line fits these points only once it runs from the position (else best fit by geometry)
            val line = followLine?.takeIf { it.isNotEmpty() && com.onpath.app.data.Geo.distance(it.first(), pos) < 50.0 }
            val d = WaypointInsertion.decide(ahead, p, line, lineSnapM, stopOnly = stopOnly)
            return Insertion(ahead, line, d, TourSplit.insertIndex(passed, d.index), inTour = true)
        }
        // While a reroute runs, the shown line belongs to older waypoints: use best-fit only.
        val line = if (routing) null else route?.points
        val d = WaypointInsertion.decide(before, p, line, lineSnapM, wayThereFrom(), stopOnly = stopOnly)
        return Insertion(before, line, d, d.index, inTour = false)
    }

    /**
     * The add entries of a held place's menu ([PlaceAddMenu], owner 2026-11-07): with 2+ points "Als Zwischenstopp"
     * and/or "Als neues Ziel" by how clear the case is – measured on the route still to go (during a tour from my position).
     */
    fun placeMenu(poi: com.onpath.app.poi.Poi): PlaceAddMenu.Menu {
        val p = GeoPoint(poi.lat, poi.lon)
        val n = waypoints.size
        val touring = recState.value != null
        if (n < 2) return PlaceAddMenu.menu(n, touring, zone = null)
        val ins = insertionFor(p, 30.0)
        val pos = myPosition()
        // during a tour the route to go starts here (TourSplit's ahead list already does; else put my position first)
        val ahead = if (ins.inTour || !touring || pos == null) ins.points else listOf(pos) + ins.points
        return PlaceAddMenu.menu(n, touring, PlaceAddMenu.zone(ahead, p, loop = isRoundTrip()))
    }

    /** Place menu → "Als Zwischenstopp": where it fits best on the route ahead, never behind the goal (the goal stays). */
    fun addPoiAsStop(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) = addPoiWith(poi, "as a stop") { p ->
        val ins = insertionFor(p, lineSnapM, stopOnly = true)
        change { add(ins.index.coerceIn(0, size), p) }
    }

    /**
     * Place menu → "Als neues Ziel": the route ends there, the old goal is dropped (one Undo step brings it back). During a
     * tour the guidance follows the new plan as after any edit.
     */
    fun poiAsNewDestination(poi: com.onpath.app.poi.Poi) = addPoiWith(poi, "as the new destination") { p ->
        change { if (isEmpty()) add(p) else set(lastIndex, p) }
    }

    /** The bookkeeping of a place taken from its menu (name, pin, candidate fit), then [edit] with its point. */
    private fun addPoiWith(poi: com.onpath.app.poi.Poi, how: String, edit: (GeoPoint) -> Unit) {
        val candidate = isCandidate(poi)
        closeMapOverlays()
        val idx = waypoints.indexOfFirst { sameSpot(it, poi.lat, poi.lon) }
        if (idx >= 0) { openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        val pin = takePin(poi.lat, poi.lon)
        pinned += p; prefs.planPinned = pinned
        val before = waypoints.size
        if (candidate) fittingPlaceAdd { edit(p) } else edit(p)
        pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Place menu: \"${poi.name ?: poi.kind}\" $how (plan $before → ${waypoints.size} points)")
    }

    /**
     * The candidate marker's dashed preview line (owner 2026-10-31): from the search result / shared place to where
     * adding it would join the route – the same decision as [addPoint] (WaypointInsertion.joinPoint). null = no line:
     * no candidate, not in Plan, no route yet or one being computed, or the place already is a route point.
     */
    fun candidateJoin(): Pair<GeoPoint, GeoPoint>? {
        val m = searchMark ?: return null
        if (!canEditMap || waypoints.size < 2) return null
        if (waypoints.any { sameSpot(it, m.lat, m.lon) }) return null
        val p = GeoPoint(m.lat, m.lon)
        val ins = insertionFor(p, 30.0)
        return WaypointInsertion.joinPoint(ins.points, p, ins.line, ins.decision)?.let { p to it }
    }

    /**
     * Bug 02: the position while a tour is led to this plan ("Guide me") and has never reached it – a new point that fits
     * on the way there becomes the new start ([WaypointInsertion] rule 4); else null.
     */
    private fun wayThereFrom(): GeoPoint? {
        val s = recState.value?.nav?.session ?: return null
        if (!s.lead || s.reached || s.kind != com.onpath.app.navigation.NavKind.PLAN || followIsTrack) return null
        return myPosition()
    }

    /** Plan point [index] deleted (map menu, search card): a round trip stays closed, as in the Points list. */
    fun removePoint(index: Int) {
        val after = com.onpath.app.routing.PlanPoints.deleteAt(waypoints, index)
        if (after.size != waypoints.size) set(after)
    }

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
        // The found place's pin stays when its menu closes (owner 2026-11-02: a tap elsewhere closes only the menu); it
        // goes when the place is added, with a new search, "Neu planen" or the menu's "Nadel entfernen" ([removePin]).
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

    /** The pin's name chip tapped (owner 2026-11-02): the same menu a hold on the pin opens. */
    fun openPinMenu() {
        val m = searchMark ?: return
        Problems.trace("plan", "Pin chip tapped: menu of \"${m.title()}\"")
        openPoiMenu(m.toPoi())
    }

    /**
     * The found place's pin goes (owner 2026-11-02): its menu's "Nadel entfernen" or "Neu planen" ([clearPlan]); adding
     * the place ([clearSearchMarkAt]) and a new search pick replace it on their own.
     */
    fun removePin(why: String) {
        if (searchMark == null) return
        Problems.trace("plan", "Pin removed: $why")
        searchMark = null; searchPlace = null
    }

    /** POI menu on an empty plan → "Go here": start = position, the POI (pinned, named) = destination. */
    fun poiFromMyLocation(poi: com.onpath.app.poi.Poi) {
        closeMapOverlays()
        val loc = tracker.location.value ?: return // the menu only offers this with a position
        if (waypoints.isNotEmpty()) { addPoiPoint(poi); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        val pin = takePin(poi.lat, poi.lon)
        pinned += p; prefs.planPinned = pinned
        change { add(GeoPoint(loc.latitude, loc.longitude)); add(p) }
        pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Go here from my location: \"${poi.name ?: poi.kind}\" (plan 0 → ${waypoints.size} points)")
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
    /**
     * What the POI menu's add action says – the SAME decision [addPoint] then takes ([insertionFor]: during a tour only
     * the points ahead count, from the position), so the label never promises something else (field 2026-11-07: the
     * label was decided on the whole plan and read "Als Start setzen" while the tour ran).
     */
    fun poiAddLabel(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0): String {
        val ins = insertionFor(GeoPoint(poi.lat, poi.lon), lineSnapM)
        return com.onpath.app.search.AddPreview.label(WaypointInsertion.Decision(ins.index, ins.decision.kind), waypoints.size)
    }

    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) {
        // the candidate marker's menu (a search result / shared place, maybe far off): the new route is fitted
        val candidate = isCandidate(poi)
        closeMapOverlays()
        val idx = waypoints.indexOfFirst { sameSpot(it, poi.lat, poi.lon) }
        if (idx >= 0) { openPointMenu(idx, via = "POI that is a stop"); return }
        val p = GeoPoint(poi.lat, poi.lon)
        rememberPlaceName(p, com.onpath.app.search.Place.fromPoi(poi).title())
        val pin = takePin(poi.lat, poi.lon)
        val before = waypoints.size
        if (candidate) fittingPlaceAdd { addPoint(p, lineSnapM, onPoi = true) } else addPoint(p, lineSnapM, onPoi = true)
        pinTaken = pin?.let { TakenPin(it, p) }
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
    /** Index of the round trip's ① (stops on the way before it), null without a loop ([PlanEdits.loopStart]). */
    fun loopStart(): Int? = PlanEdits.loopStart(waypoints)

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
        namesJob = viewModelScope.launchSafe("app", "refreshPointNames") {
            // only the places near each point are read from the index file (off the main thread)
            val index = try { search.offlineIndex() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Problems.info("plan", "No place index for point names; \"Point N\" shown", e); return@launchSafe }
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
        viewModelScope.launchSafe("app", "showPointFromList") { delay(3_000); if (highlight == p) highlight = null }
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

    var searchOpen: Boolean
        get() = searchFlow.searchOpen
        private set(v) { searchFlow.searchOpen = v }
    /** The picked search result (address, kind) behind planPoi; null for a POI tapped on the map. */
    private var searchPlace: com.onpath.app.search.Place?
        get() = searchFlow.searchPlace
        set(v) { searchFlow.searchPlace = v }
    /** Search result / shared place marked on the map (the candidate marker). */
    var searchMark: com.onpath.app.search.Place?
        get() = searchFlow.searchMark
        set(v) { searchFlow.searchMark = v }
    fun openSearch() = searchFlow.openSearch()
    fun closeSearch() = searchFlow.closeSearch()
    fun pickSearchResult(place: com.onpath.app.search.Place) = searchFlow.pickSearchResult(place)
    /** [poi] is the found place's pin (its menu then ends with "Nadel entfernen"). */
    fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = searchFlow.isCandidate(poi)
    private fun clearSearchMarkAt(lat: Double, lon: Double) = searchFlow.clearSearchMarkAt(lat, lon)

    /** The found place at ([lat], [lon]) goes into the plan: its pin goes; returns that place (null = no pin there). */
    private fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? {
        val m = searchMark?.takeIf { sameSpot(GeoPoint(it.lat, it.lon), lat, lon) }
        clearSearchMarkAt(lat, lon)
        if (m != null) Problems.trace("plan", "Pin taken into the plan: \"${m.title()}\"")
        return m
    }

    /** The found place the last edit added ([TakenPin]): a Cancel that undoes that edit shows its pin again. */
    private var pinTaken: TakenPin? = null
    /**
     * [block] adds a place from the place card or the candidate marker's menu: the new route is fitted once computed
     * ([WholeRoute.fitAfterPlace], the same fit as the first route).
     */
    private inline fun fittingPlaceAdd(block: () -> Unit) {
        val before = waypoints.toList()
        block()
        if (WholeRoute.fitAfterPlace(before, waypoints.toList())) fitNextRoute = true
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
            r?.takeIf { it.durationS > 0 }?.let { it.distanceM / it.durationS }, here = wayThereFrom())
        // during a tour the add goes among the points ahead ([insertionFor]) – say what really happens
        val ins = insertionFor(GeoPoint(place.lat, place.lon), 30.0)
        val k = ins.decision.kind
        val label = if (ins.inTour) com.onpath.app.search.AddPreview.label(WaypointInsertion.Decision(ins.index, k), waypoints.size)
            else pv.label
        return com.onpath.app.ui.search.PlaceCardUi(place, null, waypoints.size, if (ins.inTour) "" else pv.subtitle, label,
            addIsStart = k == WaypointInsertion.Kind.START,
            addIsDestination = k == WaypointInsertion.Kind.DESTINATION && waypoints.size <= 1)
    }

    /** Adds the place as a pinned stop (passed exactly), named after the place. Moves it when already planned. */
    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) = fittingPlaceAdd {
        val p = GeoPoint(place.lat, place.lon)
        val name = place.title()
        val idx = routeIndexOf(place)
        closePlaceCard()
        rememberPlaceName(p, name)
        // taken: the candidate marker goes (the numbered route point shows the place now)
        val pin = takePin(place.lat, place.lon)
        if (idx != null) {
            // "As start" = the point menu's "Start here" (owner); "As destination" moves it to the end
            when (role) { PlaceRole.START -> startAtPoint(idx); PlaceRole.DESTINATION -> makeDestination(idx); PlaceRole.SMART -> Unit }
            return@fittingPlaceAdd
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
        pinTaken = pin?.let { TakenPin(it, p) }
        Problems.trace("plan", "Place card: \"$name\" added (${role.name.lowercase()}, plan now ${waypoints.size} points)")
    }

    fun removePlace(place: com.onpath.app.search.Place) { routeIndexOf(place)?.let { removePoint(it) }; closePlaceCard() }

    /** Name of a route point added from a place, or null (UI: "Leiter Alm" instead of "Point 3"). */
    fun stopName(p: GeoPoint): String? = searchFlow.stopName(p)
    private fun rememberPlaceName(p: GeoPoint, name: String) = searchFlow.rememberPlaceName(p, name)
    // ---------------- end search ----------------

    // ---------------- shared places (another app → OnPath): [SearchFlow] ----------------
    val sharedLookup: Boolean get() = searchFlow.sharedLookup
    fun receiveSharedPlace(shared: com.onpath.app.search.SharedPlace) = searchFlow.receiveSharedPlace(shared)
    val sharedUnsure: String? get() = searchFlow.sharedUnsure
    fun searchSharedUnsure() = searchFlow.searchSharedUnsure()
    val sharedMenuWaiting: Boolean get() = searchFlow.sharedMenuWaiting
    val cameraIdleTick: Int get() = searchFlow.cameraIdleTick
    fun cameraIdle() = searchFlow.cameraIdle()
    fun openSharedMenuIfArrived(cameraLat: Double?, cameraLon: Double?, metersPerPx: Double, arrivedPx: Double) =
        searchFlow.openSharedMenuIfArrived(cameraLat, cameraLon, metersPerPx, arrivedPx)
    private fun holdCameraForSharedPlace() = searchFlow.holdCameraForSharedPlace()

    fun makeDestination(index: Int) {
        // the last row; a round trip stays closed at its start (as dragging the row to the end of the Points list)
        if (index in waypoints.indices && index != waypoints.lastIndex) set(com.onpath.app.routing.PlanPoints.move(waypoints, index, waypoints.size - 1))
    }
    /** "⇅ Umkehren" changes the plan (greyed out for an out-and-back over one point, owner 2026-11-06 #40). */
    fun canReverse(): Boolean = PlanEdits.reverseChanges(waypoints)
    fun reverseRoute() {
        if (!PlanEdits.reverseChanges(waypoints)) {
            Problems.trace("plan", "Reverse: nothing to reverse (plan ${waypoints.size} points, the same backwards)"); return
        }
        Problems.trace("plan", "Reverse: plan ${waypoints.size} points")
        set(PlanEdits.reverse(waypoints))
    }

    /** "↻ Rundtour" can be switched: on (adds the way back to the start) or off (the plan is a round trip). */
    fun canRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints) || PlanEdits.canRoundTrip(waypoints)

    /**
     * "↻ Rundtour" is a switch (owner 2026-11-06, onpath.private#40): on → the start becomes the final destination; on a
     * round trip a tap takes it back – the closing point goes (A → B → A → A → B). One Undo step either way.
     */
    fun roundTrip() {
        val on = PlanEdits.isRoundTrip(waypoints)
        val w = if (on) PlanEdits.unRoundTrip(waypoints) else PlanEdits.roundTrip(waypoints)
        if (w.size == waypoints.size) { Problems.trace("plan", "Round trip: nothing to change (plan ${waypoints.size} points)"); return }
        Problems.trace("plan", "Round trip ${if (on) "off" else "on"}: plan ${waypoints.size} → ${w.size} points")
        set(w)
    }

    fun undoStep() = step(plan.undo(), "Undo")
    fun redoStep() = step(plan.redo(), "Redo")

    private fun step(prev: List<GeoPoint>?, what: String) {
        if (prev == null) { Problems.trace("plan", "$what: nothing to ${what.lowercase()}"); return }
        Problems.trace("plan", "$what: plan ${waypoints.size} → ${prev.size} points")
        pinTaken = null // the user's own Undo / Redo: a later Cancel does not bring a pin back
        val before = waypoints.toList()
        routingSpot = RoutingSpot.of(before, prev)
        val split = splitOf(before, prev)
        plan.replace(prev)
        canUndo = plan.canUndo; canRedo = plan.canRedo
        // the plan an open question was about is gone; one point left where I stand asks nothing (standing on it counts it
        // as passed, so the split says NothingAhead – GitHub run 2026-11-07, as for edits in change())
        routeBackAsk = split == TourSplit.Outcome.NothingAhead && !plan.leftIsHere(myPosition())
        oneLeftGoesFromHere(before)
        closePointMenu("undo / redo"); firstPointMenu = null
        prefs.planWaypoints = waypoints.toList()
        planEditPending = true
        planChanged()
        endGuidanceIfPlanEmpty()
        runCauses.step(undo = what == "Undo")
        recalc()
    }

    /** Clear the plan right away (owner): one Undo step brings it back, so no question. */
    /**
     * ✕ / ☰ Neu planen: the plan leaves the map at once – no question, also during a tour (owner 2026-11-06): a plan not
     * saved as it is goes under My routes → Zuletzt ([keepAsDraft]), one Undo step brings it back, and during a tour the
     * empty sheet says "Tour läuft weiter – …" (the recording goes on, the guidance ends).
     */
    fun requestClear() = clearPlan()

    /**
     * Resets the whole plan in one undoable step: points, route line, pinned marks (kept while
     * undo can bring them back), error, highlight, notice and point menu. A routing job still
     * running is cancelled and its result ignored (recalc → RouteRequests.invalidate).
     * A followed copy of this plan goes too, unless a recording is using it right now.
     */
    fun clearPlan() {
        keepAsDraft("cleared")
        Problems.trace("plan", "Clear route: plan ${waypoints.size} → 0 points" + if (recState.value != null) " (tour running)" else "")
        val planLine = route?.points
        if (waypoints.isNotEmpty()) change { clear() } else recalc()
        closePointMenu("plan cleared"); firstPointMenu = null; highlight = null
        removePin("plan cleared")
        // the empty sheet says how to start a plan: not rolled up
        if (planSize == PlanSize.COLLAPSED) planSize = PlanSize.MINI
        // no tour: the followed line goes with the plan – also when it was the way there ("Guide me") or the app restarted
        // meanwhile (followFromPlan lives in memory only; Pixel 2026-11-01: a blue line without points stayed). A shown
        // GPX track keeps its own line.
        if (com.onpath.app.domain.plan.ClearRule.dropsFollowWithoutTour(following = followLine != null,
                recording = recState.value != null, followFromPlan = followFromPlan,
                followIsPlanLine = planLine != null && followLine == planLine, followIsTrack = followIsTrack)) clearFollow()
        // during a tour (owner 2026-11-05): "Neu planen" ends every guidance – also a followed track, also when the plan was
        // already empty; Navigate shows a free tour, the recording never stops. Undo brings the points (and following them).
        if (PlanGuidance.clearEndsGuidance(recState.value != null, followLine != null)) {
            Problems.trace("plan", "Plan cleared during the tour: route guidance ended, recording continues")
            clearFollow()
            guidanceEndedByEmptyPlan = true
        }
    }

    fun answerLongRoute(plan: Boolean) {
        longRouteAsk = null
        val deferred = longRouteDeferred; longRouteDeferred = null
        if (plan) {
            longRouteOk = waypoints.toList()
            deferred?.let { cellsForNewPoints(it, waypoints) } // now the new point's maps load
            runCauses.again(); recalc(); return
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
        val cause = runCauses.takeForCancel()
        val what = PlanStatus.onCancel(cause, canUndo, canRedo)
        Problems.trace("routing", "Cancel ($why) of a run for ${cause::class.simpleName}: ${what.name.lowercase()}")
        val taken = pinTaken; pinTaken = null
        leaveUnrouted(why)
        when (what) {
            PlanStatus.OnCancel.UNDO -> undoStep()
            PlanStatus.OnCancel.REDO -> redoStep()
            PlanStatus.OnCancel.RESTORE_ACTIVITY -> (cause as? PlanStatus.Cause.ActivityChange)?.let { restoreActivity(it) }
            PlanStatus.OnCancel.KEEP_POINTS -> Unit
        }
        // the add of a found place taken back: the place stays as its pin (field 2026-11-07, S10 – never forgotten)
        TakenPin.back(taken, waypoints.toList(), searchMark)?.let { p ->
            searchMark = p
            Problems.trace("plan", "Cancel took back the add of \"${p.title()}\": its pin is shown again")
        }
    }

    /** The activity and T level from before the cancelled change; the route is planned for them again (no undo step). */
    private fun restoreActivity(c: PlanStatus.Cause.ActivityChange) {
        activity = c.activity; prefs.activity = c.activity
        sac = c.sac; prefs.sac = c.sac
        routeChangedNotice = null; activityBefore = null; activityRecalc = false
        runCauses.other()
        recalc()
    }

    /** The answer to [slowPlanAsk]: wait one more period (the run again, longer) or stop like Cancel. */
    fun answerSlowPlan(answer: com.onpath.app.routing.SlowPlan.Answer) {
        val rounds = slowPlanAsk ?: return
        slowPlanAsk = null
        val next = com.onpath.app.routing.SlowPlan.next(rounds, answer)
        Problems.info("routing", "Slow plan: ${answer.name.lowercase()} after $rounds period(s)")
        if (next == null) { planRounds = 1; cancelRun(NotRouted.STOPPED); return }
        planRounds = next; keepRounds = true
        runCauses.again() // Cancel later still takes back the change that started this run
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
        route = null; routeError = null; routing = false; routingSlow = false; routeProgress = null; routeShare = null; longRouteAsk = null; slowPlanAsk = null
        followNextRoute = false; planEditPending = false
        notRouted = why
        mapData.corridorFollowsPlan(null)
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

    /**
     * From the planning thread ([Planner]'s progress): the wait card's legs done / to route ([routeProgress]) – only while
     * the run [gen] is the current one.
     */
    private fun showProgress(gen: Int, done: Int, total: Int) {
        viewModelScope.launchSafe("app", "showProgress", Dispatchers.Main) { if (requests.isCurrent(gen) && routing) routeProgress = done to total }
    }

    private fun recalc() {
        runCauses.start()
        // a new run (an edit, an activity change …) starts at one time-limit period and drops an open "Weiter warten?"
        if (!keepRounds) planRounds = 1
        keepRounds = false
        slowPlanAsk = null
        val rounds = planRounds
        routeJob?.cancel(); routeJob = null
        val viaData = routingDataReroute; routingDataReroute = false // this run: newer routing data arrived
        highlight = null; spotLine = null; spotsOpen = false
        // the route before an activity / T level change, compared with this run's result ("Route changed for …")
        val changeBefore = activityBefore.takeIf { activityRecalc }; activityRecalc = false
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        routeCancel?.cancel(); routeCancel = null; routingSlow = false // an older request stops at once
        planReach = null
        routeProgress = null; routeShare = null
        followCancel?.cancel(); followCancel = null // the tour's follow line of the old plan: a new one comes with this run
        notRouted = null
        if (pts.size < 2) {
            requests.invalidate(); route = null; routeError = null; routing = false; followNextRoute = false
            fitNextRoute = false
            mapData.corridorFollowsPlan(null); return
        }
        val act = activity; val level = sac
        // owner: a plan far beyond a day (a point in another country by mistake) is asked about first –
        // nothing is routed or loaded until the answer
        if (pts != longRouteOk && com.onpath.app.routing.LongRoute.needsAsk(pts, act)) {
            requests.invalidate(); route = null; routeError = null; routing = false
            longRouteAsk = com.onpath.app.routing.LongRoute.estimateS(pts, act)
            Problems.trace("routing", "Very long plan (${com.onpath.app.routing.LongRoute.describe(longRouteAsk!!)}) – asking first")
            mapData.corridorFollowsPlan(null) // nothing loads for a plan that is not routed
            return
        }
        longRouteAsk = null
        val f = planFactors(act)
        val gen = requests.next()
        routing = true
        val started = android.os.SystemClock.elapsedRealtime()
        val cancel = com.onpath.app.routing.RouteCancel().also { routeCancel = it }
        val askAtLimit = mode == AppMode.PLAN
        routeJob = viewModelScope.launchSafe("app", "recalc") {
            delay(150) // debounce quick taps
            // the Cancel button appears only when it takes a while (no flicker for normal routes)
            val slow = launch { delay(ROUTING_SLOW_MS); if (requests.isCurrent(gen)) routingSlow = true }
            // the wait bar within a leg (owner 2026-11-05): the router's search, polled off the main thread
            val planThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
            val bar = WaitBar()
            val poll = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(WAIT_POLL_MS)
                    val t = planThread.get() ?: continue
                    val legs = routeProgress ?: continue
                    val share = bar.next(legs.first, legs.second, (engine as? com.onpath.app.routing.BRouterEngine)?.searchProgress(t))
                    withContext(Dispatchers.Main) { if (requests.isCurrent(gen) && routing) routeShare = share }
                }
            }
            var planner: Planner? = null
            var reach: com.onpath.app.routing.PlanReach? = null
            val result = withContext(routingDispatcher) {
                exceptions {
                    val eng = routingEngine()
                    // route, model time, snapped points, gaps, warnings: the one computation the web planner shares
                    // (named stops: read on the main thread when the result is taken – snapToRoute → PlanResult.withNamed)
                    // in Plan the time limit asks "Weiter warten?" (SlowPlan); a run for Navigate keeps the straight lines
                    com.onpath.app.routing.PlanResult.plan(Planner(eng, cancel, askAtLimit = askAtLimit, rounds = rounds, legs = legCache,
                        progress = { done, total -> planThread.set(Thread.currentThread()); showProgress(gen, done, total) }).also { planner = it },
                        pts, act, level, pins, emptySet(), f)
                }.also { r ->
                    // a stop not reached: what does reach it ("② erst ab T2 erreichbar") – only the missing piece,
                    // after the plan route, on the same thread; the next edit cancels it with the route ([cancel])
                    r.getOrNull()?.gaps?.firstOrNull()?.let { g -> reach = findReach(g, act, level, cancel) }
                }
            }
            slow.cancel(); poll.cancel()
            // the corridor work of a result off the main thread
            val post = result.getOrNull()?.let { r -> withContext(Dispatchers.Default) { mapData.postRoute(r.route, pts, act) } }
            if (requests.isCurrent(gen)) { routingSlow = false; routeProgress = null; routeShare = null }
            if (!requests.isCurrent(gen) || !isActive) { // superseded or cleared
                Problems.trace("routing", "Route #$gen for ${pts.size} points superseded – result ignored")
                return@launchSafe
            }
            // one line per route: distance, size, profile, each try's time and outcome
            val done = result.getOrNull()?.route
            Problems.trace("routing", "Route #$gen for ${pts.size} points " + (if (result.isSuccess) "ready" else "failed") +
                " after ${android.os.SystemClock.elapsedRealtime() - started} ms (plan now ${waypoints.size} points): " +
                "${act.name}, ${"%.1f".format(Locale.ROOT, com.onpath.app.routing.RouteTimeLimit.straightKm(pts))} km straight" +
                (done?.let { ", ${"%.1f".format(Locale.ROOT, it.distanceM / 1000)} km routed, ${it.points.size} line points, " +
                    "${it.legs.count { l -> l.straight }} straight legs" } ?: "") +
                (planner?.describeTries()?.takeIf { it.isNotEmpty() }?.let { "; tries: $it" } ?: ""))
            var baseline = false
            result.onSuccess { res ->
                val it = res.route
                baseline = adoptPlanBaseline
                route = it; planReach = reach; snapToRoute(pts, res)
                if (fitNextRoute) { fitNextRoute = false; fitFirstRoute(it.points, pts) }
                // the route warnings of this result (computed with it, off the main thread): routeSpots reads them
                spotsMemo = Triple(it.points, act, level) to res.spots
                if (changeBefore != null) {
                    activityBefore = null
                    if (RouteWarnings.changedNoticeably(changeBefore.points, changeBefore.distanceM, changeBefore.ascentM,
                            it.points, it.distanceM, it.ascentM)) routeChangedNotice = RouteWarnings.changedText(act, level)
                }
                // routing data missing in the route's corridor (hike 10 km, bike 20 km – OfflineCorridor): it loads now; the notice (plain words, no path)
                // only when a part under a planned point is missing – those make the straight line
                post?.routingKeys?.let { k -> mapData.routingForLine(k) }
                routeError = RoutingDataNotice.forRoute(it.error, post?.tilesMissing == true, mapData.routingLoading)
                // T4: the corridor – named after the plan's destination (round trip: its start) in the loading chip
                // the last point: a round trip closes at its ① (also with stops on the way before the loop)
                val dest = pts.lastOrNull()
                mapData.routeComputed(it.points, post?.cells, dest, act)
                if (mode == AppMode.PLAN && !followNextRoute && !planEditPending && !viaData) freshenAfterRoute(it.points, post?.cells, act)
                if (viaData) routingDataRouteChanged(it)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (adoptPlanBaseline) { adoptPlanBaseline = false; planBaseline = it.points }
            }
                .onFailure {
                    if (it is com.onpath.app.routing.SlowPlan.TimeLimit) {
                        // the time limit in Plan: ask (the box stays until answered); the points and the line stay as
                        // they are – no straight lines, no error line
                        Problems.info("routing", "Plan reached its time limit after ${it.rounds} period(s) – asking to wait on")
                        slowPlanAsk = it.rounds
                        routing = false
                        return@launchSafe
                    }
                    fitNextRoute = false // the points were fitted when they came (link) or are where the user held
                    Problems.error("routing", "Route calculation failed", it)
                    // during a tour one failed run must not take the whole plan line off the map (fix-tour-edit-line.md)
                    if (recState.value == null) route = null
                    // our own routing texts only – never an exception's raw text (review Arch 4)
                    routeError = com.onpath.app.debug.UserText.of(it, Txt.s(Res.string.msg_routing_failed))
                    mapData.corridorFollowsPlan(null)
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
        com.onpath.app.routing.PlanResult.withModelTime(r, a, f)

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

    /** Indices of the named stops of [pts] (they keep their coordinates when the route is snapped). */
    private fun namedStops(pts: List<GeoPoint>): Set<Int> = pts.indices.filter { stopName(pts[it]) != null }.toSet()

    /**
     * Takes the snapped plan points of [res0] ([com.onpath.app.routing.PlanResult.snap]: free points onto the way the
     * router used – owner: no off-road guidance – so marker, stored plan and route line agree; POI-pinned and named
     * stops and a stop the route does not reach or skips keep their coordinates, its gap is shown). Amends the current
     * state (no undo step; history snapshots get the same values) and does not re-route: [route] already
     * belongs to the snapped points. Only for the newest result of exactly these points.
     */
    private fun snapToRoute(routed: List<GeoPoint>, res0: com.onpath.app.routing.PlanResult) {
        if (waypoints.toList() != routed) { planResult = res0; return }
        // the named stops keep their place: their names are read here, on the main thread (a stop named while the
        // route was calculated counts too)
        val res = res0.withNamed(namedStops(routed)).also { planResult = it }
        val far = res.far
        if (far.isNotEmpty()) Problems.trace("routing", "Route does not reach point(s) ${far.sorted().joinToString { "${it + 1}" }}")
        val snapped = res.points.takeIf { res.moved } ?: return
        for (i in snapped.indices) if (snapped[i] != routed[i]) waypoints[i] = snapped[i]
        if (routed.size >= 2 && !RouteRules.isUnsaved(routed, savedPoints)) setSaved(linkName, linkId, snapped)
        linkTitle?.let { (title, at) -> if (at == routed) linkTitle = title to snapped }
        linkPoints?.let { at -> if (at.map { it.lat to it.lon } == routed.map { it.lat to it.lon }) linkPoints = snapped }
        val moves = WaypointSnap.moves(routed, snapped)
        plan.snapped(moves)
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
        PlanGaps.reachText(gaps, planReach)?.let { return it }
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
        syncRoutingNote = null // edited here: the route is the phone's own now
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

    /** How many hints the amber line sums up: the ⚠ count of the rolled-up sheet. */
    fun warningCount(): Int = RouteWarnings.count(routeSpots)

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

    /** What the map column's greyed buttons need to know ([MapControlsEnabled]). */
    internal fun controlsIn(layer: AppMode, pointsOpen: Boolean) = MapControlsEnabled.In(layer, pointsOpen,
        planLine = hasPlanLine(), shownLine = hasShownLine(), shownOpen = shown != null, followLine = hasFollowLine(),
        canUndo = canUndo, canRedo = canRedo)

    private fun hasPlanLine() = (route?.points?.size ?: 0) >= 2
    private fun hasShownLine() = (shown?.segments?.sumOf { it.size } ?: 0) >= 2
    private fun hasFollowLine() = (followLine?.size ?: 0) >= 2

    /**
     * "Show whole route" (map button, owner 2026-10-31): following stops first (also free look – it would follow
     * again by itself), then the map shows the whole line and its points north up; in Navigate also where you are.
     * Following comes back with the locate button only.
     */
    fun showWholeRoute() {
        val src = WholeRoute.source(mode, hasPlanLine(), hasShownLine(), hasFollowLine()) ?: return
        val here = tracker.location.value?.takeIf { mode == AppMode.NAVIGATE }?.let { GeoPoint(it.latitude, it.longitude) }
        val pts = when (src) {
            WholeRoute.Source.SHOWN -> shown?.let { t -> WholeRoute.bounds(t.segments, t.waypoints.map { GeoPoint(it.lat, it.lon) }, null) }
            WholeRoute.Source.PLANNED -> route?.let { r -> WholeRoute.bounds(listOf(r.points), waypoints.toList(), null) }
            // with the original while a new route is followed (drawn thin grey)
            WholeRoute.Source.FOLLOWED -> WholeRoute.bounds(listOfNotNull(followLine, recState.value?.nav?.original), emptyList(), here)
        } ?: return
        followMe = false
        freeLookAt = null
        events += UiEvent.Fit(pts)
    }

    /**
     * Locate while I am off the map in Plan ([LocateDistance], owner 2026-11-07): the plan (or the shown track) and my
     * position together, in the room [FitPaddingRule] leaves. Following stops first; the bearing follows the session's one
     * orientation (owner 2026-11-07: north up when that is set, else the current bearing – MapController.northLocked).
     */
    fun showPlanAndMe() {
        val me = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) } ?: run { locate(); return }
        val pts = shown?.let { t -> WholeRoute.bounds(t.segments, t.waypoints.map { GeoPoint(it.lat, it.lon) }, me) }
            ?: WholeRoute.bounds(listOfNotNull(route?.points), waypoints.toList(), me)
        followMe = false
        freeLookAt = null
        events += UiEvent.Fit(pts) // the session's orientation decides the bearing (MapController.northLocked)
        Problems.trace("map", "Locate off the map: plan and position shown together (${pts.size} points)")
    }

    /**
     * The whole plan in front at a [PlanCamera.Moment] (owner 2026-11-06): once the sheet has settled, the fit of "Show
     * whole route" over the route line and the points (one point: centred) – not when a finger touched the map meanwhile,
     * Plan was left or a track is shown. Following stops first (CLAUDE.md "Map camera").
     */
    fun showPlanWhole(moment: PlanCamera.Moment) {
        val asked = android.os.SystemClock.uptimeMillis()
        viewModelScope.launchSafe("app", "showPlanWhole") {
            delay(WholeRoute.SETTLE_MS)
            val pts = waypoints.toList()
            val what = if (mode != AppMode.PLAN) PlanCamera.Show.NOTHING
                else PlanCamera.show(pts.size, touchedSince = lastMapTouchMs > asked, trackShown = shown != null)
            if (what == PlanCamera.Show.NOTHING) {
                Problems.trace("map", "Plan not shown whole (${moment.name.lowercase()}): the map was touched, Plan left or nothing to show")
                return@launchSafe
            }
            followMe = false
            freeLookAt = null
            events += if (what == PlanCamera.Show.ONE_POINT) UiEvent.CenterOn(pts[0], PlanCamera.ONE_POINT_ZOOM)
                else UiEvent.Fit(WholeRoute.bounds(listOfNotNull(route?.points), pts, null))
            Problems.trace("map", "Plan shown whole (${moment.name.lowercase()}, ${pts.size} points)")
        }
    }

    /**
     * The next computed route is fitted ([WholeRoute.firstRoute], a plan link, a place added from the card / candidate
     * menu – [WholeRoute.fitAfterPlace]): the whole line shows (field A-019).
     */
    private var fitNextRoute = false

    /**
     * The plan's first route is there: the map shows it whole – the same fit as "Show whole route" (north up, the
     * [FitPaddingRule] padding), once the sheet has grown to its route size. Not when the user took the map meanwhile,
     * left Plan or the route changed again. Following stops first (CLAUDE.md "Map camera").
     */
    private fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>) {
        val asked = android.os.SystemClock.uptimeMillis()
        viewModelScope.launchSafe("app", "fitFirstRoute") {
            delay(WholeRoute.SETTLE_MS)
            if (lastMapTouchMs > asked || mode != AppMode.PLAN || shown != null || route?.points != line) {
                Problems.trace("map", "First route not fitted: the map was touched or the plan moved on")
                return@launchSafe
            }
            followMe = false
            freeLookAt = null
            events += UiEvent.Fit(WholeRoute.bounds(listOf(line), pts, null))
        }
    }

    /** A spot in the list tapped: the list closes, following stops, the map shows that piece (drawn amber). */
    fun showSpot(s: RouteSpot) {
        val pts = route?.points ?: return
        showPiece(RouteWarnings.showPoints(pts, s), RouteWarnings.centerFor(pts, s))
    }

    /** One section on the map (a spot of the list, the way to a point not reached): drawn amber, the map goes there. */
    private fun showPiece(piece: List<GeoPoint>, center: Pair<GeoPoint, Double>?) {
        spotsOpen = false
        followMe = false
        spotLine = piece
        events += if (center != null) UiEvent.CenterOn(center.first, center.second) else UiEvent.Fit(piece)
    }

    /**
     * Tap on the red line of a point the route does not reach – like a steep section (owner 2026-11-07): the section
     * is drawn amber and the map shows it – the way that reaches the point ([planReach], e.g. the T2 path), else the
     * missing piece.
     */
    fun showPlanGap() {
        val g = planGaps.firstOrNull() ?: return
        val way = planReach?.takeIf { it.index == g.index && !g.skipped }?.way
        if (way != null) showPiece(way + g.point, null)
        else showPiece(listOf(g.routeEnd, g.point), PlanGaps.showTarget(g))
    }

    /**
     * Save – the sheet's green Save, ☰ "Save route", sheet ⋯ → Save route – always opens the name dialog (owner
     * 2026-10-31, replaces "save at once"): a plan that belongs to a saved route that still exists ([RouteRules.saveMode])
     * shows that route's name and is overwritten on Save – renamed when the name changed, still ONE route; else the
     * proposed name ([RouteRules.newRouteName]: plan link title / "Vellau → Leiteralm", else "Route 31 Oct, 10:12") and
     * a new route. The sheet then shows "Saved as …" (no message).
     */
    fun requestSave() = askSave(null)

    /**
     * My routes → ⋮ → Save as copy (planned routes; owner 2026-10-31, was the plan sheet's ⋯): the same dialog with
     * "<name> (2)" ([RouteRules.copyName]); Save copies the stored route ([copyRoute]) – the plan stays as it is.
     */
    fun requestCopyRoute(t: Tour) {
        if (t.kind != TourKind.PLANNED || saving) return
        saveAsk = com.onpath.app.domain.routes.SaveFlow.askCopy(t.name, t.id, plannedNames())
    }

    /** Opens Save's dialog; [then] runs after a successful save ("Save first" before loading another route). */
    private fun askSave(then: (() -> Unit)?) {
        val pts = waypoints.toList()
        if (pts.size < 2 || saving) return
        val dated = defaultName(Txt.s(Res.string.msg_name_route))
        val taken = plannedNames()
        val ask = com.onpath.app.domain.routes.SaveFlow.ask(currentSaveMode(), savedName, savedId, proposal = null, dated, taken, then)
        saveAsk = ask
        if (ask.kind == SaveAsk.Kind.OVERWRITE) return
        // the proposal (plan link title / "Vellau → Leiteralm") replaces the date name in the field until the user types
        val r = route
        viewModelScope.launchSafe("app", "askSave") {
            val proposed = withTimeoutOrNull(NAME_WAIT_MS) { proposeName(pts, r) } ?: return@launchSafe
            if (saveAsk === ask) saveAsk = ask.copy(name = RouteRules.newRouteName(proposed, dated, taken))
        }
    }

    /** Save's dialog → Cancel / Back / a tap outside: nothing is saved, "Save first" does not load. */
    fun cancelSave() { saveAsk = null }

    /**
     * Save's dialog → Save with [input]: a blank name keeps the proposal / old name ([RouteRules.cleanName]). A new
     * route or a copy is inserted; the saved route is overwritten in place and renamed if the name changed.
     */
    fun confirmSave(input: String) {
        val ask = saveAsk ?: return
        saveAsk = null
        val then = ask.then
        when (val w = com.onpath.app.domain.routes.SaveFlow.confirm(ask, input, savedId)) {
            is com.onpath.app.domain.routes.SaveFlow.Write.Copy -> copyRoute(w.routeId, w.name)
            is com.onpath.app.domain.routes.SaveFlow.Write.Overwrite -> overwriteSaved(w.name, then)
            is com.onpath.app.domain.routes.SaveFlow.Write.New -> saveRoute(w.name, then)
        }
    }

    /** Save as copy from My routes: a new planned route [name] with the stored line, points and pins of route [id]. */
    private fun copyRoute(id: Long, name: String) {
        if (saving) return
        saving = true
        viewModelScope.launchSafe("app", "copyRoute") {
            try {
                val newId = withContext(Dispatchers.IO) {
                    runCatching {
                        val t = db.getTour(id) ?: return@runCatching null
                        db.insertTour(TourKind.PLANNED, name, t.activity, t.sac, t.distanceM, t.ascentM, t.descentM, t.durationS,
                            db.loadSegments(id), t.waypoints, pinned = t.pinned)
                            .also { n -> planTimes.get(id, t.distanceM)?.let { planTimes.put(n, t.distanceM, it) } }
                    }.onFailure { Problems.error("db", "Copying a planned route failed", it) }.getOrNull()
                }
                if (newId == null) { routesProblem = Txt.s(Res.string.msg_route_save_failed); return@launchSafe }
                Backups.tourChanged(getApplication(), newId)
                sync.changed("route copied")
                refreshTours()
            } finally { saving = false }
        }
    }

    /** How long the dialog waits for the place proposal of a new route's name (it shows the date name meanwhile). */
    private val NAME_WAIT_MS = 5_000L

    // a plan from "Zuletzt" is no saved route: Save makes a new one (and the draft goes – [insertPlanned])
    private fun currentSaveMode() =
        RouteRules.saveMode(savedId, savedName, tours.filter { it.kind == TourKind.PLANNED && !it.draft }.map { it.id })

    private fun plannedNames() = tours.filter { it.kind == TourKind.PLANNED && !it.draft }.map { it.name }

    /** The plan on the map was loaded from "Zuletzt" (a draft) and still belongs to it. */
    private val linkedDraft: Tour? get() = if (planLinked && linkId >= 0) tours.firstOrNull { it.id == linkId && it.draft } else null

    // ---------------- "Zuletzt" (owner 2026-11-06: no "Nicht gespeichert", like Komoot + recent plans) ----------------

    /**
     * A plan worth keeping that leaves the map unsaved ([planUnsaved]: never saved, or a saved plan changed – its original
     * stays as it is) goes under My routes → Zuletzt, no question ([Drafts]). The same points twice (Undo, clear again)
     * make no second entry; only the newest [Drafts.MAX] are kept.
     */
    private fun keepAsDraft(why: String) {
        val pts = waypoints.toList()
        val unsaved = planUnsaved
        if (!Drafts.keeps(pts.size, unsaved)) return
        val r = route
        val act = activity; val level = sac
        val stops = savedStops(pts); val pins = pinnedIndices()
        val dated = defaultName(Txt.s(Res.string.msg_name_route))
        val key = pts.map { it.lat to it.lon }
        // written NOW, under the date name, before the plan leaves the map (tester review 2026-11-07: it waited up to 5 s
        // for a place name in the background, and a kill in that window lost the plan). One row on the calling thread –
        // the plan points in the prefs go with the clear right after this, so the database must hold them first.
        val id = runCatching {
            // not twice: no second draft with these points, none for a saved route with them (RoutesState)
            routes.keepDraft(key, unsaved) {
                db.insertTour(TourKind.PLANNED, dated, act.name, level,
                    r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                    listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }), stops, pinned = pins, draft = true,
                )
            }
        }.onFailure { Problems.error("db", "The plan could not be kept under Zuletzt", it) }.getOrNull() ?: return
        Problems.trace("plan", "Plan kept under Zuletzt ($why): \"$dated\", ${pts.size} points")
        refreshTours()
        // then the model times and the place name ("Algund → Meran"); nothing fits in time → the date name stays
        viewModelScope.launchSafe("app", "nameDraft") {
            withContext(Dispatchers.IO) { storePlanTimes(id, r, act) }
            val proposed = withTimeoutOrNull(NAME_WAIT_MS) { proposeName(pts, r) }
            val renamed = withContext(Dispatchers.IO) {
                runCatching { routes.renameDraft(id, dated, proposed) }
                    .onFailure { Problems.warn("db", "The plan under Zuletzt keeps its date name (rename failed)", it) }.getOrDefault(false)
            }
            if (renamed) { Problems.trace("plan", "Zuletzt: \"$dated\" → \"$proposed\""); refreshTours() }
        }
    }

    /** My routes → Zuletzt → Speichern: the plan becomes saved (backup, browser sync); it shows "✓ Gerade gespeichert". */
    fun saveDraft(t: Tour) {
        viewModelScope.launchSafe("app", "saveDraft") {
            val ok = withContext(Dispatchers.IO) {
                runCatching { routes.saveDraft(t.id) }.onFailure { Problems.error("db", "A plan under Zuletzt could not be saved", it) }.getOrDefault(false)
            }
            if (!ok) { routesProblem = Txt.s(Res.string.msg_route_save_failed); return@launchSafe }
            Problems.trace("plan", "Zuletzt → saved: \"${t.name}\"")
            routes.markSaved(t.id)
            Backups.tourChanged(getApplication(), t.id)
            sync.changed("plan saved from Zuletzt")
            refreshTours()
        }
    }

    /** My routes → Zuletzt → Löschen: to the recycle bin at once, no question (owner 2026-11-06). */
    fun deleteDraft(t: Tour) {
        viewModelScope.launchSafe("app", "deleteDraft") {
            val ok = withContext(Dispatchers.IO) {
                runCatching { routes.toBin(t.id) }
                    .onFailure { Problems.error("db", "A plan under Zuletzt could not be moved to the recycle bin", it) }.getOrDefault(false)
            }
            if (ok) { Problems.trace("plan", "Zuletzt → recycle bin: \"${t.name}\""); refreshTours() }
        }
    }

    /**
     * The plan on the map for My routes (owner 2026-11-06 23:00: "unter Zuletzt ‚gerade in Bearbeitung', dann weiß ich, dass
     * immer gespeichert wird"): [LivePlanRows] puts it first under Zuletzt or tags the saved route it is. Null = empty map.
     */
    val livePlan: LivePlan?
        get() {
            if (waypoints.isEmpty()) return null
            val r = route
            return LivePlan(name = savedName, points = waypoints.size, distanceM = r?.distanceM ?: 0.0,
                ascentM = r?.ascentM ?: 0.0, durationS = r?.durationS ?: 0.0, linkedId = savedId,
                key = waypoints.map { it.lat to it.lon }, unsaved = planUnsaved)
        }

    /**
     * The line of a tour in My routes for its preview and overview (owner 2026-11-07): the stored points (a plan's route,
     * a recorded track, an imported GPX), else its via points / GPX waypoints. Empty when nothing can be read (reported).
     */
    suspend fun tourLine(t: Tour): List<List<GeoPoint>> {
        val live = route?.points ?: waypoints.toList() // read on the main thread
        return withContext(Dispatchers.IO) { routes.line(t) { live } }
    }

    /**
     * The overview's map picture ([com.onpath.app.map.RouteSnapshot]): the app's own style (offline cells, overview map
     * where there is no detail) with the line fitted. Null when the map cannot be drawn (reported) – the overview then
     * keeps the drawn shape.
     */
    suspend fun routeSnapshot(segments: List<List<GeoPoint>>, tint: RouteShape.Tint, widthPx: Int, heightPx: Int,
                              paddingPx: Int, under: List<GeoPoint> = emptyList()): com.onpath.app.map.RouteSnapshot.Shot? {
        val app = getApplication<Application>()
        val dataDir = dataFiles.dir; val theme = settings.mapTheme; val mapDir = dataFiles.mapDir()
        val cover = detailedAreas(); val dev = devToolsShown; val packages = packageAreas()
        val json = withContext(Dispatchers.IO) {
            runCatching { com.onpath.app.map.StyleLoader.load(app, dataDir, theme, mapDir, cover, dev, packages) }
                .onFailure { Problems.warn("routes", "Overview: map style could not be built – the shape is shown alone", it) }
                .getOrNull()
        } ?: return null
        val color = android.graphics.Color.argb(255, (tint.color().red * 255).toInt(), (tint.color().green * 255).toInt(),
            (tint.color().blue * 255).toInt())
        val planBlue = RouteShape.Tint.PLAN.color().let { c ->
            android.graphics.Color.argb(255, (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        }
        return com.onpath.app.map.RouteSnapshot.make(app, json, segments, color, widthPx, heightPx, paddingPx,
            app.resources.displayMetrics.density, under, planBlue)
    }

    /** My routes → the plan "In Bearbeitung" tapped: back to it on the map – nothing is loaded again. */
    fun backToLivePlan() {
        Problems.trace("routes", "My routes → the plan in progress (${waypoints.size} points, linked ${savedId}): back to the map, nothing reloaded")
        routesOpen = false
    }

    /** My routes → the plan "In Bearbeitung" → Speichern: the same as ☰ Plan speichern (the name dialog). */
    fun saveLivePlan() {
        Problems.trace("routes", "My routes → the plan in progress → Speichern (${waypoints.size} points)")
        requestSave()
    }

    /** The plan just moved from Zuletzt to saved: "✓ Gerade gespeichert" on it until My routes closes. */
    var justSavedId: Long
        get() = routes.justSavedId
        set(v) { routes.justSavedId = v }

    /**
     * Saves the plan as a new planned route named [name]. The sheet then shows "Saved as …" (no message). Without a
     * route line yet (still calculating / failed) the points are stored as the line, so nothing is lost; opening it
     * re-routes. [then] runs on success only.
     */
    fun saveRoute(name: String, then: (() -> Unit)? = null) {
        val pts = waypoints.toList()
        if (pts.size < 2 || saving) return
        saving = true
        val r = route
        viewModelScope.launchSafe("app", "saveRoute") { try { insertPlanned(name, pts, r, then) } finally { saving = false } }
    }

    private suspend fun insertPlanned(name: String, pts: List<GeoPoint>, r: PlannedRoute?, then: (() -> Unit)?) {
        val title = name.trim().ifBlank { defaultName(Txt.s(Res.string.msg_name_route)) }
        // saved from a plan under "Zuletzt": the saved route takes its place there (no second entry)
        val fromDraft = linkedDraft
        val act = activity; val level = sac
        val stops = savedStops(pts); val pins = pinnedIndices()
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
        if (id == null) { routesProblem = Txt.s(Res.string.msg_route_save_failed); return }
        // the saved route takes the place of its draft and of any draft with the same points (never a second entry)
        withContext(Dispatchers.IO) {
            runCatching { routes.afterSaved(fromDraft?.id ?: -1L, pts.map { it.lat to it.lon }) }
                .onFailure { Problems.warn("db", "The plan under Zuletzt stays after it was saved", it) }
        }
        Backups.tourChanged(getApplication(), id)
        sync.changed("route saved")
        setSaved(title, id, pts)
        refreshTours()
        then?.invoke()
    }

    /** The plan's stops as saved: with their place names (backup / restore keeps them). */
    private fun savedStops(pts: List<GeoPoint>) = com.onpath.app.gpx.PlanGpx.stops(pts) { stopName(pts[it]) }

    /**
     * Save of a plan that belongs to a saved route: overwrite it in place under [name] (renamed when it differs – still
     * one route). A new entry with that name if it is gone meanwhile.
     */
    private fun overwriteSaved(name: String, then: (() -> Unit)?) {
        val pts = waypoints.toList()
        val old = savedName
        val id = savedId
        if (pts.size < 2 || saving) return
        if (old == null || id < 0) { saveRoute(name, then); return }
        saving = true
        val r = route
        val act = activity; val level = sac
        val stops = savedStops(pts); val pins = pinnedIndices()
        val opened = savedPoints?.let { p -> com.onpath.app.sync.SaveRule.pointsKey(p.map { it.lat to it.lon }) }
        viewModelScope.launchSafe("app", "overwriteSaved") {
            try {
                // changed by sync since it was opened here (review sync H3): save as a copy, the other edit stays
                val stored = withContext(Dispatchers.IO) {
                    runCatching { db.getTour(id)?.waypoints?.map { it.lat to it.lon } }
                        .onFailure { Problems.warn("db", "Saved route not read before overwriting; overwritten as before", it) }.getOrNull()
                }
                if (com.onpath.app.sync.SaveRule.keepsOtherEdit(opened, stored?.let(com.onpath.app.sync.SaveRule::pointsKey))) {
                    val copy = RouteRules.copyName(name, plannedNames())
                    Problems.trace("plan", "Save over \"$old\": changed elsewhere since opened (browser sync) – saved as \"$copy\"")
                    sayInfo(Txt.s(Res.string.msg_route_changed_elsewhere, copy), NoticePlace.MAP)
                    insertPlanned(copy, pts, r, then)
                    return@launchSafe
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        db.replacePlanned(
                            id, act.name, level,
                            r?.distanceM ?: 0.0, r?.ascentM ?: 0.0, r?.descentM ?: 0.0, r?.durationS ?: 0.0,
                            listOf((r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) }),
                            stops, pins,
                        ).also {
                            if (it) { plannedTimes.remove(id); storePlanTimes(id, r, act) }
                            if (it && name != old) db.rename(id, name)
                            // a draft with the route's new points goes (RoutesState: never a draft with a saved route's points)
                            if (it) routes.afterSaved(-1L, pts.map { p -> p.lat to p.lon })
                        }
                    }
                }
                val replaced = ok.getOrElse {
                    Problems.error("db", "Overwriting a saved route failed", it)
                    routesProblem = Txt.s(Res.string.msg_route_save_failed)
                    return@launchSafe
                }
                if (replaced) {
                    Backups.tourChanged(getApplication(), id); sync.changed("route overwritten")
                    setSaved(name, id, pts)
                    shown?.let { if (it.tourId == id) shown = it.copy(name = name) }
                    refreshTours(); then?.invoke()
                } else insertPlanned(name, pts, r, then) // deleted meanwhile: keep the work as a new route with that name
            } finally { saving = false }
        }
    }

    /** Manage routes → Rename. The plan's "Saved as …" and a shown track follow the new name. */
    fun renameTour(t: Tour, input: String) {
        val name = RouteRules.cleanName(input, t.name)
        if (name == t.name) return
        viewModelScope.launchSafe("app", "renameTour") {
            val ok = withContext(Dispatchers.IO) { runCatching { db.rename(t.id, name) }
                .onFailure { Problems.error("db", "Renaming a route failed", it) }.isSuccess }
            if (!ok) { routesProblem = Txt.s(Res.string.msg_route_rename_failed); return@launchSafe }
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
        // saved at the tour start / on the way: the tour's copy knows its route and name now
        if (recState.value != null && followFromPlan && followLine != null && !followIsTrack) recopyFollowedPlan()
    }

    /** Navigate mode or a tour recording: no loading into the plan, no Clear. */
    fun navigating(recording: Boolean): Boolean = mode == AppMode.NAVIGATE || recording

    /** State of the ⋮ menu items (RouteRules.menu). */
    fun menuState(recording: Boolean): RouteMenuState =
        RouteRules.menu(route != null, waypoints.size, tours.size, navigating(recording), recording)

    fun openRoutes() { refreshTours(); routesOpen = true }

    /**
     * Open / load from My routes ([RouteRules.openAsk]). During a tour no question (owner 2026-11-06 22:05 – the three
     * answers "Abbrechen · Tour beenden und laden · Mit diesem Plan weiter" were "super verwirrend"; plan and tour are two
     * things): a planned route is followed at once from its point ① ([OpenAsk.FOLLOW_PLAN_DURING_TOUR]), a done tour /
     * track too ([OpenAsk.FOLLOW_DURING_TOUR]); the recording goes on, Navigate says so ([newPlanNote]) and "Rückgängig"
     * brings the plan from before ([undoNewPlan]; it is also under Zuletzt). Ending the tour stays in the pause. Without a
     * tour the current plan goes under "Zuletzt" (owner 2026-11-06) – no "Replace current plan?" any more.
     */
    fun requestOpen(t: Tour, why: String = "My routes tapped") {
        Problems.trace("routes", "Open tour ${t.id} \"${t.name}\" (${t.kind}, ${t.distanceM.toInt()} m): $why")
        val ask = routes.openAsk(t, navigating(recState.value != null), waypoints, savedPoints)
        val hadPlan = waypoints.isNotEmpty()
        when (ask) {
            OpenAsk.FOLLOW_PLAN_DURING_TOUR -> {
                Problems.trace("tour", "Plan \"${t.name}\" tapped during the tour: followed at once (no question), the recording goes on")
                tour.noteNewPlan(undo = hadPlan)
                unlessUnsaved("Load \"${t.name}\" during the tour") { openTour(t, duringTour = true) }
            }
            OpenAsk.FOLLOW_DURING_TOUR -> {
                Problems.trace("tour", "Track \"${t.name}\" tapped during the tour: followed at once (no question), the recording goes on")
                tour.noteNewPlan(undo = hadPlan)
                unlessUnsaved("Follow \"${t.name}\" during the tour") { openTour(t, trackThen = TrackThen.FOLLOW) }
            }
            OpenAsk.REPLACE_PLAN, OpenAsk.NONE -> openTour(t)
        }
    }

    /**
     * "Rückgängig" on [newPlanNote]: the plan from before comes back in one Undo step (loading the new plan / clearing the
     * plan for a followed track was one step) and the tour follows it again – a followed track gives way to it as at
     * "Weiter geht's" ([continueNavigation]). Only offered when there was a plan before.
     */
    fun undoNewPlan() {
        val note = tour.newPlanNote ?: return
        tour.dropNewPlanNote()
        if (!note.undo) return
        Problems.trace("plan", "Rückgängig on \"Neuer Plan\": the plan from before comes back, the tour follows it")
        undoStep()
        if (followIsTrack && recState.value != null) continueNavigation()
    }

    /**
     * "Los geht's" in My routes (owner 2026-11-07, [com.onpath.app.ui.routes.RoutesGo]): [t] is loaded as the plan like
     * "Planen" (an unsaved plan on the map goes under Zuletzt – no question), then, once its line is calculated, [start]
     * runs – the plan sheet's own Los geht's (phone check → [startNavigation]: the plan is followed, Hinführen when the
     * start is far). During a tour it is "Folgen" ([requestOpen]).
     */
    fun goFromRoutes(t: Tour, lineOf: List<List<TrackPoint>>? = null, start: () -> Unit) {
        if (recState.value != null) { requestOpen(t, "Los geht's in My routes during a tour"); return }
        Problems.trace("routes", "Los geht's on \"${t.name}\" in My routes: loaded as the plan, the tour starts once its line is there")
        goJob?.cancel()
        openTour(t, lineOf = lineOf) {
            val loaded = waypoints.toList()
            goJob = viewModelScope.launchSafe("app", "goFromRoutes") {
                val step = withTimeoutOrNull(com.onpath.app.ui.routes.RoutesGo.MAX_WAIT_MS) {
                    androidx.compose.runtime.snapshotFlow {
                        com.onpath.app.ui.routes.RoutesGo.step(loaded, waypoints.toList(), routing, route != null, recState.value != null)
                    }.first { it != com.onpath.app.ui.routes.RoutesGo.Step.WAIT }
                } ?: com.onpath.app.ui.routes.RoutesGo.Step.DROP
                if (step == com.onpath.app.ui.routes.RoutesGo.Step.START) {
                    Problems.trace("routes", "Los geht's from My routes: the line of \"${t.name}\" is there – the normal start")
                    start()
                } else Problems.trace("routes", "Los geht's from My routes: no tour started for \"${t.name}\" (changed meanwhile, " +
                    "no line, or waited too long) – the plan sheet's Los geht's stays")
            }
        }
    }

    private var goJob: Job? = null

    /**
     * Gemacht → a recorded tour's "Planen" (owner 2026-11-07): the plan it followed (its last version,
     * [com.onpath.app.data.TourPlan]) is loaded like a planned route's "Planen" – the plan on the map goes under Zuletzt,
     * no question. Its saved route is loaded while that is still the same plan, else the stored copy as a new plan. Not
     * during a tour (the row offers it only without one).
     */
    fun planFollowedPlan(t: Tour) = withFollowedPlan(t, "Planen") { plan, line -> openTour(plan, lineOf = line) }

    /** Gemacht → "Los geht's": the followed plan loaded as at [planFollowedPlan], then the normal start ([goFromRoutes]). */
    fun goFollowedPlan(t: Tour, start: () -> Unit) = withFollowedPlan(t, "Los geht's") { plan, line -> goFromRoutes(plan, line, start) }

    private fun withFollowedPlan(t: Tour, what: String, go: (Tour, List<List<TrackPoint>>?) -> Unit) {
        if (recState.value != null) { Problems.trace("routes", "$what on tour \"${t.name}\" during a tour: nothing loaded"); return }
        viewModelScope.launchSafe("app", "followedPlan") {
            val found = withContext(Dispatchers.IO) {
                runCatching { followedPlanTour(t) }
                    .onFailure { Problems.error("db", "The plan tour ${t.id} followed could not be read", it) }.getOrNull()
            }
            if (found == null) { routesProblem = Txt.s(Res.string.routes_followed_plan_missing); return@launchSafe }
            val (plan, line) = found
            Problems.trace("routes", "$what on tour \"${t.name}\": its followed plan " +
                (if (line == null) "is the saved route \"${plan.name}\" (unchanged)" else "from the tour's copy") +
                ", ${plan.waypoints.size} points")
            go(plan, line)
        }
    }

    /**
     * The plan [t] followed as a planned route to load: its saved route while that is the same plan (points, activity, T
     * level) – loaded as itself, no second route; else a stand-in (id -1) from the stored copy with the copy's line.
     * Null = no copy.
     */
    private fun followedPlanTour(t: Tour): Pair<Tour, List<List<TrackPoint>>?>? {
        val p = db.getTourPlan(t.id) ?: return null
        val key = p.waypoints.map { it.lat to it.lon }
        val source = p.sourceUuid?.let { u ->
            db.listTours().firstOrNull { it.uuid == u && it.kind == TourKind.PLANNED && !it.draft && it.deletedMs == null }
        }?.takeIf { it.waypoints.map { w -> w.lat to w.lon } == key && it.activity == p.activity && it.sac == p.sac }
        if (source != null) return source to null
        val line = p.line.ifEmpty { p.waypoints.map { TrackPoint(it.lat, it.lon, it.ele) } }
        return Tour(-1L, TourKind.PLANNED, p.name ?: t.name, t.createdMs, p.activity, p.sac, p.distanceM, p.ascentM,
            p.descentM, p.durationS, "done", p.waypoints, pinned = p.pinned) to listOf(line)
    }

    /** The overview of a recorded tour: the line of the plan it followed (thin under the track); empty = none. */
    suspend fun followedPlanLine(t: Tour): List<GeoPoint> {
        if (t.kind != TourKind.RECORDED || !t.hasPlan) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { db.getTourPlan(t.id)?.line?.map { GeoPoint(it.lat, it.lon) }.orEmpty() }
                .onFailure { Problems.warn("db", "The followed plan's line of tour ${t.id} not read – the overview shows the track alone", it) }
                .getOrDefault(emptyList())
        }
    }

    /** My routes ⋮ → "Als Plan übernehmen" (a done tour / track, owner 2026-11-06 F1): opened, then made a plan of it. */
    fun planFromTour(t: Tour) = unlessUnsaved("\"${t.name}\" as the plan") { openTour(t, trackThen = TrackThen.PLAN) }

    /**
     * Something else takes the plan's place (a track or a plan followed during a tour, "Als Plan
     * übernehmen"): no question any more (owner 2026-11-06) – the replaced plan goes under Zuletzt ([keepAsDraft]).
     */
    private fun unlessUnsaved(what: String, go: () -> Unit) { Problems.trace("plan", "$what: the plan on the map goes under Zuletzt if not saved"); go() }

    /**
     * A shown track followed during a tour (the track sheet's "Tour nachfahren", F1's "Folgen" – flow map M1, owner rule
     * "what Plan shows = what Navigate follows"): the plan leaves the map (one Undo step, like ✕ Neu planen), the tour
     * follows the track, Navigate. An unsaved plan asks first ([unlessUnsaved]).
     */
    fun followShownDuringTour() = unlessUnsaved("Follow the shown track during the tour") { followShownNow() }

    private fun followShownNow() {
        if (recState.value == null) return
        if (waypoints.isNotEmpty()) {
            Problems.trace("plan", "Track followed during the tour: the plan (${waypoints.size} points) leaves the map (Undo brings it back)")
            clearPlan()
        }
        followShown(); closeShown(); continueNavigation()
    }

    /** The end question closed without ending the tour. */
    fun dismissEndAsk() = tour.dismissEndAsk()

    /**
     * Proposed name of a new route: a plan from a link keeps its title while its points are unchanged, else
     * "Algund → Meran" (search/RouteNamer) from the offline place index (built first if needed); null when nothing fits.
     */
    private suspend fun proposeName(pts: List<GeoPoint>, r: PlannedRoute?): String? {
        if (pts.size < 2) return null
        linkTitle?.let { (title, at) -> if (at == pts) return title }
        val line = r?.points?.takeIf { it.size >= 2 } ?: pts
        val startName = stopName(pts.first()); val endName = stopName(pts.last())
        val via = pts.subList(1, pts.size - 1).mapNotNull { stopName(it) }
        val index = try { search.offlineIndex() } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { Problems.warn("search", "Place index unavailable; route name from stop names only", e); null }
        return withContext(Dispatchers.IO) {
            try {
                val places = index?.let { com.onpath.app.search.RouteNamer.placesNear(it, line) }.orEmpty()
                com.onpath.app.search.RouteNamer.name(line, places, startName, endName, via)
            } catch (e: Exception) {
                Problems.warn("search", "No route name proposed (naming failed)", e); null
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
        viewModelScope.launchSafe("app", "nameRecordedTour") {
            val name = try {
                val index = search.offlineIndex()
                withContext(Dispatchers.IO) {
                    // "Save only until …": named after the part that is the tour
                    val line = com.onpath.app.rides.RideView.keptSegments(db.loadSegments(t.id), t.rides).flatten().map { GeoPoint(it.lat, it.lon) }
                    com.onpath.app.search.RouteNamer.name(line, com.onpath.app.search.RouteNamer.placesNear(index, line))
                }?.let { if (demo) com.onpath.app.recording.DemoLocation.TOUR_PREFIX + it else it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Problems.warn("search", "No name proposed for the recorded tour; it keeps its date name", e); null
            } ?: return@launchSafe
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.rename(t.id, name) }.onFailure { Problems.warn("db", "Naming the recorded tour failed; it keeps its date name", it) }.isSuccess
            }
            if (!ok) return@launchSafe
            Backups.tourChanged(getApplication(), t.id) // the file follows the new name
            tour.finishedTour?.let { if (it.id == t.id) tour.finishedTour = it.copy(name = name) }
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
        tapHoldHint = false
        offlineInfo = false
        if (saveAsk != null) cancelSave() // Save's dialog does not stay open behind another mode
        // the start zoom of Navigate, once per start (owner); the map takes it when it follows
        if (m == AppMode.NAVIGATE && was != AppMode.NAVIGATE) navStartZoom = AppMode.navStartZoom(activity)
        else if (m != AppMode.NAVIGATE) navStartZoom = null
        if (m == AppMode.NAVIGATE) { planPoi = null; poiMenu = null; closePointMenu("mode change to Navigate"); firstPointMenu = null; highlight = null; followMe = true }
        // Plan keeps the compass choice: walking direction up stays turned and following (until you edit or move the
        // map); north up stops following
        else followMe = headingUp && followMe && tracker.location.value != null
        // Plan during a tour: the sheet is one row (tour status + Continue), the map free for holds (owner 2026-10-31)
        if (m == AppMode.PLAN && was == AppMode.NAVIGATE) planSize = PlanSize.backInPlan(planSize, recState.value != null)
        // Navigate → Plan (also the end of a tour): the plan in front, not the position (owner 2026-11-06, PlanCamera)
        if (m == AppMode.PLAN && was == AppMode.NAVIGATE && waypoints.isNotEmpty())
            showPlanWhole(if (recState.value == null) PlanCamera.Moment.TOUR_END else PlanCamera.Moment.FROM_NAVIGATE)
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_NAVIGATING) {
            putExtra(RecordingService.EXTRA_ON, m == AppMode.NAVIGATE)
        }
    }

    /**
     * Plan's primary action (location permission is checked by the caller). Follows the shown track, else the planned
     * route, else nothing (free recording – the button then reads "Start recording · …", [PlanStart]), and starts recording.
     */
    /**
     * [recordOnly]: ☰ → "Nur aufzeichnen" (owner 2026-11-06, flow map H1): a plain recording whatever the plan holds –
     * nothing is followed, the plan stays as it is.
     */
    fun startNavigation(force: Boolean = false, recordOnly: Boolean = false) {
        if (recState.value != null) { continueNavigation(force); return }
        val s = shown
        if (!recordOnly && s == null && !force && planHasStraightLegs()) { straightAsk = true; return }
        // PlanStart: the sheet's button says "Start recording" exactly when nothing is followed here
        when (if (recordOnly) PlanStart.RECORD_ONLY else PlanStart.of(trackPoints = s?.points?.size, hasRoute = route != null, pointCount = waypoints.size)) {
            PlanStart.FOLLOW_TRACK -> { followShown(); shown = null }
            PlanStart.NAVIGATE_PLAN -> { followPlanned(); secureTourCorridor(); saveNewPlanAtStart() }
            // the button waits for the line; reached anyway: record (a tour is never refused), nothing followed.
            // A shown track without a line closes, like ☰ → Start recording.
            PlanStart.WAIT_FOR_ROUTE, PlanStart.RECORD_ONLY -> { clearFollow(); shown = null }
        }
        startRecording()
        // Navigate only once the service really records (R-01): a refused foreground service, a missing
        // permission or a broken database must not leave the user in Navigate with nothing being recorded.
        startJob?.cancel()
        startJob = viewModelScope.launchSafe("app", "startNavigation") {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) { RecordingService.state.filterNotNull().first() } != null
            if (started) enter(AppMode.NAVIGATE)
            else if (errorCard == null) sayError(Txt.s(Res.string.msg_recording_not_started), NoticePlace.MAP)
        }
    }

    private var startJob: Job? = null

    /**
     * "Los geht's" with a plan that was never saved (owner 2026-11-06: the tour and the plan are two things – like Komoot,
     * the plan is in My routes → Planned from the start, so the end of the tour never has to ask about it): saved at once
     * under the name the Save dialog would propose, no dialog. A saved plan changed since updates that route in place, like
     * ☰ Plan speichern (owner 2026-11-07: no "Name (2)" copies) – its version before goes under Zuletzt
     * ([updateSavedAtStart]). Renaming / deleting: My routes.
     */
    private fun saveNewPlanAtStart() {
        val pts = waypoints.toList()
        val draft = linkedDraft
        val how = PlanAutoSave.atStart(pts.size, planUnsaved, currentSaveMode() == SaveMode.OVERWRITE, draft != null)
        if (how == PlanAutoSave.AtStart.NOTHING || saving) return
        if (how == PlanAutoSave.AtStart.SAVE_OVERWRITE) { updateSavedAtStart(pts); return }
        saving = true
        val r = route
        val dated = defaultName(Txt.s(Res.string.msg_name_route))
        val taken = plannedNames()
        viewModelScope.launchSafe("app", "saveNewPlanAtStart") {
            try {
                // a plan from Zuletzt keeps its name
                val name = draft?.name?.let { RouteRules.newRouteName(it, dated, taken) }
                    ?: RouteRules.newRouteName(withTimeoutOrNull(NAME_WAIT_MS) { proposeName(pts, r) }, dated, taken)
                Problems.trace("plan", "Plan saved at the tour start as \"$name\" (${how.name.lowercase()}, ${pts.size} points)")
                insertPlanned(name, pts, r, null)
            } finally { saving = false }
        }
    }

    /**
     * "Los geht's" with a loaded saved route changed since (owner 2026-11-07): the route is updated in place under its
     * name ([overwriteSaved], the same write as ☰ Plan speichern), then its version before – read first – is kept under
     * Zuletzt ([com.onpath.app.ui.routes.RoutesState.keepReplaced]), so nothing is lost and no "Name (2)" appears.
     */
    private fun updateSavedAtStart(pts: List<GeoPoint>) {
        val id = savedId
        val name = savedName ?: return
        viewModelScope.launchSafe("app", "updateSavedAtStart") {
            val before = withContext(Dispatchers.IO) {
                runCatching { db.getTour(id)?.let { it to db.loadSegments(id) } }
                    .onFailure { Problems.warn("db", "The saved route's version before the tour start not read – not kept under Zuletzt", it) }
                    .getOrNull()
            }
            Problems.trace("plan", "Plan saved at the tour start as \"$name\" (save_overwrite, ${pts.size} points)")
            overwriteSaved(name) { before?.let { (old, segments) -> keepReplacedVersion(old, segments) } }
        }
    }

    /** The version of a saved route that a tour start just replaced: under Zuletzt with its name, line and points. */
    private fun keepReplacedVersion(old: Tour, segments: List<List<TrackPoint>>) {
        val key = com.onpath.app.ui.routes.RoutesState.keyOf(old)
        viewModelScope.launchSafe("app", "keepReplacedVersion") {
            val id = withContext(Dispatchers.IO) {
                runCatching {
                    routes.keepReplaced(key) {
                        db.insertTour(TourKind.PLANNED, old.name, old.activity, old.sac, old.distanceM, old.ascentM, old.descentM,
                            old.durationS, segments, old.waypoints, pinned = old.pinned, draft = true)
                    }
                }.onFailure { Problems.error("db", "The saved route's version before the tour start could not be kept under Zuletzt", it) }
                    .getOrNull()
            }
            if (id != null) { Problems.trace("plan", "Version before the tour start kept under Zuletzt: \"${old.name}\", ${key.size} points"); refreshTours() }
            else Problems.trace("plan", "Version before the tour start not added to Zuletzt: these points are there already (a saved route or a draft)")
        }
    }

    /**
     * A tour starts on the plan's route (F-OFF-02 "started route", owner: "new route – even without signal"): map areas
     * and routing parts of its corridor (hike 10 km, bike 20 km either side – OfflineCorridor) still missing – loading
     * cancelled, server failed, waited – are asked for again, quietly (background chip; Wi-Fi-only rule as for the plan).
     * Geometry off the main thread.
     */
    private fun secureTourCorridor() {
        val line = route?.points?.takeIf { it.size >= 2 } ?: return
        val pts = waypoints.toList()
        mapData.secureTourCorridor(line, pts.lastOrNull(), activity) // a round trip closes at its ①
    }

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

    /** A tour is guided along the plan (not a followed GPX track): Navigate shows the plan's points (owner 2026-11-01). */
    val planGuides: Boolean get() = followLine != null && !followIsTrack && waypoints.isNotEmpty()

    /**
     * Look of the followed / shown line on the map (see MapController.setTrackLook): a shown track its own colour, else
     * route blue – in Navigate the followed line stays blue while a track is shown ([gpxLine]).
     */
    fun trackLook(): com.onpath.app.map.MapController.TrackLook =
        gpxLine(mode == AppMode.NAVIGATE, shown?.segments, followLine).look

    private fun checkNow() {
        if (recState.value != null && followLine != null) RecordingService.send(getApplication(), RecordingService.ACTION_CHECK_NOW)
    }

    /** Off-route question in Navigate: take the new route the service found. */
    fun takeNewRoute(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_TAKE_ROUTE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** "Off route – re-plan?" in Navigate – Yes: take the new route via the stop in question. */
    fun takeViaRoute(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_TAKE_VIA) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** Off-route question in Navigate: keep following the original route. */
    fun keepOriginalRoute(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_KEEP_ROUTE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** "Route starts 31 km away" (never on the route yet) – Guide me: a route from here via the start and all points. */
    fun leadToStart(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_LEAD_THERE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** "Route starts 31 km away" – Just record: quiet until the route is reached. */
    fun justRecord(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_JUST_RECORD) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** "Du stehst mitten auf der Route" – Hier einsteigen: start here, the points before count as passed (owner 2026-11-03). */
    fun joinHere(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_JOIN_HERE) {
        putExtra(RecordingService.EXTRA_QUESTION, questionId)
    }

    /** OK on "No way to the route found – recording continues". */
    fun leadFailedOk(questionId: Int) = RecordingService.send(getApplication(), RecordingService.ACTION_LEAD_FAILED_OK) {
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

    private fun sacFor(a: Activity) = if (a == Activity.HIKE) checkHikeSac ?: sac else checkSession?.sac ?: sac

    /** The T level the check sheet's Hike chips chose (K1); null = the remembered level. Cleared with the sheet. */
    private var checkHikeSac: Int? = null

    /**
     * Opens the sheet with every other activity, nothing routed yet (owner 2026-11-07: "erst dann, wenn ich wirklich eine
     * Routenart ausgewählt habe" – field bug tour 207: all six rows routed at once, 15 s each, from 700 km away). A tap on
     * a row routes that one ([chooseActivity]). Free recording: plain choices.
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
        // the position is needed only once the route was reached (never reached: from the route's start, ActivityCheck.rest)
        activityCheck = ActivityCheckUi(cur, curSac, others.map { ActivityRow(it, sacFor(it), ActivityRowState.Idle) },
            note = if (session.reached && tracker.location.value == null) Txt.s(Res.string.msg_check_waiting_gps) else null)
    }

    /**
     * Routes the rest of the followed route for [a] at [level] – this row only (spinner on it); a running check of
     * another row is cancelled (its row is plain again). Found → [applySwitch] as a tap on a checked row; none → the
     * row says so and the sheet stays open. Runs on its own thread ([checkRouting]), so plan edits never queue behind it.
     */
    private fun startCheck(a: Activity, level: Int) {
        val session = checkSession ?: return
        val ui = activityCheck ?: return
        cancelCheck()
        val cancel = com.onpath.app.routing.RouteCancel()
        checkCancel = cancel
        activityCheck = ActivityCheckRules.checking(ui, a, level)
        Problems.trace("navigation", "Activity check: routing the rest for ${a.name}" + (if (a == Activity.HIKE) " T$level" else "") +
            (if (session.reached) " from the position" else " from the route's start (never reached)"))
        checkJob = viewModelScope.launchSafe("app", "activityCheck") {
            val rest = checkRest ?: run {
                val pos = if (!session.reached) null else {
                    val loc = tracker.location.value ?: tracker.location.filterNotNull().first()
                    GeoPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
                }
                val progress = recState.value?.nav?.progressM ?: 0.0
                withContext(Dispatchers.Default) {
                    runCatching { com.onpath.app.navigation.ActivityCheck.rest(session, progress, pos) }
                        .onFailure { Problems.warn("navigation", "Activity check: rest of the route could not be computed", it) }.getOrNull()
                }.also { checkRest = it }
            }
            if (!isActive || cancel.isCancelled) return@launchSafe
            if (rest == null) {
                updateCheck(null) { ActivityRowState.Failed(Txt.s(Res.string.msg_check_nothing_left)) }
                return@launchSafe
            }
            val st = checkRow(rest, a, level, cancel)
            if (!isActive || cancel.isCancelled) return@launchSafe
            if (checkCancel === cancel) checkCancel = null
            updateCheck(a) { st }
            if (st is ActivityRowState.Ready) applySwitch(st.fit)
        }
    }

    /** Stops a running row check (a new tap, Keep, Back): BRouter is terminated, nothing is applied. */
    private fun cancelCheck() {
        checkCancel?.cancel(); checkCancel = null
        checkJob?.cancel(); checkJob = null
    }

    /** How the rest of the tour fits [a] at T level [level] (routed offline on [checkRouting]). */
    private suspend fun checkRow(rest: com.onpath.app.navigation.ActivityCheck.Rest, a: Activity, level: Int,
                                 cancel: com.onpath.app.routing.RouteCancel): ActivityRowState = withContext(checkRouting) {
        try {
            val eng = routingEngine()
            if (eng is com.onpath.app.routing.StraightLineEngine) {
                ActivityRowState.Failed(Txt.s(Res.string.msg_check_cannot_route))
            } else {
                val r = eng.route(rest.waypoints, a, level,
                    com.onpath.app.routing.RouteLimits(com.onpath.app.navigation.ActivityCheck.TIMEOUT_MS, cancel))
                if (r.points.size < 2) ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))
                else ActivityRowState.Ready(com.onpath.app.navigation.ActivityCheck.evaluate(rest.line, a, level, r, planFactors(a)))
            }
        } catch (e: Exception) {
            if (cancel.isCancelled) Problems.trace("routing", "Activity check for ${a.name} cancelled (a new tap or the sheet closed)")
            else Problems.warn("routing", "Activity check: routing for another activity failed", e)
            ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))
        }
    }

    /**
     * The check sheet's T1–T5 chips under Hike (owner 2026-11-06, flow map K1: during a tour Hike could only be taken with
     * the remembered level). Free recording: Hike with that level at once, as in the plan's activity dialog. A followed
     * route: a chip is a tap on the Hike row at that level ([ActivityCheckRules.withHikeLevel]) – routed for it alone and
     * switched when found. Hiking already at that level: nothing to switch to (a running Hike check stops).
     */
    fun chooseHikeLevel(level: Int) {
        val ui = activityCheck ?: return
        val l = level.coerceIn(1, 5)
        if (ui.current == Activity.HIKE && l == ui.sac && ui.rows.none { it.activity == Activity.HIKE }) return
        Problems.trace("navigation", "Activity check: Hike T$l chosen (was ${if (ui.current == Activity.HIKE) "Hike T${ui.sac}" else ui.current.name})")
        val session = checkSession
        if (session == null) {
            closeActivityCheck()
            selectActivity(Activity.HIKE, userChange = false)
            selectSac(l)
            recState.value?.tourId?.let { id -> viewModelScope.launchSafe("app", "chooseHikeLevel", Dispatchers.IO) {
                runCatching { db.updateActivity(id, Activity.HIKE.name, l) }
                    .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
            } }
            return
        }
        checkHikeSac = l
        val hikingRunning = ui.rows.any { it.activity == Activity.HIKE && it.state == ActivityRowState.Checking }
        activityCheck = ActivityCheckRules.withHikeLevel(ui, l)
        if (activityCheck?.rows?.none { it.activity == Activity.HIKE } == true) { // hiking at that level already
            if (hikingRunning) cancelCheck()
            return
        }
        startCheck(Activity.HIKE, l)
    }

    /** Clears the note; [a] null = every row, else only that row. */
    private fun updateCheck(a: Activity?, f: (ActivityRowState) -> ActivityRowState) {
        val ui = activityCheck ?: return
        activityCheck = ui.copy(note = null, rows = ui.rows.map { if (a == null || it.activity == a) it.copy(state = f(it.state)) else it })
    }

    /**
     * A row was chosen: free recording → switch at once; navigating → that activity alone is routed ([startCheck]) and,
     * when found, switched ([applySwitch]). A row already checked switches at once.
     */
    fun chooseActivity(a: Activity) {
        val ui = activityCheck ?: return
        val row = ui.rows.firstOrNull { it.activity == a }?.takeIf { it.choosable } ?: return
        val st = row.state
        val session = checkSession
        if (st == ActivityRowState.Plain || session == null) {
            closeActivityCheck()
            selectActivity(a, userChange = false)
            recState.value?.tourId?.let { id -> viewModelScope.launchSafe("app", "chooseActivity", Dispatchers.IO) {
                runCatching { db.updateActivity(id, a.name, sacFor(a)) }
                    .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
            } }
            return
        }
        if (st is ActivityRowState.Ready) applySwitch(st.fit) else startCheck(a, row.sac)
    }

    /**
     * A route was found for the chosen activity ([fit]): on the route → the rest of the tour for it becomes the dashed
     * proposal with the usual question (Take new route / Keep original), asked by the service. Never on the route yet and
     * following the plan → the whole plan is planned again for it, like the plan's activity picker ([switchPlanActivity]).
     */
    private fun applySwitch(fit: com.onpath.app.navigation.ActivityFit) {
        val session = checkSession ?: return
        val rest = checkRest
        val a = fit.activity
        closeActivityCheck()
        if (!session.reached && session.kind == NavKind.PLAN && followFromPlan && waypoints.size >= 2) {
            switchPlanActivity(a, fit.sac)
            return
        }
        val left = rest?.stops.orEmpty()
        // on the route the new line starts at the position (an extra first stop); never reached it starts at a stop
        val here = if (session.reached) 1 else 0
        val proposed = runCatching {
            if (session.kind == NavKind.PLAN && left.isNotEmpty()) {
                NavSession.start(NavKind.PLAN, session.name, a, fit.sac, fit.line,
                    stops = (if (here == 1) listOf(fit.line.first()) else emptyList()) + left.map { it.point },
                    names = (if (here == 1) listOf(null) else emptyList()) + left.map { it.name },
                    pinned = left.indices.filter { left[it].pinned }.map { it + here }.toSet())
            } else NavSession.start(session.kind, session.name, a, fit.sac, fit.line)
        }.onFailure { Problems.error("navigation", "Proposed route for the new activity could not be started", it) }.getOrNull() ?: return
        val app = getApplication<Application>()
        NavSessionStore(File(app.filesDir, com.onpath.app.navigation.ActivityCheck.PROPOSAL_FILE)).save(proposed)
        RecordingService.send(app, RecordingService.ACTION_PROPOSE_SWITCH) { putExtra(RecordingService.EXTRA_TIME_S, fit.timeS) }
        // chosen in Plan during the tour: the Take new route / Keep question is asked in Navigate
        if (mode == AppMode.PLAN) enter(AppMode.NAVIGATE)
    }

    /**
     * Never on the route yet (owner 2026-11-07, tour 207: at home, the T1 line ended 552 m short of point 2): the switch
     * plans the whole plan again with [a] / [level] – the plan path of the activity picker, so the plan line and its gap
     * update – and the tour follows the new plan (a way there is led to again, NavSession.afterPlanEdit). No question:
     * nothing of the route was walked yet.
     */
    private fun switchPlanActivity(a: Activity, level: Int) {
        Problems.trace("navigation", "Activity check: route never reached – the plan is planned again as ${a.name}" +
            (if (a == Activity.HIKE) " T$level" else "") + " and followed")
        if (a == Activity.HIKE) { sac = level.coerceIn(1, 5); prefs.sac = sac }
        followNextRoute = true // the plan's result is followed at once (recalc → followPlanned)
        selectActivity(a, userChange = false)
        val storedSac = if (a == Activity.HIKE) sac else level
        recState.value?.tourId?.let { id -> viewModelScope.launchSafe("app", "switchPlanActivity", Dispatchers.IO) {
            runCatching { db.updateActivity(id, a.name, storedSac) }
                .onFailure { Problems.error("db", "Activity of the recorded tour not updated in the database", it) }
        } }
    }

    /** "Keep <activity>" (or Back): close the sheet, stop the checks. Nothing changes. */
    fun closeActivityCheck() {
        cancelCheck() // the running row check stops (BRouter terminated): nothing waits behind it
        checkRest = null
        checkHikeSac = null
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

    /**
     * The tour has ended – saved or deleted, from the end sheet, the ride summary or the arrival box (owner 2026-11-07:
     * "Tour beenden" → the track is saved, the plan is saved as always, and the app is back at the start as after
     * "✕ Neu planen"): the plan goes the one clear way ([clearPlan]) – a plan saved as it is (at "Los geht's" or by
     * Save) stays in My routes, one changed on the way goes under Zuletzt ([keepAsDraft]), one Undo brings it back on the
     * map; the empty sheet shows its guide. The tour and the plan stay two things (owner 2026-11-06): nothing about the
     * plan is asked at the end.
     */
    private fun afterTourEnded() {
        Problems.trace("tour", "Tour ended: back to the start – the plan (${waypoints.size} points) goes the \"Neu planen\" way")
        clearPlan()
        guidanceEndedByEmptyPlan = false // nothing of the ended tour is resumed by the next plan
    }

    /** The arrival box → "Weiter aufzeichnen": the box goes, the recording goes on (the guidance stays quiet). */
    fun keepRecordingAfterArrival() {
        Problems.trace("tour", "Arrival: keep recording")
        arrivalAsk = false
    }

    /** "End tour" in Plan (after confirmation) or "Tour beenden" on arrival: stop and save. */
    fun endTour() {
        tour.end(delete = false)
        stopRecording() // the plan is not touched (owner 2026-11-06: two things)
    }

    /** What "End tour" shows now (null = nothing): see [askEndTour]. */
    var endAsk: EndAsk?
        get() = tour.endAsk
        set(v) { tour.endAsk = v }

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
        viewModelScope.launchSafe("app", "askEndTour") {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val segs = db.loadSegments(id)
                    val a = navActivity ?: activityOrNull(db.getTour(id)?.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check at End tour failed – asked without it", it) }.getOrNull()
            }
            if (recState.value?.tourId != id) return@launchSafe // ended meanwhile (notification, arrival)
            when (com.onpath.app.domain.tour.TourEndFlow.afterRideCheck(s != null, saveIfNoRides)) {
                com.onpath.app.domain.tour.TourEndFlow.Next.RIDES -> endAsk = EndAsk.Rides(id, s!!)
                com.onpath.app.domain.tour.TourEndFlow.Next.SAVE_NOW -> endTour()
                com.onpath.app.domain.tour.TourEndFlow.Next.ASK_PLAIN -> endAsk = EndAsk.Plain
            }
        }
    }

    /** Save from the ride summary: the choice is stored on the tour first, the recording service applies it at the end. */
    fun endTourWith(a: EndAsk.Rides, choice: com.onpath.app.rides.RideChoice) {
        endAsk = null
        viewModelScope.launchSafe("app", "endTourWith") {
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
        viewModelScope.launchSafe("app", "openEditRides") {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val onFoot = (t.activity ?: Activity.HIKE.name) == Activity.HIKE.name
                    com.onpath.app.rides.RideView.summary(db.loadSegments(t.id), rec.rides, onFoot, allowSplit = false)
                }.onFailure { Problems.warn("rides", "Rides of a tour could not be shown", it) }.getOrNull()
            }
            if (s == null) { sayError(Txt.s(Res.string.msg_rides_read_failed), NoticePlace.ROUTES); return@launchSafe }
            editRides = t to s
        }
    }

    fun applyEditRides(t: Tour, choice: com.onpath.app.rides.RideChoice) {
        editRides = null
        viewModelScope.launchSafe("app", "applyEditRides") {
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

    /**
     * End sheet / arrival → Löschen: the TOUR goes straight to the recycle bin, no summary. The plan stays on the map as
     * it is (owner 2026-11-06: the tour and the plan are two things – it was saved at the start).
     */
    fun endTourAndDelete() {
        tour.end(delete = true)
        stopRecording()
    }

    /**
     * The service found the end of the followed line (RecState.arrived, review H2 – also with the phone in the pocket;
     * it says "arrived" itself): the arrival question once per arrival this app instance sees.
     */
    private fun onArrived(arrived: Boolean) = tour.onArrived(arrived)

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
        // the plan the tour follows changed (also an edit of passed points only): its copy follows
        recopyFollowedPlan()
        val split = pendingSplit; pendingSplit = null
        when (split) {
            // only passed points edited: the guidance ahead stays, the plan line counts as followed
            TourSplit.Outcome.Unchanged -> { planBaseline = r.points; return }
            // "No destination ahead" is open: the followed line stays until it is answered
            TourSplit.Outcome.NothingAhead -> return
            is TourSplit.Outcome.Follow -> {
                // Undo / Redo back to a plan followed before in this tour: that route exactly (FollowHistory)
                val now = recState.value?.nav?.session?.takeIf { it.kind == com.onpath.app.navigation.NavKind.PLAN }
                val back = followHistory.find(waypoints.toList())
                val before = splitBefore
                if (now != null && before != null) followHistory.remember(before, now)
                if (back != null) { followAgain(back, r.points, now?.version ?: 0); return }
                followAhead(split.ahead, r.points)
                return
            }
            else -> Unit
        }
        if (r.points.size < 2) return // nothing to follow (e.g. all points on one spot): keep the running line
        val orig = followOriginal
        if (planFollowed(r.points)) return
        if (orig != null && NavLine.sameRoute(r.points, orig)) { planBaseline = r.points; return }
        // Bug 02: back at plan points followed before in this tour (Undo / Redo) → that route exactly, a way there
        // ("Guide me") or a taken re-route included, no routing; the session it replaces is kept for Redo
        val now = recState.value?.nav?.session?.takeIf { it.kind == com.onpath.app.navigation.NavKind.PLAN }
        val back = followHistory.find(waypoints.toList())
        now?.let { followHistory.remember(it.stops.map { st -> st.point }, it) }
        if (back != null) { followAgain(back, r.points, now?.version ?: 0); return }
        followPlanned()
    }

    /** Follows [s] again as it was (Undo during a tour, [FollowHistory]); [planLine] = the plan's route line now. */
    private fun followAgain(s: com.onpath.app.navigation.NavSession, planLine: List<GeoPoint>, versionNow: Int) {
        // a new version: the service and the junction cues re-read the line
        val session = s.copy(version = maxOf(s.version, versionNow) + 1, token = newSessionToken())
        guidanceEndedByEmptyPlan = false
        navStore.save(session)
        prefs.saveFollow(session.name, session.active)
        followName = session.name
        followLine = session.active
        followOriginal = session.original
        planBaseline = planLine
        followIsTrack = false; prefs.followIsTrack = false
        followFromPlan = true
        planAtTrackFollow = null
        copyFollowedPlan(session.activity, session.sac)
        Problems.trace("tour", "The tour follows \"${session.name}\" again as before the edit (${session.stops.size} points" +
            (if (session.rerouted) ", " + (if (session.lead && !session.reached) "way there" else "new route") + " kept" else "") + ")")
        RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED)
    }

    /**
     * The tour follows the plan's route. Its name (lock screen "Back to …", the tour log) is the saved route's while the
     * plan belongs to one ([savedName]), else "Route <date>".
     */
    fun followPlanned(checkNow: Boolean = false) {
        val r = route ?: return
        val w = waypoints.toList()
        setFollow(savedName ?: defaultName(Txt.s(Res.string.msg_name_route)), r.points, isTrack = false, checkNow = checkNow,
            stops = w, names = w.map { stopName(it) }, pinned = pinnedIndices())
        followFromPlan = true
        copyFollowedPlan(activity, sac)
        if (recState.value != null) Problems.trace("tour", "The tour follows \"$followName\" now (${activity.name}, ${w.size} points)")
    }

    /**
     * The tour follows the plan (its newest version): a copy goes with the recorded tour ([TourPlanCopy], owner
     * 2026-11-07) – before the start it waits for the tour. [act] / [level] = what the tour follows it as.
     */
    /** The followed plan copied again as the activity the tour follows (one taken on the route is the session's, not the plan's). */
    private fun recopyFollowedPlan() {
        val followed = recState.value?.nav?.session?.takeIf { it.kind == com.onpath.app.navigation.NavKind.PLAN }
        copyFollowedPlan(followed?.activity ?: activity, followed?.sac ?: sac)
    }

    private fun copyFollowedPlan(act: Activity, level: Int) {
        val p = tourPlanNow(act, level) ?: return
        val id = savedId
        planCopy {
            // the saved route it is (read here: a route saved at the tour start is not in My routes' list yet)
            val source = if (id < 0) null else runCatching { db.getTour(id)?.uuid?.takeIf { it.isNotEmpty() } }
                .onFailure { Problems.warn("db", "The followed plan's saved route not read – the copy is kept without it", it) }.getOrNull()
            followed(p.copy(sourceUuid = source))
        }
    }

    /** The plan on the map as the tour's copy: points with names, pins, the line, its numbers; null below two points. */
    private fun tourPlanNow(act: Activity, level: Int): com.onpath.app.data.TourPlan? {
        val pts = waypoints.toList()
        if (pts.size < 2) return null
        val r = route
        return com.onpath.app.data.TourPlan(name = savedName, activity = act.name, sac = level, waypoints = savedStops(pts),
            pinned = pinnedIndices(), line = (r?.points ?: pts).map { TrackPoint(it.lat, it.lon, it.ele) },
            distanceM = r?.distanceM ?: 0.0, ascentM = r?.ascentM ?: 0.0, descentM = r?.descentM ?: 0.0,
            durationS = r?.durationS ?: 0.0)
    }

    /**
     * The tour follows position → [ahead] ([TourSplit.Outcome.Follow]): routed like any plan (the same [PlanResult] the
     * web planner uses), only for these points. [planLine] = the whole plan's route, which then counts as followed (Plan
     * draws the followed line). No route found: the guidance followed so far stays, a red box says so.
     */
    private fun followAhead(ahead: List<GeoPoint>, planLine: List<GeoPoint>) {
        val pos = myPosition()
        val pts = listOfNotNull(pos) + ahead
        if (pts.size < 2) { followPlanned(); return } // no position yet and one point ahead: the plan as a whole
        val gen = splitRequests.next(waypoints)
        val act = activity; val level = sac; val f = planFactors(act)
        val off = if (pos != null) 1 else 0
        val pins = ahead.indices.filter { ahead[it] in pinned }.map { it + off }.toSet()
        val name = savedName ?: defaultName(Txt.s(Res.string.msg_name_route))
        val firstAhead = waypoints.indexOf(ahead.first()) + 1
        // a newer edit stops this run ([recalc]); the legs ahead come from the plan's run just before (LegCache) – only
        // position → first point ahead is new
        followCancel?.cancel()
        val cancel = com.onpath.app.routing.RouteCancel().also { followCancel = it }
        viewModelScope.launchSafe("app", "followAhead") {
            val res = withContext(routingDispatcher) {
                exceptions {
                    com.onpath.app.routing.PlanResult.plan(Planner(routingEngine(), cancel, legs = legCache), pts, act, level,
                        pins, emptySet(), f)
                }
            }
            if (followCancel === cancel) followCancel = null
            if (cancel.isCancelled) {
                Problems.trace("tour", "Follow line to the ${ahead.size} points ahead stopped – a newer edit came")
                return@launchSafe
            }
            if (!splitRequests.isCurrent(gen, waypoints.toList()) || recState.value == null) return@launchSafe
            val line = res.getOrNull()?.route?.takeIf { it.error == null && it.points.size >= 2 }?.points
            if (line == null) {
                Problems.warn("tour", "No route from the position to the ${ahead.size} points ahead; the guidance so far stays",
                    res.exceptionOrNull())
                sayError(Txt.s(Res.string.nav_no_route_ahead, firstAhead), NoticePlace.MAP)
                return@launchSafe
            }
            setFollow(name, line, isTrack = false, checkNow = true, stops = ahead, names = ahead.map { stopName(it) },
                pinned = ahead.indices.filter { ahead[it] in pinned }.toSet(), firstNumber = firstAhead)
            planBaseline = planLine
            followFromPlan = true
            copyFollowedPlan(act, level)
            Problems.trace("tour", "The tour follows position → ${ahead.size} points ahead of \"$name\" (${line.size} line points)")
        }
    }

    fun followShown() {
        val s = shown ?: return
        setFollow(s.name, s.points, isTrack = true)
        followFromPlan = false
        planCopy { notFollowed() } // a track: the plan's copy stays as it was
        planAtTrackFollow = waypoints.toList()
    }

    /**
     * Starts a navigation session for [points] (nav-session.json for the service: original line, stops for re-routing)
     * and the plain follow line. [checkNow]: the service checks the next fix at once ("Continue navigation").
     */
    private fun setFollow(name: String, points: List<GeoPoint>, isTrack: Boolean, checkNow: Boolean = false,
                          stops: List<GeoPoint> = emptyList(), names: List<String?> = emptyList(), pinned: Set<Int> = emptySet(),
                          firstNumber: Int = 1) {
        guidanceEndedByEmptyPlan = false
        val session = runCatching {
            // a new token (a re-route of the old line never overwrites it) and a new version (cues / voice re-read the line)
            NavSession.start(if (isTrack) NavKind.TRACK else NavKind.PLAN, name, activity, sac, points, stops, names, pinned)
                .copy(token = newSessionToken(), version = (recState.value?.nav?.version ?: navVersion).coerceAtLeast(0) + 1,
                    firstNumber = firstNumber.coerceAtLeast(1))
        }.onFailure { Problems.error("navigation", "Navigation session could not be started; following without it", it) }.getOrNull()
        navStore.save(session)
        prefs.saveFollow(name, points)
        followName = name
        followLine = points
        followOriginal = points
        planBaseline = null
        followIsTrack = isTrack; prefs.followIsTrack = isTrack
        planAtTrackFollow = null
        if (recState.value != null) RecordingService.send(getApplication(), RecordingService.ACTION_FOLLOW_CHANGED) {
            putExtra(RecordingService.EXTRA_CHECK_NOW, checkNow)
        }
    }

    /** A token no earlier session of this install had (time-based, strictly increasing). */
    private fun newSessionToken(): Long = maxOf(System.currentTimeMillis(), lastSessionToken + 1).also { lastSessionToken = it }
    private var lastSessionToken = 0L

    fun clearFollow() {
        splitRequests.invalidate()
        followFromPlan = false
        planCopy { notFollowed() } // nothing followed ("Nur aufzeichnen", guidance ended): what is stored stays
        guidanceEndedByEmptyPlan = false
        followIsTrack = false; prefs.followIsTrack = false
        planAtTrackFollow = null
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
        tour.start(activity.name, sac)
        // no followMe here: in Plan it briefly turned the map with the compass before Navigate took over (owner
        // 2026-11-07: the map never turns by itself) – entering Navigate starts the following
    }

    fun continueRecording(t: Tour) {
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.CONTINUE)
        tour.continueTour(t)
    }

    /** "Unfinished tour" → Save with a bus / car / lift found: the ride summary for it (null = none). */
    val unfinishedRides: Pair<Tour, com.onpath.app.rides.RideView.Summary>? get() = tour.unfinishedRides

    /**
     * "Unfinished tour" → Save: like End tour – rides found → the summary with the four
     * choices ([unfinishedRides]), else saved at once. Detection failing = saved as recorded (reported).
     */
    fun askSaveUnfinished(t: Tour) {
        tour.unfinished = null
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        viewModelScope.launchSafe("app", "askSaveUnfinished") {
            val s = withContext(Dispatchers.IO) {
                runCatching {
                    val segs = db.loadSegments(t.id)
                    val a = activityOrNull(t.activity) ?: activity
                    com.onpath.app.rides.RideView.summary(segs, com.onpath.app.rides.RideDetector.detect(segs, a), onFoot = a == Activity.HIKE)
                }.onFailure { Problems.warn("rides", "Ride check of the unfinished tour failed – saved without it", it) }.getOrNull()
            }
            when (com.onpath.app.domain.tour.TourEndFlow.unfinishedSave(s != null)) {
                com.onpath.app.domain.tour.TourEndFlow.Next.RIDES -> tour.unfinishedRides = t to s!!
                else -> saveUnfinished(t)
            }
        }
    }

    /** Ride summary of the unfinished tour → Cancel: back to its question (Continue · Delete · Save). */
    fun backToUnfinished() = tour.backToUnfinished()

    /**
     * Saves the interrupted tour; [choice] from the ride summary is applied like at the end of a recording
     * (RideApply.finish – without a choice the rides are only marked, Edit rides can change it later).
     */
    fun saveUnfinished(t: Tour, choice: com.onpath.app.rides.RideChoice? = null) {
        val rides = tour.unfinishedRides?.second?.rides
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.SAVE)
        tour.closeUnfinished()
        // the sticky service came back meanwhile and records this tour: it is not unfinished, leave it alone
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        enter(AppMode.PLAN)
        viewModelScope.launchSafe("app", "saveUnfinished", Dispatchers.IO) {
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
        com.onpath.app.recording.InterruptedLog.chose(t.name, com.onpath.app.recording.InterruptedLog.Choice.DELETE)
        tour.closeUnfinished()
        if (recState.value?.tourId == t.id) { enter(AppMode.NAVIGATE); return }
        Problems.trace("tour", "Unfinished tour ${t.id} deleted (recycle bin)")
        enter(AppMode.PLAN)
        viewModelScope.launchSafe("app", "deleteUnfinished", Dispatchers.IO) {
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

    fun pauseRecording(pause: Boolean) = tour.pause(pause)

    fun stopRecording() {
        routeBackAsk = false // a question about this tour's plan never reaches the next tour
        planSize = PlanSize.afterTour(planSize)
        tour.stop()
    }

    // ---------------- tours ----------------

    fun refreshTours() {
        // the newest read wins: an older one ending late must not hide a row added since (RoutesState.show)
        val load = routes.beginLoad()
        viewModelScope.launchSafe("app", "refreshTours", Dispatchers.IO) {
            val list = routes.load { withModelTime(it) }
            withContext(Dispatchers.Main) { routes.show(list, load) }
        }
    }

    /**
     * [duringTour] (a plan tapped in My routes during a tour, owner 2026-11-03 / 2026-11-06 without a question): the running tour follows the loaded route – its
     * activity stays, the plan is replaced like an edit during the tour (followEditedPlan), and the app goes to Navigate.
     */
    fun openTour(t: Tour, duringTour: Boolean = false, trackThen: TrackThen = TrackThen.SHOW,
                 /** A stand-in with no stored route behind it (a recorded tour's followed plan, [followedPlanTour]): its line. */
                 lineOf: List<List<TrackPoint>>? = null, planned: (() -> Unit)? = null) {
        Problems.trace("routes", "Tour ${t.id} on the map (${if (duringTour) "during the tour, " else ""}then $trackThen)")
        syncMarkSeen(t)
        viewModelScope.launchSafe("app", "openTour") {
            val segs = lineOf ?: withContext(Dispatchers.IO) { db.loadSegments(t.id) }
            routesOpen = false
            if (RouteRules.replacesPlan(t.kind, t.waypoints.size)) {
                // Back into planning with its via points (recalculated with the current engine).
                if (!duringTour) {
                    activityOrNull(t.activity)?.let { activity = it; prefs.activity = it } // unknown: the current one stays
                    sac = t.sac
                }
                shown = null
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                // stop names and POI pins saved with the plan come back too – the pins
                // before the points, so the first route already passes them exactly (recalc reads them at once)
                t.pinned.mapNotNull { pts.getOrNull(it) }.takeIf { it.isNotEmpty() }?.let { pinned += it; prefs.planPinned = pinned }
                // during a tour: the loaded route is followed from its point ① (owner 2026-11-03), not from the position
                loadedOnTheWay = duringTour && recState.value != null
                keepAsDraft("replaced by \"${t.name}\"")
                change { clear(); addAll(pts) }
                t.waypoints.forEachIndexed { i, w -> w.name?.let { rememberPlaceName(pts[i], it) } }
                // a stand-in (no stored route behind it): an unsaved plan – Los geht's saves it like every new plan
                if (t.id >= 0) setSaved(t.name, t.id, pts) else setSaved(null, -1L, null)
                events += UiEvent.Fit(segs.flatten().map { it.toGeo() })
                syncRoutingCheck(t, pts)
                if (duringTour && recState.value != null) enter(AppMode.NAVIGATE)
                planned?.invoke()
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
                when (trackThen) {
                    // a done tour tapped during a tour (owner 2026-11-06, no question): exactly what the track sheet's "Tour nachfahren" did, without the sheet
                    TrackThen.FOLLOW -> followShownNow()
                    TrackThen.PLAN -> planFromShownNow()
                    TrackThen.SHOW -> Unit
                }
            }
        }
    }

    // ---------------- plan sync with the browser (A-007: plansync/, docs/design/web-sync.md) ----------------

    /**
     * Saved routes and imported tracks ⇄ the paired browser. Runs in the background (plansync/PlanSync): at start,
     * back in the app, after a change here, when the network is back, about every minute while the app stays open on
     * My routes / Browser sync ([syncPoll]) and right away when one of them opens – never during a recording (after the tour).
     */
    val sync = com.onpath.app.plansync.PlanSync(app, com.onpath.app.plansync.DbTourStore(app, db) { a -> planFactors(a) },
        recording = { recState.value != null }, onReport = ::syncReport)

    /** Routes from the browser not opened yet (My routes → Planned: a "New" / "Changed" tag – RouteTag); read once at start. */
    val syncMarks: kotlinx.coroutines.flow.StateFlow<Map<String, com.onpath.app.plansync.SyncMark>> get() = sync.files.marks.state

    /** Opened (load as plan / open the track): the route's "New from / Changed in the browser" mark goes. */
    private fun syncMarkSeen(t: Tour) {
        if (t.uuid.isEmpty() || t.uuid !in syncMarks.value) return
        viewModelScope.launchSafe("app", "syncMarkSeen", Dispatchers.IO) { sync.files.marks.remove(t.uuid) }
    }

    /** Sync while the app stays open (owner: automatic like WhatsApp Web): every minute, back-off when offline. */
    private val syncPoll = com.onpath.app.plansync.SyncPoll()

    init {
        viewModelScope.launchSafe("app", "start-up work", Dispatchers.IO) { sync.files.marks.all() } // marks.txt → syncMarks (small file, never throws)
        sync.start()
        // a tour ended: what waited for it comes now
        viewModelScope.launchSafe("app", "start-up work") {
            var was = recState.value != null
            recState.collect { s -> if (was && s == null) sync.request("tour ended"); was = s != null }
        }
        // on screen, paired, no tour, My routes / Browser sync shown (not the Plan map): a pass about every minute (SyncPoll)
        viewModelScope.launchSafe("app", "start-up work") {
            val screen = androidx.compose.runtime.snapshotFlow {
                appOnScreen to com.onpath.app.plansync.SyncPoll.routesScreen(routesOpen, showSettings,
                    settingsPage == SettingsPage.SYNC)
            }
            combine(screen, recState, sync.state) { (visible, routesScreen), rec, st ->
                com.onpath.app.plansync.SyncPoll.wanted(visible, st.paired, recording = rec != null, routesScreen)
            }.distinctUntilChanged().collectLatest { on ->
                if (!on) return@collectLatest
                while (isActive) {
                    val wait = syncPoll.waitMs(android.os.SystemClock.elapsedRealtime())
                    when {
                        wait > 0 -> delay(wait)
                        sync.poll("every minute") -> syncPoll.requested(android.os.SystemClock.elapsedRealtime())
                        else -> delay(com.onpath.app.plansync.SyncPoll.BUSY_RECHECK_MS) // a pass runs or waits
                    }
                }
            }
        }
        // My routes / its Planned tab / Settings → Browser sync opened: pull right away (owner 2026-10-30), debounced
        viewModelScope.launchSafe("app", "start-up work") {
            var before = com.onpath.app.plansync.SyncPoll.Shown.OTHER
            androidx.compose.runtime.snapshotFlow {
                com.onpath.app.plansync.SyncPoll.shown(appOnScreen, routesOpen, routesTab == RoutesTab.PLANNED, showSettings,
                    settingsPage == SettingsPage.SYNC)
            }.collect { now ->
                val why = com.onpath.app.plansync.SyncPoll.opened(before, now)
                before = now
                val t = android.os.SystemClock.elapsedRealtime()
                if (why != null && syncPoll.openDue(t, sync.state.value.paired, recording = recState.value != null, busy = sync.busy())) {
                    syncPoll.requested(t)
                    sync.request(why)
                }
            }
        }
    }

    /** Pairing with a browser: [PairingFlow] (the pairing half of the sync), forwarded member by member. */
    private val pairing by lazy {
        PairingFlow(sync, prefs, helperScope, object : PairingFlow.Screens {
            override var showSettings: Boolean
                get() = this@AppViewModel.showSettings
                set(v) { this@AppViewModel.showSettings = v }
            override var settingsPage: SettingsPage?
                get() = this@AppViewModel.settingsPage
                set(v) { this@AppViewModel.settingsPage = v }
            override var routesOpen: Boolean
                get() = this@AppViewModel.routesOpen
                set(v) { this@AppViewModel.routesOpen = v }
            override var syncNote: SyncNote?
                get() = this@AppViewModel.syncNote
                set(v) { this@AppViewModel.syncNote = v }
            override fun openSettings(page: SettingsPage?) = this@AppViewModel.openSettings(page)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.say(text, kind, place)
        })
    }
    /** A pairing link was opened: the secret, until Pair / Cancel in Settings → Browser sync; null = no question. */
    val pairAsk: ByteArray? get() = pairing.pairAsk
    fun syncNoteOk() = pairing.syncNoteOk()
    /** ☰ → Pair with browser, the first time: the step line + Open camera as a question box on the Plan map ([PairEntry]). */
    val pairHint: Boolean get() = pairing.pairHint
    /** ☰ → Pair with browser: camera at once (paired / a problem → Settings → Browser sync; first time the step line). */
    fun startPairing(start: (String) -> Unit) = pairing.startPairing(start)
    fun pairHintCamera(start: (String) -> Unit) = pairing.pairHintCamera(start)
    fun pairHintCancel() = pairing.pairHintCancel()
    /** The camera for the pairing code (no camera app: Settings → Browser sync with the line). */
    fun openPairCamera(start: (String) -> Unit, fromSettings: Boolean) = pairing.openPairCamera(start, fromSettings)
    /** Pair with another browser runs (A-017): the check digits stay, a spinner instead of Pair / Cancel. */
    val pairJoining: Boolean get() = pairing.pairJoining
    /** "Pair" (first browser) or another browser into this phone's mailbox (A-017). */
    fun confirmPair() = pairing.confirmPair()
    fun cancelPair() = pairing.cancelPair()
    /** Settings → Unpair (after the question): the mailbox ends for both sides; the routes stay everywhere. */
    fun unpair() = pairing.unpair()
    private fun receivePairing(secret: ByteArray, via: String) = pairing.receivePairing(secret, via)

    /** A sync pass ended (main thread): new / changed routes into My routes, plans from the browser routed. */
    private fun syncReport(r: com.onpath.app.plansync.PlanSync.Report) {
        val res = r.result
        syncPoll.passEnded(android.os.SystemClock.elapsedRealtime(), com.onpath.app.plansync.SyncPoll.ok(r))
        if (r.ended) sayInfo(Txt.s(Res.string.sync_ended), NoticePlace.SETTINGS)
        // Pair during a tour: only the handshake ran – Browser sync / ☰ show "Routes sync after the tour" (State.afterTour)
        if (res?.toursChanged == true) {
            refreshTours()
            followSyncedSavedPlan()
            routeSyncedPlans(res.toRoute)
        }
        // a plan from the browser was routed with newer routing data than the phone's: get it now, not after 35 days
        res?.newerData?.takeIf { it.isNotEmpty() }?.let { refreshRoutingTiles(it, "a plan from the browser was routed with newer data") }
    }

    /**
     * A saved route opened as the plan: when it is a synced plan, the grey line if the browser routed it with another
     * fingerprint, and its tiles checked for newer routing data (field 8 newer than the phone's, or without field 8
     * older than 7 days – plansync/SyncRouting.opened).
     */
    private fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) {
        syncRoutingNote = null
        if (t.uuid.isEmpty()) return
        viewModelScope.launchSafe("app", "syncRoutingCheck") {
            val o = withContext(Dispatchers.IO) {
                try { sync.opened(t.uuid, pts) } catch (e: Exception) {
                    Problems.warn("sync", "Routing of the opened synced plan not compared", e); null
                }
            } ?: return@launchSafe
            if (linkId != t.id || waypoints.toList() != pts) return@launchSafe // another plan or edited meanwhile
            if (o.otherRouting) {
                Problems.info("sync", "Opened plan was routed in the browser with another routing fingerprint – grey line shown")
                syncRoutingNote = Txt.s(Res.string.sync_routing_other)
            }
            if (o.refresh.isNotEmpty()) refreshRoutingTiles(o.refresh, "opened a synced plan")
        }
    }

    /** The open plan's saved route was renamed or deleted from the browser: "Saved as …" follows (like Rename / Delete). */
    private fun followSyncedSavedPlan() {
        val id = linkId.takeIf { it >= 0 } ?: return
        viewModelScope.launchSafe("app", "followSyncedSavedPlan") {
            // a database error is not "deleted": the plan stays linked (review Arch 9)
            val read = withContext(Dispatchers.IO) { runCatching { db.getTour(id) } }
            val t = read.getOrElse { Problems.warn("db", "Saved route not read after a browser change; the plan stays linked", it); return@launchSafe }
            if (linkId != id) return@launchSafe
            if (t == null || t.deletedMs != null) setSaved(null, -1, null) else if (t.name != linkName) setSaved(t.name, id, savedPoints)
        }
    }

    /**
     * Routes from the browser are saved with their points as the line; here they get the real route (the router, the
     * pace) like a route saved on the phone. No routing data there yet: the points stay – opening the route routes it.
     */
    /**
     * Like runCatching, but only for exceptions (review Arch 9): a cancellation goes on to the coroutine, an Error
     * (OutOfMemoryError) is not taken for a failed route.
     */
    private inline fun <T> exceptions(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    /** Routes from the browser that waited for routing data (review routing M5): routed again when data arrives. */
    private val syncRoutingLater = LinkedHashSet<Long>()

    private fun routeSyncedPlans(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launchSafe("app", "routeSyncedPlans") {
            for (id in ids.distinct()) {
                val t = withContext(Dispatchers.IO) {
                    runCatching { db.getTour(id) }.onFailure { Problems.warn("db", "A route from the browser not read; routed when opened", it) }.getOrNull()
                } ?: continue
                if (t.kind != TourKind.PLANNED || t.waypoints.size < 2 || t.deletedMs != null) continue
                val act = activityOrNull(t.activity) ?: Activity.HIKE
                val pts = t.waypoints.map { GeoPoint(it.lat, it.lon) }
                val f = planFactors(act)
                val r = withContext(backgroundRouting) {
                    try {
                        val eng = routingEngine()
                        if (eng is com.onpath.app.routing.StraightLineEngine) null
                        else withModelTime(Planner(eng, com.onpath.app.routing.RouteCancel(), legs = legCache).plan(pts, act, t.sac, t.pinned), act, f)
                    } catch (e: Exception) {
                        Problems.warn("sync", "A route from the browser could not be routed; it keeps its points until opened", e); null
                    }
                } ?: continue
                // no routing data there yet (straight legs): keep the points, route it when the data arrives (review M5)
                if (!com.onpath.app.plansync.SyncedRouting.storable(r)) {
                    Problems.trace("sync", "Route \"${t.name}\" from the browser: no routing data there yet – points kept, routed later")
                    syncRoutingLater += id
                    continue
                }
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
            // a pairing link of the web planner ("Pair phone" in its header): Settings → Browser sync asks first (A-007)
            is com.onpath.app.routing.PlanLinks.Found.Pairing -> { receivePairing(found.secret, via); return }
            is com.onpath.app.routing.PlanLinks.Found.BrokenPairing -> {
                Problems.warn("sync", "Pairing link ($via) could not be read: cut off or a newer version")
                openSettings(SettingsPage.SYNC)
                sayError(Txt.s(Res.string.sync_link_broken), NoticePlace.SETTINGS)
                return
            }
        }
        viewModelScope.launchSafe("app", "receivePlanLink") {
            // Cold start: the start mode (Plan / Navigate, an unfinished tour) is decided after a database check
            if (!startModeKnown) androidx.compose.runtime.snapshotFlow { startModeKnown }.first { it }
            when (RouteRules.linkOpen(navigating(recState.value != null), waypoints.toList(), savedPoints, plan.points, linkPoints)) {
                LinkOpen.AFTER_TOUR -> {
                    Problems.info("plan", "Plan link ($via, ${plan.points.size} points) not opened: a tour is running")
                    sayError(Txt.s(Res.string.msg_plan_link_after_tour), NoticePlace.MAP)
                }
                // the current plan goes under "Zuletzt" (owner 2026-11-06) – no question any more
                LinkOpen.ASK_FIRST, LinkOpen.NOW -> { if (shown != null) closeShown(); openSharedPlan(plan, via) }
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
        keepAsDraft("replaced by a plan link")
        change { clear(); addAll(load.points) }
        load.names.forEach { (p, n) -> rememberPlaceName(p, n) }
        setSaved(null, -1, null) // not in My routes yet: Save asks for a name
        linkTitle = plan.title?.let { it to load.points }
        linkPoints = load.points
        Problems.info("plan", "Plan link opened ($via): ${load.points.size} points, ${plan.activity.name}" +
            (if (plan.activity == Activity.HIKE) " T${plan.sac}" else "") + ", ${load.pinned.size} pinned, ${load.names.size} names")
        // the map shows the plan (field A-019: it stayed where it was): its points now, the whole route once computed;
        // the start camera must not centre on the position afterwards, following stops first (CLAUDE.md "Map camera")
        holdCameraForSharedPlace()
        followMe = false
        freeLookAt = null
        fitNextRoute = true
        events += UiEvent.Fit(load.points)
    }

    /**
     * The open plan as a plan.onpath.earth link (PlanLinks) in the share sheet; from two points. No button since 0.13.5
     * (the sheet's ⋯ is gone – My routes → ⋮ → Share plan, [shareRoute]); kept for the plan-link round trip
     * (`CorePlanLinkE2ETest`) and the same link shape.
     */
    fun sharePlan() {
        if (waypoints.size < 2) return
        val pts = waypoints.toList()
        // the route's name: as saved, else the name a link brought while its points are unchanged
        val title = savedName ?: linkTitle?.takeIf { it.second == pts }?.first
        sendPlanLink(com.onpath.app.routing.PlanLinks.share(pts, activity, sac, pinnedIndices(), ::stopName, title), pts.size, title)
    }

    /**
     * My routes → ⋮ → Share plan (planned routes; owner 2026-10-31, was the plan sheet's ⋯): the saved route as a
     * plan.onpath.earth link (PlanLinks) in the share sheet – its points, names, pins, activity and name.
     */
    fun shareRoute(t: Tour) {
        if (t.kind != TourKind.PLANNED || t.waypoints.size < 2) return
        val pts = t.waypoints.map { GeoPoint(it.lat, it.lon, it.ele) }
        val names = t.waypoints.associate { GeoPoint(it.lat, it.lon, it.ele) to it.name }
        val act = activityOrNull(t.activity) ?: Activity.HIKE
        sendPlanLink(com.onpath.app.routing.PlanLinks.share(pts, act, t.sac, t.pinned, { names[it] }, t.name), pts.size, t.name)
    }

    private fun sendPlanLink(plan: com.onpath.app.routing.SharedPlan, count: Int, title: String?) {
        val link = com.onpath.app.routing.PlanLinks.link(plan)
        Problems.trace("plan", "Plan shared as a link: $count points, ${link.length} characters")
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

    /**
     * A track is being turned into a plan ([planFromShown]): a modal box with Cancel over everything (owner 2026-11-01 –
     * nothing else at the same time; review routing M3: minutes for a long track, points set meanwhile were overwritten).
     */
    var planFromTrackBusy by mutableStateOf(false)
        private set
    private var planFromTrackCancel: com.onpath.app.routing.RouteCancel? = null

    fun cancelPlanFromTrack() {
        Problems.trace("plan", "Track → plan cancelled by the user")
        planFromTrackCancel?.cancel()
        planFromTrackBusy = false
    }

    /**
     * Shown track → a plannable route with few route points (owner; routing/TrackToPlan): our router and the
     * current activity re-trace the track; the result replaces the plan (Undo brings the old one back).
     */
    /** The track sheet's "Als Plan übernehmen": an unsaved plan asks first ([unlessUnsaved], save paths P3). */
    fun planFromShown() = unlessUnsaved("Track → plan") { planFromShownNow() }

    private fun planFromShownNow() {
        val sh = shown ?: return
        if (planFromTrackBusy) return
        val track = sh.segments.flatten()
        if (track.size < 2) return
        // an exported OnPath plan (gpx/PlanGpx): its <wpt> are the route points – the plan comes back exactly, with names
        com.onpath.app.gpx.PlanGpx.stopsOf(track, sh.waypoints)?.let { stops ->
            Problems.trace("plan", "Track → plan: the file's ${stops.size} route points (a plan GPX), no fitting")
            val pts = stops.map { GeoPoint(it.lat, it.lon) }
            closeShown()
            // pinned route points come back pinned (review routing L1)
            com.onpath.app.gpx.PlanGpx.pinnedOf(stops).forEach { pinned += pts[it] }
            prefs.planPinned = pinned
            keepAsDraft("replaced by a track's plan")
            change { clear(); addAll(pts) }
            stops.forEachIndexed { i, w -> w.name?.let { rememberPlaceName(pts[i], it) } }
            return
        }
        val act = activity; val level = sac
        // routing data along the track first: without it every leg fails
        val missing = try { mapData.routingData.ensure(com.onpath.app.data.RoutingTiles.forLine(track, act)) } catch (e: Exception) {
            Problems.warn("routing", "Routing data along the track not checked; planning from the track anyway", e); emptyList()
        }
        if (missing.isNotEmpty()) {
            sayError(Txt.s(Res.string.plan_from_track_wait), NoticePlace.MAP)
            return
        }
        planFromTrackBusy = true
        val cancel = com.onpath.app.routing.RouteCancel().also { planFromTrackCancel = it }
        viewModelScope.launchSafe("app", "planFromShownNow") {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = withContext(backgroundRouting) {
                try {
                    val eng = routingEngine()
                    if (eng is com.onpath.app.routing.StraightLineEngine) null
                    else com.onpath.app.routing.TrackToPlan.fit(track, eng, act, level, cancel = cancel)
                } catch (e: Exception) {
                    if (!cancel.isCancelled) Problems.error("routing", "Track could not be turned into a plan", e); null
                }
            }
            if (planFromTrackCancel === cancel) planFromTrackCancel = null
            if (cancel.isCancelled) return@launchSafe // the user cancelled: nothing changes
            planFromTrackBusy = false
            if (r == null || r.points.size < 2) { sayError(Txt.s(Res.string.plan_from_track_failed), NoticePlace.MAP); return@launchSafe }
            Problems.trace("plan", "Track → plan: ${track.size} track points → ${r.points.size} route points, follows " +
                "${(r.follows * 100).toInt()} %, ${r.calls} routing calls, ${r.failedLegs} legs without a route, " +
                "${android.os.SystemClock.elapsedRealtime() - t0} ms")
            closeShown()
            keepAsDraft("replaced by a track's plan")
            change { clear(); addAll(r.points) }
        }
    }

    fun deleteTour(t: Tour) {
        // never the tour being recorded: the service keeps writing into it (the list hides Delete for it too)
        if (recState.value?.tourId == t.id) { sayError(Txt.s(Res.string.msg_track_recording_now), NoticePlace.ROUTES); return }
        viewModelScope.launchSafe("app", "deleteTour", Dispatchers.IO) {
            // into the recycle bin (30 days, My routes → ⋮ → Recycle bin); its backup file moves to Deleted/
            val ok = runCatching { routes.toBin(t.id) }
                .onFailure { Problems.error("db", "Moving a tour to the recycle bin failed", it) }.getOrDefault(false)
            if (ok) { Backups.tourChanged(getApplication(), t.id); sync.files.marks.remove(t.uuid); sync.changed("moved to the recycle bin") }
            withContext(Dispatchers.Main) {
                if (!ok) { routesProblem = Txt.s(Res.string.msg_tour_delete_failed, t.name); return@withContext }
                if (shown?.tourId == t.id) shown = null
                if (linkId == t.id) setSaved(null, -1, null) // the plan is no longer saved anywhere
                if (tour.finishedTour?.id == t.id) tour.closeFinished()
                refreshTours()
            }
        }
    }

    // ---------------- recycle bin + backup (docs/core-features.md C11) ----------------

    /** Recycle bin list (My routes → ⋮ → Recycle bin); null = closed. */
    var recycleBin by mutableStateOf<List<Tour>?>(null)

    fun openRecycleBin() {
        viewModelScope.launchSafe("app", "openRecycleBin") {
            recycleBin = withContext(Dispatchers.IO) {
                runCatching { db.listDeleted() }.onFailure { Problems.error("db", "Recycle bin could not be read", it) }.getOrDefault(emptyList())
            }
        }
    }

    /** Recycle bin → Restore: back into My routes (and its file out of Deleted/). */
    fun restoreFromBin(t: Tour) {
        viewModelScope.launchSafe("app", "restoreFromBin") {
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
        viewModelScope.launchSafe("app", "deleteForever") {
            val ok = withContext(Dispatchers.IO) {
                runCatching { db.deleteTour(t.id) }.onFailure { Problems.error("db", "Deleting a tour for good failed", it) }.isSuccess
            }
            if (ok) { Backups.purge(getApplication(), listOf(t.uuid)); sync.changed("deleted for good") }
            openRecycleBin()
        }
    }

    /** "Export all": one ZIP with every tour and plan as GPX, handed to the share sheet (Files, Drive, mail …). */
    fun exportAll() {
        busy = Busy.EXPORT
        viewModelScope.launchSafe("app", "exportAll") {
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
    val loadModalOpen: Boolean get() = mapData.loadModalOpen
    // ---------------- Phone setup (docs/design/onboarding-phone-check.md) ----------------
    /** Phone setup (docs/design/onboarding-phone-check.md): [PhoneSetupState], forwarded member by member. */
    private val setup = PhoneSetupState(prefs) { settings }
    /** The phone as last read by MainActivity (every return to the app, every system answer). */
    val phone: com.onpath.app.setup.PhoneState get() = setup.phone
    /** Android's unused-app reset for OnPath (read off the main thread at start). */
    var unusedReset: com.onpath.app.setup.ResetStatus
        get() = setup.unusedReset
        set(v) { setup.unusedReset = v }
    /** What the phone or the user removed since the last use (the guide and Settings say so calmly). */
    var phoneTaken: Map<com.onpath.app.setup.CheckId, com.onpath.app.setup.Taken>
        get() = setup.phoneTaken
        set(v) { setup.phoneTaken = v }
    /** The first-start welcome ("Set up now / Later"). */
    var setupWelcome: Boolean
        get() = setup.setupWelcome
        set(v) { setup.setupWelcome = v }
    /** The open step-by-step guide; null = none. */
    val setupGuide: com.onpath.app.setup.SetupGuide? get() = setup.setupGuide
    val setupOpen: Boolean get() = setup.setupOpen
    /** The tour whose "Notifications off" chip was confirmed with OK (in memory: the next tour shows it again). */
    var notificationsOkTour: Long?
        get() = setup.notificationsOkTour
        set(v) { setup.notificationsOkTour = v }
    /** Navigation vibrations in use: the vibration step applies only then. */
    val vibrationUsed: Boolean get() = setup.vibrationUsed
    /** Spoken directions switched on for bike or hike: the voice step applies only then. */
    val voiceUsed: Boolean get() = setup.voiceUsed
    fun updatePhone(s: com.onpath.app.setup.PhoneState) = setup.updatePhone(s)
    /**
     * Opens the guide for [purpose] with what is missing; nothing missing → [then] runs at once. Before a tour and for
     * the locate button [then] runs when the guide ends and what it needs is there.
     */
    fun openSetup(purpose: com.onpath.app.setup.Purpose, then: (() -> Unit)? = null) = setup.openSetup(purpose, then)
    /** Settings → Phone setup → a row: that one step. */
    fun openSetupStep(id: com.onpath.app.setup.CheckId) = setup.openSetupStep(id)
    fun setupWelcomeNow() = setup.setupWelcomeNow()
    /** "Later": before a tour a recommended step stays quiet for a week. */
    fun setupLater() = setup.setupLater()
    /** The system dialog for [id] came back without it. */
    fun setupDenied(id: com.onpath.app.setup.CheckId) = setup.setupDenied(id)
    fun setupVibrationPlayed() = setup.setupVibrationPlayed()
    fun setupVibrationAnswer(f: com.onpath.app.setup.Felt) = setup.setupVibrationAnswer(f)
    /** The voice test found the phone's speech output in this [status] (recording/Speaker). */
    fun setupVoiceStatus(status: com.onpath.app.setup.VoiceStatus, fix: com.onpath.app.setup.VoiceSetup.Fix?) =
        setup.setupVoiceStatus(status, fix)
    /** What the last tap on the voice step's Test did (shown under the step); null = not tapped. */
    val voiceTest: com.onpath.app.setup.VoiceSetup.VoiceTest? get() = setup.voiceTest
    fun setupVoiceTest(t: com.onpath.app.setup.VoiceSetup.VoiceTest?) = setup.setupVoiceTest(t)
    fun setupBrandDone() = setup.setupBrandDone()
    /** Closes the welcome / guide (Back, Close, Later on the welcome). */
    fun closeSetup() = setup.closeSetup()

    /** A restore is running: its progress line ("Restoring 5 / 28"); null = none. */
    var restoreProgress by mutableStateOf<String?>(null)
        private set

    private fun checkRestoreOffer() {
        viewModelScope.launchSafe("app", "checkRestoreOffer", Dispatchers.IO) {
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
        viewModelScope.launchSafe("app", "start-up work") {
            Backups.failure.collect { text ->
                if (text == null) return@collect
                while (errorCard != null) delay(500) // after the current error box
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
        viewModelScope.launchSafe("app", "restoreFrom") {
            val app = getApplication<Application>()
            val r = withContext(Dispatchers.IO) {
                try { Backups.restore(app, tree) { line -> viewModelScope.launchSafe("app", "restoreFrom", Dispatchers.Main) { if (restoreProgress != null) restoreProgress = line } } }
                catch (e: Exception) { Problems.error("backup", "Restore from the backup folder failed", e); null }
            }
            restoreProgress = null
            if (r != null) sync.changed("restored from the backup folder")
            // the result where it was started (Settings → Backup), else where the tracks are now: My routes (restore
            // offered at the first start); a failure there → the error box
            if (r != null) sayInfo(com.onpath.app.backup.BackupRestore.summary(r),
                if (showSettings) NoticePlace.SETTINGS else { routesOpen = true; NoticePlace.ROUTES })
            else sayError(Txt.s(Res.string.msg_restore_folder_unreadable), if (showSettings) NoticePlace.SETTINGS else NoticePlace.MAP)
            refreshTours()
        }
    }

    fun exportTour(t: Tour) {
        viewModelScope.launchSafe("app", "exportTour") {
            // as chosen at End tour: rides not counted are their own segments, a dropped ride is not exported
            val segs = withContext(Dispatchers.IO) { com.onpath.app.rides.RideView.layout(db.loadSegments(t.id), t.rides).trk.map { it.first } }
            share(t.name) {
                // a planned route: the one plan export shared with the web planner (gpx/PlanGpx) – its route points
                // as <wpt> (start, stops, goal – with their names): other apps show them, a re-import rebuilds the plan
                if (t.kind == TourKind.PLANNED) com.onpath.app.gpx.PlanGpx.export(t.name, segs,
                    com.onpath.app.gpx.PlanGpx.withPins(t.waypoints, t.pinned), t.activity, t.sac)
                // the sport as `<trk><type>` (Komoot's string, so Komoot and OnPath read the activity back);
                // the file name the same in every language (owner): "Hilden - rec.gpx"
                else com.onpath.app.gpx.GpxFile(GpxWriter.fileName(t.name, GpxWriter.kindSuffix(false, t.kind == TourKind.RECORDED)),
                    GpxWriter.write(t.name, segs, t.waypoints, type = SportType.typeOf(t.activity, t.sac)))
            }
        }
    }

    /** Writes the GPX [gpx] builds (off the main thread) into the export folder and opens the share sheet. */
    private suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) {
        try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().cacheDir, "export").apply { mkdirs() }
                val g = gpx()
                val file = File(dir, g.fileName)
                file.writeText(g.text)
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
        viewModelScope.launchSafe("app", "importGpx") {
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
                // the file's own moving time, else the one time model with the pace level – the one import stats (app,
                // sync, web: TimeEstimate.trackStats)
                val st = com.onpath.app.time.TimeEstimate.trackStats(segs, pick.activity, planFactors(pick.activity))
                val id = withContext(Dispatchers.IO) {
                    db.insertTour(TourKind.IMPORTED, name, pick.activity.name, pick.sac, st.distanceM, st.ascentM, st.descentM,
                        st.durationS, segs, data.waypoints)
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
                showTrack(ShownTrack(id, name, geo, data.waypoints, st.distanceM, st.ascentM, st.descentM, st.durationS, note = choice.note,
                    skippedNote = data.skippedNote))
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
                // My routes when the import was started there; a file opened from another app → the error box
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
        refreshRd5(due.map { it.name })
    }

    /** Synced plans' tile checks: once per tile in [ROUTING_CHECK_MS] (sync passes come every minute). */
    private val syncRoutingThrottle = com.onpath.app.plansync.RefreshThrottle(ROUTING_CHECK_MS)

    /**
     * Newer routing data for [tiles] ("E10_N45") right away – a synced plan says the browser routed it with newer data,
     * or a synced plan without that date was opened (plansync/SyncRouting). The rules of [checkRoutingData]: never
     * during a tour, maps only on Wi-Fi respected, quiet (INFO); at most once per tile in [ROUTING_CHECK_MS].
     */
    private fun refreshRoutingTiles(tiles: List<String>, why: String) {
        if (routingDataBusy || recState.value != null) return
        val due = syncRoutingThrottle.take(tiles.filter { java.io.File(dataFiles.segmentsDir, "$it.rd5").isFile }, System.currentTimeMillis())
        if (due.isEmpty()) return
        if (settings.mapsWifiOnly && !com.onpath.app.cells.AndroidNetworkStatus(getApplication()).unmetered()) {
            Problems.info("routing", "Routing data ${due.joinToString()}: newer data not checked ($why) – maps only on Wi-Fi"); return
        }
        Problems.info("routing", "Routing data ${due.joinToString()}: checking for newer data now ($why)")
        refreshRd5(due.map { "$it.rd5" })
    }

    /** [names] ("E10_N45.rd5") fetched again when the server has another version; the open plan routed again then. */
    private fun refreshRd5(names: List<String>) {
        routingDataBusy = true
        val due = names.map { java.io.File(dataFiles.segmentsDir, it) }
        viewModelScope.launchSafe("app", "refreshRd5", Dispatchers.IO) {
            var got = 0
            for (f in due) {
                try {
                    if (mapData.routingData.store.refreshIfNewer(f.name)) got++ // only the 1° parts on the phone
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

    // Last in the class: every property it touches is initialised by now.
    init {
        // a new cell store generation (cell added / replaced / deleted, region re-joined) → reload the map style
        // (new file paths; MapLibre keeps no stale PMTiles index) and recheck the data. The search index notices by itself.
        viewModelScope.launchSafe("app", "start-up work") {
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
        viewModelScope.launchSafe("app", "start-up work") {
            cells.generation.drop(1).collectLatest { delay(5_000); search.prepare() }
        }
        // map cells (map-cells.md §9): Wi-Fi setting, relevance, cells still loading from the last run, T3 on fixes
        mapData.start()
        // a tour started indoors (owner): the fused position until its first GPS fix – off with that fix, a pause, the end
        viewModelScope.launchSafe("app", "start-up work") {
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
        mapData.stop()
        sync.stop()
        routingDispatcher.close()
        backgroundRouting.close()
        checkRouting.close()
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
/** Routing data: checked at most this often after a route (ms), and refreshed when older than this (days). */
private const val ROUTING_CHECK_MS = 12 * 3600_000L
private const val ROUTING_MAX_AGE_DAYS = 35
/** Free look in Navigate ends after walking this far from where the map was turned / moved by hand. */
private const val FREE_LOOK_BACK_M = 25.0

/** What [AppViewModel.openTour] does with a done tour / track once it is shown. */
enum class TrackThen { SHOW, FOLLOW, PLAN }
