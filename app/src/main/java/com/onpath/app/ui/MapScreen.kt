package com.onpath.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    /** Opens the system file picker for a GPX file (needs the Activity). */
    onImportGpx: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val topInsetPx = WindowInsets.statusBars.getTop(density)
    var controller by remember { mutableStateOf<MapController?>(null) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    var scale by remember { mutableStateOf<Pair<String, Dp>?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var bearing by remember { mutableFloatStateOf(0f) }
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
            gps == GpsStatus.OFF -> { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }; Unit }
            gps == GpsStatus.NO_PERMISSION -> onRequestLocation()
            vm.tracker.location.value == null -> vm.message = "Searching GPS…"
            else -> vm.followMe = true
        }
    }
    // One grouped control: compass (Navigate always; Plan only when the map is turned), eye (only with
    // something route-like on the map), locate (always, with the GPS dot).
    val footer = @Composable {
        MapFooter(scale) {
            MapControls(
                compass = when {
                    vm.mode == AppMode.NAVIGATE -> CompassSpec(vm.navHeadingUp, 0f, vm::toggleNavOrientation)
                    kotlin.math.abs(bearing) > 1f -> CompassSpec(null, bearing) { controller?.northUp() }
                    else -> null
                },
                showEye = vm.route != null || vm.shown != null || vm.followLine != null,
                onPeek = { vm.routePeek = it },
                gps = gps, following = vm.followMe, onLocate = onLocate,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapHost(
            vm = vm,
            topInsetPx = topInsetPx,
            onReady = { controller = it },
            onCamera = { map -> scale = computeScale(map, density.density); cameraTick++; bearing = map.cameraPosition.bearing.toFloat() },
            modifier = Modifier.fillMaxSize(),
        )
        MapSync(vm, controller, rec, location, panelHeightPx)
        KeepScreenOn(vm.settings.keepScreenOn && rec != null && vm.followLine != null && vm.mode == AppMode.NAVIGATE)
        // Quiet update check (once a day, only when online; F-OFF-06).
        LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }

        when (vm.mode) {
            AppMode.PLAN -> PlanMode(vm, rec, footer, onStartNavigation, onImportGpx,
                onActivity = { showActivity = true }, onEndTour = { confirmEnd = true },
                onPanelHeight = { panelHeightPx = it })
            AppMode.NAVIGATE -> NavigateMode(vm, rec, location, bearing, footer)
        }
        // Plan: menu of a held POI / route point, following the map.
        MapOverlayHost(vm, controller, cameraTick,
            topPx = topInsetPx + with(density) { 128.dp.roundToPx() }, // below search pill + activity chip
            bottomPx = panelHeightPx + with(density) { 8.dp.roundToPx() }, gps = gps)
    }

    if (showActivity) ActivityDialog(
        vm.activity, vm.sac, vm.overlayOn,
        onSelect = { vm.selectActivity(it) }, onSac = { vm.selectSac(it) },
        onOverlay = { vm.updateSettings(vm.settings.copy(routeOverlay = it)) },
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
    @Suppress("UNUSED_PARAMETER") onImportGpx: () -> Unit, // Import GPX lives in Manage routes now
    onActivity: () -> Unit, onEndTour: () -> Unit, onPanelHeight: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PlanTopBar(
            vm.activity, vm.sac, onActivity = onActivity, onSearch = vm::openSearch,
            menu = PlanMenu(
                state = vm.menuState(recording = rec != null),
                onLoad = { vm.openRoutes(RoutesMode.PICK) },
                onSave = { vm.saveDialog = true },
                onManage = { vm.openRoutes(RoutesMode.MANAGE) },
                // Free recording without a route: a shown track is closed so it is not followed.
                onStartRecording = { vm.closeShown(); onStartNavigation() },
                onSettings = { vm.showSettings = true },
                onOpened = vm::refreshTours,
                onUpdates = if (vm.updatesEnabled) ({ vm.checkForUpdates(manual = true); vm.showSettings = true }) else null,
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
                rec?.let { TourRunningBar(it.distanceM, it.elapsedS, it.paused, onEnd = onEndTour) }
                // The place card (tapped POI / search result, ui/search) takes the sheet's place while open.
                if (vm.cardPlace != null) com.onpath.app.ui.search.PlaceCardHost(vm)
                else PlanBottom(vm, rec, onStartNavigation)
            }
        }
        com.onpath.app.ui.search.SearchHost(vm) // full-screen search view while open
    }
}

