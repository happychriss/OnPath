package com.onpath.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.onpath.app.debug.Problems
import com.onpath.app.map.MapController
import com.onpath.app.recording.GpsStatus
import com.onpath.app.recording.RecState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.rememberUpdatedState
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The main screen: full-screen map with the Plan or Navigate controls on top (AppMode). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: AppViewModel,
    onRequestLocation: () -> Unit,
    /** Start navigation; the caller makes sure location permission is granted first. */
    onStartNavigation: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    var controller by remember { mutableStateOf<MapController?>(null) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var scale by remember { mutableStateOf<Pair<String, Dp>?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var bearing by remember { mutableFloatStateOf(0f) }
    /** Visible map box (south, west, north, east; rounded ≈ 100 m) + 1 at z ≥ 10 / 0 below – "Overview only" hint, modal rule. */
    var hintKey by remember { mutableStateOf<List<Double>?>(null) }
    var showActivity by remember { mutableStateOf(false) }

    val rec by vm.recState.collectAsState()
    val location by vm.tracker.location.collectAsState()

    // GPS status for the dot on the locate button, refreshed every second (age of the last fix matters).
    val gps by produceState(GpsStatus.SEARCHING, vm.tracker) {
        while (true) { value = vm.tracker.status(); delay(1000) }
    }
    val onLocate = {
        when {
            gps == GpsStatus.OFF -> {
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .onFailure { Problems.warn("ui", "Location settings screen could not be opened", it) }
                Unit
            }
            gps == GpsStatus.NO_PERMISSION -> onRequestLocation()
            vm.tracker.location.value == null -> vm.followMe = true
            else -> vm.followMe = true
        }
    }
    // One grouped control: compass (always: Plan = needle, tap north up; Navigate = orientation toggle), eye
    // (only with something route-like on the map), locate (always, with the GPS dot).
    // [mode] = the layer the footer sits in (not vm.mode: the leaving layer keeps its look while it animates out)
    val footer = @Composable { mode: AppMode ->
        // the points step leaves little map: only Undo + locate (owner 2026-10-22, the compass went over ☰)
        val pointsOpen = mode == AppMode.PLAN && vm.planSize == PlanSize.FULL && vm.shown == null && vm.cardPlace == null
        MapFooter(scale) {
            MapControls(
                // same cycle in both modes: turned by hand → north up → walking direction up → north up (AppViewModel.onCompass)
                compass = if (pointsOpen) null
                    else CompassSpec(vm.headingUp && vm.followMe, bearing) { vm.onCompass(kotlin.math.abs(bearing) > 1f) },
                showEye = !pointsOpen && (vm.route != null || vm.shown != null || vm.followLine != null),
                onPeek = { vm.routePeek = it },
                gps = gps, following = vm.followMe, onLocate = onLocate,
                onUndo = if (mode == AppMode.PLAN && vm.canUndo && vm.shown == null) vm::undoStep else null,
                large = mode == AppMode.NAVIGATE,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapHost(
            vm = vm,
            topInsetPx = topInsetPx,
            onReady = { controller = it },
            onCamera = { map ->
                scale = computeScale(map, density.density); cameraTick++; bearing = map.cameraPosition.bearing.toFloat()
                hintKey = try {
                    val b = map.projection.visibleRegion.latLngBounds
                    listOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast).map { kotlin.math.round(it * 1000) / 1000 } +
                        (if (map.cameraPosition.zoom >= OverviewHintRule.MIN_ZOOM) 1.0 else 0.0)
                } catch (e: Exception) {
                    Problems.warn("map", "Visible map area unknown; overview hint hidden", e); null
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // A newer version of an area the user has (checked after each route, owner 2026-10-21): the map is veiled for
        // the few seconds it loads and switches; panels stay usable on top.
        val updStates by vm.cells.states.collectAsState()
        val updInstalled by vm.cells.installed.collectAsState()
        // no flash (owner rule): a load of a second never shows, whatever shows stays readable (NoFlash)
        val mapUpdate = rememberNoFlash(if (vm.mode == AppMode.PLAN) MapUpdates.of(updStates, updInstalled.map { it.id }.toSet()) else null,
            remember { NoFlash<MapUpdateUi>(stepKey = { it::class }) })
        MapUpdateLayer(mapUpdate, Modifier.fillMaxSize())
        MapSync(vm, controller, rec, location, panelHeightPx)
        KeepScreenOn(vm.settings.keepScreenOn && rec != null && vm.followLine != null && vm.mode == AppMode.NAVIGATE)
        // Quiet update check (once a day, only when online; F-OFF-06).
        LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }

        // Plan ⇄ Navigate: the control layers slide / fade (≈250 ms, ModeSwitch); the map above is not touched.
        ModeSwitch(vm.mode,
            plan = {
                PlanMode(vm, rec, { footer(AppMode.PLAN) }, onStartNavigation, onRequestLocation,
                    onActivity = { showActivity = true }, onEndTour = { vm.askEndTour() },
                    onPanelHeight = { panelHeightPx = it })
            },
            navigate = { NavigateMode(vm, rec, location, bearing) { footer(AppMode.NAVIGATE) } },
        )
        // "Where I face" arrow at the dot in heading-up following (MapController.Facing)
        controller?.let { ctl -> FacingArrow(ctl.facing) { bearing } }
        // hints below the search bar go below the background-download strip while it shows
        val hintTop = if (vm.backgroundStripShown && vm.mode == AppMode.PLAN) 124.dp else 76.dp
        // A shared place is being looked up (link + address search take a moment): say so at once.
        val sharedLookup = rememberNoFlash(if (vm.sharedLookup) Unit else null, remember { NoFlash<Unit>() }) != null
        androidx.compose.animation.AnimatedVisibility(sharedLookup,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = hintTop),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                shadowElevation = 3.dp, color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.Text("Finding the shared place…")
                }
            }
        }
        // "Overview only – set a point and the detailed map follows" (Plan, z ≥ 10, a large part of the view without
        // detailed map – OverviewHintRule); persistent while true, no flash while panning (NoFlash)
        val generation by vm.cells.generation.collectAsState()
        val overviewView by produceState(false, hintKey, generation) {
            val k = hintKey
            val without = k != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vm.overviewOnly(k[0], k[1], k[2], k[3])
            }
            // a route computed while the view has no detailed map is waited for (modal, owner 2026-10-21)
            vm.viewWithoutDetail = without
            value = without && k!![4] > 0
        }
        val overviewOnly = rememberNoFlash(if (overviewView) Unit else null, remember { NoFlash<Unit>() }) != null
        if (overviewOnly && vm.mode == AppMode.PLAN && !vm.searchOpen && !sharedLookup) {
            OverviewHint(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = hintTop, start = 16.dp, end = 16.dp))
        }
        // A newer version of an area the user has: small hint while the server builds it (the veil is under the panels).
        if (mapUpdate is MapUpdateUi.Preparing && !overviewOnly && !vm.searchOpen && !sharedLookup) {
            MapPreparingHint(mapUpdate, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = hintTop, start = 16.dp, end = 16.dp))
        }
        // Plan: menu of a held POI / route point, following the map.
        MapOverlayHost(vm, controller, cameraTick,
            topPx = topInsetPx + with(density) { (hintTop + 4.dp).roundToPx() }, // below the search pill + ☰ row (+ strip)
            bottomPx = panelHeightPx + with(density) { 8.dp.roundToPx() }, gps = gps)
    }

    if (showActivity) ActivityDialog(
        vm.activity,
        onSelect = { vm.selectActivity(it) },
        onDismiss = { showActivity = false },
        sac = vm.sac, onSac = vm::selectSac,
    )
    if (vm.confirmClear) ConfirmClearDialog(vm.waypoints.size, onClear = vm::clearPlan, onDismiss = { vm.confirmClear = false })
    if (vm.routeBackAsk && rec != null) RouteBackDialog(onYes = { vm.answerRouteBack(true) }, onNo = { vm.answerRouteBack(false) })
    vm.longRouteAsk?.let { s -> LongRouteDialog(s, vm.activity, onPlan = { vm.answerLongRoute(true) }, onCancel = { vm.answerLongRoute(false) }) }
    when (val ask = vm.endAsk) {
        EndAsk.Plain -> EndTourDialog(onSave = { vm.endTour() }, onDelete = { vm.endTourAndDelete() }, onDismiss = { vm.endAsk = null })
        is EndAsk.Rides -> RideSummaryDialog(ask.summary, onSave = { vm.endTourWith(ask, it) },
            onDelete = { vm.endTourAndDelete() }, onDismiss = { vm.endAsk = null })
        null -> {}
    }
    if (vm.arrivalAsk && rec != null) ArrivalDialog(onSave = vm::endTour, onDelete = vm::endTourAndDelete,
        onKeepGoing = { vm.arrivalAsk = false })
    vm.selectedPoi?.let { poi ->
        // Read-only details (Navigate, or a shown track): no "add to route" here.
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { vm.selectedPoi = null }) {
            com.onpath.app.poi.PoiDetails(poi = poi, onClose = { vm.selectedPoi = null })
        }
    }
}

