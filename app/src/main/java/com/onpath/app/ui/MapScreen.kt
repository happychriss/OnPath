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
    /** Map centre + zoom, rounded (≈ 1 km, whole zoom steps below / above 10) – for the "Overview only" hint. */
    var hintKey by remember { mutableStateOf<Triple<Double, Double, Boolean>?>(null) }
    var showActivity by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

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
    val footer = @Composable {
        MapFooter(scale) {
            MapControls(
                // same cycle in both modes: turned by hand → north up → walking direction up → north up (AppViewModel.onCompass)
                compass = CompassSpec(vm.headingUp && vm.followMe, bearing) { vm.onCompass(kotlin.math.abs(bearing) > 1f) },
                showEye = vm.route != null || vm.shown != null || vm.followLine != null,
                onPeek = { vm.routePeek = it },
                gps = gps, following = vm.followMe, onLocate = onLocate,
                onUndo = if (vm.mode == AppMode.PLAN && vm.canUndo && vm.shown == null) vm::undoStep else null,
                large = vm.mode == AppMode.NAVIGATE,
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
                map.cameraPosition.target?.let { t ->
                    hintKey = Triple(kotlin.math.round(t.latitude * 100) / 100, kotlin.math.round(t.longitude * 100) / 100,
                        map.cameraPosition.zoom >= 10.0)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        MapSync(vm, controller, rec, location, panelHeightPx)
        KeepScreenOn(vm.settings.keepScreenOn && rec != null && vm.followLine != null && vm.mode == AppMode.NAVIGATE)
        // Quiet update check (once a day, only when online; F-OFF-06).
        LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }

        when (vm.mode) {
            AppMode.PLAN -> PlanMode(vm, rec, footer, onStartNavigation,
                onActivity = { showActivity = true }, onEndTour = { confirmEnd = true },
                onPanelHeight = { panelHeightPx = it })
            AppMode.NAVIGATE -> NavigateMode(vm, rec, location, bearing, footer)
        }
        // "Where I face" arrow at the dot in heading-up following (MapController.Facing)
        controller?.let { ctl -> FacingArrow(ctl.facing) { bearing } }
        // A shared place is being looked up (link + address search take a moment): say so at once.
        androidx.compose.animation.AnimatedVisibility(vm.sharedLookup,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp),
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
        // "Overview only – set a point and the detailed map follows" (Plan, z ≥ 10, no detailed map at the centre)
        val generation by vm.cells.generation.collectAsState()
        val overviewOnly by produceState(false, hintKey, generation) {
            val k = hintKey
            value = k != null && k.third && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vm.overviewOnly(k.first, k.second, 10.0)
            }
        }
        if (overviewOnly && vm.mode == AppMode.PLAN && !vm.searchOpen && !vm.sharedLookup) {
            OverviewHint(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp, start = 16.dp, end = 16.dp))
        }
        // Plan: menu of a held POI / route point, following the map.
        MapOverlayHost(vm, controller, cameraTick,
            topPx = topInsetPx + with(density) { 80.dp.roundToPx() }, // below the search pill + ☰ row
            bottomPx = panelHeightPx + with(density) { 8.dp.roundToPx() }, gps = gps)
    }

    if (showActivity) ActivityDialog(
        vm.activity,
        onSelect = { vm.selectActivity(it) },
        onDismiss = { showActivity = false },
    )
    if (vm.confirmClear) ConfirmClearDialog(vm.waypoints.size, onClear = vm::clearPlan, onDismiss = { vm.confirmClear = false })
    if (confirmEnd) EndTourDialog(onEnd = { confirmEnd = false; vm.endTour() }, onDismiss = { confirmEnd = false })
    if (vm.arrivalAsk && rec != null) ArrivalDialog(onEnd = vm::endTour, onKeepGoing = { vm.arrivalAsk = false })
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
    onActivity: () -> Unit, onEndTour: () -> Unit, onPanelHeight: (Int) -> Unit,
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
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding(),
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val upd = vm.update
            val updVersion = (upd as? AppViewModel.UpdateState.Available)?.release?.version
                ?: (upd as? AppViewModel.UpdateState.Downloading)?.release?.version
            if (rec == null && updVersion != null && updVersion != vm.updateDismissed) {
                UpdateBanner(upd, onInstall = { vm.installUpdate() }, onLater = { vm.updateDismissed = updVersion },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            footer()
            Column(Modifier.onSizeChanged { onPanelHeight(it.height) }) {
                // The tour controls sit at the top of the plan sheet; a separate card only when the sheet is replaced
                // (place card or shown track).
                if (vm.cardPlace != null || vm.shown != null) rec?.let {
                    TourRunningBar(it.distanceM, it.elapsedS, it.paused, onEnd = onEndTour, onPause = vm::pauseRecording,
                        onContinue = vm::continueNavigation)
                }
                // The place card (tapped POI / search result, ui/search) takes the sheet's place while open.
                if (vm.cardPlace != null) com.onpath.app.ui.search.PlaceCardHost(vm)
                else PlanBottom(vm, rec, onStartNavigation, onActivity, onEndTour)
            }
        }
        com.onpath.app.ui.search.SearchHost(vm) // full-screen search view while open
    }
}

