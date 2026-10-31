package com.onpath.app.ui

import com.onpath.strings.*
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** startActivity of the camera action (☰ → Pair with browser, [PairEntry]); throws without a camera app. */
    pairCamera: (String) -> Unit = {},
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
    // only on / off: the demo state changes every second, the map screen need not recompose for that
    val demoOn by remember { com.onpath.app.recording.DemoLocation.state.map { it != null }.distinctUntilChanged() }
        .collectAsState(com.onpath.app.recording.DemoLocation.active)

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
            else -> vm.followMe = true
        }
    }
    // One grouped control: compass (always: Plan = needle, tap north up; Navigate = orientation toggle), eye
    // (only with something route-like on the map), locate (always, with the GPS dot).
    // [mode] = the layer the footer sits in (not vm.mode: the leaving layer keeps its look while it animates out)
    val footer = @Composable { mode: AppMode ->
        // the points step leaves little map: only Undo + locate (the compass would sit over ☰)
        val pointsOpen = mode == AppMode.PLAN && vm.planSize == PlanSize.FULL && vm.shown == null && vm.cardPlace == null
        MapFooter(scale, onCredits = { vm.openSettings(SettingsPage.ABOUT) }, demo = demoOn) {
            MapControls(
                // same cycle in both modes: turned by hand → north up → walking direction up → north up (AppViewModel.onCompass)
                compass = if (pointsOpen) null
                    else CompassSpec(vm.headingUp && vm.followMe, bearing) { vm.onCompass(kotlin.math.abs(bearing) > 1f) },
                showEye = !pointsOpen && (vm.route != null || vm.shown != null || vm.followLine != null),
                onPeek = { vm.routePeek = it },
                gps = gps, following = vm.followMe, onLocate = onLocate,
                onUndo = if (mode == AppMode.PLAN && vm.canUndo && vm.shown == null) vm::undoStep else null,
                // Undo and Redo side by side as map buttons; Redo only when there is something to redo
                onRedo = if (mode == AppMode.PLAN && vm.canRedo && vm.shown == null) vm::redoStep else null,
                large = mode == AppMode.NAVIGATE,
                onSettings = if (mode == AppMode.NAVIGATE) { { vm.navSettingsOpen = true } } else null,
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
        // A newer version of an area the user has (checked after each route) is told by the background-download strip
        // only – one loading indicator is enough. No cell-state read here: download ticks must not recompose this screen.
        MapSync(vm, controller, rec, location, panelHeightPx)
        // hike: keep-on + pocket logic; bike: Settings → "Screen while cycling", pocket after 5 s covered (ScreenPolicy,
        // rest view in NavigateMode)
        val screen = ScreenPolicy.of(vm.settings, rec?.nav?.session?.activity ?: vm.activity)
        KeepScreenOn(screen.keepOn && rec != null && vm.followLine != null && vm.mode == AppMode.NAVIGATE, pocketAfterMs = screen.pocketAfterMs)
        // Quiet update check (once a day, only when online; F-OFF-06).
        LaunchedEffect(Unit) { vm.checkForUpdates(manual = false) }

        // Plan ⇄ Navigate: the control layers slide / fade (≈250 ms, ModeSwitch); the map above is not touched.
        ModeSwitch(vm.mode,
            plan = {
                PlanMode(vm, rec, { footer(AppMode.PLAN) }, onStartNavigation, onRequestLocation, pairCamera,
                    // during a tour the same check-and-switch sheet as Navigate: Take new route / Keep
                    onActivity = { if (rec != null) vm.openActivityCheck() else showActivity = true }, onEndTour = { vm.askEndTour() },
                    onPanelHeight = { panelHeightPx = it })
            },
            navigate = { NavigateMode(vm, rec, location, bearing) { footer(AppMode.NAVIGATE) } },
        )
        // "Where I face" arrow at the dot in heading-up following (MapController.Facing)
        // (not over the rest view while cycling)
        if (!vm.screenRest) controller?.let { ctl -> FacingArrow(ctl.facing) { bearing } }
        // hints below the search bar go below the background-download strip while it shows
        // (measured height: a message box is taller than the progress chip)
        val hintTop = if (vm.backgroundStripShown && vm.mode == AppMode.PLAN)
            (vm.backgroundStripBottomDp + MessagePlacement.GAP_DP).coerceAtLeast(124).dp else 76.dp
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
                    androidx.compose.material3.Text(str(Res.string.plan_finding_shared_place))
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
            // a route computed while the view has no detailed map is waited for (modal)
            vm.viewWithoutDetail = without
            value = without && k!![4] > 0
        }
        val overviewOnly = rememberNoFlash(if (overviewView) Unit else null, remember { NoFlash<Unit>() }) != null
        // not while the strip asks "Detailed map for here?" – it says the same
        if (overviewOnly && vm.mode == AppMode.PLAN && !vm.searchOpen && !sharedLookup && vm.hereAsk == null) {
            OverviewHint(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = hintTop, start = 16.dp, end = 16.dp))
        }
        // Plan: menu of a held POI / route point, following the map.
        MapOverlayHost(vm, controller, cameraTick,
            topPx = topInsetPx + with(density) { (hintTop + 4.dp).roundToPx() }, // below the search pill + ☰ row (+ strip)
            bottomPx = panelHeightPx + with(density) { 8.dp.roundToPx() }, gps = gps)
        // Plan during a tour: the activity check-and-switch sheet over the plan sheet (Navigate draws its own)
        if (vm.mode == AppMode.PLAN) vm.activityCheck?.let {
            androidx.activity.compose.BackHandler { vm.closeActivityCheck() }
            ActivitySwitchSheet(it, vm::chooseActivity, vm::closeActivityCheck, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (showActivity) ActivityDialog(
        vm.activity,
        onSelect = { vm.selectActivity(it) },
        onDismiss = { showActivity = false },
        sac = vm.sac, onSac = vm::selectSac,
    )
    if (vm.routeBackAsk && rec != null) RouteBackDialog(onYes = { vm.answerRouteBack(true) }, onNo = { vm.answerRouteBack(false) })
    vm.longRouteAsk?.let { s -> LongRouteDialog(s, vm.activity, onPlan = { vm.answerLongRoute(true) }, onCancel = { vm.answerLongRoute(false) }) }
    when (val ask = vm.endAsk) {
        EndAsk.Plain -> EndTourDialog(onSave = { vm.endTour() }, onDelete = { vm.endTourAndDelete() }, onDismiss = { vm.endAsk = null })
        is EndAsk.Rides -> RideSummaryDialog(ask.summary, onSave = { vm.endTourWith(ask, it) },
            onDelete = { vm.endTourAndDelete() }, onDismiss = { vm.endAsk = null })
        null -> {}
    }
    // arrival Save goes the End tour way: a bus / car / lift found → the ride summary
    if (vm.arrivalAsk && rec != null) ArrivalDialog(onSave = { vm.askEndTour(saveIfNoRides = true) }, onDelete = vm::endTourAndDelete,
        onKeepGoing = { vm.arrivalAsk = false })
    if (vm.navSettingsOpen) {
        // Navigate → ⚙: the Navigation settings page on the way (owner); everything else stays in Plan → Settings
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { vm.navSettingsOpen = false }) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item {
                    NavigationSection(vm.settings, vm::updateSettings,
                        onPhoneSetup = { vm.navSettingsOpen = false; vm.openSetup(com.onpath.app.setup.Purpose.SETTINGS) },
                        onTestVibration = { vm.navSettingsOpen = false; vm.openSetupStep(com.onpath.app.setup.CheckId.VIBRATION) })
                }
            }
        }
    }
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
    onRequestLocation: () -> Unit, pairCamera: (String) -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit,
    onPanelHeight: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PlanTopBar(
            onSearch = vm::openSearch,
            menu = PlanMenu(
                state = vm.menuState(recording = rec != null),
                onManage = { vm.openRoutes() },
                // Free recording without a route: a shown track is closed so it is not followed.
                onStartRecording = { vm.closeShown(); onStartNavigation() },
                onSettings = { vm.openSettings() },
                // plan sync (A-007), owner 2026-10-30: not paired → the camera at once (first time the step line
                // before it); paired / a problem → Settings → Browser sync ([PairEntry])
                onPairBrowser = { vm.startPairing(pairCamera) },
                // owner 2026-10-30: "Paired with browser ✓ · Pixel 8 ↔ Chrome · 1 min ago"; amber + ☰ dot on a real problem
                sync = SyncMenuUi.of(vm.sync.state.collectAsState().value, vm.sync.deviceName,
                    System.currentTimeMillis()),
                // owner 2026-10-30: red "Phone setup" first while a tour needs something (vm.phone is re-read on resume)
                onPhoneSetup = if (!SettingsSummary.phoneNeeded(com.onpath.app.setup.PhoneCheck.all(vm.phone, vm.vibrationUsed, vm.phoneTaken))) null
                    else ({ vm.openSettings(SettingsPage.PHONE) }),
                // owner 2026-10-30: "Save route" on top while the plan has unsaved changes (same as the sheet's Save)
                onSaveRoute = if (RouteRules.saveAtHand(vm.waypoints.size, vm.planUnsaved)) vm::requestSave else null,
                onOpened = vm::refreshTours,
                // no "Check for updates" in the ☰ menu – Settings has the Updates section (owner)
                onUpdates = null,
            ),
            modifier = Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().statusBarsPadding(),
        )
        // background downloads (corridor, refresh, routing data): small blue chip under ☰ after 10 s, until all is done;
        // tap → details with "Cancel all" (own composable: its per-second progress recomposes only the chip, not PlanMode)
        PlanMessageHost(vm, rec, onRequestLocation, pairCamera)
        // The sheet's height changes every frame while it moves; the map only hears the height once it rests (a state
        // read by MapScreen would recompose the whole screen per frame and the sheet stutters).
        val sheetHeight = remember { mutableIntStateOf(0) }
        val panelHeight by rememberUpdatedState(onPanelHeight)
        LaunchedEffect(Unit) {
            snapshotFlow { sheetHeight.intValue }.collectLatest { delay(120); panelHeight(it) }
        }
        val density = LocalDensity.current
        val topLimit = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + TOP_BAR_BOTTOM
        SheetWithFooter(topLimit, modifier = Modifier.modeEdge(ModeEdge.BOTTOM), footer = {
            Column(Modifier.fillMaxWidth()) {
                // after Install: the download's progress (a state, no box); the question itself is a box at the top
                if (rec == null) UpdateDownloadingCard(vm.update, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                footer()
            }
        }, sheet = {
            Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) {
                // The tour controls sit at the top of the plan sheet; a separate card only when the sheet is replaced
                // (place card or shown track).
                if (vm.cardPlace != null || vm.shown != null) rec?.let {
                    TourRunningBar(it.distanceM, it.elapsedS, it.paused, onEnd = onEndTour, onPause = vm::pauseRecording,
                        onContinue = vm::continueNavigation, activity = vm.activity)
                }
                // The place card (tapped POI / search result, ui/search) takes the sheet's place while open.
                if (vm.cardPlace != null) com.onpath.app.ui.search.PlaceCardHost(vm)
                else PlanBottom(vm, rec, onStartNavigation, onActivity, onEndTour, onRequestLocation)
            }
        })
        com.onpath.app.ui.search.SearchHost(vm) // full-screen search view while open
    }
}