/** Plan mode: top bar, POI card, tour bar and the plan (or shown track) panel. */
@Composable
private fun PlanMode(
    vm: AppViewModel, rec: RecState?, footer: @Composable () -> Unit, onStartNavigation: () -> Unit,
    onRequestLocation: () -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit, onPanelHeight: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PlanTopBar(
            onSearch = vm::openSearch,
            menu = PlanMenu(
                state = vm.menuState(recording = rec != null),
                onManage = { vm.openRoutes() },
                // Free recording without a route: a shown track is closed so it is not followed.
                onStartRecording = { vm.closeShown(); onStartNavigation() },
                onSettings = { vm.showSettings = true },
                onOpened = vm::refreshTours,
                // owner 2026-10-20: no "Check for updates" in the ☰ menu – Settings has the Updates section
                onUpdates = null,
            ),
            modifier = Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().statusBarsPadding(),
        )
        // background downloads (corridor, refresh, routing data): thin blue strip below the search bar, after 10 s,
        // until all is done; tap → details with "Cancel all" (owner 2026-10-22)
        val bg = rememberBackgroundDownload(vm)
        val stripOn = bg != null && !vm.searchOpen
        LaunchedEffect(stripOn) { vm.backgroundStripShown = stripOn }
        if (stripOn) BackgroundDownloadStrip(bg!!, onClick = { vm.downloadDetailsOpen = true },
            modifier = Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).statusBarsPadding().padding(top = 68.dp, start = 16.dp, end = 16.dp))
        BackgroundDownloadDetails(vm, bg)
        // The sheet's height changes every frame while it moves; the map only hears the height once it rests (a state
        // read by MapScreen would recompose the whole screen per frame – owner field report 0.9.3, the sheet stuttered).
        val sheetHeight = remember { mutableIntStateOf(0) }
        val panelHeight by rememberUpdatedState(onPanelHeight)
        LaunchedEffect(Unit) {
            snapshotFlow { sheetHeight.intValue }.collectLatest { delay(120); panelHeight(it) }
        }
        val density = LocalDensity.current
        val topLimit = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + TOP_BAR_BOTTOM
        SheetWithFooter(topLimit, modifier = Modifier.modeEdge(ModeEdge.BOTTOM), footer = {
            Column(Modifier.fillMaxWidth()) {
                val upd = vm.update
                val updVersion = (upd as? AppViewModel.UpdateState.Available)?.release?.version
                    ?: (upd as? AppViewModel.UpdateState.Downloading)?.release?.version
                if (rec == null && updVersion != null && updVersion != vm.updateDismissed) {
                    UpdateBanner(upd, onInstall = { vm.installUpdate() }, onLater = { vm.updateDismissed = updVersion },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                footer()
            }
        }, sheet = {
            Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) {
                // The tour controls sit at the top of the plan sheet; a separate card only when the sheet is replaced
                // (place card or shown track).
                if (vm.cardPlace != null || vm.shown != null) rec?.let {
                    TourRunningBar(it.distanceM, it.elapsedS, it.paused, onEnd = onEndTour, onPause = vm::pauseRecording,
                        onContinue = vm::continueNavigation)
                }
                // The place card (tapped POI / search result, ui/search) takes the sheet's place while open.
                if (vm.cardPlace != null) com.onpath.app.ui.search.PlaceCardHost(vm)
                else PlanBottom(vm, rec, onStartNavigation, onActivity, onEndTour, onRequestLocation)
            }
        })
        com.onpath.app.ui.search.SearchHost(vm) // full-screen search view while open
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlanBottom(vm: AppViewModel, rec: RecState?, onStartNavigation: () -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit,
                       onRequestLocation: () -> Unit = {}) {
    val shown = vm.shown
    if (shown != null) {
        TrackPanel(
            shown, isFollowed = vm.followName == shown.name, recording = rec != null,
            onFollow = {
                if (rec == null) onStartNavigation()
                else { vm.followShown(); vm.closeShown(); vm.continueNavigation() }
            },
            onExport = vm::exportShown,
            onClose = vm::closeShown, onHighlight = { vm.highlight = it },
        )
        return
    }
    val cellStates by vm.cells.states.collectAsState()
    val cellsInstalled by vm.cells.installed.collectAsState()
    // no flash (owner rule, field test 0.8.7): fast steps ("Preparing…" for a moment before "Downloading") are skipped,
    // whatever shows stays readable; a tap answers at once
    val cellGate = remember { NoFlash<CellCardUi>(stepKey = CellCards::stepKey, urgent = CellCards::waitsForUser) }
    val cellCard = rememberNoFlash(vm.cellCardUi(cellStates, cellsInstalled), cellGate)
    fun acted(f: () -> Unit): () -> Unit = { cellGate.userActed(android.os.SystemClock.uptimeMillis()); f() }
    val cellCb = CellCardCallbacks(onMobileYes = acted(vm::cellCardMobileYes), onWifiOnly = acted(vm::cellCardWifiOnly),
        onRetry = acted(vm::cellCardRetry), onClose = acted(vm::closeCellCard), onCancel = acted(vm::cancelCellLoading),
        onAllowLocation = acted(onRequestLocation))
    // the user waits for the map of their area (first start, a point without detailed map): one plain modal with %,
    // time left and Cancel (owner 2026-10-21); ticks every second so the bar moves smoothly
    val loadUi by produceState<MapLoadUi?>(null, cellStates, vm.cellJob, vm.mode) {
        while (true) { value = vm.mapLoadUi(cellStates, android.os.SystemClock.uptimeMillis()); delay(1000) }
    }
    // Rows of the Points section, composed with the sheet in every step (StepSheet: it is only revealed). The distances
    // along the route walk the whole line: done once per route off the main thread (PlanPoints.legsCached); until then
    // the previous rows stay (owner field report 0.9.3).
    val pts = vm.waypoints.toList()
    val line = vm.route?.takeIf { !vm.routing }?.points
    var legsReady by remember(pts, line) { mutableStateOf(com.onpath.app.routing.PlanPoints.legsReady(pts, line)) }
    LaunchedEffect(pts, line) {
        if (!legsReady) {
            withContext(Dispatchers.Default) { com.onpath.app.routing.PlanPoints.legsCached(pts, line) }
            legsReady = true
        }
    }
    val lastRows = remember { arrayOfNulls<List<com.onpath.app.routing.PlanPointRow>>(1) }
    val rows = lastRows[0]?.takeIf { !legsReady } ?: vm.planPointRows().also { lastRows[0] = it }
    MapLoadModal(loadUi, MapLoadCallbacks(onCancel = vm::cancelLoadModal, onRetry = vm::cellCardRetry, onClose = vm::hideLoadModal))
    vm.loadAsk?.let { LoadAskDialog(it.regions, it.bytes, onLoad = { vm.answerLoadAsk(true) }, onNotNow = { vm.answerLoadAsk(false) }) }
    PlanPanel(
        PlanUi(vm.waypoints.size, vm.route, vm.routing, vm.routeError, vm.canUndo, vm.canRedo, vm.activity, vm.planSize,
            sac = vm.sac, tourRunning = rec != null, savedName = vm.savedName, unsaved = vm.planUnsaved, roundTrip = vm.isRoundTrip(),
            tour = rec?.let { TourState(it.distanceM, it.elapsedS, it.paused) },
            cellCard = cellCard,
            points = rows,
            routingSlow = vm.routingSlow, notRouted = vm.notRouted, gapText = vm.planGapText(), warnText = vm.warningLineText()),
        PlanCallbacks(
            onSheetDrag = vm::mapGesture, onUndo = vm::undoStep, onRedo = vm::redoStep, onClear = vm::requestClear,
            onStartNavigation = onStartNavigation, onContinue = vm::continueNavigation,
            onReverse = vm::reverseRoute, onRoundTrip = vm::roundTrip,
            onActivity = onActivity, onSave = vm::requestSave, onExport = vm::exportPlanned, onTimeDetails = vm::openTimeDetails, onDetails = { vm.routeDetailsOpen = true },
            onHighlight = { vm.highlight = it },
            onCancelRouting = vm::cancelRouting,
            onPlanAnyway = vm::planAnyway,
            onShowGap = vm::showPlanGap,
            onWarnings = vm::tapWarningLine,
            onResize = { vm.planSize = it },
            onPause = vm::pauseRecording, onEndTour = onEndTour,
            cellCard = cellCb,
            onMovePoint = vm::movePointInList, onDeletePoint = vm::deletePointFromList, onShowPoint = vm::showPointFromList,
        ),
    )
    if (vm.spotsOpen) {
        // the spots of the amber line (owner 2026-10-22): tap one → the map shows it
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = vm::closeSpots) {
            RouteSpotsList(vm.routeSpots, vm.sac, onShow = vm::showSpot)
        }
    }
    // names for the Points section (offline place index, off the main thread) whenever the points change – ready before
    // the sheet is pulled up (owner field report 0.9.3: names arriving mid-pull rebuilt the list)
    LaunchedEffect(vm.waypoints.toList()) { vm.refreshPointNames() }
}

