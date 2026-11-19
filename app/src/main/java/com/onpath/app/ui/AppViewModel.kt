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

    /** Model times of saved planned routes, by tour id ([PlanRouteSlice.plannedTimes]). */
    private val plannedTimes get() = planRoute.plannedTimes
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
    val tour = com.onpath.app.ui.tour.TourController(com.onpath.app.ui.tour.tourRecorder(recording))

    // --- screens and mode ---
    // messages (declared before routesOpen / showSettings, whose setters clear their lines) – see [say]
    /**
     * The messages the screens show ([NoticeBoard], in `:state`): the red error box, the lines in My routes / Settings, the
     * quiet map line, the spinner. The names below forward to it. It reads the screens through lambdas, when a message is said.
     */
    val notices = NoticeBoard(routesOpen = { routesOpen }, settingsOpen = { showSettings }, mapShown = { mode == AppMode.PLAN && !searchOpen })
    /** The red error box (MessageCard HINWEIS_ERROR: ⚠, red edge, OK) on the screen shown; null = none. Never cleared by itself. */
    var errorCard: String? get() = notices.errorCard; set(v) { notices.errorCard = v }
    /** The line at the top of My routes; goes when My routes is left. */
    var routesNote: Notice? get() = notices.routesNote; set(v) { notices.routesNote = v }
    /**
     * A plan or a done tour was tapped in My routes during a tour and is followed now – no question (owner 2026-11-06
     * 22:05, "super verwirrend"): Navigate says "Neuer Plan – deine Aufnahme läuft weiter" at the bottom for a few
     * seconds ([NewPlanLine]), with "Rückgängig" when a plan was replaced ([undoNewPlan]). Null = no line.
     */
    val newPlanNote: NewPlanNote? get() = tour.newPlanNote
    fun dropNewPlanNote() = tour.dropNewPlanNote()

    /** The line at the top of the open Settings page; goes when the page changes or Settings closes. */
    var settingsNote: Notice? get() = notices.settingsNote; set(v) { notices.settingsNote = v }
    /**
     * My routes' state and decisions ([com.onpath.app.ui.routes.RoutesState], audit package 4 stage 3): open, tab, the
     * list, Zuletzt, the "✓ Gerade gespeichert" mark; the database behind a small store. The names below forward to it.
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
    private val routes = com.onpath.app.ui.routes.RoutesState(routesStore, recent = prefs.zuletztId).also {
        it.onClosed = { routesNote = null } // the line at the top lasts until My routes is left
        it.onRecent = { id -> prefs.zuletztId = id } // the Zuletzt row survives an app start
    }
    /**
     * Zuletzt and the recycle bin as the user's actions ([com.onpath.app.ui.routes.RecentPlans], cleanup piece 4): a plan
     * that leaves the map is kept, Zuletzt → Speichern / Löschen, the bin's list, Restore, Delete now. The names below
     * ([SaveSlice.keepAsDraft], [saveDraft], [recycleBin] …) forward to it.
     */
    private val recent = com.onpath.app.ui.routes.RecentPlans(routes, routesStore, object : com.onpath.app.ui.routes.RecentPlansHost {
        override fun insertDraft(row: com.onpath.app.ui.routes.RecentPlans.DraftRow) =
            db.insertTour(TourKind.PLANNED, row.name, row.activity, row.sac, row.distanceM, row.ascentM, row.descentM, row.durationS,
                row.segments, row.waypoints, pinned = row.pinned, draft = true, difficulty = row.difficulty, days = row.days)
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = storePlanTimes(id, route, activity)
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?) = this@AppViewModel.proposeName(points, route)
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun purged(uuid: String) = Backups.purge(getApplication(), listOf(uuid))
        override fun syncChanged(why: String) = sync.changed(why)
        override fun refreshTours() = this@AppViewModel.refreshTours()
        override fun problem(text: String) { routesProblem = text }
        override var trimmed: Boolean
            get() = prefs.zuletztTrimmed
            set(v) { prefs.zuletztTrimmed = v }
    }, viewModelScope, Dispatchers.IO)
    /**
     * My routes' row pictures and the large overviews' map pictures (owner 2026-11-13 / 2026-11-15): files, makers and the
     * snapshot live in [RoutePictures] (`:app`); the names below forward to it.
     */
    private val pictures by lazy {
        RoutePictures(getApplication(), viewModelScope, Dispatchers.IO, db, routes, object : RoutePicturesHost {
            override val mode: AppMode get() = this@AppViewModel.mode
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val settings: AppSettings get() = this@AppViewModel.settings
            override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles
            override val devToolsShown: Boolean get() = this@AppViewModel.devToolsShown
            override fun detailedAreas() = this@AppViewModel.detailedAreas()
            override fun packageAreas() = this@AppViewModel.packageAreas()
            override suspend fun followedPlanLine(t: Tour) = this@AppViewModel.followedPlanLine(t)
        })
    }
    val thumbs get() = pictures.thumbs
    val thumbVersion: Int get() = pictures.thumbVersion
    val overviewPictures get() = pictures.overviewPictures
    suspend fun overviewShot(t: Tour, segments: List<List<GeoPoint>>, tint: RouteShape.Tint, widthPx: Int, heightPx: Int,
                             paddingPx: Int, under: List<GeoPoint> = emptyList()): com.onpath.app.map.RouteSnapshot.Shot? =
        pictures.overviewShot(t, segments, tint, widthPx, heightPx, paddingPx, under)
    val thumbTimings: List<Long> get() = pictures.thumbTimings

    /** My routes list (☰ → My routes) is open. */
    var routesOpen: Boolean
        get() = routes.open
        set(v) { routes.open = v }
    /** My routes' last chosen tab (Planned / Done); kept while the app runs, Planned after a restart. */
    var routesTab: RoutesTab
        get() = routes.tab
        set(v) { routes.tab = v }
    /** My routes' order (owner 2026-11-12, [com.onpath.app.domain.routes.RouteSort]): one for both tabs, kept across app starts. */
    var routesSort: com.onpath.app.domain.routes.RouteSort.Choice
        get() = routes.sort
        set(v) { routes.sort = v } // while My routes is open only: "Neueste zuerst" at each opening (owner 2026-11-13)
    /** My routes' "☆ Favoriten" pill (owner 2026-11-13, favourites C): on = only the starred ones; kept while the app runs. */
    var routesFavouritesOnly = false
    /** Plan (home) or Navigate; see AppMode and docs/ux-two-modes.md. */
    val mode: AppMode get() = tourSession.mode
    /**
     * Zoom the map takes when Navigate starts or locate is tapped in Navigate ([AppMode.navStartZoom],
     * [AppMode.locateZoom]); null once taken (then the user's zoom stays).
     */
    var navStartZoom: Double?
        get() = tourSession.navStartZoom
        set(v) { tourSession.navStartZoom = v }
    /** Map taps may add, move or insert points (Plan with no track shown). */
    val canEditMap: Boolean get() = mode == AppMode.PLAN && shown == null
    /** "You have arrived – end tour?" is open. */
    var arrivalAsk: Boolean
        get() = tour.arrivalAsk
        set(v) { tour.arrivalAsk = v }
    /** POI whose details sheet is open (F-MAP-09). */
    var selectedPoi by mutableStateOf<com.onpath.app.poi.Poi?>(null)

    // --- activity ---
    /**
     * The plan's route, first half ([PlanRouteSlice], rebuild phase 2 piece 8): activity / T level (every app start begins
     * with the last chosen ones, owner; fresh install Hike T2), the routing engine with its leg cache and threads, the time
     * estimate, the route warnings. Declared here – above the init that calls `recalc()` and every property that reads it – because
     * `recalc()` runs in the init; the ports read the lazies and the later properties only when called (ViewModelInitOrderTest).
     * The names below forward to it.
     */
    private val planRoute = com.onpath.app.ui.plan.PlanRouteSlice(
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
            override val settings: AppSettings get() = this@AppViewModel.settings
            override var route: PlannedRoute?
                get() = this@AppViewModel.route
                set(v) { this@AppViewModel.route = v }
            override val routing: Boolean get() = this@AppViewModel.routing
            override val longRouteAsk: Double? get() = this@AppViewModel.longRouteAsk
            override val mode: AppMode get() = this@AppViewModel.mode
            override val touring: Boolean get() = recState.value != null
            override fun recalc() = this@AppViewModel.recalc()
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }
            override fun refreshTours() = this@AppViewModel.refreshTours()
            override fun clearShownNote() { shown?.let { if (it.note != null) shown = it.copy(note = null) } }
            override fun showWhole(points: List<GeoPoint>) { followMe = false; events += UiEvent.Fit(points) }
        },
    )
    var activity: Activity
        get() = planRoute.activity
        private set(v) { planRoute.activity = v }
    var sac: Int
        get() = planRoute.sac
        private set(v) { planRoute.sac = v }
    // --- settings (Settings page) ---
    /**
     * Settings ([com.onpath.app.ui.SettingsSlice] in `:state`, rebuild phase 2 piece 4): the settings, open / page / back / the
     * on-the-way list, relief auto, developer tools, the last visit to Diagnostics, the routing fingerprint. The preferences and
     * files behind [settingsStore], the rest of the app behind [settingsHost]; the names below forward to it.
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
        override val planMapShown: Boolean get() = mode == AppMode.PLAN && !searchOpen
        override fun clearOfflineResult() { offlineResult = null }
        override fun syncEndedSeen() = sync.endedSeen()
        override fun mapsWifiOnly(on: Boolean) { cells.wifiOnly = on }
        override fun retime() = this@AppViewModel.retime()
        override val tourRecording: Boolean get() = recState.value != null
        override fun recordingSettingsChanged() = recording.settingsChanged()
    }
    private val settingsSlice = com.onpath.app.ui.SettingsSlice(notices, settingsStore, settingsHost, viewModelScope, Dispatchers.IO)
    val settings: AppSettings get() = settingsSlice.settings
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
    /** Settings opened from Navigate's ⚙ (owner 2026-11-07): the on-the-way list; see [com.onpath.app.ui.SettingsSlice]. */
    val settingsOnTheWay: Boolean get() = settingsSlice.settingsOnTheWay
    /** Open topic of Settings (list of topics → pages); null = the list. */
    var settingsPage: SettingsPage?
        get() = settingsSlice.settingsPage
        set(v) { settingsSlice.settingsPage = v }
    /** Settings → Diagnostics: the routing fingerprint and the routing data's dates (RoutingFingerprint); null = not read yet. */
    val routingInfo: String? get() = settingsSlice.routingInfo
    private val routingFingerprint by lazy {
        com.onpath.app.routing.RoutingFingerprint.of(read = { n -> getApplication<Application>().assets.open("brouter/$n").use { it.readBytes() } })
    }
    /** Developer tools in Diagnostics (owner): after 7 taps on the version (remembered) – also in debug builds (Arch H1). */
    val devToolsShown: Boolean get() = settingsSlice.devToolsShown
    fun unlockDevTools() = settingsSlice.unlockDevTools()

    /** Round trips („Rundtour von hier“, tour-suggestions.md §7 stage 4) – ui/suggest/SuggestSlice; Übernehmen → [planFromLine]. */
    val suggest = com.onpath.app.ui.suggest.SuggestSlice(viewModelScope, com.onpath.app.ui.suggest.AppSuggestHost(app, prefs, { mapData.routingData },
        fit = { followMe = false; freeLookAt = null; events += UiEvent.Fit(it) }, take = { pts, a, l, done -> if (a != activity) selectActivity(a); selectSac(l)
            planFromLine(pts, com.onpath.app.data.RoutingTiles.forLine(pts, 1_000.0), a, l, done) }), prefs.suggestTest) { prefs.suggestTest = it }
    /** Last visit to Diagnostics: a serious problem after it shows red on the Settings list (owner). */
    val diagnosticsSeenMs: Long get() = settingsSlice.diagnosticsSeenMs

    /** Opens Settings, at [page] if given (map credits → About). */
    fun openSettings(page: SettingsPage? = null) = settingsSlice.openSettings(page)
    /** Navigate → ⚙ (owner 2026-11-07): Settings in the on-the-way list; back returns to Navigate, the tour runs on. */
    fun openSettingsOnTheWay() = settingsSlice.openSettingsOnTheWay()
    /** On-the-way list → "Alle Einstellungen": the whole topic list (back from it closes Settings). */
    fun showAllSettings() = settingsSlice.showAllSettings()
    /** Back in Settings: a topic page returns to the list, the list closes Settings. */
    fun settingsBack() = settingsSlice.settingsBack()

    // --- Navigate orientation + next turn (see NavigateScreen, MapController.setCamera) ---
    /** Navigate: heading-up (walking direction up) or north-up; persisted in settings. */
    val navHeadingUp: Boolean get() = settings.navHeadingUp

    /**
     * The session's one map orientation (owner 2026-11-07, domain/map/MapOrientation): walking direction up or north up,
     * the same in Plan and Navigate; only the compass tap / the setting change it.
     */
    val headingUp: Boolean get() = navHeadingUp
    /** Camera, compass, scrub, the fits and the start camera's flags (rebuild phase 2 piece 5); the names below forward to it. */
    private val mapCamera = object : com.onpath.app.ui.Camera {
        override fun fit(points: List<GeoPoint>) { events += UiEvent.Fit(points) }
        override fun centerOn(point: GeoPoint, zoom: Double) { events += UiEvent.CenterOn(point, zoom) }
        override fun panTo(point: GeoPoint) { events += UiEvent.PanTo(point) }
    }
    private val mapHost = object : com.onpath.app.ui.MapViewHost {
        override val mode: AppMode get() = this@AppViewModel.mode
        override val activity: com.onpath.app.routing.Activity get() = this@AppViewModel.activity
        override val headingUp: Boolean get() = this@AppViewModel.headingUp
        override fun setHeadingUp(on: Boolean) {
            if (settings.navHeadingUp != on) { settingsSlice.put(settings.copy(navHeadingUp = on)) }
        }
        override var navStartZoom: Double?
            get() = this@AppViewModel.navStartZoom
            set(v) { this@AppViewModel.navStartZoom = v }
        override val routePoints: List<GeoPoint>? get() = route?.points
        override val waypoints: List<GeoPoint> get() = this@AppViewModel.waypoints
        override val shown: ShownTrack? get() = this@AppViewModel.shown
        override val followLine: List<GeoPoint>? get() = this@AppViewModel.followLine
        override val navOriginal: List<GeoPoint>? get() = recState.value?.nav?.original
        override val touring: Boolean get() = recState.value != null
        override val keepCameraUntilMs: Long get() = searchFlow.keepCameraUntil
        override fun highlight(at: GeoPoint?) { this@AppViewModel.highlight = at }
        override fun closeSpots() { spotsOpen = false }
        override fun spotLine(piece: List<GeoPoint>) { this@AppViewModel.spotLine = piece }
        override fun appReturned(inPlan: Boolean) {
            mapData.onReturn(inPlan = inPlan) // T3 in Plan: the next usable fix checks the own area
            sync.request("back in the app")
        }
        override fun appLeft() { mapData.onLeave() }
    }
    private val map = com.onpath.app.ui.MapViewSlice(notices, mapCamera,
        { tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) } }, mapHost, viewModelScope,
        uptimeMs = { android.os.SystemClock.uptimeMillis() }, elapsedMs = { android.os.SystemClock.elapsedRealtime() })
    /** Bumped to turn the map north (MapScreen → MapController.northUp). */
    val northTick: Int get() = map.northTick
    /** Bumped when the camera must follow the position again although [followMe] did not change. */
    val recentreTick: Int get() = map.recentreTick
    /** Locate button: follow the position again ([MapViewSlice.locate]). */
    fun locate() = map.locate()
    /** Plan's neutral GPS note after a tap of the user's ([PlanGps]); null = none. */
    var planGpsNote: PlanGps.Note?
        get() = map.planGpsNote
        set(v) { map.planGpsNote = v }
    val planGpsNoteTick: Int get() = map.planGpsNoteTick
    private fun noPositionForEdit(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = map.noPositionForEdit(note, text)
    fun showPlanGpsNote(note: PlanGps.Note) = map.showPlanGpsNote(note)
    fun onCompass(mapTurned: Boolean) = map.onCompass(mapTurned)
    /** Navigate: a finger on the elevation profile ([MapViewSlice.navScrub]). */
    fun navScrub(p: GeoPoint) = map.navScrub(p)
    fun navScrubEnd() = map.navScrubEnd()
    /** The app is on screen again ([MapViewSlice.onReturnToApp]). */
    fun onReturnToApp() = map.onReturnToApp()
    /** The app left the screen ([MapViewSlice.onLeaveApp]). */
    fun onLeaveApp() = map.onLeaveApp()
    /** The app is on screen (Compose state: MapHost catches up with newer map data on return). */
    val appOnScreen: Boolean get() = map.appOnScreen

    // ---------------- map cells (docs/design/map-cells.md §9, §19) ----------------

    /** Downloaded map cells: requests, states, installed list (UI: card, Settings list – see map-cells.md §19). */
    val cells: com.onpath.app.cells.MapCells by lazy { com.onpath.app.cells.AppCells.get(getApplication()) }

    // ---------------- demo location (Developer tools → Demo, recording/DemoLocation) ----------------

    /**
     * The developer-tools demo rides ([DemoRides] in `:app`; the decisions are [DemoRules] in `:state`): "Demo: ride",
     * Navigate's "▶ Sim", a recorded tour ridden again, the map centre. The names below forward to it.
     */
    private val demo by lazy {
        DemoRides(viewModelScope, Dispatchers.IO, db, object : DemoRidesHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val shown: ShownTrack? get() = this@AppViewModel.shown
            override val activity: Activity get() = this@AppViewModel.activity
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val tours: List<Tour> get() = this@AppViewModel.tours
            override fun planFactors() = this@AppViewModel.planFactors()
            override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.sayInfo(text, place)
            override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.sayError(text, place)
            override fun initialCamera() = this@AppViewModel.initialCamera()
        })
    }
    fun demoLine(): Pair<String, List<GeoPoint>>? = demo.demoLine()
    fun startDemoRide() = demo.startDemoRide()
    fun startNavSim(line: List<GeoPoint>?) = demo.startNavSim(line)
    fun recentRecordedTours(): List<Tour> = demo.recentRecordedTours()
    fun demoTourLabel(t: Tour): String = demo.demoTourLabel(t)
    fun startDemoTour(t: Tour) = demo.startDemoTour(t)
    fun navSimFollow(line: List<GeoPoint>?) = demo.navSimFollow(line)
    fun startDemoAtMapCentre() = demo.startDemoAtMapCentre()

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
    /** The first-map card on the map (owner 2026-11-08): the map for here still loading / offline after the setup. */
    fun firstMapCard(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>,
                     installed: List<com.onpath.app.cells.CellStore.InstalledCell>): FirstMapCard.Ui? =
        FirstMapCard.of(mapData.cellJob, states, installed.associate { it.id to it.totalBytes }, viewWithoutDetail, setupOpen,
            plan = mode == AppMode.PLAN, available = cells.downloadsAvailable)
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
        if (after.size >= 2 && after.toList() != routeRun.longOk && com.onpath.app.routing.LongRoute.needsAsk(after.toList(), activity)) {
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
            syncedRouting.routeWaiting()
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
    /** The quiet line on the Plan map ([NoticeSurface.MAP_LINE], goes after [MapNote.MS]); [mapNoteTick] restarts its time. */
    var mapNote: Notice? get() = notices.mapNote; set(v) { notices.mapNote = v }
    val mapNoteTick: Int get() = notices.mapNoteTick

    /** One map gesture is over with its frame times (MapHost): relief auto – see [com.onpath.app.ui.SettingsSlice.mapGestureMeasured]. */
    fun mapGestureMeasured(kind: com.onpath.app.map.ReliefAuto.Kind, stats: com.onpath.app.debug.FrameStats) = settingsSlice.mapGestureMeasured(kind, stats)

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
    private val plan = com.onpath.app.ui.plan.PlanDocument(com.onpath.app.ui.plan.PrefsPlanStore(prefs))
    /** The plan's points ([com.onpath.app.ui.plan.PlanDocument.points]). */
    val waypoints get() = plan.points
    /** Pinned (POI) points, kept by value ([com.onpath.app.ui.plan.PlanDocument.pinned]). */
    private val pinned: Set<GeoPoint> get() = plan.pinned
    /**
     * Plan links in and out ([com.onpath.app.ui.sync.PlanLinkFlow], cleanup piece 4): a link opened in OnPath, what the
     * last link brought (its name, its points), a plan sent as a link. [receivePlanLink] … forward to it.
     * Declared up here, above the init blocks: a route result at start reaches it ([com.onpath.app.ui.sync.PlanLinkFlow.snapped]).
     */
    private val links = com.onpath.app.ui.sync.PlanLinkFlow(object : com.onpath.app.ui.sync.PlanLinkHost {
        override fun backToMap() {
            routesOpen = false; showSettings = false; offlineResult = null; timeDetails = null; routeDetailsOpen = false; searchOpen = false
            closeMapOverlays()
        }
        override fun sayError(text: String, place: NoticePlace) { this@AppViewModel.sayError(text, place) }
        override fun receivePairing(secret: ByteArray, via: String) { this@AppViewModel.receivePairing(secret, via) }
        override fun openSyncSettings() { openSettings(SettingsPage.SYNC) }
        override suspend fun startModeKnown() {
            if (!this@AppViewModel.startModeKnown) androidx.compose.runtime.snapshotFlow { this@AppViewModel.startModeKnown }.first { it }
        }
        override fun navigating(): Boolean = navigating(recState.value != null)
        override fun planPoints(): List<GeoPoint> = waypoints.toList()
        override fun savedPoints(): List<GeoPoint>? = this@AppViewModel.savedPoints
        override fun open(plan: com.onpath.app.routing.SharedPlan, via: String) { if (shown != null) closeShown(); openSharedPlan(plan, via) }
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
    fun pinnedIndices(): Set<Int> = plan.pinnedIndices()
    /** Kind by point index for the pinned points that sit on a POI the map draws ([PlaceMenusSlice.pinnedPoiKinds]). */
    fun pinnedPoiKinds(): Map<Int, String> = placeMenus.pinnedPoiKinds()
    /** Looks up the POI under each pinned point without a known kind ([PlaceMenusSlice.refreshPinnedKinds]). */
    fun refreshPinnedKinds() = placeMenus.refreshPinnedKinds()
    /** Followed routes replaced by plan edits during the tour (Undo takes them back exactly, Bug 02). */
    private val followHistory = FollowHistory()
    val canUndo: Boolean get() = plan.canUndo
    val canRedo: Boolean get() = plan.canRedo
    /** Plan panel size, kept while the app runs ([PlanEditSlice.planSize]). */
    var planSize: PlanSize
        get() = planEdit.planSize
        set(v) { planEdit.planSize = v }

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

    /** The route point whose point menu is open ([PlaceMenusSlice.pointMenuTarget]). */
    val pointMenuTarget: PointMenuTarget? get() = placeMenus.pointMenuTarget
    /** Index of that point in the plan now, or null (no menu, or its point is gone). */
    val pointMenu: Int? get() = placeMenus.pointMenu
    var route by mutableStateOf<PlannedRoute?>(null)
        private set
    /**
     * The plan's route run ([PlanRunSlice], rebuild phase 2 piece 9): the [RouteRun] state (changed only by [routeEvent]), its
     * effects, the run's job / cancel signal / wait card progress, Cancel and the two answers. Declared before init {}
     * (recalc() runs there, ViewModelInitOrderTest); the run's coroutine itself is [recalc]. The names below forward to it.
     */
    val planRun = com.onpath.app.ui.plan.PlanRunSlice(
        runCauses = planRoute.runCauses,
        host = object : com.onpath.app.ui.plan.PlanRunHost {
            override fun points(): List<GeoPoint> = waypoints.toList()
            override fun clearRoute() { route = null; routeError = null }
            override fun noCorridor() = mapData.corridorFollowsPlan(null)
            override fun loadDeferredMaps(before: List<GeoPoint>) = cellsForNewPoints(before, waypoints)
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
    private val planEdit: com.onpath.app.ui.plan.PlanEditSlice = com.onpath.app.ui.plan.PlanEditSlice(
        plan = plan, planRun = planRun, runCauses = planRoute.runCauses,
        host = object : com.onpath.app.ui.plan.PlanEditHost {
            override fun cancelHoldMeasure(why: String) = placeAdd.cancelHoldMeasure(why)
            override fun stopFollowingWhenMapTurns() { if (mode == AppMode.PLAN && headingUp && followMe) followMe = false }
            override fun closePointMenu(why: String) = placeMenus.closePointMenu(why)
            override fun clearFirstPointMenu() { placeAdd.firstPointMenu = null }
            override fun clearHoldAddMenu() { placeAdd.holdAddMenu = null }
            override fun clearHighlight() { highlight = null }
            override fun removePin(why: String) = placeMenus.removePin(why)
            override fun menuAction(what: String): Int? = placeMenus.menuAction(what)
            override fun setRoutingSpot(spot: GeoPoint?) { routingSpot = spot }
            override fun loadMapsForNewPoints(before: List<GeoPoint>) = cellsForNewPoints(before, waypoints)
            override fun keepAsDraft(why: String) = save.keepAsDraft(why)
            override fun routeLine(): List<GeoPoint>? = route?.points
            override fun recalc() = this@AppViewModel.recalc()
            override fun restoreActivity(c: PlanStatus.Cause.ActivityChange) = this@AppViewModel.restoreActivity(c)
            override fun planChanged() = this@AppViewModel.planChanged()
            override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()
            override fun touring(): Boolean = recState.value != null
            override fun following(): Boolean = followLine != null
            override fun followIsTrack(): Boolean = this@AppViewModel.followIsTrack
            override fun followsLine(line: List<GeoPoint>): Boolean = followLine == line
            override fun followFromPlan(): Boolean = this@AppViewModel.followFromPlan
            override fun splitOf(before: List<GeoPoint>, after: List<GeoPoint>): TourSplit.Outcome? = tourEdits.splitOf(before, after)
            override fun sayNoDestination() = sayEvent(RecordingService.SAY_NO_DESTINATION)
            override fun oneLeftGoesFromHere(before: List<GeoPoint>, mayEmpty: Boolean) = tourEdits.oneLeftGoesFromHere(before, mayEmpty)
            override fun endGuidanceIfPlanEmpty() = tourEdits.endGuidanceIfPlanEmpty()
            override fun clearFollow() = this@AppViewModel.clearFollow()
            override fun guidanceEndedByEmptyPlan() { this@AppViewModel.guidanceEndedByEmptyPlan = true }
            override fun stopName(p: GeoPoint): String? = this@AppViewModel.stopName(p)
            override suspend fun pointNames(): com.onpath.app.ui.plan.PointNames {
                val index = search.offlineIndex()
                return com.onpath.app.ui.plan.PointNames { p ->
                    com.onpath.app.search.RouteNamer.label(p, com.onpath.app.search.RouteNamer.placesNear(index, listOf(p)))
                }
            }
            override var searchMark: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchMark
                set(v) { this@AppViewModel.searchMark = v }
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
    private val placeAdd = PlaceAddSlice(
        plan = plan, planEdit = planEdit, planRun = planRun,
        host = object : com.onpath.app.ui.plan.PlaceAddHost {
            override fun closeMapOverlays(why: String) = placeMenus.closeMapOverlays(why)
            override fun openPointMenu(index: Int, via: String) = placeMenus.openPointMenu(index, via = via)
            override fun closePlaceCard() = placeMenus.closePlaceCard()
            override fun planMode(): Boolean = mode == AppMode.PLAN
            override fun canEditMap(): Boolean = this@AppViewModel.canEditMap
            override fun activity(): Activity = this@AppViewModel.activity
            override fun sac(): Int = this@AppViewModel.sac
            override fun routeLine(): List<GeoPoint>? = route?.points
            override fun touring(): Boolean = recState.value != null
            override fun myPosition(): GeoPoint? = this@AppViewModel.myPosition()
            override fun tourPassed(plan: List<GeoPoint>): Set<Int> = tourEdits.tourPassed(plan)
            override fun followLine(): List<GeoPoint>? = this@AppViewModel.followLine
            override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()
            override fun gpsPoint(): GeoPoint? = tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchMark
            override fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? = this@AppViewModel.takePin(lat, lon)
            override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.isCandidate(poi)
            override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.rememberPlaceName(p, name)
            override fun rememberPoiKind(p: GeoPoint, kind: String?) = placeMenus.rememberPoiKind(p, kind)
            override fun stopIndexAt(lat: Double, lon: Double): Int = waypoints.indexOfFirst { sameSpot(it, lat, lon) }
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
    private val placeMenus: com.onpath.app.ui.plan.PlaceMenusSlice = com.onpath.app.ui.plan.PlaceMenusSlice(
        plan = plan, planRun = planRun, placeAdd = placeAdd,
        host = object : com.onpath.app.ui.plan.PlaceMenusHost {
            override fun planMode(): Boolean = mode == AppMode.PLAN
            override fun touring(): Boolean = recState.value != null
            override fun activity(): Activity = this@AppViewModel.activity
            override fun sac(): Int = this@AppViewModel.sac
            override fun route(): PlannedRoute? = this@AppViewModel.route
            override fun wayThereFrom(): GeoPoint? = tourEdits.wayThereFrom()
            override fun routeIndexOf(place: com.onpath.app.search.Place): Int? = this@AppViewModel.routeIndexOf(place)
            override var searchMark: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchMark
                set(v) { this@AppViewModel.searchMark = v }
            override var searchPlace: com.onpath.app.search.Place?
                get() = this@AppViewModel.searchPlace
                set(v) { this@AppViewModel.searchPlace = v }
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
    private val keptPlaces: com.onpath.app.ui.plan.KeptPlacesSlice = com.onpath.app.ui.plan.KeptPlacesSlice(
        store = object : com.onpath.app.ui.plan.PlacesStore {
            override fun saved(): com.onpath.app.search.SavedPlaces = searchStore.saved()
            override fun update(change: (com.onpath.app.search.SavedPlaces) -> com.onpath.app.search.SavedPlaces): com.onpath.app.search.SavedPlaces =
                searchStore.update(change = change)
            override fun backupChanged() = com.onpath.app.backup.Backups.placesChanged(getApplication())
            override fun syncChanged() = sync.changed("kept places")
        },
        host = object : com.onpath.app.ui.plan.KeptPlacesHost {
            override fun searchMark(): com.onpath.app.search.Place? = this@AppViewModel.searchMark
            override fun isCandidate(poi: com.onpath.app.poi.Poi): Boolean = this@AppViewModel.isCandidate(poi)
        },
    )
    private val routeRun get() = planRun.routeRun
    val routing: Boolean get() = planRun.routing
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
    // The state of the route warnings lives in [PlanRouteSlice] (declared above `activity`, so above the init that calls `recalc()`)
    var routeChangedNotice: String?
        get() = planRoute.routeChangedNotice
        private set(v) { planRoute.routeChangedNotice = v }
    var syncRoutingNote: String?
        get() = planRoute.syncRoutingNote
        private set(v) { planRoute.syncRoutingNote = v }
    private var activityBefore: PlannedRoute?
        get() = planRoute.activityBefore
        set(v) { planRoute.activityBefore = v }
    var spotsOpen: Boolean
        get() = planRoute.spotsOpen
        private set(v) { planRoute.spotsOpen = v }
    var spotLine: List<GeoPoint>?
        get() = planRoute.spotLine
        private set(v) { planRoute.spotLine = v }
    private var spotsMemo: Pair<Triple<List<GeoPoint>, Activity, Int>, List<RouteSpot>>?
        get() = planRoute.spotsMemo
        set(v) { planRoute.spotsMemo = v }

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
    private var routeCancel: com.onpath.app.routing.RouteCancel?
        get() = planRun.routeCancel
        set(v) { planRun.routeCancel = v }
    /**
     * The plan run reached its time limit (owner 2026-11-03, onpath.private#21 B, [com.onpath.app.routing.SlowPlan]):
     * the box "Die Route ist lang, die Berechnung dauert. Weiter warten?" – the periods of the run that asked; null = none.
     */
    val slowPlanAsk: Int? get() = planRun.slowPlanAsk

    private val ROUTING_SLOW_MS = 2_500L
    /** The route has been calculating for a while: the plan sheet offers Cancel. */
    val routingSlow: Boolean get() = planRun.routingSlow
    /**
     * The wait card's progress (owner 2026-11-05): legs done / legs to route in the running plan run – only legs the
     * router works on count (legs from the cache are there at once); null = none known yet (the bar runs).
     */
    var routeProgress: Pair<Int, Int>?
        get() = planRun.routeProgress
        private set(v) { planRun.routeProgress = v }
    /**
     * The wait bar's share 0..1 (owner 2026-11-05: it moves within one long leg too): legs done + the running leg's
     * search ([com.onpath.app.routing.BRouterEngine.searchProgress]) over the legs to route, never back ([WaitBar]);
     * null = not known yet, or every leg routed (the wait window then checks the route, [com.onpath.app.domain.RouteShares]).
     */
    var routeShare: Float?
        get() = planRun.routeShare
        private set(v) { planRun.routeShare = v }
    /** How often the wait bar asks the router how far it is (ms). */
    private val WAIT_POLL_MS = 250L
    /** Estimated duration (s) of a plan longer than a day: "Plan it anyway?" (null = no question). */
    val longRouteAsk: Double? get() = planRun.longRouteAsk
    /** The points before the edit that made the plan very long: its maps load only after "Plan anyway". */
    private var longRouteDeferred: List<GeoPoint>?
        get() = planRun.longRouteDeferred
        set(v) { planRun.longRouteDeferred = v }
    /** The plan is left without a route on purpose (Cancel with nothing to take back); the sheet offers to calculate. */
    val notRouted: NotRouted? get() = planRun.notRouted
    /** What started the routing run: Cancel takes back only that ([PlanRouteSlice.runCauses]). */
    private val runCauses get() = planRoute.runCauses
    internal val multiDay = com.onpath.app.ui.plan.MultiDaySlice(this, plan, ::change, ::setSaved, ::rememberPlaceName, { mapData }) { nearbyNames }
    // --- saved state of the plan (menus / Manage routes) ---
    /** Name, tour id and points the plan was last saved or loaded as ([com.onpath.app.ui.plan.PlanDocument]: stored, linked, unsaved). */
    private val linkName: String? get() = plan.linkName
    private val linkId: Long get() = plan.linkId
    private val savedPoints: List<GeoPoint>? get() = plan.savedPoints
    /** The plan still belongs to that saved route (after Clear or with every point replaced it is a new plan). */
    private val planLinked: Boolean get() = plan.linked
    /** Name the plan was saved / loaded as, while it still belongs to that route. */
    val savedName: String? get() = plan.savedName
    private val savedId: Long get() = plan.savedId
    /** The plan has a route worth keeping that differs from the last save / load (RouteRules.isUnsaved). */
    val planUnsaved: Boolean get() = plan.unsaved
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
        override fun tourTimes() =
            com.onpath.app.time.TourTimesStore(File(getApplication<Application>().filesDir, com.onpath.app.time.TourTimesStore.FILE)).all()
    }
    /** Routes from the browser on the phone ([com.onpath.app.ui.sync.SyncedRoutingSlice], rebuild phase 2 piece 15); the names forward to it (declared here: `recalc()` of the init reaches it). */
    private val syncedRouting = com.onpath.app.ui.sync.SyncedRoutingSlice(plan, planRoute, toursStore,
        object : com.onpath.app.ui.sync.SyncedRoutingHost {
            override fun opened(uuid: String, points: List<GeoPoint>) = sync.opened(uuid, points)
            override fun refreshRoutingTiles(tiles: List<String>, why: String) = this@AppViewModel.refreshRoutingTiles(tiles, why)
            override fun refreshTours() = this@AppViewModel.refreshTours()
            override fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = this@AppViewModel.setSaved(name, id, points)
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        }, viewModelScope, Dispatchers.IO)

    private val saveHost = object : com.onpath.app.ui.routes.SaveHost {
        override val route: PlannedRoute? get() = this@AppViewModel.route
        override val activity: Activity get() = this@AppViewModel.activity
        override val sac: Int get() = this@AppViewModel.sac
        override fun stops(points: List<GeoPoint>) = savedStops(points)
        override fun difficulty(route: PlannedRoute?, activity: Activity) = difficultyOf(route, activity)
        override fun days(points: List<GeoPoint>) = multiDay.daysFor(points)
        override fun keepWhole() = multiDay.keepWhole()
        override fun overwriteWhole(name: String, then: (() -> Unit)?) = multiDay.overwriteWhole(name, then)
        override suspend fun proposeName(points: List<GeoPoint>, route: PlannedRoute?) = this@AppViewModel.proposeName(points, route)
        override fun datedName() = defaultName(Txt.s(Res.string.msg_name_route))
        override fun storeTimes(id: Long, route: PlannedRoute?, activity: Activity) = storePlanTimes(id, route, activity)
        override fun forgetTimes(id: Long) { plannedTimes.remove(id) }
        override fun copyTimes(from: Long, to: Long, distanceM: Double) { planTimes.get(from, distanceM)?.let { planTimes.put(to, distanceM, it) } }
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun syncChanged(why: String) = sync.changed(why)
        override fun refreshTours() = this@AppViewModel.refreshTours()
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = setSaved(name, id, points)
        override fun shownRenamed(id: Long, name: String) { shown?.let { if (it.tourId == id) shown = it.copy(name = name) } }
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)
    }
    private val save = com.onpath.app.ui.routes.SaveSlice(plan, routes, recent, toursStore, saveHost, viewModelScope, Dispatchers.IO)
    /** Save's name dialog while open (owner 2026-10-31: Save always asks for the name; [requestSave], [requestCopyRoute]). */
    val saveAsk: SaveAsk? get() = save.ask
    /** A save failed: shown in a dialog until OK (no message that disappears by itself). */
    var routesProblem: String?
        get() = save.problem
        set(v) { save.problem = v }

    // --- shown track / follow ---
    /** `nav-session.json` + `follow.json`, shared with the service: the app's writes are one locked step each ([FollowStore]). */
    private val followStore = FollowStore(app.filesDir)
    /**
     * Routes I ([com.onpath.app.ui.routes.RoutesSlice], rebuild phase 2 piece 13): My routes' list read again, a tour or route
     * opened on the map, the shown track, a track made a plan, Delete. The database behind [toursStore] (the one Save uses),
     * the rest of the app behind [routesHost]; the names ([refreshTours], [openTour], [closeShown], [deleteTour] …) forward to it.
     */
    private val routesHost = object : com.onpath.app.ui.routes.RoutesHost {
        override val touring: Boolean get() = recState.value != null
        override val recordingTourId: Long? get() = recState.value?.tourId
        override val mode: AppMode get() = this@AppViewModel.mode
        override fun enterMode(m: AppMode) = enter(m)
        override fun syncMarkSeen(t: Tour) = this@AppViewModel.syncMarkSeen(t)
        override fun rememberActivity(a: Activity) { prefs.activity = a }
        override fun rememberSac(level: Int) { prefs.sac = level }
        override fun loadedOnTheWay(on: Boolean) { tourEdits.loadedOnTheWay = on }
        override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }
        override fun change(block: MutableList<GeoPoint>.() -> Unit) = this@AppViewModel.change(block)
        override fun rememberPlaceName(p: GeoPoint, name: String) = this@AppViewModel.rememberPlaceName(p, name)
        override fun saved(name: String?, id: Long, points: List<GeoPoint>?) = setSaved(name, id, points)
        override fun opened(t: Tour, pts: List<GeoPoint>) = multiDay.opened(t, pts)
        override fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) = this@AppViewModel.syncRoutingCheck(t, pts)
        override fun standIn(pts: List<GeoPoint>, act: Activity, level: Int, preview: com.onpath.app.routing.StoredLine.Preview) {
            this@AppViewModel.standIn = StandIn(pts, act, level, preview)
        }
        override fun withMaps(t: Tour) = multiDay.withMaps(t)
        override fun kickThumbs() = this@AppViewModel.kickThumbs()
        override fun clearHighlight() { highlight = null }
        override fun followShownNow() = tourEdits.followShownNow()
        override fun ensureRoutingData(parts: List<String>) = mapData.routingData.ensure(parts)
        override fun routingParts(track: List<GeoPoint>, act: Activity) = com.onpath.app.data.RoutingTiles.forLine(track, act)
        override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        override fun syncForget(uuid: String) { sync.files.marks.remove(uuid) }
        override fun syncChanged(why: String) = sync.changed(why)
        override val route: PlannedRoute? get() = this@AppViewModel.route
        override val routing: Boolean get() = this@AppViewModel.routing
        override val followIsTrack: Boolean get() = this@AppViewModel.followIsTrack
        override fun undoStep() = this@AppViewModel.undoStep()
        override fun continueNavigation() = this@AppViewModel.continueNavigation()
        override suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = this@AppViewModel.share(name, gpx)
        override fun stopName(p: GeoPoint) = this@AppViewModel.stopName(p)
        override fun holdCameraForSharedPlace() = this@AppViewModel.holdCameraForSharedPlace()
        override var followMe: Boolean
            get() = this@AppViewModel.followMe
            set(v) { this@AppViewModel.followMe = v }
        override var freeLookAt: GeoPoint?
            get() = this@AppViewModel.freeLookAt
            set(v) { this@AppViewModel.freeLookAt = v }
        override fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)
    }
    private val routesFlow = com.onpath.app.ui.routes.RoutesSlice(plan, routes, save, tour, planRoute, toursStore, mapCamera, routesHost,
        viewModelScope, Dispatchers.IO, Dispatchers.Main, elapsedMs = { android.os.SystemClock.elapsedRealtime() })
    /**
     * Routes II ([com.onpath.app.ui.routes.RoutesGoSlice], rebuild phase 2 piece 14): what a tap / "Los geht's" in My routes does,
     * a tour's followed plan, the review, plan links in and out. Same host and store as [routesFlow]; starting or changing a
     * tour's following stays here (`startNavigation`, `continueNavigation` …), reached through [routesHost].
     */
    private val routesGo = com.onpath.app.ui.routes.RoutesGoSlice(plan, routes, tour, planRoute, save, toursStore, links, routesFlow, mapCamera,
        routesHost, viewModelScope, Dispatchers.IO)
    /** The track shown on the map ([com.onpath.app.ui.routes.RoutesSlice.shown]); the view model sets it where it ends or follows one. */
    var shown: ShownTrack?
        get() = routesFlow.shown
        private set(v) { routesFlow.shown = v }
    val followName: String? get() = tourFollow.followName
    /** Line currently followed (drawn on the map while browsing); after a re-route the new line (from the service). */
    var followLine: List<GeoPoint>?
        get() = tourFollow.followLine
        private set(v) { tourFollow.followLine = v }

    // --- position / camera: the slice is `map` ([MapViewSlice]); the names below forward to it ---
    var followMe: Boolean
        get() = map.followMe
        set(v) { map.followMe = v }
    /** Navigate free look ([MapViewSlice.freeLookAt]). */
    private var freeLookAt: GeoPoint?
        get() = map.freeLookAt
        set(v) { map.freeLookAt = v }
    /** Last finger-down on the map (MapHost). */
    var lastMapTouchMs: Long
        get() = map.lastMapTouchMs
        set(v) { map.lastMapTouchMs = v }
    /** Finger down on the map ([MapViewSlice.onMapTouch]). */
    fun onMapTouch() = map.onMapTouch()
    /** MapLibre stopped following ([MapViewSlice.onMapGesture]). */
    fun onMapGesture() = map.onMapGesture()
    /** Every fix: back to following after free look once the walker moved on. */
    fun checkFreeLook(lat: Double, lon: Double) = map.checkFreeLook(lat, lon)
    /** The start mode (Plan / Navigate) is decided (database checked for an unfinished tour). */
    var startModeKnown: Boolean
        get() = map.startModeKnown
        private set(v) { map.startModeKnown = v }
    /** The user moved or held the map since the app started (StartCamera never jumps after that). */
    var userMovedMap: Boolean
        get() = map.userMovedMap
        set(v) { map.userMovedMap = v }
    /** StartCamera ran (centred or gave up) – reset each time the app comes to the front ([onAppStart]). */
    var startCameraDone: Boolean
        get() = map.startCameraDone
        set(v) { map.startCameraDone = v }
    /** Bumped by [onAppStart]: restarts the start-camera check in MapScreen. */
    val startCameraGen: Int get() = map.startCameraGen
    /** App goes to the background (Activity.onStop). */
    fun onAppStop() = map.onAppStop()
    /** App comes to the front (Activity.onStart) – [MapViewSlice.onAppStart]. */
    fun onAppStart() = map.onAppStart()

    // --- dialogs / messages ---
    /** Says [text] where it belongs: a line where the user acted, the red box for an error, else only the log ([NoticeBoard.say]). */
    fun say(text: String, kind: NoticeKind, place: NoticePlace) = notices.say(text, kind, place)
    fun sayError(text: String, place: NoticePlace) = notices.sayError(text, place)
    fun sayInfo(text: String, place: NoticePlace) = notices.sayInfo(text, place)
    /** Settings → Updates, or the update banner on the map. */
    private fun updatePlace() = if (showSettings) NoticePlace.SETTINGS else NoticePlace.MAP

    /** Work the user started that takes a moment: a spinner where it was started. */
    var busy: Busy? get() = notices.busy; set(v) { notices.busy = v }
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

    val recState: StateFlow<RecState?> = recording.state

    // The four routing threads, the engine and the leg cache: [PlanRouteSlice] (rebuild phase 2 piece 8). Created with the
    // slice (declared at `activity` above), closed in [onCleared] where they always were.
    private val routingDispatcher get() = planRoute.threads.routing
    private val backgroundRouting get() = planRoute.threads.background
    private var engine: RoutingEngine?
        get() = planRoute.engine
        set(v) { planRoute.engine = v }
    private fun routingEngine(): RoutingEngine = planRoute.routingEngine()
    private val legCache get() = planRoute.legCache

    /**
     * A loaded route's stand-in line ([com.onpath.app.routing.StoredLine]), taken by the next [recalc] for exactly these
     * points, activity and T level: on the map at once while the run routes the legs it could not reuse.
     */
    private class StandIn(val points: List<GeoPoint>, val activity: Activity, val sac: Int, val preview: com.onpath.app.routing.StoredLine.Preview)
    private var standIn: StandIn? = null

    private var routeJob: Job?
        get() = planRun.routeJob
        set(v) { planRun.routeJob = v }
    /** The followed line was taken from this plan ("Start navigation"), so Clear removes it too. */
    private var followFromPlan: Boolean
        get() = tourFollow.followFromPlan
        set(v) { tourFollow.followFromPlan = v }
    /** All plan points were deleted during the tour, which ended the guidance; points back → followed again (PlanGuidance). */
    /** The route guidance was ended in the tour (all points deleted / "Just record"): the status says "Recording only". */
    var guidanceEndedByEmptyPlan: Boolean
        get() = tourFollow.guidanceEndedByEmptyPlan
        private set(v) { tourFollow.guidanceEndedByEmptyPlan = v }

    /** The followed line is a shown track: "Continue navigation" keeps it instead of re-planning. */
    private val followIsTrack: Boolean get() = tourFollow.followIsTrack

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
     * The names below ([followPlanned], [clearFollow], [followShown] …) forward to it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val planCopyDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val tourFollow = com.onpath.app.ui.tour.TourFollow(
        plan = plan, planRoute = planRoute, recording = recording, files = followStore,
        prefs = object : com.onpath.app.ui.tour.FollowPrefs {
            override var followIsTrack: Boolean
                get() = prefs.followIsTrack
                set(v) { prefs.followIsTrack = v }
        },
        rows = tourRows,
        host = object : com.onpath.app.ui.tour.TourFollowHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override val shown: ShownTrack? get() = this@AppViewModel.shown
            override fun placeName(p: GeoPoint): String? = this@AppViewModel.stopName(p)
            override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun stopsAsSaved(pts: List<GeoPoint>) = this@AppViewModel.savedStops(pts)
            override fun savedTourUuid(id: Long): String? = db.getTour(id)?.uuid?.takeIf { it.isNotEmpty() }
            override fun invalidateSplits() = tourEdits.splitRequests.invalidate()
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
        },
        scope = viewModelScope, planCopyDispatcher = planCopyDispatcher,
    )

    /**
     * The tour's session ([com.onpath.app.ui.tour.TourSession] in `:state`, rebuild phase 2 piece 19): the mode and what entering
     * one does, Start / Continue, the seven answers to the service's questions, and the start-up work with the collectors of the
     * service's state (`startFailed`, `state`). Declared right after the follow slice (the edits slice's host reaches it through
     * `resumeNavigation`, and `ViewModelInitOrderTest` follows that edge from the small `init`); the big `init` starts it
     * ([com.onpath.app.ui.tour.TourSession.start]) at the place that code always stood. The names below forward to it.
     */
    private val tourSession: com.onpath.app.ui.tour.TourSession = com.onpath.app.ui.tour.TourSession(
        plan = plan, planEdit = planEdit, planRun = planRun, planRoute = planRoute, save = save, map = map, tour = tour,
        follow = tourFollow, recording = recording, rows = tourRows, tours = toursStore, followHistory = followHistory,
        host = object : com.onpath.app.ui.tour.TourSessionHost {
            override val errorCard: String? get() = this@AppViewModel.errorCard
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override var shown: ShownTrack?
                get() = this@AppViewModel.shown
                set(v) { this@AppViewModel.shown = v }
            override val headingUp: Boolean get() = this@AppViewModel.headingUp
            override val hasGpsFix: Boolean get() = tracker.location.value != null
            override fun clearHints() { tapHoldHint = false; offlineInfo = false }
            override fun closeMenusForNavigate() { planPoi = null; poiMenu = null; closePointMenu("mode change to Navigate"); firstPointMenu = null; holdAddMenu = null; cancelHoldMeasure("mode change to Navigate"); highlight = null }
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
    private val tourEdits: com.onpath.app.ui.tour.TourEdits = com.onpath.app.ui.tour.TourEdits(
        plan = plan, planEdit = planEdit, planRoute = planRoute, follow = tourFollow, recording = recording,
        position = com.onpath.app.ui.Position { myPosition() }, followHistory = followHistory,
        host = object : com.onpath.app.ui.tour.TourEditsHost {
            override val route: PlannedRoute? get() = this@AppViewModel.route
            override fun replan() = recalc()
            override fun mapsForNewPoints(before: List<GeoPoint>, after: List<GeoPoint>) = cellsForNewPoints(before, after)
            override fun noPositionNote(note: PlanGps.Note, text: org.jetbrains.compose.resources.StringResource) = noPositionForEdit(note, text)
            override fun placeName(p: GeoPoint): String? = this@AppViewModel.stopName(p)
            override fun newRouteName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun routeRunEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }
            override fun closeShownTrack() = closeShown()
            override fun resumeNavigation() = tourSession.continueNavigation()
        },
        say = notices, scope = viewModelScope, routingDispatcher = routingDispatcher,
    )

    init {
        // the recording tour's id: a new tour takes the plan followed at its start, its end writes the last version
        tourFollow.start()
    }

    /**
     * The tour's end ([com.onpath.app.ui.tour.TourEndSlice] in `:state`, rebuild phase 2 piece 17): the `finished` collector,
     * the end sheet's questions and writes, the short recording, Edit rides, the interrupted tour (Continue · Save · Delete).
     * Declared above the big `init` that starts its collector ([com.onpath.app.ui.tour.TourEndSlice.start], where the collector
     * always was); the names below the big `init` forward to it. The follow state, the mode and the plan stay here behind its host.
     */
    private val tourEnd = com.onpath.app.ui.tour.TourEndSlice(
        tour = tour, routes = routes, recording = recording, tours = toursStore, rows = tourRows,
        host = object : com.onpath.app.ui.tour.TourEndHost {
            override val activity: Activity get() = this@AppViewModel.activity
            override fun activityOrNull(name: String?): Activity? = this@AppViewModel.activityOrNull(name)
            override fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = this@AppViewModel.tourTimes(t)
            override fun nameRecordedTour(t: Tour) = this@AppViewModel.nameRecordedTour(t)
            override fun refreshTours() = this@AppViewModel.refreshTours()
            override fun clearFollow() = tourFollow.clearFollow()
            override fun enterMode(m: AppMode) = enter(m)
            override fun endGuidanceFlag() { tourFollow.guidanceEndedByEmptyPlan = false }
            override fun kickThumbs() = this@AppViewModel.kickThumbs()
            override fun demoEndedWithTour() { if (com.onpath.app.recording.DemoLocation.endWithTour()) demo.navSimLine = null }
            override fun tourChanged(id: Long) = Backups.tourChanged(getApplication(), id)
            override fun clearInterruptedNote() = this@AppViewModel.clearInterruptedNote()
            override val planPoints: Int get() = waypoints.size
            override fun clearPlan() = this@AppViewModel.clearPlan()
            override var routeBackAsk: Boolean
                get() = this@AppViewModel.routeBackAsk
                set(v) { this@AppViewModel.routeBackAsk = v }
            override var planSize: PlanSize
                get() = this@AppViewModel.planSize
                set(v) { this@AppViewModel.planSize = v }
            override val shownTourId: Long? get() = shown?.tourId
            override fun reopenShownTour(t: Tour) = this@AppViewModel.openTour(t)
        },
        say = notices, scope = viewModelScope, io = Dispatchers.IO, main = Dispatchers.Main,
    )

    init {
        recalc()
        // every plan change is traced, so the diagnostics show what the user did
        Problems.trace("plan", "Plan restored at app start: ${waypoints.size} points" +
            (followName?.let { ", following \"$it\" (${followLine?.size ?: 0} line points)" } ?: ", nothing followed"))
        refreshTours() // "Load route" is enabled only when something is saved
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

    /** E2E: the legs routed in this process are forgotten, as after an app restart – only the phone's leg store keeps them. */
    fun forgetLegsInMemoryForTest() = legCache.clear()

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
     * import, "Export all" / Export GPX, "Restore from folder", the first-start restore offer. The names below forward to it.
     */
    private val importExport by lazy {
        ImportExport(app, viewModelScope, Dispatchers.IO, db, object : ImportExportHost {
            override val recState: StateFlow<RecState?> get() = this@AppViewModel.recState
            override val dataFiles: DataFiles get() = this@AppViewModel.dataFiles
            override val prefs: Prefs get() = this@AppViewModel.prefs
            override val cells: com.onpath.app.cells.MapCells get() = this@AppViewModel.cells
            override val sync: com.onpath.app.plansync.PlanSync get() = this@AppViewModel.sync
            override val events: MutableList<UiEvent> get() = this@AppViewModel.events
            override val activity: Activity get() = this@AppViewModel.activity
            override val showSettings: Boolean get() = this@AppViewModel.showSettings
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
                get() = this@AppViewModel.busy
                set(v) { this@AppViewModel.busy = v }
            override var sac: Int
                get() = this@AppViewModel.sac
                set(v) { this@AppViewModel.sac = v }
            override var routesOpen: Boolean
                get() = this@AppViewModel.routesOpen
                set(v) { this@AppViewModel.routesOpen = v }
            override var savedPlaces: com.onpath.app.search.SavedPlaces?
                get() = keptPlaces.savedPlaces
                set(v) { keptPlaces.savedPlaces = v }
            override fun recheckData() = this@AppViewModel.recheckData()
            override fun packageNote() = this@AppViewModel.packageNote()
            override fun say(text: String, kind: NoticeKind, place: NoticePlace) = this@AppViewModel.say(text, kind, place)
            override fun sayInfo(text: String, place: NoticePlace) = this@AppViewModel.sayInfo(text, place)
            override fun sayError(text: String, place: NoticePlace) = this@AppViewModel.sayError(text, place)
            override fun refreshTours() = this@AppViewModel.refreshTours()
            override fun planFactors(a: Activity) = this@AppViewModel.planFactors(a)
            override fun defaultName(prefix: String) = this@AppViewModel.defaultName(prefix)
            override fun selectActivity(a: Activity, userChange: Boolean) = this@AppViewModel.selectActivity(a, userChange)
            override fun showTrack(s: ShownTrack) = this@AppViewModel.showTrack(s)
        })
    }

    /** Imports the onpath-data zip or a single data/style file picked by the user. */
    fun importData(uri: Uri) = importExport.importData(uri)

    // ---------------- activity: [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    /** The activity chosen in the picker: the route is planned again (see [PlanRouteSlice.selectActivity]). */
    fun selectActivity(a: Activity, userChange: Boolean = true) = planRoute.selectActivity(a, userChange)
    /** A stored activity name; null for none or an unknown / old one (expected: the caller falls back). */
    private fun activityOrNull(name: String?): Activity? = planRoute.activityOrNull(name)
    /** T level from the activity picker (Hike): remembered, the route is planned again at once. */
    fun selectSac(level: Int) = planRoute.selectSac(level)

    // ---------------- planning ----------------

    fun updateSettings(s: AppSettings) = settingsSlice.updateSettings(s)

    /** One undoable change of the planned points ([PlanEditSlice.change]). */
    private fun change(block: MutableList<GeoPoint>.() -> Unit) = planEdit.change(block)

    /** A tour event for the spoken directions (the service says it when they are on). */
    private fun sayEvent(what: String) {
        if (recState.value != null) recording.say(what)
    }

    /** Current position (the map's GPS), else the tour's last recorded point; null without either. */
    private fun myPosition(): GeoPoint? =
        tracker.location.value?.let { GeoPoint(it.latitude, it.longitude) }
            ?: recState.value?.track?.lastOrNull()?.lastOrNull()

    /** The plan points passed in the running tour ([TourEdits.tourPassed]). */
    fun tourPassed(plan: List<GeoPoint> = waypoints.toList()): Set<Int> = tourEdits.tourPassed(plan)

    /** "Route back to the start?" is asked ([PlanEditSlice.routeBackAsk]); the tour's answer below sets it too. */
    var routeBackAsk: Boolean
        get() = planEdit.routeBackAsk
        private set(v) { planEdit.routeBackAsk = v }

    /** The tour's answer to "Route back to the start?" ([TourEdits.answerRouteBack]). */
    fun answerRouteBack(yes: Boolean) = tourEdits.answerRouteBack(yes)

    /** Adds a held point where it most likely belongs ([PlaceAddSlice.addPoint]). */
    fun addPoint(p: GeoPoint, lineSnapM: Double = 30.0, onPoi: Boolean = false) = placeAdd.addPoint(p, lineSnapM, onPoi)

    /** The add entries of a held place's menu ([PlaceMenusSlice.placeMenu]). */
    fun placeMenu(poi: com.onpath.app.poi.Poi): PlaceAddMenu.Menu = placeMenus.placeMenu(poi)

    /** Place menu → „Einfügen“ ([PlaceAddSlice.addPoiAsStop]). */
    fun addPoiAsStop(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) = placeAdd.addPoiAsStop(poi, lineSnapM)

    /** Place menu → „Neues Ziel“ ([PlaceAddSlice.poiAsNewDestination]). */
    fun poiAsNewDestination(poi: com.onpath.app.poi.Poi) = placeAdd.poiAsNewDestination(poi)

    /** The open „Einfügen“ / „Neues Ziel“ menu of a hold on the empty map; null = none ([PlaceAddSlice.holdAddMenu]). */
    var holdAddMenu: PlaceAddSlice.HoldAdd?
        get() = placeAdd.holdAddMenu
        set(v) { placeAdd.holdAddMenu = v }

    /** A hold beside the plan being measured on the paths ([PlaceAddSlice.holdMeasuring]): the spot, a ring turns on it; null = none. */
    var holdMeasuring: GeoPoint?
        get() = placeAdd.holdMeasuring
        private set(v) { placeAdd.holdMeasuring = v }

    /** A hold on the empty map in Plan ([PlaceAddSlice.holdOnMap], core feature C1). */
    fun holdOnMap(p: GeoPoint, lineSnapM: Double) = placeAdd.holdOnMap(p, lineSnapM)

    /** Stops a running measure of a hold ([PlaceAddSlice.cancelHoldMeasure]). */
    private fun cancelHoldMeasure(why: String) = placeAdd.cancelHoldMeasure(why)

    /**
     * The routed length [a] → [b] for [act] / [level] in metres (on the hold's thread): from [legCache], else one leg by the
     * [Planner] (as a plan run routes it – then in the cache for the run after the decision). Throws when the leg has
     * no route (a straight leg: no routing data, no way near the spot, an island) or [cancel] fired.
     */
    private fun routedLength(a: GeoPoint, b: GeoPoint, act: Activity, level: Int, cancel: com.onpath.app.routing.RouteCancel): Double {
        legCache.lookup(a, b, act, level)?.let { return it.leg.distanceM }
        val r = Planner(routingEngine(), cancel, legs = legCache).plan(listOf(a, b), act, level)
        if (r.legs.any { it.straight }) throw com.onpath.app.routing.RoutingException(r.error ?: "no route for the leg")
        return r.distanceM
    }

    /** The hold menu's „Einfügen“ ([PlaceAddSlice.holdAsStop]). */
    fun holdAsStop() = placeAdd.holdAsStop()

    /** The hold menu's „Neues Ziel“ ([PlaceAddSlice.holdAsNewDestination]). */
    fun holdAsNewDestination() = placeAdd.holdAsNewDestination()

    /** The candidate marker's dashed preview line ([PlaceAddSlice.candidateJoin]). */
    fun candidateJoin(): Pair<GeoPoint, GeoPoint>? = placeAdd.candidateJoin()

    /** Plan point [index] deleted (map menu, search card): a round trip stays closed, as in the Points list. */
    fun removePoint(index: Int) = planEdit.removePoint(index)

    /** Search result shown in the place card ([PlaceMenusSlice.planPoi]). */
    var planPoi: com.onpath.app.poi.Poi?
        get() = placeMenus.planPoi
        set(v) { placeMenus.planPoi = v }
    /** Eye button held: route lines and markers are hidden (position and recorded track stay). */
    var routePeek by mutableStateOf(false)

    // ---------------- map hold menus (MapGestures, ui/MapOverlays) ----------------
    /** POI held on the map in Plan: its menu "Add to route · Open in Google Maps" ([PlaceMenusSlice.poiMenu]). */
    var poiMenu: com.onpath.app.poi.Poi?
        get() = placeMenus.poiMenu
        set(v) { placeMenus.poiMenu = v }

    /** First hold on an empty plan: the held spot, its menu "Start here · Go here (from my location)" is open ([PlaceAddSlice.firstPointMenu]). */
    var firstPointMenu: GeoPoint?
        get() = placeAdd.firstPointMenu
        set(v) { placeAdd.firstPointMenu = v }

    val mapOverlayOpen: Boolean get() = placeMenus.mapOverlayOpen

    /** Closes every map menu ([PlaceMenusSlice.closeMapOverlays]). */
    fun closeMapOverlays(why: String = "closed") = placeMenus.closeMapOverlays(why)

    /** Closes the point menu (if open) ([PlaceMenusSlice.closePointMenu]). */
    fun closePointMenu(why: String) = placeMenus.closePointMenu(why)

    fun openFirstPointMenu(p: GeoPoint) = placeAdd.openFirstPointMenu(p)

    /** First-point menu → "Start here" ([PlaceAddSlice.startHere]). */
    fun startHere(p: GeoPoint) = placeAdd.startHere(p)

    /** First-point menu → "Go here" ([PlaceAddSlice.routeFromMyLocation]). */
    fun routeFromMyLocation(p: GeoPoint) = placeAdd.routeFromMyLocation(p)
    /** Opens a held place's menu ([PlaceMenusSlice.openPoiMenu]). */
    fun openPoiMenu(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) = placeMenus.openPoiMenu(poi, lineSnapM)

    /** The pin's name chip tapped ([PlaceMenusSlice.openPinMenu]). */
    fun openPinMenu() = placeMenus.openPinMenu()

    /** The found place's pin goes ([PlaceMenusSlice.removePin]). */
    fun removePin(why: String) = placeMenus.removePin(why)

    /** POI menu on an empty plan → "Go here" ([PlaceAddSlice.poiFromMyLocation]). */
    fun poiFromMyLocation(poi: com.onpath.app.poi.Poi) = placeAdd.poiFromMyLocation(poi)
    /** Opens the menu of route point [index] ([PlaceMenusSlice.openPointMenu]). */
    fun openPointMenu(index: Int, distDp: Int? = null, via: String = "hold") = placeMenus.openPointMenu(index, distDp, via)

    /** What the POI menu's add action says ([PlaceMenusSlice.poiAddLabel]). */
    fun poiAddLabel(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0): String = placeMenus.poiAddLabel(poi, lineSnapM)

    fun addPoiPoint(poi: com.onpath.app.poi.Poi, lineSnapM: Double = 30.0) = placeAdd.addPoiPoint(poi, lineSnapM)

    /** Point menu → "Start here" ([PlaceMenusSlice.makeStartFromMenu]). */
    fun makeStartFromMenu() = placeMenus.makeStartFromMenu()

    fun isRoundTrip(): Boolean = PlanEdits.isRoundTrip(waypoints)
    /** Index of the round trip's ① (stops on the way before it), null without a loop ([PlanEdits.loopStart]). */
    fun loopStart(): Int? = PlanEdits.loopStart(waypoints)

    // ---------------- Points section of the full plan sheet (PointsSection, routing/PlanPoints) ----------------

    /** Rows of the Points section ([PlanEditSlice.planPointRows]). */
    fun planPointRows(): List<com.onpath.app.routing.PlanPointRow> = planEdit.planPointRows(route)
    /** Names the route points from the offline place index ([PlanEditSlice.refreshPointNames]). */
    fun refreshPointNames() = planEdit.refreshPointNames()
    /** Route details open ([PlanEditSlice.routeDetailsOpen]). */
    var routeDetailsOpen: Boolean
        get() = planEdit.routeDetailsOpen
        set(v) { planEdit.routeDetailsOpen = v }
    private val nearbyNames: Map<GeoPoint, String> get() = planEdit.nearbyNames
    /** Points list: row dropped at another row ([PlanEditSlice.movePointInList]). */
    fun movePointInList(from: Int, to: Int) = planEdit.movePointInList(from, to)
    /** Points list → ✕ ([PlanEditSlice.deletePointFromList]). */
    fun deletePointFromList(index: Int) = planEdit.deletePointFromList(index)

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

    /** Point menu → "Delete" ([PlanEditSlice.deletePointFromMenu]). */
    fun deletePointFromMenu() = planEdit.deletePointFromMenu()

    /** Point menu → "Open in Google Maps" (closes the menu; the caller opens the link). */
    fun googleFromMenu(): GeoPoint? = placeMenus.menuAction("Open in Google Maps")?.let { waypoints[it] }
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

    // --- kept places: Zuhause + favourites (owner 2026-11-15, „Orte merken“; SavedPlaces) ---

    fun isFavouritePlace(poi: com.onpath.app.poi.Poi): Boolean = keptPlaces.isFavouritePlace(poi)
    fun isHomePlace(poi: com.onpath.app.poi.Poi): Boolean = keptPlaces.isHomePlace(poi)
    /** „☆ Merken“ / „★ Gemerkt“ in a held place's menu ([KeptPlacesSlice.toggleFavouritePlace]). */
    fun toggleFavouritePlace(poi: com.onpath.app.poi.Poi) = keptPlaces.toggleFavouritePlace(poi)
    /** „⌂ Als Zuhause festlegen“ / „⌂ Zuhause entfernen“ ([KeptPlacesSlice.toggleHomePlace]). */
    fun toggleHomePlace(poi: com.onpath.app.poi.Poi) = keptPlaces.toggleHomePlace(poi)
    private fun clearSearchMarkAt(lat: Double, lon: Double) = searchFlow.clearSearchMarkAt(lat, lon)

    /** The found place at ([lat], [lon]) goes into the plan: its pin goes; returns that place (null = no pin there). */
    private fun takePin(lat: Double, lon: Double): com.onpath.app.search.Place? {
        val m = searchMark?.takeIf { sameSpot(GeoPoint(it.lat, it.lon), lat, lon) }
        clearSearchMarkAt(lat, lon)
        if (m != null) Problems.trace("plan", "Pin taken into the plan: \"${m.title()}\"")
        return m
    }

    /** Place shown in the card: the search result, or the tapped POI ([PlaceMenusSlice.cardPlace]). */
    val cardPlace: com.onpath.app.search.Place? get() = placeMenus.cardPlace

    fun closePlaceCard() = placeMenus.closePlaceCard()

    private fun routeIndexOf(place: com.onpath.app.search.Place) =
        waypoints.indexOfFirst { sameSpot(it, place.lat, place.lon) }.takeIf { it >= 0 }

    /** Card content incl. what "Add to route" will do ([PlaceMenusSlice.placeCardUi]). */
    fun placeCardUi(place: com.onpath.app.search.Place): com.onpath.app.ui.search.PlaceCardUi = placeMenus.placeCardUi(place)

    /** Adds the place as a pinned stop (passed exactly), named after the place ([PlaceAddSlice.addPlace]). */
    fun addPlace(place: com.onpath.app.search.Place, role: PlaceRole) = placeAdd.addPlace(place, when (role) {
        PlaceRole.SMART -> AddRole.SMART
        PlaceRole.START -> AddRole.START
        PlaceRole.DESTINATION -> AddRole.DESTINATION
    })

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

    fun makeDestination(index: Int) = planEdit.makeDestination(index)
    /** "⇅ Umkehren" changes the plan ([PlanEditSlice.canReverse]). */
    fun canReverse(): Boolean = planEdit.canReverse()
    fun reverseRoute() = planEdit.reverseRoute()
    /** "↻ Rundtour" can be switched ([PlanEditSlice.canRoundTrip]). */
    fun canRoundTrip(): Boolean = planEdit.canRoundTrip()
    fun roundTrip() = planEdit.roundTrip()
    fun undoStep() = planEdit.undoStep()
    fun redoStep() = planEdit.redoStep()
    /** ✕ / ☰ Neu planen: the plan leaves the map at once, no question ([PlanEditSlice.clearPlan]). */
    fun requestClear() = planEdit.clearPlan()
    fun clearPlan() = planEdit.clearPlan()

    fun answerLongRoute(plan: Boolean) {
        val effects = routeEvent(RouteRun.Event.LongAnswer(plan, waypoints.toList()))
        // Plan anyway: not asked again for these points, now the new point's maps load, the run again
        if (plan) { perform(effects); return }
        // points kept unrouted: "Plan anyway" later loads them (an undo replaces it)
        if (longRouteDeferred != null) Problems.trace("cells", "Very long plan cancelled: nothing was loaded for it")
        // only the change that made it so long is taken back (Redo brings it again); nothing to take back (a plan
        // restored at start or loaded, newer routing data): the points stay without a route – never "Calculating…"
        // with nothing running
        perform(effects) // [cancelRun]
        // the map goes back to what was planned before the far point (owner 2026-11-13): the point / the whole plan, during
        // a tour my position; the found place's pin and its chip stay (cancelRun)
        val target = LongRouteCancelCamera.after(waypoints.toList(), route?.points, touring = recState.value != null,
            hasPosition = tracker.location.value != null)
        Problems.trace("map", "Very long plan cancelled: camera → ${target::class.simpleName}" +
            ((target as? LongRouteCancelCamera.Target.Fit)?.let { " (${it.points.size} points)" } ?: ""))
        when (target) {
            LongRouteCancelCamera.Target.Me -> locate()
            is LongRouteCancelCamera.Target.Fit -> { followMe = false; freeLookAt = null; events += UiEvent.Fit(target.points) }
            LongRouteCancelCamera.Target.Stay -> Unit
        }
    }

    /** The activity and T level from before the cancelled change; the route is planned for them again (no undo step). */
    private fun restoreActivity(c: PlanStatus.Cause.ActivityChange) {
        activity = c.activity; prefs.activity = c.activity
        sac = c.sac; prefs.sac = c.sac
        routeChangedNotice = null; activityBefore = null; routeEvent(RouteRun.Event.CompareActivity(false))
        runCauses.other()
        recalc()
    }

    // The route run's state, its effects and its entry points: [PlanRunSlice] (rebuild phase 2 piece 9); the run's coroutine is [recalc].
    /** The answer to [slowPlanAsk]: wait one more period (the run again, longer) or stop like Cancel. */
    fun answerSlowPlan(answer: com.onpath.app.routing.SlowPlan.Answer) = planRun.answerSlowPlan(answer)
    /** "Plan anyway" / "Calculate" of the not-routed notice. */
    fun planAnyway() = planRun.planAnyway()
    private fun routeEvent(e: RouteRun.Event): List<RouteRun.Effect> = planRun.routeEvent(e)
    private fun perform(effects: List<RouteRun.Effect>) = planRun.perform(effects)
    private fun leaveUnrouted(why: NotRouted) = planRun.leaveUnrouted(why)
    /** Cancel while a route is calculated (owner): [PlanRunSlice.cancelRouting]. */
    fun cancelRouting() = planRun.cancelRouting()

    /**
     * From the planning thread ([Planner]'s progress): the wait card's legs done / to route ([routeProgress]) – only while
     * the run [gen] is the current one.
     */
    private fun showProgress(gen: Int, done: Int, total: Int) {
        viewModelScope.launchSafe("app", "showProgress", Dispatchers.Main) { if (routeRun.isCurrent(gen) && routing) routeProgress = done to total }
    }

    private fun recalc() {
        runCauses.start()
        routeJob?.cancel(); routeJob = null
        highlight = null; spotLine = null; spotsOpen = false
        val pts = waypoints.toList()
        val pins = pinnedIndices()
        val stand = standIn?.takeIf { it.points == pts && it.activity == activity && it.sac == sac }; standIn = null
        routeCancel?.cancel(); routeCancel = null // an older request stops at once
        planReach = null
        routeProgress = null; routeShare = null
        tourEdits.followCancel?.cancel(); tourEdits.followCancel = null // the tour's follow line of the old plan: a new one comes with this run
        val act = activity; val level = sac
        // what this run is ([RouteRun.Event.Recalc]): fewer than two points → no route; a plan far beyond a day (a point in
        // another country by mistake, owner) is asked about first – nothing is routed or loaded until the answer; else
        // it is routed. A new run (an edit, an activity change …) starts at one time-limit period and drops an open
        // "Weiter warten?"; in Plan the time limit asks (SlowPlan), a run for Navigate keeps the straight lines
        val effects = routeEvent(RouteRun.Event.Recalc(pts, act, planMode = mode == AppMode.PLAN, newPlan = SheetRise.resets(pts.size)))
        perform(effects)
        val start = effects.filterIsInstance<RouteRun.Effect.StartRouting>().firstOrNull() ?: return
        val gen = start.gen; val rounds = start.rounds; val askAtLimit = start.askAtLimit
        // the route before an activity / T level change, compared with this run's result ("Route changed for …")
        val changeBefore = activityBefore.takeIf { start.compareActivity }
        val f = planFactors(act)
        // a loaded route (owner 2026-11-07): its stored line at once while this run routes the legs it could not reuse;
        // replaced by the run's line (said only when the way differs) – never no line while routing
        val standBefore = stand?.let { s ->
            route = withModelTime(s.preview.route, act, f)
            Problems.trace("routing", "Route #$gen: the stored line shown at once (${s.preview.stale} legs on older routing data, " +
                "${s.preview.fromLine} from the saved line) – routed again")
            route?.points
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val cancel = com.onpath.app.routing.RouteCancel().also { routeCancel = it }
        routeJob = viewModelScope.launchSafe("app", "recalc") {
            delay(150) // debounce quick taps
            // the Cancel button appears only when it takes a while (no flicker for normal routes)
            val slow = launch { delay(ROUTING_SLOW_MS); routeEvent(RouteRun.Event.Slow(gen)) }
            // the wait bar within a leg (owner 2026-11-05): the router's search, polled off the main thread
            val planThread = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
            val bar = WaitBar()
            val poll = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(WAIT_POLL_MS)
                    val t = planThread.get() ?: continue
                    val legs = routeProgress ?: continue
                    val share = bar.next(legs.first, legs.second, (engine as? com.onpath.app.routing.BRouterEngine)?.searchProgress(t))
                    withContext(Dispatchers.Main) { if (routeRun.isCurrent(gen) && routing) routeShare = share }
                }
            }
            var planner: Planner? = null
            var reach: com.onpath.app.routing.PlanReach? = null
            val result = withContext(routingDispatcher) {
                exceptions {
                    val eng = routingEngine()
                    // route, model time, snapped points, gaps, warnings: the one computation the web planner shares
                    // (named stops: read on the main thread when the result is taken – snapToRoute → PlanResult.withNamed)
                    // askAtLimit: in Plan the time limit asks "Weiter warten?" (SlowPlan); a run for Navigate keeps the straight lines
                    com.onpath.app.routing.PlanResult.plan(Planner(eng, cancel, askAtLimit = askAtLimit, rounds = rounds, legs = legCache,
                        progress = { done, total -> planThread.set(Thread.currentThread()); showProgress(gen, done, total) }).also { planner = it },
                        pts, act, level, pins, emptySet(), f)
                }.also { r ->
                    // a stop not reached: what does reach it ("② erst ab T2 erreichbar") – only the missing piece,
                    // after the plan route, on the same thread; the next edit cancels it with the route ([cancel])
                    r.getOrNull()?.gaps?.firstOrNull()?.let { g -> reach = findReach(g, act, level, cancel) }
                }
            }
            slow.cancel(); poll.cancel(); routeShare = null // every leg routed: the wait window checks the route (RouteShares)
            // the corridor work of a result off the main thread
            val post = result.getOrNull()?.let { r -> withContext(Dispatchers.Default) { mapData.postRoute(r.route, pts, act) } }
            val back = routeEvent(RouteRun.Event.Returned(gen, isActive))
            if (RouteRun.Effect.ClearProgress in back) { routeProgress = null; routeShare = null }
            if (RouteRun.Effect.Superseded in back) { // superseded or cleared
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
            // what the tour does with this result, after it is taken ([RouteRun.Effect.FollowNewRoute] / FollowEditedPlan)
            var after = emptyList<RouteRun.Effect>()
            result.onSuccess { res ->
                val it = res.route
                route = it; planReach = reach; snapToRoute(pts, res)
                // the plan's first route: the sheet rises once to its middle step (owner 2026-11-08, [SheetRise] – once per
                // plan is the run's: [RouteRun.sheetRisen])
                after = routeEvent(RouteRun.Event.Succeeded(gen, planMode = mode == AppMode.PLAN,
                    sheetCanRise = SheetRise.rises(risen = false, planSize, mode == AppMode.PLAN, recState.value != null)))
                val take = after.filterIsInstance<RouteRun.Effect.TakeRoute>().firstOrNull() ?: return@onSuccess
                if (take.rises) {
                    planSize = SheetRise.TO
                    Problems.trace("plan", "First route of the plan: the sheet rises to ${SheetRise.TO}")
                }
                // a rising sheet: the fit waits until its new height is known (MapScreen hears it once it rests)
                if (take.fit) fitFirstRoute(it.points, pts, if (take.rises) SheetRise.FIT_WAIT_MS else 0L)
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
                if (multiDay.overview) mapData.corridorFollowsPlan(it.points, act) else mapData.routeComputed(it.points, post?.cells, dest, act) // a multi-day tour: its map question (§7)
                if (take.freshen) freshenAfterRoute(it.points, post?.cells, act)
                if (take.viaData) routingDataRouteChanged(it)
                else if (standBefore != null && com.onpath.app.data.RoutingDataCheck.differs(standBefore, it.points))
                    routeChangedNotice = Txt.s(Res.string.msg_route_updated_newer_data)
                // the plan re-computed for an activity switched while navigating: not an edit (Continue keeps the tour)
                if (take.adoptBaseline) planBaseline = it.points
            }
                .onFailure {
                    if (it is com.onpath.app.routing.SlowPlan.TimeLimit) {
                        // the time limit in Plan: ask (the box stays until answered); the points and the line stay as
                        // they are – no straight lines, no error line
                        Problems.info("routing", "Plan reached its time limit after ${it.rounds} period(s) – asking to wait on")
                        routeEvent(RouteRun.Event.TimeLimit(gen, it.rounds))
                        return@launchSafe
                    }
                    // no fit of a later route: the points were fitted when they came (link) or are where the user held
                    after = routeEvent(RouteRun.Event.Failed(gen))
                    if (RouteRun.Effect.ShowFailure !in after) return@onFailure
                    Problems.error("routing", "Route calculation failed", it)
                    // during a tour one failed run must not take the whole plan line off the map (fix-tour-edit-line.md)
                    if (recState.value == null) route = null
                    // our own routing texts only – never an exception's raw text (review Arch 4)
                    routeError = com.onpath.app.debug.UserText.of(it, Txt.s(Res.string.msg_routing_failed))
                    mapData.corridorFollowsPlan(null)
                }
            for (fx in after) when (fx) {
                // "Continue navigation" waited for this result
                RouteRun.Effect.FollowNewRoute ->
                    if (route != null) followPlanned(checkNow = true) else sayError(Txt.s(Res.string.msg_no_new_route), NoticePlace.MAP)
                // an edit of the plan during a tour: the tour follows the new plan (owner rule, no question) – not a plan
                // re-computed for an activity switched while navigating (its baseline)
                RouteRun.Effect.FollowEditedPlan -> tourEdits.followEditedPlan()
                else -> Unit
            }
            routeEvent(RouteRun.Event.Done(gen)) // "calculating" ends once the result is taken
        }
    }

    // ---------------- time estimate (docs/plan-time-estimate.md): [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    /** The pace setting for planning with [a] (learning per tour only: nothing from earlier tours). */
    fun planFactors(a: Activity = activity): com.onpath.app.time.Factors = planRoute.planFactors(a)
    /** Route sheet ⋯ → Time details (debug view of the estimate); null = closed. */
    var timeDetails: TimeDetailsUi?
        get() = planRoute.timeDetails
        set(v) { planRoute.timeDetails = v }
    fun openTimeDetails(technical: Boolean = false) = planRoute.openTimeDetails(technical)
    private fun withModelTime(r: PlannedRoute, a: Activity, f: com.onpath.app.time.Factors): PlannedRoute = planRoute.withModelTime(r, a, f)
    private fun difficultyOf(r: PlannedRoute?, a: Activity): String? = planRoute.difficultyOf(r, a)
    private fun tourTimes(t: Tour): com.onpath.app.time.TourTimes? = planRoute.tourTimes(t)
    private fun retime() = planRoute.retime()
    private fun storePlanTimes(id: Long, r: PlannedRoute?, a: Activity) = planRoute.storePlanTimes(id, r, a)
    private fun withModelTime(t: Tour): Tour = planRoute.withModelTime(t)

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
        plan.moveSnapped(routed, snapped)
        aliasSnappedLegs(routed, snapped, res.activity, res.sac)
        if (routed.size >= 2 && !RouteRules.isUnsaved(routed, savedPoints)) setSaved(linkName, linkId, snapped)
        links.snapped(routed, snapped)
        val moves = WaypointSnap.moves(routed, snapped)
        plan.snappedHistory(moves)
        // an open menu follows its point to the snapped position (it must still find it for Delete)
        placeMenus.followSnapped(moves)
    }

    /**
     * The points were moved onto the line ([snapToRoute]): the run's legs under the moved points too
     * ([com.onpath.app.routing.LegCache.alias]) – the plan as saved now loads its legs, and the next edit routes only its
     * new legs (before, the first edit after a snap routed every leg again from the moved points).
     */
    private fun aliasSnappedLegs(routed: List<GeoPoint>, snapped: List<GeoPoint>, act: Activity, level: Int) {
        if (routed.size != snapped.size) return
        val moved = (0 until routed.size - 1).filter { snapped[it] != routed[it] || snapped[it + 1] != routed[it + 1] }
        if (moved.isEmpty()) return
        viewModelScope.launchSafe("app", "aliasSnappedLegs", Dispatchers.IO) {
            val n = moved.count { legCache.alias(routed[it], routed[it + 1], snapped[it], snapped[it + 1], act, level) }
            Problems.trace("routing", "$n of ${moved.size} legs kept under their points moved onto the line")
        }
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

    // ---------------- route warnings: [PlanRouteSlice] (rebuild phase 2 piece 8) ----------------

    private fun planChanged() = planRoute.planChanged()
    /** Spots worth knowing on the current route ([RouteWarnings.detect]); none while it is being calculated. */
    val routeSpots: List<RouteSpot> get() = planRoute.routeSpots
    /** The amber line of the plan sheet: spots on the way only (pushing, steps, above T, steep). */
    fun warningLineText(): String? = planRoute.warningLineText()
    /** How many hints the amber line sums up: the ⚠ count of the rolled-up sheet. */
    fun warningCount(): Int = planRoute.warningCount()
    /** Grey INFO line "New route for … – check it" (activity / T level change during a tour or from elsewhere, newer map data). */
    fun routeChangedLineText(): String? = planRoute.routeChangedLineText()
    /** Tap on the "New route for …" line: it goes, the map shows the whole route. */
    fun tapRouteChangedLine() = planRoute.tapRouteChangedLine()
    /** Tap on the amber line: the notice goes; the list of spots opens (none: the map shows the whole route). */
    fun tapWarningLine() = planRoute.tapWarningLine()
    fun closeSpots() = planRoute.closeSpots()

    /** What the map column's greyed buttons need to know ([MapControlsEnabled]). */
    internal fun controlsIn(layer: AppMode, pointsOpen: Boolean) = MapControlsEnabled.In(layer, pointsOpen,
        planLine = map.hasPlanLine(), shownLine = map.hasShownLine(), shownOpen = shown != null, followLine = map.hasFollowLine(),
        canUndo = canUndo, canRedo = canRedo)

    /** "Show whole route" (map button, owner 2026-10-31) – [MapViewSlice.showWholeRoute]. */
    fun showWholeRoute() = map.showWholeRoute()

    /** Locate while I am off the map in Plan ([LocateDistance], owner 2026-11-07) – [MapViewSlice.showPlanAndMe]. */
    fun showPlanAndMe() = map.showPlanAndMe()

    /** The whole plan in front at a [PlanCamera.Moment] (owner 2026-11-06) – [MapViewSlice.showPlanWhole]. */
    fun showPlanWhole(moment: PlanCamera.Moment) = map.showPlanWhole(moment)

    /** The plan's first route is there: the map shows it whole – [MapViewSlice.fitFirstRoute]. */
    private fun fitFirstRoute(line: List<GeoPoint>, pts: List<GeoPoint>, extraMs: Long = 0L) = map.fitFirstRoute(line, pts, extraMs)

    /** A spot in the list tapped: the list closes, following stops, the map shows that piece (drawn amber). */
    fun showSpot(s: RouteSpot) {
        val pts = route?.points ?: return
        showPiece(RouteWarnings.showPoints(pts, s), RouteWarnings.centerFor(pts, s))
    }

    /** One section on the map (a spot of the list, the way to a point not reached): drawn amber, the map goes there. */
    private fun showPiece(piece: List<GeoPoint>, center: Pair<GeoPoint, Double>?) = map.showPiece(piece, center)

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

    // ---------------- Save (C18) – the slice is `save` ([SaveSlice]); the names below forward to it ----------------

    /**
     * Save – the sheet's green Save, ☰ "Save route", sheet ⋯ → Save route – always opens the name dialog (owner
     * 2026-10-31, replaces "save at once"; [SaveSlice.request]).
     */
    fun requestSave() = save.request(null)

    /** My routes → ⋮ → Save as copy (planned routes; owner 2026-10-31): the same dialog with "<name> (2)" ([SaveSlice.requestCopy]). */
    fun requestCopyRoute(t: Tour) = save.requestCopy(t)

    /** Save's dialog → Cancel / Back / a tap outside: nothing is saved, "Save first" does not load. */
    fun cancelSave() = save.cancel()

    /** Save's dialog → Save with [input] ([SaveSlice.confirm]). */
    fun confirmSave(input: String) = save.confirm(input)

    /** Saves the plan as a new planned route named [name] ([SaveSlice.saveRoute]). */
    fun saveRoute(name: String, then: (() -> Unit)? = null) = save.saveRoute(name, then)

    /** Manage routes → Rename. The plan's "Saved as …" and a shown track follow the new name ([SaveSlice.rename]). */
    fun renameTour(t: Tour, input: String) = save.rename(t, input)

    /** My routes → ☆ on a row (owner 2026-11-13, favourites C; [SaveSlice.setFavourite]). */
    fun setFavourite(t: Tour, favourite: Boolean) = save.setFavourite(t, favourite)

    /** My routes → Zuletzt → Speichern: the plan becomes saved (backup, browser sync); it shows "✓ Gerade gespeichert". */
    fun saveDraft(t: Tour) = recent.saveDraft(t)

    /** My routes → Zuletzt → Löschen: to the recycle bin at once, no question (owner 2026-11-06). */
    fun deleteDraft(t: Tour) = recent.deleteDraft(t)

    /**
     * The plan on the map for My routes (owner 2026-11-06 23:00: "unter Zuletzt ‚gerade in Bearbeitung', dann weiß ich, dass
     * immer gespeichert wird"): [LivePlanRows] makes it the one Zuletzt row ("In Bearbeitung"). Null = empty map.
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

    suspend fun routeSnapshot(segments: List<List<GeoPoint>>, tint: RouteShape.Tint, widthPx: Int, heightPx: Int,
                              paddingPx: Int, under: List<GeoPoint> = emptyList()): com.onpath.app.map.RouteSnapshot.Shot? =
        pictures.routeSnapshot(segments, tint, widthPx, heightPx, paddingPx, under)

    fun kickThumbs(firstUuid: String? = null) = pictures.kickThumbs(firstUuid)

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

    /** The tour under Zuletzt while the map is empty ([com.onpath.app.ui.routes.RoutesState.recentId]). */
    val zuletztId: Long get() = routes.recentId

    /** The plan just moved from Zuletzt to saved: "✓ Gerade gespeichert" on it until My routes closes. */
    var justSavedId: Long
        get() = routes.justSavedId
        set(v) { routes.justSavedId = v }

    /** The plan's stops as saved: with their place names (backup / restore keeps them). */
    private fun savedStops(pts: List<GeoPoint>) = com.onpath.app.gpx.PlanGpx.stops(pts) { stopName(pts[it]) }

    private fun setSaved(name: String?, id: Long, points: List<GeoPoint>?) = tourFollow.setSaved(name, id, points)

    // Routes II ([RoutesGoSlice], rebuild phase 2 piece 14): go from My routes, the followed plan, the review; the names forward to it.
    /** Navigate mode or a tour recording: no loading into the plan, no Clear. */
    fun navigating(recording: Boolean): Boolean = routesGo.navigating(recording)
    fun menuState(recording: Boolean): RouteMenuState = routesGo.menuState(recording)
    fun openRoutes(): Unit = routesGo.openRoutes()
    fun requestOpen(t: Tour, why: String = "My routes tapped"): Unit = routesGo.requestOpen(t, why)
    fun undoNewPlan(): Unit = routesGo.undoNewPlan()
    fun goFromRoutes(t: Tour, lineOf: List<List<TrackPoint>>? = null, start: () -> Unit): Unit = routesGo.goFromRoutes(t, lineOf, start)
    fun planFollowedPlan(t: Tour): Unit = routesGo.planFollowedPlan(t)
    fun goFollowedPlan(t: Tour, start: () -> Unit): Unit = routesGo.goFollowedPlan(t, start)
    suspend fun followedPlanLine(t: Tour): List<GeoPoint> = routesGo.followedPlanLine(t)
    suspend fun tourReview(t: Tour): com.onpath.app.time.TourReview? = routesGo.tourReview(t)
    fun exportFollowedPlan(t: Tour): Unit = routesGo.exportFollowedPlan(t)
    fun planFromTour(t: Tour): Unit = routesGo.planFromTour(t)

    /**
     * Something else takes the plan's place (a track or a plan followed during a tour, "Als Plan
     * übernehmen"): no question any more (owner 2026-11-06) – the replaced plan goes under Zuletzt ([SaveSlice.keepAsDraft]).
     */
    private fun unlessUnsaved(what: String, go: () -> Unit): Unit = routesFlow.unlessUnsaved(what, go)

    /**
     * A shown track followed during a tour (the track sheet's "Tour nachfahren", F1's "Folgen" – flow map M1, owner rule
     * "what Plan shows = what Navigate follows"): the plan leaves the map (one Undo step, like ✕ Neu planen), the tour
     * follows the track, Navigate. An unsaved plan asks first ([unlessUnsaved]).
     */
    fun followShownDuringTour() = unlessUnsaved("Follow the shown track during the tour") { tourEdits.followShownNow() }

    /** The end question closed without ending the tour. */
    fun dismissEndAsk() = tour.dismissEndAsk()

    /**
     * Proposed name of a new route: a plan from a link keeps its title while its points are unchanged, else
     * "Algund → Meran" (search/RouteNamer) from the offline place index (built first if needed); null when nothing fits.
     */
    private suspend fun proposeName(pts: List<GeoPoint>, r: PlannedRoute?): String? {
        if (pts.size < 2) return null
        links.title(pts)?.let { return it }
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

    /** Switches mode; the recording goes on either way ([com.onpath.app.ui.tour.TourSession.enter]). */
    private fun enter(m: AppMode) = tourSession.enter(m)

    /**
     * Plan's primary action (location permission is checked by the caller). Follows the shown track, else the planned
     * route, else nothing (free recording – the button then reads "Start recording · …", [PlanStart]), and starts recording.
     */
    /**
     * [recordOnly]: ☰ → "Nur aufzeichnen" (owner 2026-11-06, flow map H1): a plain recording whatever the plan holds –
     * nothing is followed, the plan stays as it is ([com.onpath.app.ui.tour.TourSession.startNavigation]).
     */
    fun startNavigation(force: Boolean = false, recordOnly: Boolean = false) = tourSession.startNavigation(force, recordOnly)

    /** A plan with legs the router could not find (drawn dashed): "Navigate anyway?" is open. */
    var straightAsk: Boolean
        get() = tourSession.straightAsk
        set(v) { tourSession.straightAsk = v }

    /** The user wants to navigate the plan although parts of it are straight lines. */
    fun straightConfirmed() = tourSession.straightConfirmed()

    /** Back to Navigate while a tour runs, no new recording ([com.onpath.app.ui.tour.TourSession.continueNavigation]). */
    fun continueNavigation(force: Boolean = false) = tourSession.continueNavigation(force)

    /** A tour is guided along the plan (not a followed GPX track): Navigate shows the plan's points (owner 2026-11-01). */
    val planGuides: Boolean get() = tourFollow.planGuides

    /**
     * Look of the followed / shown line on the map (see MapController.setTrackLook): a shown track its own colour, else
     * route blue – in Navigate the followed line stays blue while a track is shown ([gpxLine]).
     */
    fun trackLook(): com.onpath.app.map.MapController.TrackLook =
        gpxLine(mode == AppMode.NAVIGATE, shown?.segments, followLine).look

    /** Off-route question in Navigate: take the new route the service found. */
    fun takeNewRoute(questionId: Int) = tourSession.takeNewRoute(questionId)

    /** "Off route – re-plan?" in Navigate – Yes: take the new route via the stop in question. */
    fun takeViaRoute(questionId: Int) = tourSession.takeViaRoute(questionId)

    /** Off-route question in Navigate: keep following the original route. */
    fun keepOriginalRoute(questionId: Int) = tourSession.keepOriginalRoute(questionId)

    /** "Route starts 31 km away" (never on the route yet) – Guide me: a route from here via the start and all points. */
    fun leadToStart(questionId: Int) = tourSession.leadToStart(questionId)

    /** "Route starts 31 km away" – Just record: quiet until the route is reached. */
    fun justRecord(questionId: Int) = tourSession.justRecord(questionId)

    /** "Du stehst mitten auf der Route" – Hier einsteigen: start here, the points before count as passed (owner 2026-11-03). */
    fun joinHere(questionId: Int) = tourSession.joinHere(questionId)

    /** OK on "No way to the route found – recording continues". */
    fun leadFailedOk(questionId: Int) = tourSession.leadFailedOk(questionId)

    // ---------------- activity check while navigating (owner: a wrong activity is a disaster) ----------------

    /**
     * The activity check ([com.onpath.app.ui.tour.ActivityCheckSlice] in `:state`, rebuild phase 2 piece 16b): the sheet, the
     * row routing on its own thread, what a found switch does. The service gets its one command through [recording] (the
     * proposal file first, [followStore]); the names below forward to it. The follow state lives in
     * [tourFollow] (piece 18a).
     */
    private val activityCheckSlice = com.onpath.app.ui.tour.ActivityCheckSlice(
        planRoute = planRoute, recording = recording, follow = followStore,
        rows = tourRows,
        fixes = object : com.onpath.app.ui.tour.Fixes {
            private fun point(loc: android.location.Location) = GeoPoint(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null)
            override fun now(): GeoPoint? = tracker.location.value?.let { point(it) }
            override suspend fun first(): GeoPoint = point(tracker.location.filterNotNull().first())
        },
        host = object : com.onpath.app.ui.tour.ActivityCheckHost {
            override val mode: AppMode get() = this@AppViewModel.mode
            override fun enterNavigate() = enter(AppMode.NAVIGATE)
            override val followFromPlan: Boolean get() = this@AppViewModel.followFromPlan
            override val planPoints: Int get() = waypoints.size
            override fun routeEvent(e: RouteRun.Event) { this@AppViewModel.routeEvent(e) }
            override fun rememberSac(level: Int) { prefs.sac = level }
        },
        scope = viewModelScope, io = Dispatchers.IO, cpu = Dispatchers.Default,
    )

    /** The check-and-switch sheet (Navigate → tap the activity icon); null = closed. Stays until a choice is made. */
    val activityCheck: ActivityCheckUi? get() = activityCheckSlice.activityCheck
    /** Plan line computed for an activity switched while navigating: counts as followed (not an edit) ([com.onpath.app.ui.tour.TourFollow.planBaseline]). */
    private var planBaseline: List<GeoPoint>?
        get() = tourFollow.planBaseline
        set(v) { tourFollow.planBaseline = v }

    /** Opens the sheet with every other activity, nothing routed yet ([com.onpath.app.ui.tour.ActivityCheckSlice.openActivityCheck]). */
    fun openActivityCheck() = activityCheckSlice.openActivityCheck()

    /** The check sheet's T1–T5 chips under Hike ([com.onpath.app.ui.tour.ActivityCheckSlice.chooseHikeLevel]). */
    fun chooseHikeLevel(level: Int) = activityCheckSlice.chooseHikeLevel(level)

    /** A row was chosen: free recording → switch at once; navigating → routed, then switched. */
    fun chooseActivity(a: Activity) = activityCheckSlice.chooseActivity(a)

    /** "Keep <activity>" (or Back): close the sheet, stop the checks. Nothing changes. */
    fun closeActivityCheck() = activityCheckSlice.closeActivityCheck()

    /** The slide switch in Navigate. Recording continues. */
    fun switchToPlan() = tourSession.switchToPlan()

    // ---- the tour's end: TourEndSlice (rebuild phase 2 piece 17); the names forward to it ----

    /** The short recording's box ("Kurze Aufzeichnung (12 m)" – Rückgängig · OK) while set. */
    val shortBinnedTour: Tour? get() = tour.shortBinned

    /** The short box → Rückgängig ([com.onpath.app.ui.tour.TourEndSlice.undoShortRecording]). */
    fun undoShortRecording() = tourEnd.undoShortRecording()

    /** The short box → OK: it stays in the recycle bin (30 days). */
    fun closeShortRecording() = tourEnd.closeShortRecording()

    /** The arrival box → "Weiter aufzeichnen": the box goes, the recording goes on. */
    fun keepRecordingAfterArrival() = tourEnd.keepRecordingAfterArrival()

    /** "End tour" in Plan (after confirmation) or "Tour beenden" on arrival: stop and save. */
    fun endTour() = tourEnd.endTour()

    /** What "End tour" shows now (null = nothing): see [askEndTour]. */
    var endAsk: EndAsk?
        get() = tour.endAsk
        set(v) { tour.endAsk = v }

    /** "End tour" tapped: the ride check, then the summary or the short question ([com.onpath.app.ui.tour.TourEndSlice.askEndTour]). */
    fun askEndTour(saveIfNoRides: Boolean = false) = tourEnd.askEndTour(saveIfNoRides)

    /** Save from the ride summary: the choice is stored on the tour first, the recording service applies it at the end. */
    fun endTourWith(a: EndAsk.Rides, choice: com.onpath.app.rides.RideChoice) = tourEnd.endTourWith(a, choice)

    /** My routes → ⋮ → Edit rides: the same summary for a saved tour (no split, no delete there). */
    var editRides: Pair<Tour, com.onpath.app.rides.RideView.Summary>?
        get() = tourEnd.editRides
        set(v) { tourEnd.editRides = v }

    fun openEditRides(t: Tour) = tourEnd.openEditRides(t)

    fun applyEditRides(t: Tour, choice: com.onpath.app.rides.RideChoice) = tourEnd.applyEditRides(t, choice)

    /** End sheet / arrival → Löschen: the TOUR goes straight to the recycle bin, no summary. */
    fun endTourAndDelete() = tourEnd.endTourAndDelete()

    // ---------------- follow / record ----------------

    /**
     * The tour follows the plan's route. Its name (lock screen "Back to …", the tour log) is the saved route's while the
     * plan belongs to one ([savedName]), else "Route <date>".
     */
    fun followPlanned(checkNow: Boolean = false) = tourFollow.followPlanned(checkNow)

    fun followShown() = tourFollow.followShown()

    fun clearFollow() = tourFollow.clearFollow()

    fun startRecording() = tourSession.startRecording()

    /** Any answer in "Unfinished tour" ends the "Tour interrupted" notification (Meran pictures bug 2). */
    private fun clearInterruptedNote() = com.onpath.app.recording.TourInterruptedReceiver.cancelNote(getApplication())

    fun continueRecording(t: Tour) = tourEnd.continueRecording(t)

    /** "Unfinished tour" → Save with a bus / car / lift found: the ride summary for it (null = none). */
    val unfinishedRides: Pair<Tour, com.onpath.app.rides.RideView.Summary>? get() = tour.unfinishedRides

    /** "Unfinished tour" → Save: like End tour ([com.onpath.app.ui.tour.TourEndSlice.askSaveUnfinished]). */
    fun askSaveUnfinished(t: Tour) = tourEnd.askSaveUnfinished(t)

    /** Ride summary of the unfinished tour → Cancel: back to its question (Continue · Delete · Save). */
    fun backToUnfinished() = tour.backToUnfinished()

    /** Saves the interrupted tour ([com.onpath.app.ui.tour.TourEndSlice.saveUnfinished]). */
    fun saveUnfinished(t: Tour, choice: com.onpath.app.rides.RideChoice? = null) = tourEnd.saveUnfinished(t, choice)

    /** "Unfinished tour" → Delete (asked again in the dialog): saved as done, then into the recycle bin (30 days). */
    fun deleteUnfinished(t: Tour) = tourEnd.deleteUnfinished(t)

    fun pauseRecording(pause: Boolean) = tourEnd.pauseRecording(pause)

    fun stopRecording() = tourEnd.stopRecording()

    // ---------------- tours ----------------

    fun refreshTours(): Unit = routesFlow.refreshTours()

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
    val sync = com.onpath.app.plansync.PlanSync(app, com.onpath.app.plansync.DbTourStore(app, db) { a -> planFactors(a) },
        recording = { recState.value != null }, onReport = ::syncReport)

    /**
     * When the sync runs and what follows a pass ([com.onpath.app.ui.sync.SyncDriver], cleanup piece 4): the triggers
     * while the app is open, pull to refresh, the "New / Changed" marks, a pass's report. The names below forward to it.
     */
    private val syncDriver = com.onpath.app.ui.sync.SyncDriver(
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
                appOnScreen to com.onpath.app.plansync.SyncPoll.routesScreen(routesOpen, showSettings, settingsPage == SettingsPage.SYNC)
            }
            override val shown = androidx.compose.runtime.snapshotFlow {
                com.onpath.app.plansync.SyncPoll.shown(appOnScreen, routesOpen, routesTab == RoutesTab.PLANNED, showSettings,
                    settingsPage == SettingsPage.SYNC)
            }
            override fun ended() { sayInfo(Txt.s(Res.string.sync_ended), NoticePlace.SETTINGS) }
            override fun placesChanged() { keptPlaces.savedPlaces = null }
            override fun toursChanged(toRoute: List<Long>) { refreshTours(); followSyncedSavedPlan(); routeSyncedPlans(toRoute) }
            override fun newerData(tiles: List<String>) { refreshRoutingTiles(tiles, "a plan from the browser was routed with newer data") }
        },
        recording = recState.map { it != null }, isRecording = { recState.value != null },
        scope = viewModelScope, io = Dispatchers.IO, clock = { android.os.SystemClock.elapsedRealtime() })

    /** Routes from the browser not opened yet (My routes → Planned: a "New" / "Changed" tag – RouteTag); read once at start. */
    val syncMarks: kotlinx.coroutines.flow.StateFlow<Map<String, com.onpath.app.plansync.SyncMark>> get() = syncDriver.marks

    /** Opened (load as plan / open the track): the route's "New from / Changed in the browser" mark goes. */
    private fun syncMarkSeen(t: Tour) = syncDriver.markSeen(t.uuid)

    /** Pull to refresh in My routes → Planned runs: the spinner shows until its pass reports. */
    val syncPulling: Boolean get() = syncDriver.pulling

    /** My routes → Planned pulled down (owner 2026-11-13, only offered when paired): a pass now, the spinner until it ends. */
    fun pullSync() = syncDriver.pull()

    init { syncDriver.start() }

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

    /** A sync pass ended (main thread): new / changed routes into My routes, plans from the browser routed ([syncDriver]). */
    private fun syncReport(r: com.onpath.app.plansync.SyncReport) { syncDriver.onReport(r) }

    private fun syncRoutingCheck(t: Tour, pts: List<GeoPoint>) = syncedRouting.syncRoutingCheck(t, pts)
    private fun followSyncedSavedPlan() = syncedRouting.followSyncedSavedPlan()
    private fun routeSyncedPlans(ids: List<Long>) = syncedRouting.routeSyncedPlans(ids)

    /**
     * Like runCatching, but only for exceptions (review Arch 9): a cancellation goes on to the coroutine, an Error
     * (OutOfMemoryError) is not taken for a failed route.
     */
    private inline fun <T> exceptions(block: () -> T): Result<T> = try { Result.success(block()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    // ---------------- plan links (A-005: routing/PlanLinks, docs/core-features/C15-plan-links.md) ----------------

    /**
     * A plan link opened in OnPath (camera / QR code, a tapped link, shared text, pasted into the search box; [via] for
     * the log): back to the map in Plan and loaded like a saved route. During a tour it is not loaded (the followed route
     * stays) and a line says so. A broken link changes nothing and says so in one red line.
     */
    fun receivePlanLink(found: com.onpath.app.routing.PlanLinks.Found, via: String) = links.receive(found, via)

    /** Text pasted into the search box: a plan link opens the plan (true); anything else is a search (false). */
    fun pastedPlanLink(text: String): Boolean = links.pasted(text) { search.clear() }

    private fun openSharedPlan(plan: com.onpath.app.routing.SharedPlan, via: String = "after asking"): Unit = routesGo.openSharedPlan(plan, via)
    fun sharePlan(): Unit = routesGo.sharePlan()
    fun shareRoute(t: Tour): Unit = routesGo.shareRoute(t)

    // Routes I ([RoutesSlice], rebuild phase 2 piece 13): the shown track, a track made a plan, Delete; the names forward to it.
    private fun showTrack(s: ShownTrack): Unit = routesFlow.showTrack(s)
    fun closeShown(): Unit = routesFlow.closeShown()
    /** A track is being turned into a plan ([planFromShown]): a modal box with Cancel over everything. */
    val planFromTrackBusy: Boolean get() = routesFlow.planFromTrackBusy
    fun cancelPlanFromTrack(): Unit = routesFlow.cancelPlanFromTrack()
    fun planFromShown(): Unit = routesFlow.planFromShown()
    private fun planFromLine(track: List<GeoPoint>, parts: List<String>, act: Activity, level: Int, done: (Boolean) -> Unit): Unit =
        routesFlow.planFromLine(track, parts, act, level, done)
    fun deleteTour(t: Tour): Unit = routesFlow.deleteTour(t)

    // ---------------- recycle bin + backup (docs/core-features/C11-backup.md) ----------------

    /** Recycle bin list (My routes → ⋮ → Recycle bin); null = closed. */
    var recycleBin: List<Tour>?
        get() = recent.bin
        set(v) { recent.bin = v }

    fun openRecycleBin() = recent.openBin()

    /** Recycle bin → Restore: back into My routes (and its file out of Deleted/); a draft becomes the Zuletzt plan. */
    fun restoreFromBin(t: Tour) = recent.restore(t)

    /** Recycle bin → Delete now (asked first): gone for good, its backup file too. */
    fun deleteForever(t: Tour) = recent.deleteForever(t)

    /** "Export all": one ZIP with every tour and plan as GPX, handed to the share sheet (Files, Drive, mail …). */
    fun exportAll() = importExport.exportAll()

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

    private fun checkRestoreOffer() = importExport.checkRestoreOffer()

    init {
        Backups.init(app)
        Backups.purgeAndSync(app) // 30-day recycle bin, files for tours that have none yet
        checkRestoreOffer()
        recent.trimOnce()
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
    fun restoreFrom(tree: Uri) = importExport.restoreFrom(tree)

    fun exportTour(t: Tour) = importExport.exportTour(t)

    /** Writes the GPX [gpx] builds (off the main thread) into the export folder and opens the share sheet. */
    private suspend fun share(name: String, gpx: () -> com.onpath.app.gpx.GpxFile) = importExport.share(name, gpx)

    // ---------------- GPX import ----------------

    fun importGpx(uri: Uri) = importExport.importGpx(uri)

    // ---------------- routing data freshness (owner: routing matters most – a changed way must reroute) ----

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
                        routingDataBefore = route?.points; routeEvent(RouteRun.Event.NewerData); recalc()
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