/**
 * Plan: the one message box at the top (MessageCard, docs/design/ui-style.md) – or, when no box waits, the quiet pill /
 * the background-download chip under ☰ (states) – + the download details dialog. One box at a time (Messages.pick):
 * questions first (pairing step, map questions, map for here?, new version), then the must-know notes (error, paired,
 * a map failure / note). The rest wait in their state and come next.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlanMessageHost(vm: AppViewModel, rec: RecState?,
                                                                        onRequestLocation: () -> Unit,
                                                                        pairCamera: (String) -> Unit) {
    // Cell states tick with every downloaded chunk: read only through derived values, so the box recomposes when the
    // card / modal really changes, not per tick (else the sheet moves janky while areas download – CellStateReads.kt).
    val cellCardTarget by rememberDerived(vm.cells.states, vm.cells.installed) { s, i -> vm.cellCardUi(s, i) }
    // no flash (owner rule): fast steps ("Preparing…" for a moment before "Downloading") are skipped,
    // whatever shows stays readable; a tap answers at once
    val cellGate = remember { NoFlash<CellCardUi>(stepKey = CellCards::stepKey, urgent = CellCards::waitsForUser) }
    val cellCard = rememberNoFlash(cellCardTarget, cellGate)
    fun acted(f: () -> Unit): () -> Unit = { cellGate.userActed(android.os.SystemClock.uptimeMillis()); f() }
    val cellCb = CellCardCallbacks(onMobileYes = acted(vm::cellCardMobileYes), onWifiOnly = acted(vm::cellCardWifiOnly),
        onRetry = acted(vm::cellCardRetry), onClose = acted(vm::closeCellCard), onCancel = acted(vm::cancelCellLoading),
        onAllowLocation = acted(onRequestLocation))
    // a map answer / problem (mobile data, failed, offline …) – no second map card in the plan sheet
    val notice = mapNotice(cellCard)
    val bg = rememberBackgroundDownload(vm)
    val here = vm.hereAsk
    val syncNote = vm.syncNote
    val upd = vm.update as? AppViewModel.UpdateState.Available
    val mapBox = notice?.let { mapNoticeMessage(it, cellCb) }
    val message = if (vm.searchOpen) null else Messages.pick(listOf(
        // ☰ → Pair with browser, the first time (owner 2026-10-30): the one step, then the camera
        if (vm.pairHint) pairHintMessage(onCamera = { vm.pairHintCamera(pairCamera) }, onCancel = vm::pairHintCancel) else null,
        mapBox?.takeIf { it.kind == MessageKind.FRAGE },
        // opening the app asks for the map of here, it never loads by itself, e.g. in a car (owner)
        here?.let { hereAskMessage(it.bytes, onLoad = { vm.answerHereAsk(true) }, onNotNow = { vm.answerHereAsk(false) }, mustBuild = it.mustBuild) },
        upd?.release?.version?.takeIf { rec == null && it != vm.updateDismissed }?.let { v ->
            updateMessage(v, onInstall = { vm.installUpdate() }, onLater = { vm.updateDismissed = v })
        },
        vm.errorCard?.let { errorMessage(it) { vm.errorCard = null } },
        // a pairing was just made or ended (owner 2026-10-30): "Paired. …" / "Unpaired. …" with OK, until OK
        syncNote?.let { syncNoteMessage(it, onOk = vm::syncNoteOk) },
        mapBox?.takeIf { it.kind != MessageKind.FRAGE },
    ))
    // states (no box) only while no box shows: the quiet pill, else plain progress as a small chip under ☰
    val pill = notice?.takeIf { message == null && !vm.searchOpen && mapNoticeKind(it) == null }
    val chipOn = bg != null && !vm.searchOpen && message == null && notice == null
    LaunchedEffect(chipOn, message != null, pill != null) { vm.backgroundStripShown = chipOn || message != null || pill != null }
    val density = LocalDensity.current
    // the bottom below the status bar: top + measured height (hints under the search bar go below it)
    fun Modifier.measured(topDp: Int) =
        onSizeChanged { vm.backgroundStripBottomDp = topDp + with(density) { it.height.toDp().value.toInt() } }
    val top = MessagePlacement.topDp(MessageScreen.PLAN)
    MessageSlot(message, top, Modifier.modeEdge(ModeEdge.TOP), onBottomDp = { vm.backgroundStripBottomDp = it })
    if (pill != null) {
        MapNoticePill(pill, Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).statusBarsPadding()
            .padding(top = top.dp, start = 16.dp, end = 16.dp).measured(top))
    } else if (chipOn) {
        BackgroundDownloadChip(bg!!, onClick = { vm.downloadDetailsOpen = true },
            modifier = Modifier.align(Alignment.TopEnd).modeEdge(ModeEdge.TOP).statusBarsPadding()
                .padding(top = MessagePlacement.CHIP_TOP_DP.dp, end = 12.dp).measured(MessagePlacement.CHIP_TOP_DP))
    }
    BackgroundDownloadDetails(vm, bg)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlanBottom(vm: AppViewModel, rec: RecState?, onStartNavigation: () -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit,
                       onRequestLocation: () -> Unit = {}) {
    val shown = vm.shown
    if (shown != null) {
        TrackPanel(
            shown, isFollowed = vm.followName == shown.name, recording = rec != null, activity = vm.activity,
            onFollow = {
                if (rec == null) onStartNavigation()
                else { vm.followShown(); vm.closeShown(); vm.continueNavigation() }
            },
            onClose = vm::closeShown, onHighlight = { vm.highlight = it },
            onPlan = vm::planFromShown, planBusy = vm.planFromTrackBusy,
        )
        return
    }
    // the user waits for the map of their area (first start, a point without detailed map): one plain modal with %,
    // time left and Cancel; ticks every second so the bar moves smoothly
    val loadUi by produceState<MapLoadUi?>(null, vm.cellJob, vm.mode) {
        vm.cells.states.collectLatest { states ->
            while (true) { value = vm.mapLoadUi(states, android.os.SystemClock.uptimeMillis()); delay(1000) }
        }
    }
    // Rows of the Points section, composed with the sheet in every step (StepSheet: it is only revealed). The distances
    // along the route walk the whole line: done once per route off the main thread (PlanPoints.legsCached); until then
    // the previous rows stay.
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
    MapLoadModal(loadUi, MapLoadCallbacks(onCancel = vm::cancelLoadModal, onRetry = vm::cellCardRetry, onClose = vm::hideLoadModal,
        onLoadOnMobile = vm::loadModalOnMobile))
    vm.loadAsk?.let { LoadAskDialog(it.regions, it.bytes, onLoad = { vm.answerLoadAsk(true) }, onNotNow = { vm.answerLoadAsk(false) }) }
    PlanPanel(
        PlanUi(vm.waypoints.size, vm.route, vm.routing, vm.routeError, vm.canUndo, vm.canRedo, vm.activity, vm.planSize,
            sac = vm.sac, tourRunning = rec != null, savedName = vm.savedName, unsaved = vm.planUnsaved, roundTrip = vm.isRoundTrip(),
            tour = rec?.let { TourState(it.distanceM, it.elapsedS, it.paused) },
            points = rows,
            routingSlow = vm.routingSlow, notRouted = vm.notRouted, gapText = vm.planGapText(), warnText = vm.warningLineText(),
            changedText = vm.routeChangedLineText(), syncRoutingText = vm.syncRoutingNote),
        PlanCallbacks(
            onSheetDrag = vm::mapGesture, onUndo = vm::undoStep, onClear = vm::requestClear,
            onStartNavigation = onStartNavigation, onContinue = vm::continueNavigation,
            onReverse = vm::reverseRoute, onRoundTrip = vm::roundTrip,
            onActivity = onActivity, onSave = vm::requestSave, onShare = vm::sharePlan, onTimeDetails = vm::openTimeDetails, onDetails = { vm.routeDetailsOpen = true },
            onHighlight = { vm.highlight = it },
            onCancelRouting = vm::cancelRouting,
            onPlanAnyway = vm::planAnyway,
            onShowGap = vm::showPlanGap,
            onWarnings = vm::tapWarningLine,
            onRouteChanged = vm::tapRouteChangedLine,
            onResize = { vm.planSize = it },
            onPause = vm::pauseRecording, onEndTour = onEndTour,
            onMovePoint = vm::movePointInList, onDeletePoint = vm::deletePointFromList, onShowPoint = vm::showPointFromList,
        ),
    )
    if (vm.spotsOpen) {
        // the spots of the amber line: tap one → the map shows it
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = vm::closeSpots) {
            RouteSpotsList(vm.routeSpots, vm.sac, onShow = vm::showSpot)
        }
    }
    // names for the Points section (offline place index, off the main thread) whenever the points change – ready before
    // the sheet is pulled up (names arriving mid-pull would rebuild the list)
    LaunchedEffect(vm.waypoints.toList()) { vm.refreshPointNames() }
}

/** Keeps the map layers in step with the app state. */
@Composable
private fun MapSync(vm: AppViewModel, c: MapController?, rec: RecState?, fix: android.location.Location?, panelHeightPx: Int) {
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
                // unreachable points: grey dashed missing piece + grey hollow marker
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
    // rest view while cycling (NavigateMode): the covered map gets no fixes, no direction and no track updates, so it
    // draws no frames; the camera mode is kept, everything is sent again the moment the map shows
    val rest = vm.screenRest
    val location = fix.takeUnless { rest }
    LaunchedEffect(c, rest) { c.setQuiet(rest) }
    LaunchedEffect(c, nav?.original) { c.setNavOriginal(nav?.original) }
    // Heading-up on the route: the stretch ahead points straight up (HeadingFilter). Only in Navigate, matched to
    // the line, not off route; otherwise null = compass / GPS bearing.
    val navLine = remember(nav?.line) { nav?.line?.let { com.onpath.app.navigation.NavLine(it) } }
    val routeAhead = if (vm.mode == AppMode.NAVIGATE && nav != null && nav.prompt == null &&
        (nav.offM ?: Double.MAX_VALUE) <= ROUTE_UP_MAX_OFF_M) nav.shownAlongM?.let { navLine?.aheadBearing(it) } else null
    // re-sent on every fix: standing still on the route keeps the route direction (else after 15 s without progress the
    // compass takes over and, with the phone tilted / in the pocket, can turn the map 180°)
    LaunchedEffect(c, routeAhead, nav?.shownAlongM, location) { c.setRouteHeading(routeAhead) }
    // turn round: the U-turn sign where the way back starts
    LaunchedEffect(c, nav?.uTurnAt) { c.setUTurn(nav?.uTurnAt) }
    LaunchedEffect(c, (nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.id) {
        c.setNavProposal((nav?.prompt as? com.onpath.app.navigation.NavPrompt.Question)?.line)
    }
    LaunchedEffect(c, nav?.toRoute?.target, nav?.prompt?.javaClass, location) {
        val guide = nav?.toRoute?.takeIf { nav.prompt != null && nav.prompt !is com.onpath.app.navigation.NavPrompt.Question }
        c.setNavGuide(location?.let { com.onpath.app.routing.GeoPoint(it.latitude, it.longitude) }, guide?.target)
    }
    LaunchedEffect(c, rec?.track?.sumOf { it.size }?.takeUnless { rest }) { if (!rest) c.setTrack(MapController.SRC_REC, rec?.track ?: emptyList()) }
    LaunchedEffect(c, vm.highlight) { c.setHighlight(vm.highlight) }
    // the spot tapped in the route warnings list: its piece of route amber (Plan only)
    LaunchedEffect(c, vm.spotLine, vm.mode) { c.setSpotLine(vm.spotLine?.takeIf { vm.mode == AppMode.PLAN }) }
    LaunchedEffect(c, vm.searchMark) {
        val m = vm.searchMark
        c.setSearchMark(m?.let { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }, m?.title())
    }
    // Google Maps place → go there (owner): the ring's menu opens by itself once the camera has arrived there (and the
    // map-download modal for it is gone) – checked on every camera stop while it waits (SharedPlaceMenu)
    LaunchedEffect(c, vm.sharedMenuWaiting, vm.cameraIdleTick, vm.loadModalOpen) {
        if (!vm.sharedMenuWaiting) return@LaunchedEffect
        delay(SharedPlaceMenu.SETTLE_MS)
        val t = try { c.map.cameraPosition.target } catch (e: Exception) {
            Problems.warn("map", "Camera position unreadable; shared place menu waits", e); null
        }
        vm.openSharedMenuIfArrived(t?.latitude, t?.longitude, c.metersPerPixel(), c.dpToPx(SharedPlaceMenu.ARRIVED_DP).toDouble())
    }
    // Marked route networks always show (owner): the band of the current activity.
    LaunchedEffect(c, vm.activity, vm.sac) { c.setOverlay(vm.activity, true, vm.sac) }
    // hiking route band above the T level from Settings: grey dashes (the router forbids those trails)
    LaunchedEffect(c, vm.sac) { c.setSacLimit(vm.sac) }
    val snapLine = remember(rec?.nav?.line) { rec?.nav?.line?.let { com.onpath.app.navigation.NavLine(it) } }
    LaunchedEffect(c, location) {
        location?.let { loc ->
            // "Position on the route": the dot on the followed line while close to it – the drawn
            // dot only; everything else uses the real fix
            val n = rec?.nav
            val on = com.onpath.app.navigation.SnapToRoute.position(vm.settings.positionOnRoute && vm.mode == AppMode.NAVIGATE,
                snapLine, n?.shownAlongM, n?.offM, n?.prompt)
            c.updateLocation(if (on == null) loc else android.location.Location(loc).apply { latitude = on.lat; longitude = on.lon })
            vm.checkFreeLook(loc.latitude, loc.longitude)
        }
    }
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
    LaunchedEffect(c, vm.followMe, vm.mode, vm.headingUp, vm.recentreTick, vm.navStartZoom) {
        val startZoom = vm.navStartZoom.takeIf { vm.mode == AppMode.NAVIGATE && vm.followMe }
        val zoomed = c.setCamera(vm.followMe, vm.mode == AppMode.NAVIGATE, vm.headingUp, startZoom) { vm.onMapGesture() }
        if (startZoom != null && zoomed) vm.navStartZoom = null
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
 * Navigate shows only the route line, no numbered points (owner).
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