@Composable
private fun PlanBottom(vm: AppViewModel, rec: RecState?, onStartNavigation: () -> Unit) {
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
    PlanPanel(
        PlanUi(vm.waypoints.size, vm.route, vm.routing, vm.routeError, vm.canUndo, vm.canRedo, vm.activity, vm.planSize,
            tourRunning = rec != null, savedName = vm.savedName, unsaved = vm.planUnsaved),
        PlanCallbacks(
            onUndo = vm::undoStep, onRedo = vm::redoStep, onClear = vm::requestClear, onFromMe = vm::startAtMyPosition,
            onStartNavigation = onStartNavigation, onContinue = vm::continueNavigation,
            onReverse = vm::reverseRoute, onRoundTrip = vm::roundTrip,
            onHighlight = { vm.highlight = it },
            onResize = { vm.planSize = it },
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
            vm.events.filterIsInstance<UiEvent.Share>().forEach { runCatching { context.startActivity(it.intent) } }
            vm.events.removeAll { it is UiEvent.Share }
        }
        return
    }
    LaunchedEffect(c, vm.routePeek) { c.setRouteOverlaysVisible(!vm.routePeek) } // eye button (re-applied per style)
    // Planned line + markers only while the plan can be edited (Plan mode, no track shown).
    LaunchedEffect(c) {
        snapshotFlow { Triple(vm.route, vm.canEditMap, vm.waypoints.toList()) }
            .collect { (r, show, w) ->
                c.setPlanned(if (show) r else null)
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
    LaunchedEffect(c, (nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.id) {
        c.setNavProposal((nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.line)
    }
    LaunchedEffect(c, nav?.toRoute?.target, nav?.prompt?.javaClass, location) {
        val guide = nav?.toRoute?.takeIf { nav.prompt != null && nav.prompt !is com.onpath.app.navigation.NavPrompt.Question }
        c.setNavGuide(location?.let { com.onpath.app.routing.GeoPoint(it.latitude, it.longitude) }, guide?.target)
    }
    LaunchedEffect(c, rec?.track?.sumOf { it.size }) { c.setTrack(MapController.SRC_REC, rec?.track ?: emptyList()) }
    LaunchedEffect(c, vm.highlight) { c.setHighlight(vm.highlight) }
    LaunchedEffect(c, vm.activity, vm.settings.routeOverlay) { c.setOverlay(vm.activity, vm.settings.routeOverlay) }
    LaunchedEffect(c, location) { location?.let { c.updateLocation(it) } }
    // Camera: follow in Plan, heading-up / north-up in Navigate; next-turn arrow (see NavigateScreen).
    LaunchedEffect(c, vm.followMe, vm.mode, vm.navHeadingUp) {
        c.setCamera(vm.followMe, vm.mode == AppMode.NAVIGATE, vm.navHeadingUp) { vm.followMe = false }
    }
    LaunchedEffect(c, vm.navTurn) { c.setTurnArrow(vm.navTurn) }
    LaunchedEffect(c, vm.events.size) {
        val pending = vm.events.toList()
        vm.events.clear()
        pending.forEach { e ->
            when (e) {
                is UiEvent.Fit -> c.fitTo(e.points, panelHeightPx)
                is UiEvent.Share -> runCatching { context.startActivity(e.intent) }
            }
        }
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
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
} catch (_: Exception) { null }