@Composable
private fun PlanBottom(vm: AppViewModel, rec: RecState?, onStartNavigation: () -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit) {
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
    val cellCard = vm.cellCardUi(cellStates, cellsInstalled)
    val cellCb = CellCardCallbacks(onMobileYes = vm::cellCardMobileYes, onWifiOnly = vm::cellCardWifiOnly,
        onRetry = vm::cellCardRetry, onClose = vm::closeCellCard, onCancel = vm::cancelCellLoading)
    // building / downloading map areas: a clear modal overlay with Cancel (owner 2026-10-21)
    if (cellCard?.wantsOverlay() == true) MapLoadOverlay(cellCard, cellCb)
    PlanPanel(
        PlanUi(vm.waypoints.size, vm.route, vm.routing, vm.routeError, vm.canUndo, vm.canRedo, vm.activity, vm.planSize,
            tourRunning = rec != null, savedName = vm.savedName, unsaved = vm.planUnsaved, roundTrip = vm.isRoundTrip(),
            tour = rec?.let { TourState(it.distanceM, it.elapsedS, it.paused) },
            cellCard = cellCard),
        PlanCallbacks(
            onUndo = vm::undoStep, onRedo = vm::redoStep, onClear = vm::requestClear,
            onStartNavigation = onStartNavigation, onContinue = vm::continueNavigation,
            onReverse = vm::reverseRoute, onRoundTrip = vm::roundTrip,
            onActivity = onActivity, onSave = vm::requestSave, onExport = vm::exportPlanned, onTimeDetails = vm::openTimeDetails,
            onHighlight = { vm.highlight = it },
            onResize = { vm.planSize = it },
            onPause = vm::pauseRecording, onEndTour = onEndTour,
            cellCard = cellCb,
        ),
    )
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
    // Planned line + markers only while the plan can be edited (Plan mode, no track shown).
    LaunchedEffect(c) {
        snapshotFlow { Triple(vm.route, vm.canEditMap && !vm.planIsFollowed(), vm.waypoints.toList()) }
            .collect { (r, showLine, w) ->
                c.setPlanned(if (showLine) r else null)
                val show = vm.canEditMap
                c.setWaypoints(if (show) w else emptyList(), vm.pinnedIndices())
            }
    }
    LaunchedEffect(c, vm.shown, vm.followLine) {
        c.setTrack(MapController.SRC_GPX, vm.shown?.segments ?: listOfNotNull(vm.followLine))
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
    LaunchedEffect(c, vm.searchMark) {
        val m = vm.searchMark
        c.setSearchMark(m?.let { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }, m?.title())
    }
    // Marked route networks always show (owner): the band of the current activity.
    LaunchedEffect(c, vm.activity) { c.setOverlay(vm.activity, true) }
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
