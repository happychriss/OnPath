package com.onpath.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.HoldPaths
import com.onpath.app.domain.NewPlanNote
import com.onpath.app.domain.NotRouted
import com.onpath.app.domain.OfflineReady
import com.onpath.app.domain.PlaceAddMenu
import com.onpath.app.domain.PlaceMenuMeasure
import com.onpath.app.domain.PlanAutoSave
import com.onpath.app.domain.PlanCamera
import com.onpath.app.domain.PlanGap
import com.onpath.app.domain.PlanGaps
import com.onpath.app.domain.PlanGuidance
import com.onpath.app.domain.PlanStart
import com.onpath.app.domain.PlanStatus
import com.onpath.app.domain.PointMenuTarget
import com.onpath.app.domain.RouteMenuState
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.RouteShape
import com.onpath.app.domain.RouteSpot
import com.onpath.app.domain.RouteWarnings
import com.onpath.app.domain.RoutesTab
import com.onpath.app.domain.SaveAsk
import com.onpath.app.domain.SaveMode
import com.onpath.app.domain.SplitRequests
import com.onpath.app.domain.StyleReloadRule
import com.onpath.app.domain.TakenPin
import com.onpath.app.domain.WaitBar
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.SportType
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.recording.LocationTracker
import com.onpath.app.navigation.NavKind
import com.onpath.app.navigation.NavLine
import com.onpath.app.navigation.NavSession
import com.onpath.app.navigation.FollowStore
import com.onpath.app.navigation.TourSplit
import com.onpath.app.recording.RecState
import com.onpath.app.recording.RecordingClient
import com.onpath.app.recording.RecordingService
import com.onpath.app.recording.ServiceRecordingClient
import com.onpath.app.routing.Activity
import com.onpath.app.routing.EngineProvider
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanEdits
import com.onpath.app.search.SharedPlaceLookup
import com.onpath.app.ui.plan.AddRole
import com.onpath.app.ui.plan.PlaceAddSlice
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
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.domain.plan.RoutingSpot
import com.onpath.app.domain.tour.QuietGate

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

    val tracker = LocationTracker(app)

    // --- data check ---
    var missing by mutableStateOf(dataFiles.missing())
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

    /** Everything the app asks of the recording service and reads from it (the typed contract, cleanup piece 5). */
    private val recording: RecordingClient = ServiceRecordingClient(app)

    // --- the tour's lifecycle state (audit package 4 stage 2: a focused state holder, the VM forwards) ---
    /** End sheet, arrival box, saved box, interrupted tour, "Neuer Plan" line – see [TourController]. */
    val tour: com.onpath.app.ui.tour.TourController = com.onpath.app.ui.tour.TourController(com.onpath.app.ui.tour.tourRecorder(recording))

    // --- screens and mode ---
    // messages (declared before routesOpen / showSettings, whose setters clear their lines) – see [say]
    /**
     * The messages the screens show ([NoticeBoard], in `:state`): the red error box, the lines in My routes / Settings, the
     * quiet map line, the spinner. It reads the screens through lambdas, when a message is said.
     */
    val notices: NoticeBoard = NoticeBoard(routesOpen = { routesOpen }, settingsOpen = { showSettings }, mapShown = { mode == AppMode.PLAN && !searchOpen })
    /** The red error box (MessageCard HINWEIS_ERROR: ⚠, red edge, OK) on the screen shown; null = none. Never cleared by itself. */
    var errorCard: String? get() = notices.errorCard; set(v) { notices.errorCard = v }

    /**
     * My routes' state and decisions ([com.onpath.app.ui.routes.RoutesState], audit package 4 stage 3): open, tab, the
     * list, Zuletzt, the "✓ Gerade gespeichert" mark; the database behind a small store.
     */
    private val routesStore = object : com.onpath.app.ui.routes.RoutesStore {
        override fun listTours() = db.listTours()
        override fun listDeleted() = db.listDeleted()
        override fun setDraft(id: Long, draft: Boolean) = db.setDraft(id, draft)
        override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id, deletedMs)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun deleteForGood(id: Long) { db.deleteTour(id) }
        override fun rename(id: Long, name: String) = db.rename(id, name)
    }
    val routes: com.onpath.app.ui.routes.RoutesState = com.onpath.app.ui.routes.RoutesState(routesStore, recent = prefs.zuletztId).also {
        it.onClosed = { notices.routesNote = null } // the line at the top lasts until My routes is left
        it.onRecent = { id -> prefs.zuletztId = id } // the Zuletzt row survives an app start
    }
    /**
     * Zuletzt and the recycle bin as the user's actions ([com.onpath.app.ui.routes.RecentPlans], cleanup piece 4): a plan
     * that leaves the map is kept, Zuletzt → Speichern / Löschen, the bin's list, Restore, Delete now. The names below
     * ([SaveSlice.keepAsDraft], [saveDraft], [recycleBin] …) forward to it.
     */
    val recent: com.onpath.app.ui.routes.RecentPlans = com.onpath.app.ui.routes.RecentPlans(routes, routesStore, object : com.onpath.app.ui.routes.RecentPlansHost {
        override fun insertDraft(row: com.onpath.app.ui.routes.RecentPlans.DraftRow) =
            db.insertTour(TourKind.PLANNED, row.name, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS,
                row.segments, row.waypoints, pinned = row.pinned, draft = true, difficulty = row.difficulty, days = row.days)
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = planRoute.storePlanTimes(id, route, activity)
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?) = this@AppViewModel.proposeName(points, route)
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun purged(uuid: String) = Backups.purge(getApplication(), listOf(uuid))
        override fun syncChanged(why: String) = sync.changed(why)
        override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
        override fun problem(text: String) { save.problem = text }
        override var trimmed: Boolean
            get() = prefs.zuletztTrimmed
            set(v) { prefs.zuletztTrimmed = v }
    }, viewModelScope, Dispatchers.IO)
    /**
     * My routes' row pictures and the large overviews' map pictures (owner 2026-11-13 / 2026-11-15): files, makers and the
     * snapshot live in [RoutePictures] (`:app`); the screens read it as `vm.<slice>` (piece 21).
     */
    val pictures: RoutePictures by lazy {
        RoutePictures(getApplication(), viewModelScope, Dispatchers.IO, db, routes, object : RoutePicturesHost {
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val settings: AppSettings get() = this@AppViewModel.settingsSlice.settings
            override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles
            override val devToolsShown: Boolean get() = this@AppViewModel.settingsSlice.devToolsShown
            override fun detailedAreas() = this@AppViewModel.mapData.detailedAreas()
            override fun packageAreas() = this@AppViewModel.mapData.packageAreas()
            override suspend fun followedPlanLine(t: Tour) = this@AppViewModel.followedPlanLine(t)
        })
    }
    suspend fun overviewShot(t: Tour, segments: List<List<GeoPoint>>, tint: RouteShape.Tint, widthPx: Int, heightPx: Int,
                             paddingPx: Int, under: List<GeoPoint> = emptyList()): com.onpath.app.map.RouteSnapshot.Shot? =
        pictures.overviewShot(t, segments, tint, widthPx, heightPx, paddingPx, under)

    /** My routes list (☰ → My routes) is open. */
    var routesOpen: Boolean
        get() = routes.open
        set(v) { routes.open = v }
    /** My routes' order (owner 2026-11-12, [com.onpath.app.domain.routes.RouteSort]): one for both tabs, kept across app starts. */
    var routesSort: com.onpath.app.domain.routes.RouteSort.Choice
        get() = routes.sort
        set(v) { routes.sort = v } // while My routes is open only: "Neueste zuerst" at each opening (owner 2026-11-13)
    /** My routes' "☆ Favoriten" pill (owner 2026-11-13, favourites C): on = only the starred ones; kept while the app runs. */
    var routesFavouritesOnly = false
    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes.md. */
    val mode: AppMode get() = tourSession.mode
    /** Map taps may add, move or insert points (Plan with no track shown). */
    val canEditMap: Boolean get() = tourSession.mode == AppMode.PLAN && routesFlow.shown == null
    /** POI whose details sheet is open (F-MAP-09). */
    var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    // --- activity ---
    /**
     * The plan's route, first half ([PlanRouteSlice], rebuild phase 2 piece 8): activity / T level (every app start begins
     * with the last chosen ones, owner; fresh install Hike T2), the routing engine with its leg cache and threads, the time
     * estimate, the route warnings. Declared here – above the init that calls `recalc()` and every property that reads it – because
     * `recalc()` runs in the init; the ports read the lazies and the later properties only when called (ViewModelInitOrderTest).
     */
    val planRoute: com.onpath.app.ui.plan.PlanRouteSlice = com.onpath.app.ui.plan.PlanRouteSlice(
        prefs = object : com.onpath.app.ui.plan.PlanRoutePrefs {
            override var activity: Activity
                get() = prefs.activity
                set(v) { prefs.activity = v }
            override var sac: Int
                get() = prefs.sac
                set(v) { prefs.sac = v }
        },
        router = object : com.onpath.app.ui.plan.RouterPort {
            override fun create() = EngineProvider.create(getApplication(), dataFiles.segmentsDir)
            override val isRealRouter: Boolean get() = EngineProvider.isRealRouter
            override fun fingerprint(): String = routingFingerprint
            override val legsDir: File get() = File(app.filesDir, "legs")
            override fun partVersion(part: com.onpath.app.routing.Rd5Parts.Part) = mapData.routingData.store.partVersion(part)
        },
        times = object : com.onpath.app.ui.plan.TimesStore {
            override fun loadSegments(id: Long) = db.loadSegments(id)
            override fun planGroups(id: Long, distanceM: Double) = planTimes.get(tourId = id, distanceM = distanceM)
            override fun putPlanGroups(id: Long, distanceM: Double, groups: DoubleArray) = planTimes.put(tourId = id, distanceM = distanceM, groups = groups)
            override fun tourTimes(id: Long) =
                com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).get(id)
        },
        host = object : com.onpath.app.ui.plan.PlanRouteHost {
            override val settings: AppSettings get() = this@AppViewModel.settingsSlice.settings
            override var route: PlannedRoute?
                get() = this@AppViewModel.route
                set(v) { this@AppViewModel.route = v }
            override val routing: Boolean get() = this@AppViewModel.planRun.routing
            override val longRouteAsk: Double? get() = this@AppViewModel.planRun.longRouteAsk
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override val touring: Boolean get() = recState.value != null
            override fun recalc() = this@AppViewModel.recalc()
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }
            override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
            override fun clearShownNote() { routesFlow.shown?.let { if (it.note != null) routesFlow.shown = it.copy(note = null) } }
            override fun showWhole(points: List<GeoPoint>) { map.followMe = false; events += UiEvent.Fit(points) }
        },
    )
    // --- settings (Settings page) ---
    /**
     * Settings ([com.onpath.app.ui.SettingsSlice] in `:state`, rebuild phase 2 piece 4): the settings, open / page / back / the
     * on-the-way list, relief auto, developer tools, the last visit to Diagnostics, the routing fingerprint. The preferences and
     * files behind [settingsStore], the rest of the app behind [settingsHost]; the screens read it as `vm.<slice>` (piece 21).
     */
    private val settingsStore = object : com.onpath.app.ui.SettingsStore {
        override var settings: AppSettings
            get() = prefs.settings
            set(v) { prefs.settings = v }
        override var reliefAuto: com.onpath.app.map.ReliefAuto.State
            get() = prefs.reliefAuto
            set(v) { prefs.reliefAuto = v }
        override var devToolsUnlocked: Boolean
            get() = prefs.devToolsUnlocked
            set(v) { prefs.devToolsUnlocked = v }
        override var diagnosticsSeenMs: Long
            get() = prefs.diagnosticsSeenMs
            set(v) { prefs.diagnosticsSeenMs = v }
        override fun rd5Files(): List<java.io.File> = dataFiles.segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty().toList()
        override fun fingerprint(): String = routingFingerprint
    }
    private val settingsHost = object : com.onpath.app.ui.SettingsHost {
        override val planMapShown: Boolean get() = tourSession.mode == AppMode.PLAN && !searchFlow.searchOpen
        override fun clearOfflineResult() { mapData.offlineResult = null }
        override fun syncEndedSeen() = sync.endedSeen()
        override fun mapsWifiOnly(on: Boolean) { cells.wifiOnly = on }
        override fun retime() = this@AppViewModel.planRoute.retime()
        override val tourRecording: Boolean get() = recState.value != null
        override fun recordingSettingsChanged() = recording.settingsChanged()
    }
    val settingsSlice: com.onpath.app.ui.SettingsSlice = com.onpath.app.ui.SettingsSlice(notices, settingsStore, settingsHost, viewModelScope, Dispatchers.IO)
    /**
     * A pairing was just made (first browser or another one, A-017) or ended here: the Plan map shows "Paired. …" /
     * "Unpaired. …" with OK at the top (owner 2026-10-30) until OK or the next Pair / Unpair; never for "already
     * paired" or a failure ([PairLanding]).
     */
    var syncNote by mutableStateOf<SyncNote?>(null)
        private set

    var showSettings: Boolean
        get() = settingsSlice.showSettings
        set(v) { settingsSlice.showSettings = v }

    private val routingFingerprint by lazy {
        com.onpath.app.routing.RoutingFingerprint.of(read = { n -> getApplication<Application>().assets.open("brouter/$n").use { it.readBytes() } })
    }

    /** Round trips („Rundtour von hier“, tour-suggestions.md §7 stage 4) – ui/suggest/SuggestSlice; Übernehmen → [planFromLine]. */
    val suggest = com.onpath.app.ui.suggest.SuggestSlice(viewModelScope, com.onpath.app.ui.suggest.AppSuggestHost(app, prefs, { mapData.routingData },
        fit = { map.followMe = false; map.freeLookAt = null; events += UiEvent.Fit(it) }, take = { pts, a, l, done -> if (a != planRoute.activity) planRoute.selectActivity(a); planRoute.selectSac(l)
            routesFlow.planFromLine(pts, com.onpath.app.data.RoutingTiles.forLine(pts, 1_000.0), a, l, done) }), prefs.suggestTest) { prefs.suggestTest = it }

    // --- Navigate orientation + next turn (see NavigateScreen, MapController.setCamera) ---

    /** Camera, compass, scrub, the fits and the start camera's flags (rebuild phase 2 piece 5); the screens read it as `vm.<slice>` (piece 21). */
    private val mapCamera = object : com.onpath.app.ui.Camera {
        override fun fit(points: List<GeoPoint>) { events += UiEvent.Fit(points) }
        override fun centerOn(point: GeoPoint, zoom: Double) { events += UiEvent.CenterOn(point, zoom) }
        override fun panTo(point: GeoPoint) { events += UiEvent.PanTo(point) }
    }
    private val mapHost = object : com.onpath.app.ui.MapViewHost {
        override val mode: AppMode get() = this@AppViewModel.tourSession.mode
        override val activity: com.onpath.app.routing.Activity get() = this@AppViewModel.planRoute.activity
        override val headingUp: Boolean get() = this@AppViewModel.settingsSlice.settings.navHeadingUp
        override fun setHeadingUp(on: Boolean) {
            if (settingsSlice.settings.navHeadingUp != on) { settingsSlice.put(settingsSlice.settings.copy(navHeadingUp = on)) }
        }
        override var navStartZoom: Double?
            get() = this@AppViewModel.tourSession.navStartZoom
            set(v) { this@AppViewModel.tourSession.navStartZoom = v }
        override val routePoints: List<GeoPoint>? get() = route?.points
        override val waypoints: List<GeoPoint> get() = this@AppViewModel.plan.points
        override val shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown
        override val followLine: List<GeoPoint>? get() = this@AppViewModel.tourFollow.followLine
        override val navOriginal: List<GeoPoint>? get() = recState.value?.nav?.original
        override val touring: Boolean get() = recState.value != null
        override val keepCameraUntilMs: Long get() = searchFlow.keepCameraUntil
        override fun highlight(at: GeoPoint?) { this@AppViewModel.highlight = at }
        override fun closeSpots() { planRoute.spotsOpen = false }
        override fun spotLine(piece: List<GeoPoint>) { this@AppViewModel.planRoute.spotLine = piece }
        override fun appReturned(inPlan: Boolean) {
            mapData.onReturn(inPlan = inPlan) // T3 in Plan: the next usable fix checks the own area
            sync.request("back in the app")
        }
        override fun appLeft() { mapData.onLeave() }
    }
    val map: com.onpath.app.ui.MapViewSlice = com.onpath.app.ui.MapViewSlice(notices, mapCamera,
        { tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) } }, mapHost, viewModelScope,
        uptimeMs = { android.os.SystemClock.uptimeMillis() }, elapsedMs = { android.os.SystemClock.elapsedRealtime() })

    // ---------------- map cells (docs/design/map-cells.md §9, §19) ----------------

    /** Downloaded map cells: requests, states, installed list (UI: card, Settings list – see map-cells.md §19). */
    val cells: com.onpath.app.cells.MapCells by lazy { com.onpath.app.cells.AppCells.get(getApplication()) }

    // ---------------- demo location (Developer tools → Demo, recording/DemoLocation) ----------------

    /**
     * The developer-tools demo rides ([DemoRides] in `:app`; the decisions are [DemoRules] in `:state`): "Demo: ride",
     * Navigate's "▶ Sim", a recorded tour ridden again, the map centre.
     */
    val demo: DemoRides by lazy {
        DemoRides(viewModelScope, Dispatchers.IO, db, object : DemoRidesHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown
            override val activity: Activity get() = this@AppViewModel.planRoute.activity
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val tours: List<Tour> get() = this@AppViewModel.routes.tours
            override fun planFactors() = this@AppViewModel.planRoute.planFactors()
            override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.notices.sayInfo(text, place)
            override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.notices.sayError(text, place)
            override fun initialCamera() = this@AppViewModel.initialCamera()
        })
    }

    /** Settings → Diagnostics → "Map cells (debug)": request the cell under the map centre (own-point rule). */
    fun requestCellAtMapCentre() {
        val (lat, lon, _) = initialCamera()
        val c = com.onpath.app.cells.CellId.at(lat, lon) ?: return
        val added = cells.request(listOf(c), com.onpath.app.cells.CellReason.POINT)
        notices.sayInfo(if (added.isEmpty()) "Cell ${c.id} is already on the phone or requested." else "Cell ${c.id} requested – see the list below.",
            NoticePlace.SETTINGS)
    }

    /** Map cells + routing data on the phone: [MapDataFlow], forwarded member by member (the UI and the E2E tests use these). */
    val mapData: MapDataFlow by lazy {
        MapDataFlow(getApplication(), db, helperScope, { cells }, object : MapDataFlow.Host {
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val waypoints: List<GeoPoint> get() = this@AppViewModel.plan.points
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val tracker: LocationTracker get() = this@AppViewModel.tracker
            override val settings: AppSettings get() = this@AppViewModel.settingsSlice.settings
            override val missing: List<DataFiles.Check> get() = this@AppViewModel.missing
            override val startModeKnown: Boolean get() = this@AppViewModel.map.startModeKnown
            override val searchMark: com.onpath.app.search.Place? get() = this@AppViewModel.searchFlow.searchMark
            override val activity: com.onpath.app.routing.Activity get() = this@AppViewModel.planRoute.activity
            override var routeError: String?
                get() = this@AppViewModel.routeError
                set(v) { this@AppViewModel.routeError = v }
            override var dataBusy: String?
                get() = this@AppViewModel.dataBusy
                set(v) { this@AppViewModel.dataBusy = v }
            override fun initialCamera() = this@AppViewModel.initialCamera()
            override fun clearSearchRing() { this@AppViewModel.searchFlow.searchMark = null; searchFlow.searchPlace = null }
            override fun placeName(at: GeoPoint): String? = searchFlow.stopName(at) ?: planEdit.nearbyNames[at]
            override suspend fun offlineIndex() = search.offlineIndex()
            override fun invalidateEngine() { planRoute.engine = null }
            override fun routingDataArrived(names: List<String>) = this@AppViewModel.routingDataArrived(names)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.notices.say(text, kind, place)
        })
    }
    /** The first-map card on the map (owner 2026-11-08): the map for here still loading / offline after the setup. */
    fun firstMapCard(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                     installed: List<com.onpath.app.cells.CellStore.InstalledCell>): FirstMapCard.Ui? =
        FirstMapCard.of(mapData.cellJob, states, installed.associate { it.id to it.totalBytes }, mapData.viewWithoutDetail, setup.setupOpen,
            plan = tourSession.mode == AppMode.PLAN, available = cells.downloadsAvailable)

    /**
     * T1: the user set these points – a point that makes the plan very long (owner) loads nothing until the long-plan
     * question is answered: Plan anyway → it loads then; Cancel → nothing was loaded.
     */
    private fun cellsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) {
        if (after.size >= 2 && after.toList() != planRun.routeRun.longOk && com.onpath.app.routing.LongRoute.needsAsk(after.toList(), planRoute.activity)) {
            planRun.longRouteDeferred = before.toList()
            Problems.trace("cells", "Very long plan: maps and routing data for the new point wait for the answer")
            return
        }
        planRun.longRouteDeferred = null
        mapData.pointsSet(before, after)
    }

    /**
     * Main thread: routing data arrived – the plan is computed again at once (no question: this is planning, not
     * re-routing a tour), so the straight line becomes a route with elevation.
     */
    private fun routingDataArrived(names: List<String>) {
        Problems.info("routing", "Routing data installed (${names.joinToString()}); plan computed again")
        recheckData()
        planRoute.engine = null // a new engine reads the new tile
        // once after the batch, not per arriving part (a recalc can take up to a minute on a long plan)
        arrivedNames += names
        arrivalGateJob = gated(routingArrivalGate, arrivalGateJob, busy = { mapData.routingLoading }) {
            val n = arrivedNames.size; arrivedNames.clear()
            syncedRouting.routeWaiting()
            if (RoutingLoads.recalcOnArrival(tourSession.mode, recState.value != null, plan.points.size)) {
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
    /**
     * The plan on the map as one object ([com.onpath.app.ui.plan.PlanDocument], cleanup piece 4): its points, Undo / Redo,
     * the pinned points and the saved route it belongs to – written to the preferences as it changes, read back at start.
     */
    val plan: com.onpath.app.ui.plan.PlanDocument = com.onpath.app.ui.plan.PlanDocument(com.onpath.app.ui.plan.PrefsPlanStore(prefs))
    /**
     * Plan links in and out ([com.onpath.app.ui.sync.PlanLinkFlow], cleanup piece 4): a link opened in OnPath, what the
     * last link brought (its name, its points), a plan sent as a link. [receivePlanLink] … forward to it.
     * Declared up here, above the init blocks: a route result at start reaches it ([com.onpath.app.ui.sync.PlanLinkFlow.snapped]).
     */
    val links: com.onpath.app.ui.sync.PlanLinkFlow = com.onpath.app.ui.sync.PlanLinkFlow(object : com.onpath.app.ui.sync.PlanLinkHost {
        override fun backToMap() {
            routes.open = false; settingsSlice.showSettings = false; mapData.offlineResult = null; planRoute.timeDetails = null; planEdit.routeDetailsOpen = false; searchFlow.searchOpen = false
            placeMenus.closeMapOverlays()
        }
        override fun sayError(text: String, place: NoticePlace) { this@AppViewModel.notices.sayError(text, place) }
        override fun receivePairing(secret: ByteArray, via: String) { this@AppViewModel.pairing.receivePairing(secret, via) }
        override fun openSyncSettings() { settingsSlice.openSettings(SettingsPage.SYNC) }
        override suspend fun startModeKnown() {
            if (!this@AppViewModel.map.startModeKnown) androidx.compose.runtime.snapshotFlow { this@AppViewModel.map.startModeKnown }.first { it }
        }
        override fun navigating(): Boolean = routesGo.navigating(recState.value != null)
        override fun planPoints(): List<GeoPoint> = plan.points.toList()
        override fun savedPoints(): List<GeoPoint>? = this@AppViewModel.plan.savedPoints
        override fun open(plan: com.onpath.app.routing.SharedPlan, via: String) { if (routesFlow.shown != null) routesFlow.closeShown(); routesGo.openSharedPlan(plan, via) }
        override fun share(link: String, title: String?) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, link)
                title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            events += UiEvent.Share(Intent.createChooser(send, Txt.s(Res.string.plan_share_plan)))
        }
        override var storedLinkPoints: List<GeoPoint>?
            get() = prefs.planLinkPoints
            set(v) { prefs.planLinkPoints = v }
    }, viewModelScope)
    /** Followed routes replaced by plan edits during the tour (Undo takes them back exactly, Bug 02). */
    private val followHistory = FollowHistory()

    /**
     * A tap on the open map in Plan (owner 2026-11-03, novice fix): the plan sheet says "Gedrückt halten, um hier einen
     * Punkt zu setzen." ([MapGestures.tapShowsHoldHint]). A line that stays until the user acts – a hold on the map, a
     * plan change, a sheet drag, a mode switch – never gone by itself (docs/design/ui-style.md, no flashing).
     */
    var tapHoldHint by mutableStateOf(false)
    fun showTapHoldHint() {
        if (!tapHoldHint) com.onpath.app.debug.Problems.trace("plan", "Tap on the map: 'hold to set a point' shown")
        tapHoldHint = true
        if (planEdit.planSize == PlanSize.COLLAPSED) planEdit.planSize = PlanSize.MINI // the line sits in the summary step
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
            if (planEdit.planSize == PlanSize.COLLAPSED) planEdit.planSize = PlanSize.MINI // the line sits in the summary step
        }
    }
    /** Blocking: what [OfflineReady] says for [line] (MapScreen calls it on Dispatchers.IO). */
    fun computeOfflineReady(line: List<GeoPoint>): OfflineReady.Result? = mapData.offlineReady(line, planRoute.activity)

    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    /**
     * The plan's route run ([PlanRunSlice], rebuild phase 2 piece 9): the [RouteRun] state (changed only by [routeEvent]), its
     * effects, the run's job / cancel signal / wait card progress, Cancel and the two answers. Declared before init {}
     * (recalc() runs there, ViewModelInitOrderTest); the run's coroutine itself is [recalc].
     */
    val planRun: com.onpath.app.ui.plan.PlanRunSlice = com.onpath.app.ui.plan.PlanRunSlice(
        runCauses = planRoute.runCauses,
        host = object : com.onpath.app.ui.plan.PlanRunHost {
            override fun points(): List<GeoPoint> = plan.points.toList()
            override fun clearRoute() { route = null; routeError = null }
            override fun noCorridor() = mapData.corridorFollowsPlan(null)
            override fun loadDeferredMaps(before: List<GeoPoint>) = cellsForNewPoints(before, plan.points)
            override fun recalc() = this@AppViewModel.recalc()
            override fun cancelRun(why: NotRouted) = planEdit.cancelRun(why)
        },
    )
    /**
     * The plan's points edited ([PlanEditSlice], rebuild phase 2 piece 10): the one [change] every edit goes through, the
     * Points list, reverse / round trip, Undo / Redo, Clear, Cancel's take-back and the names of unnamed points. The tour
     * hooks (what an edit means for a tour in progress) stay below behind [com.onpath.app.ui.plan.PlanEditHost]. Declared
     * before init {} and before [multiDay] (which gets [change]).
     */
    val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(
        plan = plan, planRun = planRun, runCauses = planRoute.runCauses,
        host = object : com.onpath.app.ui.plan.PlanEditHost {
            override fun cancelHoldMeasure(why: String) = placeAdd.cancelHoldMeasure(why)
            override fun stopFollowingWhenMapTurns() { if (tourSession.mode == AppMode.PLAN && settingsSlice.settings.navHeadingUp && map.followMe) map.followMe = false }
            override fun closePointMenu(why: String) = placeMenus.closePointMenu(why)
            override fun clearFirstPointMenu() { placeAdd.firstPointMenu = null }
            override fun clearHoldAddMenu() { placeAdd.holdAddMenu = null }
            override fun clearHighlight() { highlight = null }
            override fun removePin(why: String) = placeMenus.removePin(why)
            override fun menuAction(what: String): Int? = placeMenus.menuAction(what)
            override fun setRoutingSpot(spot: GeoPoint?) { routingSpot = spot }
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = cellsForNewPoints(before, plan.points)
            override fun keepAsDraft(why: String) = save.keepAsDraft(why)
            override fun routeLine(): List<GeoPoint>? = route?.points
            override fun recalc() = this@AppViewModel.recalc()
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = this@AppViewModel.restoreActivity(c)
            override fun planChanged() = this@AppViewModel.planRoute.planChanged()
            override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()
            override fun touring(): Boolean = recState.value != null
            override fun following(): Boolean = tourFollow.followLine != null
            override fun followIsTrack(): Boolean = this@AppViewModel.tourFollow.followIsTrack
            override fun followsLine(line: List<GeoPoint>): Boolean = tourFollow.followLine == line
            override fun followFromPlan(): Boolean = this@AppViewModel.tourFollow.followFromPlan
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = tourEdits.splitOf(before, after)
            override fun sayNoDestination() = sayEvent(RecordingService.SAY_NO_DESTINATION)
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = tourEdits.oneLeftGoesFromHere(before, mayEmpty)
            override fun endGuidanceIfPlanEmpty() = tourEdits.endGuidanceIfPlanEmpty()
            override fun clearFollow() = this@AppViewModel.tourFollow.clearFollow()
            override fun guidanceEndedByEmptyPlan() { this@AppViewModel.tourFollow.guidanceEndedByEmptyPlan = true }
            override fun stopName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)
            override suspend fun pointNames(): com.onpath.app.ui.plan.PointNames {
                val index = search.offlineIndex()
                return com.onpath.app.ui.plan.PointNames { p ->
                    com.onpath.app.search.RouteNamer.label(p, com.onpath.app.search.RouteNamer.placesNear(index, listOf(p)))
                }
            }
            override var searchMark: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchFlow.searchMark
                set(v) { this@AppViewModel.searchFlow.searchMark = v }
        },
        scope = viewModelScope, io = Dispatchers.IO,
    )
    /**
     * Adding points ([PlaceAddSlice], rebuild phase 2 piece 11, core feature C2): where a new point goes, the add entries of a
     * place / a held spot, the hold on the map with its menu and the measuring on the router's paths, the places added from the
     * menu / the card, the first-point menu. The POI / point / pin menus, the place card and the kept places ([placeMenus],
     * [keptPlaces], piece 12) and the tour hooks stay here behind [com.onpath.app.ui.plan.PlaceAddHost]. Declared after [planEdit] (which it edits through) and
     * above the init blocks that can reach it (ViewModelInitOrderTest).
     */
    /** The router the hold and a held place's menu measure on (the engine, its leg cache and the Planner stay here, [routedLength]). */
    private val holdRouter = com.onpath.app.ui.plan.HoldRouter { a, b, act, level, cancel -> routedLength(a, b, act, level, cancel) }
    val placeAdd: PlaceAddSlice = PlaceAddSlice(
        plan = plan, planEdit = planEdit, planRun = planRun,
        host = object : com.onpath.app.ui.plan.PlaceAddHost {
            override fun closeMapOverlays(why: String) = placeMenus.closeMapOverlays(why)
            override fun openPointMenu(index: Int, via: String) = placeMenus.openPointMenu(index, via = via)
            override fun closePlaceCard() = placeMenus.closePlaceCard()
            override fun planMode(): Boolean = tourSession.mode == AppMode.PLAN
            override fun canEditMap(): Boolean = this@AppViewModel.canEditMap
            override fun activity(): Activity = this@AppViewModel.planRoute.activity
            override fun sac(): Int = this@AppViewModel.planRoute.sac
            override fun routeLine(): List<GeoPoint>? = route?.points
            override fun touring(): Boolean = recState.value != null
            override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()
            override fun tourPassed(plan: List<GeoPoint>): Set<Int> = tourEdits.tourPassed(plan)
            override fun followLine(): List<GeoPoint>? = this@AppViewModel.tourFollow.followLine
            override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()
            override fun gpsPoint(): GeoPoint? = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchFlow.searchMark
            override fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? = this@AppViewModel.takePin(lat, lon)
            override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.searchFlow.isCandidate(poi)
            override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.searchFlow.rememberPlaceName(p, name)
            override fun rememberPoiKind(p: GeoPoint, kind: String?) = placeMenus.rememberPoiKind(p, kind)
            override fun stopIndexAt(lat: Double, lon: Double): Int = plan.points.indexOfFirst { sameSpot(it, lat, lon) }
            override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)
        },
        router = holdRouter,
        holdWorker = planRoute.threads.hold, scope = viewModelScope,
    )
    /**
     * The menus of a held place / a route point / the found place's pin, the place card and the POI kinds of pinned points
     * ([PlaceMenusSlice], rebuild phase 2 piece 12, core feature C19): which menu is open and its rows (on the same router as
     * the hold), closing every map menu, what the card says. The hold's own state stays in [placeAdd]; the search's pin and
     * picked place in the search flow. Declared right after [placeAdd] (its host calls it) and above the init blocks.
     */
    val placeMenus: com.onpath.app.ui.plan.PlaceMenusSlice = com.onpath.app.ui.plan.PlaceMenusSlice(
        plan = plan, planRun = planRun, placeAdd = placeAdd,
        host = object : com.onpath.app.ui.plan.PlaceMenusHost {
            override fun planMode(): Boolean = tourSession.mode == AppMode.PLAN
            override fun touring(): Boolean = recState.value != null
            override fun activity(): Activity = this@AppViewModel.planRoute.activity
            override fun sac(): Int = this@AppViewModel.planRoute.sac
            override fun route(): PlannedRoute? = this@AppViewModel.route
            override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()
            override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)
            override var searchMark: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchFlow.searchMark
                set(v) { this@AppViewModel.searchFlow.searchMark = v }
            override var searchPlace: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchFlow.searchPlace
                set(v) { this@AppViewModel.searchFlow.searchPlace = v }
            override suspend fun offlineIndex(): com.onpath.app.ui.plan.PlaceLookup {
                val index = search.offlineIndex()
                return com.onpath.app.ui.plan.PlaceLookup { minLat, minLon, maxLat, maxLon, kinds -> index.placesIn(minLat, minLon, maxLat, maxLon, kinds) }
            }
        },
        router = holdRouter, io = Dispatchers.IO, scope = viewModelScope,
    )
    /**
     * The kept places: Zuhause + favourites ([KeptPlacesSlice], piece 12, core feature C19). The store (`SearchStore`: its
     * file, `Backups`, the browser sync) stays here behind [com.onpath.app.ui.plan.PlacesStore]; each member passes the old
     * arguments on unchanged.
     */
    val keptPlaces: com.onpath.app.ui.plan.KeptPlacesSlice = com.onpath.app.ui.plan.KeptPlacesSlice(
        store = object : com.onpath.app.ui.plan.PlacesStore {
            override fun saved(): com.onpath.app.search.SavedPlaces = searchStore.saved()
            override fun update(change: (com.onpath.app.search.SavedPlaces) -> com.onpath.app.search.SavedPlaces): com.onpath.app.search.SavedPlaces =
                searchStore.update(change = change)
            override fun backupChanged() = com.onpath.app.backup.Backups.placesChanged(getApplication())
            override fun syncChanged() = sync.changed("kept places")
        },
        host = object : com.onpath.app.ui.plan.KeptPlacesHost {
            override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchFlow.searchMark
            override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.searchFlow.isCandidate(poi)
        },
    )
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
        get() = route?.takeIf { !planRun.routing }?.let { r ->
            val w = plan.points.toList()
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

    /** The last route result applied ([recalc]; routing/PlanResult – the same computation as the web planner's). */
    private var planResult: com.onpath.app.routing.PlanResult? = null

    // Bursts → one action; declared before init {} (ViewModelInitOrderTest).
    /** Map style reload after new map store generations: once per quiet period, never while the user drags. */
    private val styleGate = QuietGate(STYLE_QUIET_MS, STYLE_MAX_WAIT_MS)
    private var styleGateJob: kotlinx.coroutines.Job? = null
    /** The plan recomputed after routing data arrived: once after a batch, not per part. */
    private val routingArrivalGate = QuietGate(ARRIVAL_QUIET_MS, ARRIVAL_MAX_WAIT_MS, maxBusyMs = ARRIVAL_MAX_WAIT_MS)
    private var arrivalGateJob: kotlinx.coroutines.Job? = null
    private val arrivedNames = ArrayList<String>()

    // --- saved state of the plan (menus / Manage routes) ---
    /**
     * Save (C18, [SaveSlice] in `:state`, rebuild phase 2 piece 3): the name dialog, the writes after it, Rename / star in
     * My routes, a plan that leaves the map unsaved, the save at "Los geht's". The database behind [toursStore], the rest of
     * the app behind [saveHost]. Declared after [plan], [routes] and [recent] (which it works on); the names below
     * ([saveAsk], [routesProblem], [requestSave] …) forward to it.
     */
    private val toursStore = object : com.onpath.app.ui.routes.ToursStore {
        override fun getTour(id: Long) = db.getTour(id)
        override fun loadSegments(id: Long) = db.loadSegments(id)
        override fun insertPlanned(name: String, row: com.onpath.app.ui.routes.PlannedRow) =
            db.insertTour(kind = TourKind.PLANNED, name = name, activity = row.activity, sac = row.sac,
                distance = row.distanceM, ascent = row.ascentM, descent = row.descentM, duration = row.durationS,
                segments = row.segments, waypoints = row.stops, pinned = row.pins, difficulty = row.difficulty, days = row.days)
        override fun replacePlanned(id: Long, row: com.onpath.app.ui.routes.PlannedRow) =
            db.replacePlanned(id = id, activity = row.activity, sac = row.sac,
                distance = row.distanceM, ascent = row.ascentM, descent = row.descentM, duration = row.durationS,
                segments = row.segments, waypoints = row.stops, pinned = row.pins, difficulty = row.difficulty, days = row.days)
        override fun rename(id: Long, name: String) = db.rename(id, name)
        override fun setFavourite(id: Long, favourite: Boolean) = db.setFavourite(id, favourite)
        override fun planPoints(id: Long) = db.planPoints(id)
        override fun listTours() = db.listTours()
        override fun getTourPlan(id: Long) = db.getTourPlan(id)
        override fun insertDraftPlanned(name: String, row: com.onpath.app.ui.routes.PlannedRow) = db.insertTour(kind = TourKind.PLANNED, name = name,
            activity = row.activity, sac = row.sac, distance = row.distanceM, ascent = row.ascentM, descent = row.descentM, duration = row.durationS,
            segments = row.segments, waypoints = row.stops, pinned = row.pins, difficulty = row.difficulty, draft = true, days = row.days)
        override fun tourTimes() =
            com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).all()
    }
    /** Routes from the browser on the phone ([com.onpath.app.ui.sync.SyncedRoutingSlice], rebuild phase 2 piece 15); screens read it as `vm.<slice>` (declared here: `recalc()` of the init reaches it). */
    private val syncedRouting: com.onpath.app.ui.sync.SyncedRoutingSlice = com.onpath.app.ui.sync.SyncedRoutingSlice(plan, planRoute, toursStore,
        object : com.onpath.app.ui.sync.SyncedRoutingHost {
            override fun opened(uuid: String, points: List<GeoPoint>) = sync.opened(uuid, points)
            override fun refreshRoutingTiles(tiles: List<String>, why: String) = this@AppViewModel.refreshRoutingTiles(tiles, why)
            override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
            override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = this@AppViewModel.tourFollow.setSaved(name, id, points)
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        }, viewModelScope, Dispatchers.IO)

    internal val multiDay = com.onpath.app.ui.plan.MultiDaySlice(plan, planRoute, toursStore, com.onpath.app.ui.plan.PrefsMultiDay(prefs), mapCamera,
        com.onpath.app.ui.plan.AppMultiDayHost(this) { mapData }, { b -> planEdit.change(b) }, { n, i, p -> tourFollow.setSaved(n, i, p) }, { p, n -> searchFlow.rememberPlaceName(p, n) }, { planEdit.nearbyNames }, viewModelScope, Dispatchers.IO, Dispatchers.Default) // :state, piece 20; after toursStore
    private val saveHost = object : com.onpath.app.ui.routes.SaveHost {
        override val route: PlannedRoute? get() = this@AppViewModel.route
        override val activity: Activity get() = this@AppViewModel.planRoute.activity
        override val sac: Int get() = this@AppViewModel.planRoute.sac
        override fun stops(points: List<GeoPoint>) = savedStops(points)
        override fun difficulty(route: PlannedRoute?, activity: Activity) = planRoute.difficultyOf(route, activity)
        override fun days(points: List<GeoPoint>) = multiDay.daysFor(points)
        override fun keepWhole() = multiDay.keepWhole()
        override fun overwriteWhole(name: String, then: (() -> Unit)?) = multiDay.overwriteWhole(name, then)
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?) = this@AppViewModel.proposeName(points, route)
        override fun datedName() = defaultName(Txt.s(Res.string.msg_name_route))
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = planRoute.storePlanTimes(id, route, activity)
        override fun forgetTimes(id: Long) { planRoute.plannedTimes.remove(id) }
        override fun copyTimes(from: Long, to: Long, distanceM: Double) { planTimes.get(from, distanceM)?.let { planTimes.put(to, distanceM, it) } }
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun syncChanged(why: String) = sync.changed(why)
        override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)
        override fun shownRenamed(id: Long, name: String) { routesFlow.shown?.let { if (it.tourId == id) routesFlow.shown = it.copy(name = name) } }
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)
    }
    val save: com.onpath.app.ui.routes.SaveSlice = com.onpath.app.ui.routes.SaveSlice(plan, routes, recent, toursStore, saveHost, viewModelScope, Dispatchers.IO)

    // --- shown track / follow ---
    /** `nav-session.json` + `follow.json`, shared with the service: the app's writes are one locked step each ([FollowStore]). */
    private val followStore = FollowStore(app.filesDir)
    /**
     * Routes I ([com.onpath.app.ui.routes.RoutesSlice], rebuild phase 2 piece 13): My routes' list read again, a tour or route
     * opened on the map, the shown track, a track made a plan, Delete. The database behind [toursStore] (the one Save uses),
     * the rest of the app behind [routesHost]; screens read it as `vm.<slice>` (piece 21).
     */
    private val routesHost = object : com.onpath.app.ui.routes.RoutesHost {
        override val touring: Boolean get() = recState.value != null
        override val recordingTourId: Long? get() = recState.value?.tourId
        override val mode: AppMode get() = this@AppViewModel.tourSession.mode
        override fun enterMode(m: AppMode) = tourSession.enter(m)
        override fun syncMarkSeen(t: Tour) = this@AppViewModel.syncMarkSeen(t)
        override fun rememberActivity(a: Activity) { prefs.activity = a }
        override fun rememberSac(level: Int) { prefs.sac = level }
        override fun loadedOnTheWay(on: Boolean) { tourEdits.loadedOnTheWay = on }
        override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }
        override fun change(block: MutableList<GeoPoint>.() -> Unit) = this@AppViewModel.planEdit.change(block)
        override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.searchFlow.rememberPlaceName(p, name)
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)
        override fun opened(t: Tour, pts: List<GeoPoint>) = multiDay.opened(t, pts)
        override fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) = this@AppViewModel.syncedRouting.syncRoutingCheck(t, pts)
        override fun standIn(pts: List<GeoPoint>, act: Activity, level: Int, preview: com.onpath.app.routing.StoredLine.Preview) {
            planRecalc.standIn = com.onpath.app.ui.plan.PlanRecalcSlice.StandIn(pts, act, level, preview)
        }
        override fun withMaps(t: Tour) = multiDay.withMaps(t)
        override fun kickThumbs() = this@AppViewModel.pictures.kickThumbs()
        override fun clearHighlight() { highlight = null }
        override fun followShownNow() = tourEdits.followShownNow()
        override fun ensureRoutingData(parts: List<String>) = mapData.routingData.ensure(parts)
        override fun routingParts(track: List<GeoPoint>, act: Activity) = com.onpath.app.data.RoutingTiles.forLine(track, act)
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun syncForget(uuid: String) { sync.files.marks.remove(uuid) }
        override fun syncChanged(why: String) = sync.changed(why)
        override val route: PlannedRoute? get() = this@AppViewModel.route
        override val routing: Boolean get() = this@AppViewModel.planRun.routing
        override val followIsTrack: Boolean get() = this@AppViewModel.tourFollow.followIsTrack
        override fun undoStep() = this@AppViewModel.planEdit.undoStep()
        override fun continueNavigation() = this@AppViewModel.tourSession.continueNavigation()
        override suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = this@AppViewModel.share(name, gpx)
        override fun stopName(p: GeoPoint) = this@AppViewModel.searchFlow.stopName(p)
        override fun holdCameraForSharedPlace() = this@AppViewModel.searchFlow.holdCameraForSharedPlace()
        override var followMe: Boolean
            get() = this@AppViewModel.map.followMe
            set(v) { this@AppViewModel.map.followMe = v }
        override var freeLookAt: GeoPoint?
            get() = this@AppViewModel.map.freeLookAt
            set(v) { this@AppViewModel.map.freeLookAt = v }
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)
    }
    val routesFlow: com.onpath.app.ui.routes.RoutesSlice = com.onpath.app.ui.routes.RoutesSlice(plan, routes, save, tour, planRoute, toursStore, mapCamera, routesHost,
        viewModelScope, Dispatchers.IO, Dispatchers.Main, elapsedMs = { android.os.SystemClock.elapsedRealtime() })
    /**
     * Routes II ([com.onpath.app.ui.routes.RoutesGoSlice], rebuild phase 2 piece 14): what a tap / "Los geht's" in My routes does,
     * a tour's followed plan, the review, plan links in and out. Same host and store as [routesFlow]; starting or changing a
     * tour's following stays here (`startNavigation`, `continueNavigation` …), reached through [routesHost].
     */
    val routesGo: com.onpath.app.ui.routes.RoutesGoSlice = com.onpath.app.ui.routes.RoutesGoSlice(plan, routes, tour, planRoute, save, toursStore, links, routesFlow, mapCamera,
        routesHost, viewModelScope, Dispatchers.IO)

    // --- position / camera: the slice is `map` ([MapViewSlice]); screens read it as `vm.<slice>` (piece 21) ---

    // --- dialogs / messages ---
    fun sayError(text: String, place: NoticePlace) = notices.sayError(text, place)
    /** Settings → Updates, or the update banner on the map. */
    private fun updatePlace() = if (settingsSlice.showSettings) NoticePlace.SETTINGS else NoticePlace.MAP

    /**
     * The box "Tour gespeichert" → "In Meine Touren ›": the box goes and My routes opens on Gemacht with the tour marked
     * "✓ Gerade gespeichert" – what the button says (field bug 2026-11-06 22:30: it opened the 0 m tour on the map, with
     * "Als Plan übernehmen" for a tour without any line).
     */
    fun openSavedTour() {
        val t = tour.closeFinished() ?: return
        Problems.trace("tour", "Saved box: In Meine Touren → My routes → Gemacht, tour ${t.id} marked")
        routesFlow.refreshTours()
        routes.showSaved(t.id)
    }

    /** The box "Tour gespeichert" → OK. */
    fun closeSavedTour() {
        tour.closeFinished()?.let { Problems.trace("tour", "Saved box: OK (tour ${it.id})") }
    }
    var events = mutableStateListOf<UiEvent>()
        private set

    val recState: StateFlow<RecState?> = recording.state

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
            geocoder = com.onpath.app.search.AndroidPlaceGeocoder(getApplication()),
            storageReady = { dataFiles.ready() }) {
            val loc = tracker.location.value
            val (cLat, cLon, _) = initialCamera()
            // Position when known and near what the user looks at, else the map centre.
            val usePos = loc != null && Geo.distance(loc.latitude, loc.longitude, cLat, cLon) < 30_000
            com.onpath.app.ui.search.SearchContext(if (usePos) loc!!.latitude else cLat, if (usePos) loc!!.longitude else cLon,
                if (routesFlow.shown == null) route?.points else null)
        }
    }
    /** Search + shared places: [SearchFlow], forwarded member by member below
     * (the UI and the E2E tests use these); declared above the init that calls recalc() (stopName). */
    val searchFlow: SearchFlow by lazy {
        SearchFlow(helperScope, { search }, { searchStore }, object : SearchFlow.Host {
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override val startModeKnown: Boolean get() = this@AppViewModel.map.startModeKnown
            override val canEditMap: Boolean get() = this@AppViewModel.canEditMap
            override val mapOverlayOpen: Boolean get() = this@AppViewModel.placeMenus.mapOverlayOpen
            override val routesOpen: Boolean get() = this@AppViewModel.routes.open
            override val showSettings: Boolean get() = this@AppViewModel.settingsSlice.showSettings
            override val loadModalOpen: Boolean get() = this@AppViewModel.mapData.loadModalOpen
            override val waypoints: List<GeoPoint> get() = this@AppViewModel.plan.points
            override fun historyPoints(): Set<GeoPoint> = plan.historyPoints()
            override fun enter(m: AppMode) = this@AppViewModel.tourSession.enter(m)
            override fun closeMapOverlays(why: String) = this@AppViewModel.placeMenus.closeMapOverlays(why)
            override fun backToMap() {
                this@AppViewModel.routes.open = false; this@AppViewModel.settingsSlice.showSettings = false
                mapData.offlineResult = null; planRoute.timeDetails = null; planEdit.routeDetailsOpen = false
            }
            override fun closeShownTrack() { if (routesFlow.shown != null) routesFlow.closeShown() }
            override fun flyTo(at: GeoPoint, zoom: Double) {
                map.followMe = false; map.freeLookAt = null
                // an intended fly to a found place: the start camera must not jump back to a late first fix afterwards
                // (field 2026-11-07, S10: the far destination left the screen once its map had loaded)
                map.startCameraDone = true; map.userMovedMap = true
                events += UiEvent.CenterOn(at, zoom)
            }
            override fun loadMapsFor(at: GeoPoint, name: String) = mapData.placeSearched(at, name)
            override fun stopFollowing() { map.followMe = false }
            override fun holdStartCamera() { map.startCameraDone = true; map.userMovedMap = true }
            override fun openPoiMenu(poi: com.onpath.app.poi.Poi) = this@AppViewModel.placeMenus.openPoiMenu(poi)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.notices.say(text, kind, place)
        })
    }

    /**
     * What the tour pieces write into the tour database ([com.onpath.app.ui.tour.TourRows]): over `TourDb`, every argument by
     * name (pinned in `TourWiringTest`). Shared by the activity check (below), the tour's end and the follow.
     */
    private val tourRows = object : com.onpath.app.ui.tour.TourRows {
        override fun findActiveRecording() = db.findActiveRecording()
        override fun updateActivity(id: Long, activity: String, sac: Int) = db.updateActivity(tourId = id, activity = activity, sac = sac)
        override fun updateStats(id: Long, distance: Double, ascent: Double, descent: Double, duration: Double, state: String) =
            db.updateStats(tourId = id, distance = distance, ascent = ascent, descent = descent, duration = duration, state = state)
        override fun setDeleted(id: Long, deletedMs: Long?) = db.setDeleted(id = id, deletedMs = deletedMs)
        override fun setTourPlan(id: Long, plan: com.onpath.app.data.TourPlan) = db.setTourPlan(tourId = id, plan = plan)
        override fun rides(): com.onpath.app.rides.RideStore = com.onpath.app.rides.TourDbRides(db)
    }

    /**
     * What the tour follows ([com.onpath.app.ui.tour.TourFollow] in `:state`, rebuild phase 2 piece 18a): the follow state, its
     * writes (files, then the command to the service), the copy of the followed plan kept with the recorded tour (written on the
     * ONE serial thread [planCopyDispatcher], in the order the plan changed; a failure is loud in the problem log and tried
     * again), the service's other activity taken over. Declared with the other tour slices; the `init` below starts its tour-id
     * collector (what lay between that collector's old place and this one are declarations only: lazies and plain properties).
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)
    val tourFollow: com.onpath.app.ui.tour.TourFollow = com.onpath.app.ui.tour.TourFollow(
        plan = plan, planRoute = planRoute, recording = recording, files = followStore,
        prefs = object : com.onpath.app.ui.tour.FollowPrefs {
            override var followIsTrack: Boolean
                get() = prefs.followIsTrack
                set(v) { prefs.followIsTrack = v }
        },
        rows = tourRows,
        host = object : com.onpath.app.ui.tour.TourFollowHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val shown: ShownTrack? get() = this@AppViewModel.routesFlow.shown
            override fun placeName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)
            override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun stopsAsSaved(pts: List<GeoPoint>) = this@AppViewModel.savedStops(pts)
            override fun savedTourUuid(id: Long): String? = db.getTour(id)?.uuid?.takeIf { it.isNotEmpty() }
            override fun invalidateSplits() = tourEdits.splitRequests.invalidate()
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        },
        scope = viewModelScope, planCopyDispatcher = planCopyDispatcher,
    )

    /**
     * The tour's session ([com.onpath.app.ui.tour.TourSession] in `:state`, rebuild phase 2 piece 19): the mode and what entering
     * one does, Start / Continue, the seven answers to the service's questions, and the start-up work with the collectors of the
     * service's state (`startFailed`, `state`). Declared right after the follow slice (the edits slice's host reaches it through
     * `resumeNavigation`, and `ViewModelInitOrderTest` follows that edge from the small `init`); the big `init` starts it
     * ([com.onpath.app.ui.tour.TourSession.start]) at the place that code always stood.
     */
    val tourSession: com.onpath.app.ui.tour.TourSession = com.onpath.app.ui.tour.TourSession(
        plan = plan, planEdit = planEdit, planRun = planRun, planRoute = planRoute, save = save, map = map, tour = tour,
        follow = tourFollow, recording = recording, rows = tourRows, tours = toursStore, followHistory = followHistory,
        host = object : com.onpath.app.ui.tour.TourSessionHost {
            override val errorCard: String? get() = this@AppViewModel.notices.errorCard
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override var shown: ShownTrack?
                get() = this@AppViewModel.routesFlow.shown
                set(v) { this@AppViewModel.routesFlow.shown = v }
            override val headingUp: Boolean get() = this@AppViewModel.settingsSlice.settings.navHeadingUp
            override val hasGpsFix: Boolean get() = tracker.location.value != null
            override fun clearHints() { tapHoldHint = false; offlineInfo = false }
            override fun closeMenusForNavigate() { placeMenus.planPoi = null; placeMenus.poiMenu = null; placeMenus.closePointMenu("mode change to Navigate"); placeAdd.firstPointMenu = null; placeAdd.holdAddMenu = null; placeAdd.cancelHoldMeasure("mode change to Navigate"); highlight = null }
            override fun secureTourCorridor(line: List<GeoPoint>, last: GeoPoint?, activity: Activity) =
                mapData.secureTourCorridor(line, last, activity)
        },
        say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,
    )

    /**
     * What a plan edit means for the tour ([com.onpath.app.ui.tour.TourEdits] in `:state`, rebuild phase 2 piece 18b): the passed
     * points, the split of an edit, the tour following the edited plan, the position → ahead route, the question "Route back to the
     * start?", one point left right here, a track followed during the tour. Declared between the follow slice and the small `init`
     * that starts it (the follow slice's host reaches `splitRequests` through it, and `ViewModelInitOrderTest` follows that edge).
     * The hosts of the plan edit, the place menus and My routes answer from it; `recalc` calls its `followCancel` / `followEditedPlan`.
     */
    val tourEdits: com.onpath.app.ui.tour.TourEdits = com.onpath.app.ui.tour.TourEdits(
        plan = plan, planEdit = planEdit, planRoute = planRoute, follow = tourFollow, recording = recording,
        position = com.onpath.app.ui.Position { myPosition() }, followHistory = followHistory,
        host = object : com.onpath.app.ui.tour.TourEditsHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override fun replan() = recalc()
            override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) = cellsForNewPoints(before, after)
            override fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = map.noPositionForEdit(note, text)
            override fun placeName(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)
            override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun routeRunEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }
            override fun closeShownTrack() = routesFlow.closeShown()
            override fun resumeNavigation() = tourSession.continueNavigation()
        },
        say = notices, scope = viewModelScope, routingDispatcher = planRoute.threads.routing,
    )

    /**
     * The route run's coroutine ([com.onpath.app.ui.plan.PlanRecalcSlice] in `:state`, rebuild phase 2 piece 9b): `recalc` with its
     * head, the result taken (`snapToRoute`), the failure said, what the tour does next. Declared after [tourEdits] (it reaches
     * `followCancel`) and above the small `init` and the big one that calls `recalc()`; its adapters touch `mapData` only when called.
     */
    private val planRecalc: com.onpath.app.ui.plan.PlanRecalcSlice = com.onpath.app.ui.plan.PlanRecalcSlice(
        plan = plan, planRun = planRun, planRoute = planRoute, planEdit = planEdit, tourEdits = tourEdits, tourFollow = tourFollow,
        map = map, links = links, recording = recording,
        mapData = object : com.onpath.app.ui.plan.MapDataPort {
            override fun postRoute(r: PlannedRoute, pts: List<GeoPoint>, activity: Activity?): com.onpath.app.ui.plan.MapDataPort.PostRoute =
                mapData.postRoute(r, pts, activity).let { com.onpath.app.ui.plan.MapDataPort.PostRoute(it.routingKeys, it.tilesMissing, it.cells) }
            override fun routingForLine(keys: List<String>) { mapData.routingForLine(keys) }
            override val routingLoading: Boolean get() = mapData.routingLoading
            override fun corridorFollowsPlan(line: List<GeoPoint>?, activity: Activity?) = mapData.corridorFollowsPlan(line, activity)
            override fun routeComputed(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, dest: GeoPoint?, activity: Activity?) =
                mapData.routeComputed(line, corridor, dest, activity)
            override fun freshenAlong(line: List<GeoPoint>, corridor: List<com.onpath.app.cells.CellId>?, activity: Activity?) =
                mapData.freshenAlong(line, corridor, activity)
        },
        searchProgress = com.onpath.app.ui.plan.RouteSearchProgress { eng, t -> (eng as? com.onpath.app.routing.BRouterEngine)?.searchProgress(t) },
        say = notices,
        host = object : com.onpath.app.ui.plan.PlanRecalcHost {
            override var route: PlannedRoute?
                get() = this@AppViewModel.route
                set(v) { this@AppViewModel.route = v }
            override var routeError: String?
                get() = this@AppViewModel.routeError
                set(v) { this@AppViewModel.routeError = v }
            override var highlight: GeoPoint?
                get() = this@AppViewModel.highlight
                set(v) { this@AppViewModel.highlight = v }
            override var planReach: com.onpath.app.routing.PlanReach?
                get() = this@AppViewModel.planReach
                set(v) { this@AppViewModel.planReach = v }
            override var planResult: com.onpath.app.routing.PlanResult?
                get() = this@AppViewModel.planResult
                set(v) { this@AppViewModel.planResult = v }
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override fun nameOfStop(p: GeoPoint): String? = this@AppViewModel.searchFlow.stopName(p)
            override val multiDayOverview: Boolean get() = multiDay.overview
            override fun menuFollowsSnap(moves: Map<GeoPoint, GeoPoint>) = placeMenus.followSnapped(moves)
            override fun checkRoutingDataFor(line: List<GeoPoint>) = this@AppViewModel.checkRoutingData(line)
        },
        scope = viewModelScope, routingDispatcher = planRoute.threads.routing, io = Dispatchers.IO, main = Dispatchers.Main, default = Dispatchers.Default,
        elapsedMs = { android.os.SystemClock.elapsedRealtime() },
    )

    init {
        // the recording tour's id: a new tour takes the plan followed at its start, its end writes the last version
        tourFollow.start()
    }

    /**
     * The tour's end ([com.onpath.app.ui.tour.TourEndSlice] in `:state`, rebuild phase 2 piece 17): the `finished` collector,
     * the end sheet's questions and writes, the short recording, Edit rides, the interrupted tour (Continue · Save · Delete).
     * Declared above the big `init` that starts its collector ([com.onpath.app.ui.tour.TourEndSlice.start], where the collector
     * always was); the screens read it as `vm.<slice>`. The follow state, the mode and the plan stay here behind its host.
     */
    val tourEnd: com.onpath.app.ui.tour.TourEndSlice = com.onpath.app.ui.tour.TourEndSlice(
        tour = tour, routes = routes, recording = recording, tours = toursStore, rows = tourRows,
        host = object : com.onpath.app.ui.tour.TourEndHost {
            override val activity: Activity get() = this@AppViewModel.planRoute.activity
            override fun activityOrNull(name: String?): Activity? = this@AppViewModel.planRoute.activityOrNull(name)
            override fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = this@AppViewModel.planRoute.tourTimes(t)
            override fun nameRecordedTour(t: Tour) = this@AppViewModel.nameRecordedTour(t)
            override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
            override fun clearFollow() = tourFollow.clearFollow()
            override fun enterMode(m: AppMode) = tourSession.enter(m)
            override fun endGuidanceFlag() { tourFollow.guidanceEndedByEmptyPlan = false }
            override fun kickThumbs() = this@AppViewModel.pictures.kickThumbs()
            override fun demoEndedWithTour() { if (com.onpath.app.recording.DemoLocation.endWithTour()) demo.navSimLine = null }
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
            override fun clearInterruptedNote() = this@AppViewModel.clearInterruptedNote()
            override val planPoints: Int get() = plan.points.size
            override fun clearPlan() = this@AppViewModel.planEdit.clearPlan()
            override var routeBackAsk: Boolean
                get() = this@AppViewModel.planEdit.routeBackAsk
                set(v) { this@AppViewModel.planEdit.routeBackAsk = v }
            override var planSize: PlanSize
                get() = this@AppViewModel.planEdit.planSize
                set(v) { this@AppViewModel.planEdit.planSize = v }
            override val shownTourId: Long? get() = routesFlow.shown?.tourId
            override fun reopenShownTour(t: Tour) = this@AppViewModel.openTour(t)
        },
        say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,
    )

    init {
        recalc()
        // every plan change is traced, so the diagnostics show what the user did
        Problems.trace("plan", "Plan restored at app start: ${plan.points.size} points" +
            (tourFollow.followName?.let { ", following \"$it\" (${tourFollow.followLine?.size ?: 0} line points)" } ?: ", nothing followed"))
        routesFlow.refreshTours() // "Load route" is enabled only when something is saved
        // the start-up work of the tour session (its class comment names it), at the place that code always stood: after recalc()
        tourSession.start()
        // the tour's end (the `finished` collector) – TourEndSlice, launched at the place the collector always was
        tourEnd.start()
    }

    fun initialCamera(): Triple<Double, Double, Double> = prefs.camera ?: Triple(46.67, 11.16, 12.0)
    fun saveCamera(lat: Double, lon: Double, zoom: Double) { prefs.camera = Triple(lat, lon, zoom) }

    /** App state for the diagnostics export (Settings → Diagnostics); never throws. */
    fun diagnosticsState(): org.json.JSONObject = try {
        val rec = recState.value
        val loc = tracker.location.value
        org.json.JSONObject()
            .put("mode", tourSession.mode.name).put("activity", planRoute.activity.name).put("sac", planRoute.sac)
            .put("waypoints", plan.points.size).put("routeMeters", route?.distanceM)
            .put("routeLegsStraight", route?.legs?.count { it.straight })
            .put("routeError", routeError)
            .put("followName", tourFollow.followName).put("followPoints", tourFollow.followLine?.size)
            .put("recording", rec?.let { org.json.JSONObject().put("tourId", it.tourId).put("paused", it.paused)
                .put("distanceM", it.distanceM).put("elapsedS", it.elapsedS).put("gpsLost", it.gpsLost)
                .put("offRouteM", it.offRouteM).put("saveFailed", it.saveFailed)
                .put("toGoS", it.time?.toGoS).put("arrivalMs", it.time?.arrivalMs) } ?: org.json.JSONObject.NULL)
            .put("gps", tracker.status().name)
            .put("phone", com.onpath.app.setup.PhoneCheck.describe(setup.phone))
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
        missing = dataFiles.missing()
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
    val updates: AppUpdates by lazy {
        AppUpdates(getApplication(), helperScope, prefs, recording = { recState.value != null },
            say = { text, kind -> notices.say(text, kind, updatePlace()) })
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

    /**
     * Import, export and backup ([ImportExport] in `:app`; the decisions are [ImportRules] in `:state`): GPX and map-data
     * import, "Export all" / Export GPX, "Restore from folder", the first-start restore offer.
     */
    val importExport: ImportExport by lazy {
        ImportExport(app, viewModelScope, Dispatchers.IO, db, object : ImportExportHost {
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles
            override val prefs: Prefs get() = this@AppViewModel.prefs
            override val cells: com.onpath.app.cells.MapCells get() = this@AppViewModel.cells
            override val sync: com.onpath.app.plansync.PlanSync get() = this@AppViewModel.sync
            override val events: MutableList<UiEvent> get() = this@AppViewModel.events
            override val activity: Activity get() = this@AppViewModel.planRoute.activity
            override val showSettings: Boolean get() = this@AppViewModel.settingsSlice.showSettings
            override var dataBusy: String?
                get() = this@AppViewModel.dataBusy
                set(v) { this@AppViewModel.dataBusy = v }
            override var restoreProgress: String?
                get() = this@AppViewModel.restoreProgress
                set(v) { this@AppViewModel.restoreProgress = v }
            override var restoreOffer: Boolean
                get() = this@AppViewModel.restoreOffer
                set(v) { this@AppViewModel.restoreOffer = v }
            override var restartForData: Boolean
                get() = this@AppViewModel.restartForData
                set(v) { this@AppViewModel.restartForData = v }
            override var styleVersion: Int
                get() = this@AppViewModel.styleVersion
                set(v) { this@AppViewModel.styleVersion = v }
            override var busy: Busy?
                get() = this@AppViewModel.notices.busy
                set(v) { this@AppViewModel.notices.busy = v }
            override var sac: Int
                get() = this@AppViewModel.planRoute.sac
                set(v) { this@AppViewModel.planRoute.sac = v }
            override var routesOpen: Boolean
                get() = this@AppViewModel.routes.open
                set(v) { this@AppViewModel.routes.open = v }
            override var savedPlaces: com.onpath.app.search.SavedPlaces?
                get() = keptPlaces.savedPlaces
                set(v) { keptPlaces.savedPlaces = v }
            override fun recheckData() = this@AppViewModel.recheckData()
            override fun packageNote() = this@AppViewModel.packageNote()
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.notices.say(text, kind, place)
            override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.notices.sayInfo(text, place)
            override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.notices.sayError(text, place)
            override fun refreshTours() = this@AppViewModel.routesFlow.refreshTours()
            override fun planFactors(a: Activity) = this@AppViewModel.planRoute.planFactors(a)
            override fun defaultName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun selectActivity(a: Activity, userChange: Boolean) = this@AppViewModel.planRoute.selectActivity(a, userChange)
            override fun showTrack(s: ShownTrack) = this@AppViewModel.routesFlow.showTrack(s)
        })
    }

    // ---------------- activity: [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    // ---------------- planning ----------------

    /** A tour event for the spoken directions (the service says it when they are on). */
    private fun sayEvent(what: String) {
        if (recState.value != null) recording.say(what)
    }

    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    private fun myPosition(): GeoPoint? =
        tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: recState.value?.track?.lastOrNull()?.lastOrNull()

    /**
     * The routed length [a] → [b] for [act] / [level] in metres (on the hold's thread): from [legCache], else one leg by the
     * [Planner] (as a plan run routes it – then in the cache for the run after the decision). Throws when the leg has
     * no route (a straight leg: no routing data, no way near the spot, an island) or [cancel] fired.
     */
    private fun routedLength(a: GeoPoint, b: GeoPoint, act: Activity, level: Int, cancel: com.onpath.app.routing.RouteCancel): Double {
        planRoute.legCache.lookup(a, b, act, level)?.let { return it.leg.distanceM }
        val r = Planner(planRoute.routingEngine(), cancel, legs = planRoute.legCache).plan(listOf(a, b), act, level)
        if (r.legs.any { it.straight }) throw com.onpath.app.routing.RoutingException(r.error ?: "no route for the leg")
        return r.distanceM
    }

    /** Eye button held: route lines and markers are hidden (position and recorded track stay). */
    var routePeek by mutableStateOf(false)

    // ---------------- map hold menus (MapGestures, ui/MapOverlays) ----------------

    fun isRoundTrip(): Boolean = PlanEdits.isRoundTrip(plan.points)
    /** Index of the round trip's ① (stops on the way before it), null without a loop ([PlanEdits.loopStart]). */
    fun loopStart(): Int? = PlanEdits.loopStart(plan.points)

    // ---------------- Points section of the full plan sheet (PointsSection, routing/PlanPoints) ----------------

    /** Rows of the Points section ([PlanEditSlice.planPointRows]). */
    fun planPointRows(): List<com.onpath.app.routing.PlanPointRow> = planEdit.planPointRows(route)

    /**
     * Points list → row tapped: the map shows that point (following stopped first – CLAUDE.md map camera rule) with a
     * short highlight; the sheet goes to half size so list and map are both visible.
     */
    fun showPointFromList(index: Int) {
        val p = plan.points.getOrNull(index) ?: return
        map.followMe = false
        planEdit.planSize = PlanSize.COMPACT
        highlight = p
        events += UiEvent.PanTo(p)
        viewModelScope.launchSafe("app", "showPointFromList") { delay(3_000); if (highlight == p) highlight = null }
    }

    /** Point menu → "Open in Google Maps" (closes the menu; the caller opens the link). */
    fun googleFromMenu(): GeoPoint? = placeMenus.menuAction("Open in Google Maps")?.let { plan.points[it] }
    // ---------------- end map hold menus ----------------

    fun isStop(poi: com.onpath.app.poi.Poi): Boolean = plan.points.any { sameSpot(it, poi.lat, poi.lon) }

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

    // --- kept places: Zuhause + favourites (owner 2026-11-15, „Orte merken“; SavedPlaces) ---

    /** The found place at ([lat], [lon]) goes into the plan: its pin goes; returns that place (null = no pin there). */
    private fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? {
        val m = searchFlow.searchMark?.takeIf { sameSpot(GeoPoint(it.lat, it.lon), lat, lon) }
        searchFlow.clearSearchMarkAt(lat, lon)
        if (m != null) Problems.trace("plan", "Pin taken into the plan: \"${m.title()}\"")
        return m
    }

    private fun routeIndexOf(place: com.onpath.app.search.Place) =
        plan.points.indexOfFirst { sameSpot(it, place.lat, place.lon) }.takeIf { it >= 0 }

    /** Adds the place as a pinned stop (passed exactly), named after the place ([PlaceAddSlice.addPlace]). */
    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) = placeAdd.addPlace(place, when (role) {
        PlaceRole.SMART -> AddRole.SMART
        PlaceRole.START -> AddRole.START
        PlaceRole.DESTINATION -> AddRole.DESTINATION
    })

    fun removePlace(place: com.onpath.app.search.Place) { routeIndexOf(place)?.let { planEdit.removePoint(it) }; placeMenus.closePlaceCard() }

    // ---------------- end search ----------------

    // ---------------- shared places (another app → OnPath): [SearchFlow] ----------------

    fun answerLongRoute(plan: Boolean) {
        val effects = planRun.routeEvent(RouteRun.Event.LongAnswer(plan, this.plan.points.toList()))
        // Plan anyway: not asked again for these points, now the new point's maps load, the run again
        if (plan) { planRun.perform(effects); return }
        // points kept unrouted: "Plan anyway" later loads them (an undo replaces it)
        if (planRun.longRouteDeferred != null) Problems.trace("cells", "Very long plan cancelled: nothing was loaded for it")
        // only the change that made it so long is taken back (Redo brings it again); nothing to take back (a plan
        // restored at start or loaded, newer routing data): the points stay without a route – never "Calculating…"
        // with nothing running
        planRun.perform(effects) // [cancelRun]
        // the map goes back to what was planned before the far point (owner 2026-11-13): the point / the whole plan, during
        // a tour my position; the found place's pin and its chip stay (cancelRun)
        val target = LongRouteCancelCamera.after(this.plan.points.toList(), route?.points, touring = recState.value != null,
            hasPosition = tracker.location.value != null)
        Problems.trace("map", "Very long plan cancelled: camera → ${target::class.simpleName}" +
            ((target as? LongRouteCancelCamera.Target.Fit)?.let { " (${it.points.size} points)" } ?: ""))
        when (target) {
            LongRouteCancelCamera.Target.Me -> map.locate()
            is LongRouteCancelCamera.Target.Fit -> { map.followMe = false; map.freeLookAt = null; events += UiEvent.Fit(target.points) }
            LongRouteCancelCamera.Target.Stay -> Unit
        }
    }

    /** The activity and T level from before the cancelled change; the route is planned for them again (no undo step). */
    private fun restoreActivity(c: PlanStatus.Cause.ActivityChange) {
        planRoute.activity = c.activity; prefs.activity = c.activity
        planRoute.sac = c.sac; prefs.sac = c.sac
        planRoute.routeChangedNotice = null; planRoute.activityBefore = null; planRun.routeEvent(RouteRun.Event.CompareActivity(false))
        planRoute.runCauses.other()
        recalc()
    }

    /** The route run's coroutine and what takes its result: [com.onpath.app.ui.plan.PlanRecalcSlice] (rebuild phase 2 piece 9b). */
    private fun recalc(): Unit = planRecalc.recalc()

    // ---------------- time estimate (docs/plan-time-estimate.md): [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = planRoute.withModelTime(r, a, f)
    private fun withModelTime(t: Tour): Tour = planRoute.withModelTime(t)

    /**
     * The router reaches a point only by its nearest allowed way (up to 1 km away): a summit above the T level, a
     * via ferrata, a point off any path. The plan sheet says so in one red line (owner: no message box), or
     * the hiker trusts a line that stops short of where they want to go. Null = every point is reached.
     */
    fun planGapText(gaps: List<PlanGap> = planGaps): String? {
        if (gaps.isEmpty()) return null
        PlanGaps.reachText(gaps, planReach)?.let { return it }
        val pts = plan.points.toList()
        return PlanGaps.text(gaps, planRoute.activity) { i -> pts.getOrNull(i)?.let { searchFlow.stopName(it) ?: planEdit.nearbyNames[it] } }
    }

    // ---------------- route warnings: [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    /** What the map column's greyed buttons need to know ([MapControlsEnabled]). */
    internal fun controlsIn(layer: AppMode, pointsOpen: Boolean) = MapControlsEnabled.In(layer, pointsOpen,
        planLine = map.hasPlanLine(), shownLine = map.hasShownLine(), shownOpen = routesFlow.shown != null, followLine = map.hasFollowLine(),
        canUndo = plan.canUndo, canRedo = plan.canRedo)

    /** A spot in the list tapped: the list closes, following stops, the map shows that piece (drawn amber). */
    fun showSpot(s: RouteSpot) {
        val pts = route?.points ?: return
        map.showPiece(RouteWarnings.showPoints(pts, s), RouteWarnings.centerFor(pts, s))
    }

    /**
     * Tap on the red line of a point the route does not reach – like a steep section (owner 2026-11-07): the section
     * is drawn amber and the map shows it – the way that reaches the point ([planReach], e.g. the T2 path), else the
     * missing piece.
     */
    fun showPlanGap() {
        val g = planGaps.firstOrNull() ?: return
        val way = planReach?.takeIf { it.index == g.index && !g.skipped }?.way
        if (way != null) map.showPiece(way + g.point, null)
        else map.showPiece(listOf(g.routeEnd, g.point), PlanGaps.showTarget(g))
    }

    // ---------------- Save (C18) – the slice is `save` ([SaveSlice]); screens read it as `vm.<slice>` (piece 21) ----------------

    /**
     * Save – the sheet's green Save, ☰ "Save route", sheet ⋯ → Save route – always opens the name dialog (owner
     * 2026-10-31, replaces "save at once"; [SaveSlice.request]).
     */
    fun requestSave() = save.request(null)

    /**
     * The plan on the map for My routes (owner 2026-11-06 23:00: "unter Zuletzt ‚gerade in Bearbeitung', dann weiß ich, dass
     * immer gespeichert wird"): [LivePlanRows] makes it the one Zuletzt row ("In Bearbeitung"). Null = empty map.
     */
    val livePlan: LivePlan?
        get() {
            if (plan.points.isEmpty()) return null
            val r = route
            return LivePlan(name = plan.savedName, points = plan.points.size, distanceM = r?.distanceM ?: 0.0,
                ascentM = r?.ascentM ?: 0.0, durationS = r?.durationS ?: 0.0, linkedId = plan.savedId,
                key = plan.points.map { it.lat to it.lon }, unsaved = plan.unsaved)
        }

    /**
     * The line of a tour in My routes for its preview and overview (owner 2026-11-07): the stored points (a plan's route,
     * a recorded track, an imported GPX), else its via points / GPX waypoints. Empty when nothing can be read (reported).
     */
    suspend fun tourLine(t: Tour): List<List<GeoPoint>> {
        val live = route?.points ?: plan.points.toList() // read on the main thread
        return withContext(Dispatchers.IO) { routes.line(t) { live } }
    }

    suspend fun routeSnapshot(segments: List<List<GeoPoint>>, tint: RouteShape.Tint, widthPx: Int, heightPx: Int,
                              paddingPx: Int, under: List<GeoPoint> = emptyList()): com.onpath.app.map.RouteSnapshot.Shot? =
        pictures.routeSnapshot(segments, tint, widthPx, heightPx, paddingPx, under)

    /** My routes → the plan "In Bearbeitung" tapped: back to it on the map – nothing is loaded again. */
    fun backToLivePlan() {
        Problems.trace("routes", "My routes → the plan in progress (${plan.points.size} points, linked ${plan.savedId}): back to the map, nothing reloaded")
        routes.open = false
    }

    /** My routes → the plan "In Bearbeitung" → Speichern: the same as ☰ Plan speichern (the name dialog). */
    fun saveLivePlan() {
        Problems.trace("routes", "My routes → the plan in progress → Speichern (${plan.points.size} points)")
        requestSave()
    }

    /** The plan's stops as saved: with their place names (backup / restore keeps them). */
    private fun savedStops(pts: List<GeoPoint>) = com.onpath.app.gpx.PlanGpx.stops(pts) { searchFlow.stopName(pts[it]) }

    suspend fun followedPlanLine(t: Tour): List<GeoPoint> = routesGo.followedPlanLine(t)
    suspend fun tourReview(t: Tour): com.onpath.app.time.TourReview? = routesGo.tourReview(t)

    /**
     * A shown track followed during a tour (the track sheet's "Tour nachfahren", F1's "Folgen" – flow map M1, owner rule
     * "what Plan shows = what Navigate follows"): the plan leaves the map (one Undo step, like ✕ Neu planen), the tour
     * follows the track, Navigate. An unsaved plan asks first ([unlessUnsaved]).
     */
    fun followShownDuringTour() = routesFlow.unlessUnsaved("Follow the shown track during the tour") { tourEdits.followShownNow() }

    /**
     * Proposed name of a new route: a plan from a link keeps its title while its points are unchanged, else
     * "Algund → Meran" (search/RouteNamer) from the offline place index (built first if needed); null when nothing fits.
     */
    private suspend fun proposeName(pts: List<GeoPoint>, r: PlannedRoute?): String? {
        if (pts.size < 2) return null
        links.title(pts)?.let { return it }
        val line = r?.points?.takeIf { it.size >= 2 } ?: pts
        val startName = searchFlow.stopName(pts.first()); val endName = searchFlow.stopName(pts.last())
        val via = pts.subList(1, pts.size - 1).mapNotNull { searchFlow.stopName(it) }
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
            routesFlow.refreshTours()
        }
    }

    /** "Route 26 Oct, 10:12" – [prefix] in the app's language (msg_name_route …), the date as the language writes it. */
    fun defaultName(prefix: String) =
        "$prefix " + SimpleDateFormat(Txt.s(Res.string.msg_name_date_pattern), Locale.getDefault()).format(Date())

    // ---------------- modes: Plan <-> Navigate ----------------

    /**
     * Look of the followed / shown line on the map (see MapController.setTrackLook): a shown track its own colour, else
     * route blue – in Navigate the followed line stays blue while a track is shown ([gpxLine]).
     */
    fun trackLook(): com.onpath.app.map.MapController.TrackLook =
        gpxLine(tourSession.mode == AppMode.NAVIGATE, routesFlow.shown?.segments, tourFollow.followLine).look

    // ---------------- activity check while navigating (owner: a wrong activity is a disaster) ----------------

    /**
     * The activity check ([com.onpath.app.ui.tour.ActivityCheckSlice] in `:state`, rebuild phase 2 piece 16b): the sheet, the
     * row routing on its own thread, what a found switch does. The service gets its one command through [recording] (the
     * proposal file first, [followStore]); the screens read it as `vm.<slice>`. The follow state lives in
     * [tourFollow] (piece 18a).
     */
    val activityCheckSlice: com.onpath.app.ui.tour.ActivityCheckSlice = com.onpath.app.ui.tour.ActivityCheckSlice(
        planRoute = planRoute, recording = recording, follow = followStore,
        rows = tourRows,
        fixes = object : com.onpath.app.ui.tour.Fixes {
            private fun point(loc: android.location.Location) = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
            override fun now(): GeoPoint? = tracker.location.value?.let { point(it) }
            override suspend fun first(): GeoPoint = point(tracker.location.filterNotNull().first())
        },
        host = object : com.onpath.app.ui.tour.ActivityCheckHost {
            override val mode: AppMode get() = this@AppViewModel.tourSession.mode
            override fun enterNavigate() = tourSession.enter(AppMode.NAVIGATE)
            override val followFromPlan: Boolean get() = this@AppViewModel.tourFollow.followFromPlan
            override val planPoints: Int get() = plan.points.size
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.planRun.routeEvent(e) }
            override fun rememberSac(level: Int) { prefs.sac = level }
        },
        scope = viewModelScope, io = Dispatchers.IO, cpu = Dispatchers.Default,
    )

    // ---- the tour's end: TourEndSlice (rebuild phase 2 piece 17); screens read it as `vm.<slice>` ----

    // ---------------- follow / record ----------------

    /** Any answer in "Unfinished tour" ends the "Tour interrupted" notification (Meran pictures bug 2). */
    private fun clearInterruptedNote() = com.onpath.app.recording.TourInterruptedReceiver.cancelNote(getApplication())

    // ---------------- tours ----------------

    /** My routes → a tour or route opened on the map ([RoutesSlice.openTour]). */
    fun openTour(t: Tour, duringTour: Boolean = false, trackThen: TrackThen = TrackThen.SHOW,
                 lineOf: List<List<TrackPoint>>? = null, planned: (() -> Unit)? = null): Unit =
        routesFlow.openTour(t, duringTour, trackThen, lineOf, planned)

    // ---------------- plan sync with the browser (A-007: plansync/, docs/design/web-sync.md) ----------------

    /**
     * Saved routes and imported tracks ⇄ the paired browser. Runs in the background (plansync/PlanSync): at start,
     * back in the app, after a change here, when the network is back, about every 15 s while the app stays open on
     * My routes / Browser sync ([syncDriver]), on pull to refresh in My routes → Planned ([pullSync]) and right away when one of them opens – never during a recording (after the tour).
     */
    val sync = com.onpath.app.plansync.PlanSync(app, com.onpath.app.plansync.DbTourStore(app, db) { a -> planRoute.planFactors(a) },
        recording = { recState.value != null }, onReport = { r -> syncDriver.onReport(r) })

    /**
     * When the sync runs and what follows a pass ([com.onpath.app.ui.sync.SyncDriver], cleanup piece 4): the triggers
     * while the app is open, pull to refresh, the "New / Changed" marks, a pass's report.
     */
    val syncDriver: com.onpath.app.ui.sync.SyncDriver = com.onpath.app.ui.sync.SyncDriver(
        engine = object : com.onpath.app.ui.sync.SyncEngine {
            override val state get() = sync.state
            override val marks get() = sync.files.marks.state
            override fun loadMarks() { sync.files.marks.all() }
            override fun markSeen(uuid: String) { sync.files.marks.remove(uuid) }
            override fun start() { sync.start() }
            override fun quiet() = sync.quiet()
            override fun busy() = sync.busy()
            override fun request(why: String, manual: Boolean) { sync.request(why, manual) }
            override fun poll(why: String) = sync.poll(why)
        },
        host = object : com.onpath.app.ui.sync.SyncHost {
            override val screen = androidx.compose.runtime.snapshotFlow {
                map.appOnScreen to com.onpath.app.plansync.SyncPoll.routesScreen(routes.open, settingsSlice.showSettings, settingsSlice.settingsPage == SettingsPage.SYNC)
            }
            override val shown = androidx.compose.runtime.snapshotFlow {
                com.onpath.app.plansync.SyncPoll.shown(map.appOnScreen, routes.open, routes.tab == RoutesTab.PLANNED, settingsSlice.showSettings,
                    settingsSlice.settingsPage == SettingsPage.SYNC)
            }
            override fun ended() { notices.sayInfo(Txt.s(Res.string.sync_ended), NoticePlace.SETTINGS) }
            override fun placesChanged() { keptPlaces.savedPlaces = null }
            override fun toursChanged(toRoute: List<Long>) { routesFlow.refreshTours(); syncedRouting.followSyncedSavedPlan(); syncedRouting.routeSyncedPlans(toRoute) }
            override fun newerData(tiles: List<String>) { refreshRoutingTiles(tiles, "a plan from the browser was routed with newer data") }
        },
        recording = recState.map { it != null }, isRecording = { recState.value != null },
        scope = viewModelScope, io = Dispatchers.IO, clock = { android.os.SystemClock.elapsedRealtime() })

    /** Opened (load as plan / open the track): the route's "New from / Changed in the browser" mark goes. */
    private fun syncMarkSeen(t: Tour) = syncDriver.markSeen(t.uuid)

    init { syncDriver.start() }

    /** Pairing with a browser: [PairingFlow] (the pairing half of the sync), forwarded member by member. */
    val pairing: PairingFlow by lazy {
        PairingFlow(sync, prefs, helperScope, object : PairingFlow.Screens {
            override var showSettings: Boolean
                get() = this@AppViewModel.settingsSlice.showSettings
                set(v) { this@AppViewModel.settingsSlice.showSettings = v }
            override var settingsPage: SettingsPage?
                get() = this@AppViewModel.settingsSlice.settingsPage
                set(v) { this@AppViewModel.settingsSlice.settingsPage = v }
            override var routesOpen: Boolean
                get() = this@AppViewModel.routes.open
                set(v) { this@AppViewModel.routes.open = v }
            override var syncNote: SyncNote?
                get() = this@AppViewModel.syncNote
                set(v) { this@AppViewModel.syncNote = v }
            override fun openSettings(page: SettingsPage?) = this@AppViewModel.settingsSlice.openSettings(page)
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.notices.say(text, kind, place)
        })
    }
    /** The camera for the pairing code (no camera app: Settings → Browser sync with the line). */
    fun openPairCamera(start: (String) -> Unit, fromSettings: Boolean) = pairing.openPairCamera(start, fromSettings)

    // ---------------- plan links (A-005: routing/PlanLinks, docs/core-features/C15-plan-links.md) ----------------

    /** Text pasted into the search box: a plan link opens the plan (true); anything else is a search (false). */
    fun pastedPlanLink(text: String): Boolean = links.pasted(text) { search.clear() }

    // ---------------- recycle bin + backup (docs/core-features/C11-backup.md) ----------------

    /** First start with an empty database: "Earlier tours found? Restore from Documents/OnPath" (asked once). */
    var restoreOffer by mutableStateOf(false)
    /** The system's location permission request is open (MainActivity); the restore offer waits ([RestoreOfferRule]). */
    var locationAsking by mutableStateOf(false)
    // ---------------- Phone setup (docs/design/onboarding-phone-check.md) ----------------
    /** Phone setup (docs/design/onboarding-phone-check.md): [PhoneSetupState], forwarded member by member. */
    val setup: PhoneSetupState = PhoneSetupState(prefs) { settingsSlice.settings }

    /** A restore is running: its progress line ("Restoring 5 / 28"); null = none. */
    var restoreProgress by mutableStateOf<String?>(null)
        private set

    init {
        Backups.init(app)
        Backups.purgeAndSync(app) // 30-day recycle bin, files for tours that have none yet
        importExport.checkRestoreOffer()
        recent.trimOnce()
        // a failed backup is said until OK is tapped (no silent loss); then it counts as seen
        viewModelScope.launchSafe("app", "start-up work") {
            Backups.failure.collect { text ->
                if (text == null) return@collect
                while (notices.errorCard != null) delay(500) // after the current error box
                notices.sayError(Txt.s(Res.string.msg_backup_failed, text), NoticePlace.BACKGROUND)
                Backups.failureSeen(getApplication())
            }
        }
    }

    fun restoreOfferAnswered() { restoreOffer = false; Backups.setRestoreOffered(getApplication()) }

    /** Writes the GPX [gpx] builds (off the main thread) into the export folder and opens the share sheet. */
    private suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = importExport.share(name, gpx)

    // ---------------- GPX import ----------------

    // ---------------- routing data freshness (owner: routing matters most – a changed way must reroute) ----

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
        if (due.isEmpty() || (settingsSlice.settings.mapsWifiOnly && !com.onpath.app.cells.AndroidNetworkStatus(getApplication()).unmetered())) return
        refreshRd5(due.map { it.name })
    }

    /** Synced plans' tile checks: once per tile in [ROUTING_CHECK_MS] (sync passes come every 15 s on My routes). */
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
        if (settingsSlice.settings.mapsWifiOnly && !com.onpath.app.cells.AndroidNetworkStatus(getApplication()).unmetered()) {
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
                    planRoute.engine = null // new engine: no cached routes from the old data
                    if (tourSession.mode == AppMode.PLAN && recState.value == null && plan.points.size >= 2) {
                        planRecalc.routingDataBefore = route?.points; planRun.routeEvent(RouteRun.Event.NewerData); recalc()
                    }
                }
            }
        }
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
        // storage not ready at start (field 2026-11-08, right after a phone restart): no "maps missing", nothing loaded
        // or removed (DataStorage); once it is readable the data is checked again and the map style loads the real files
        // (map cells reload by themselves through their generation)
        if (!dataFiles.ready()) com.onpath.app.data.DataStorage.get(getApplication()).whenReady {
            viewModelScope.launchSafe("app", "storage ready") {
                recheckData()
                if (com.onpath.app.map.StyleLoader.lastMapDir != null) {
                    Problems.trace("map", "Map style reload asked: storage ready (version ${styleVersion + 1})")
                    styleVersion++
                }
            }
        }
        // a joined / removed map area → the offline search index follows in the background (only changed areas are read;
        // not at start – an unchanged index needs nothing), so the next search or route name does not wait for it
        viewModelScope.launchSafe("app", "start-up work") {
            cells.generation.drop(1).collectLatest { delay(5_000); search.prepare() }
        }
        // map cells (map-cells.md §9): Wi-Fi setting, relevance, cells still loading from the last run, T3 on fixes
        mapData.start(); multiDay.start() // multi-day tours: kept map areas, a followed day's write-back, the overview's days
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
        placeAdd.close()
        planRoute.close() // the four routing threads
        super.onCleared()
    }
}

/** Map style reload after a new map store generation: this quiet, at most this long after the first (ms). */
private const val STYLE_QUIET_MS = 2_000L
private const val STYLE_MAX_WAIT_MS = 15_000L
/** Plan recomputed after routing data arrived: this quiet (and the queue empty), at most this long (ms). */
private const val ARRIVAL_QUIET_MS = 1_500L
private const val ARRIVAL_MAX_WAIT_MS = 20_000L

/** Routing data: checked at most this often after a route (ms), and refreshed when older than this (days). */
private const val ROUTING_CHECK_MS = 12 * 3600_000L
private const val ROUTING_MAX_AGE_DAYS = 35

// TrackThen (what openTour does with a done tour) lives in :state, ui/TrackThen.kt