/** Keeps the map layers in step with the app state. */
@Composable
private fun MapSync(vm: AppViewModel, c: MapController?, rec: RecState?, location: android.location.Location?, panelHeightPx: Int) {
    val context = LocalContext.current
    if (c == null) {
        // share events must also work before the map is ready
        LaunchedEffect(vm.events.size) {
            vm.events.filterIsInstance<UiEvent.Share>().forEach {
                runCatching { context.startActivity(it.intent) }
                    .onFailure { e -> Problems.warn("ui", "Share screen could not be opened (no app?)", e) }
            }
            vm.events.removeAll { it is UiEvent.Share }
        }
        return
    }
    LaunchedEffect(c, vm.routePeek) { c.setRouteOverlaysVisible(!vm.routePeek) } // eye button (re-applied per style)
    // Planned line only while the plan can be edited (Plan mode, no track shown); markers only in Plan (planLayers).
    LaunchedEffect(c) {
        // canEditMap is part of the key on its own: Navigate → Plan while the plan is followed must redraw the markers
        snapshotFlow {
            val (line, markers) = planLayers(vm.canEditMap, vm.planIsFollowed())
            PlanDrawing(vm.route, line, markers, vm.waypoints.toList(), vm.planGaps)
        }
            .collect { d ->
                c.setPlanned(if (d.showLine) d.route else null)
                // unreachable points: grey dashed missing piece + grey hollow marker (owner 2026-10-22)
                val gaps = if (d.showLine && d.showMarkers) d.gaps else emptyList()
                c.setPlanGaps(gaps)
                c.setWaypoints(if (d.showMarkers) d.points else emptyList(), vm.pinnedIndices(), gaps.map { it.index }.toSet())
            }
    }
    LaunchedEffect(c, vm.shown, vm.followLine) {
        c.setTrack(MapController.SRC_GPX, vm.shown?.segments ?: listOfNotNull(vm.followLine))
        val rides = vm.shown?.rides.orEmpty()
        c.setTrack(MapController.SRC_RIDES, rides.filter { it.second == com.onpath.app.rides.RideKind.VEHICLE }.map { it.first })
        c.setTrack(MapController.SRC_LIFTS, rides.filter { it.second == com.onpath.app.rides.RideKind.LIFT }.map { it.first })
    }
    // One blue line: the followed route is blue; while the plan is edited during a tour it turns thin grey
    // under the (blue) edited plan; a track that is only shown keeps its own colour.
    LaunchedEffect(c) {
        snapshotFlow { vm.trackLook() }.collect { c.setTrackLook(it) }
    }
    LaunchedEffect(c, vm.settings) { c.layers.apply(vm.settings) }
    // Re-routing: thin grey original while a new route is followed, dashed proposal while asking, dashed guide to
    // the route when no way was found / the original was kept (see NavigateScreen, navigation/Navigator).
    val nav = rec?.nav
    LaunchedEffect(c, nav?.original) { c.setNavOriginal(nav?.original) }
    // Heading-up on the route: the stretch ahead points straight up (HeadingFilter). Only in Navigate, matched to
    // the line, not off route; otherwise null = compass / GPS bearing as before.
    val navLine = remember(nav?.line) { nav?.line?.let { com.onpath.app.navigation.NavLine(it) } }
    val routeAhead = if (vm.mode == AppMode.NAVIGATE && nav != null && nav.prompt == null &&
        (nav.offM ?: Double.MAX_VALUE) <= ROUTE_UP_MAX_OFF_M) nav.shownAlongM?.let { navLine?.aheadBearing(it) } else null
    // re-sent on every fix: standing still on the route must keep the route direction (field test 2026-10-20: after
    // 15 s without progress the compass took over and, with the phone tilted / in the pocket, turned the map 180°)
    LaunchedEffect(c, routeAhead, nav?.shownAlongM, location) { c.setRouteHeading(routeAhead) }
    LaunchedEffect(c, (nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.id) {
        c.setNavProposal((nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.line)
    }
    LaunchedEffect(c, nav?.toRoute?.target, nav?.prompt?.javaClass, location) {
        val guide = nav?.toRoute?.takeIf { nav.prompt != null && nav.prompt !is com.onpath.app.navigation.NavPrompt.Question }
        c.setNavGuide(location?.let { com.onpath.app.routing.GeoPoint(it.latitude, it.longitude) }, guide?.target)
    }
    LaunchedEffect(c, rec?.track?.sumOf { it.size }) { c.setTrack(MapController.SRC_REC, rec?.track ?: emptyList()) }
    LaunchedEffect(c, vm.highlight) { c.setHighlight(vm.highlight) }
    // the spot tapped in the route warnings list: its piece of route amber (Plan only)
    LaunchedEffect(c, vm.spotLine, vm.mode) { c.setSpotLine(vm.spotLine?.takeIf { vm.mode == AppMode.PLAN }) }
    LaunchedEffect(c, vm.searchMark) {
        val m = vm.searchMark
        c.setSearchMark(m?.let { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }, m?.title())
    }
    // Marked route networks always show (owner): the band of the current activity.
    LaunchedEffect(c, vm.activity, vm.sac) { c.setOverlay(vm.activity, true, vm.sac) }
    // hiking route band above the T level from Settings: grey dashes (the router forbids those trails)
    LaunchedEffect(c, vm.sac) { c.setSacLimit(vm.sac) }
    LaunchedEffect(c, location) { location?.let { c.updateLocation(it); vm.checkFreeLook(it.latitude, it.longitude) } }
    // App start / back to the app in Plan: centre on the position (first fix, else last known), unless the user
    // moved the map (StartCamera; restarted by vm.onAppStart).
    LaunchedEffect(c, vm.startCameraGen) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (!vm.startCameraDone) {
            val loc = vm.tracker.location.value
            val step = StartCamera.decide(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - t0,
                modeKnown = vm.startModeKnown,
                plan = vm.mode == AppMode.PLAN && vm.recState.value == null && !vm.followMe,
                userMoved = vm.userMovedMap,
                fixAgeS = loc?.let { com.onpath.app.recording.LocationTracker.ageSeconds(it) },
            )
            when (step) {
                StartCamera.Step.WAIT -> delay(250)
                StartCamera.Step.CENTRE -> { loc?.let { c.centreOn(it.latitude, it.longitude) }; vm.startCameraDone = true }
                StartCamera.Step.GIVE_UP -> vm.startCameraDone = true
            }
        }
    }
    // Camera: follow the position, walking direction up or north up (both modes); next-turn arrow (see NavigateScreen).
    LaunchedEffect(c, vm.followMe, vm.mode, vm.headingUp, vm.recentreTick) {
        c.setCamera(vm.followMe, vm.mode == AppMode.NAVIGATE, vm.headingUp) { vm.onMapGesture() }
    }
    LaunchedEffect(c, vm.northTick) { if (vm.northTick > 0) c.northUp() }
    LaunchedEffect(c, vm.navTurn) { c.setTurnArrow(vm.navTurn) }
    LaunchedEffect(c, vm.events.size) {
        val pending = vm.events.toList()
        vm.events.clear()
        pending.forEach { e ->
            when (e) {
                is UiEvent.Fit -> c.fitTo(e.points, panelHeightPx)
                is UiEvent.CenterOn -> c.flyTo(e.point, e.zoom)
                is UiEvent.PanTo -> c.panTo(e.point)
                is UiEvent.Share -> runCatching { context.startActivity(e.intent) }
                    .onFailure { Problems.warn("ui", "Share screen could not be opened (no app?)", it) }
            }
        }
    }
}

/** Nice round scale bar length (max ~100dp). */
private fun computeScale(map: org.maplibre.android.maps.MapLibreMap, density: Float): Pair<String, Dp>? = try {
    val lat = map.cameraPosition.target?.latitude ?: 0.0
    val mPerPx = map.projection.getMetersPerPixelAtLatitude(lat)
    val maxM = mPerPx * 100 * density
    val pow10 = 10.0.pow(floor(log10(maxM)))
    val nice = listOf(5.0, 2.0, 1.0).map { it * pow10 }.first { it <= maxM }
    val label = if (nice >= 1000) "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
    label to (nice / mPerPx / density).dp
} catch (e: Exception) {
    Problems.warn("map", "Scale bar could not be computed; hidden", e)
    null
}

/** Farther from the followed line than this, heading-up uses the compass / GPS bearing instead of the route. */
private const val ROUTE_UP_MAX_OFF_M = 30.0

/**
 * Which parts of the plan the map draws: (planned line, numbered point markers). Both only while the plan can be
 * edited (Plan mode, no track shown); the line not while the plan is followed (the followed line is drawn instead).
 * Navigate shows only the route line, no numbered points (owner 2026-10-22, reverting 0.9.1's markers in Navigate).
 */
internal fun planLayers(canEditMap: Boolean, planFollowed: Boolean): Pair<Boolean, Boolean> =
    (canEditMap && !planFollowed) to canEditMap

/** What the plan drawing on the map depends on (MapSync). */
private data class PlanDrawing(
    val route: com.onpath.app.routing.PlannedRoute?,
    val showLine: Boolean,
    val showMarkers: Boolean,
    val points: List<com.onpath.app.routing.GeoPoint>,
    val gaps: List<PlanGap> = emptyList(),
)
