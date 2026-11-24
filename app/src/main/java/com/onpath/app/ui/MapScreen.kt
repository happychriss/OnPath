package com.onpath.app.ui

import com.onpath.app.domain.AppMode
import com.onpath.app.domain.LocateDistance
import com.onpath.app.domain.OfflineReady
import com.onpath.app.domain.PlanCamera
import com.onpath.app.domain.PlanGap
import com.onpath.app.domain.RouteRules
import com.onpath.app.domain.ScaleRule
import com.onpath.app.domain.SharedPlaceMenu
import com.onpath.app.domain.StartCamera
import com.onpath.strings.*
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
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
import com.onpath.app.domain.tour.ScreenPolicy

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
    /** Navigate: dp covered at the top (turn / status column, message box) and the bottom (slider) – route fits. */
    val navCover = remember { intArrayOf(0, 0) }
    /** Plan: size of the map button column (px, width + height) – a route fit keeps beside or above it (FitPaddingRule). */
    val planFab = remember { intArrayOf(0, 0) }
    /** Plan: the activity button's right and bottom edge (px in the window; 0 = not laid out) – a fit keeps clear of it. */
    val planActivity = remember { intArrayOf(0, 0) }
    /** The camera's per-frame values ([MapCameraState]): read only where they are drawn, never in this screen's body. */
    val cam = remember { MapCameraState() }
    var showActivity by remember { mutableStateOf(false) }

    val rec by vm.recState.collectAsState()
    val location by vm.tracker.location.collectAsState()
    // only on / off: the demo state changes every second, the map screen need not recompose for that
    val demoOn by remember { com.onpath.app.recording.DemoLocation.state.map { it != null }.distinctUntilChanged() }
        .collectAsState(com.onpath.app.recording.DemoLocation.active)

    // GPS status for the dot on the locate button, refreshed every second (age of the last fix matters).
    val gpsNow by produceState(GpsStatus.SEARCHING to false, vm.tracker) {
        while (true) { value = vm.tracker.status() to (vm.tracker.location.value != null); delay(1000) }
    }
    val gps = gpsNow.first
    val hasFix = gpsNow.second
    // Plan, my position off the map (owner 2026-11-07, variant 3): the distance under the locate icon, throttled – the
    // position it is made from moves on only after ≥ 50 m / 5 % ([LocateDistance.moved]); the view comes with the camera
    val meNow by vm.tracker.location.collectAsState()
    var labelMe by remember { mutableStateOf<com.onpath.app.routing.GeoPoint?>(null) }
    val farMetres = { LocateDistance.metres(plan = true, located = PlanGps.located(gps, hasFix), me = labelMe,
        view = LocateDistance.View.of(cam.hintKey)) }
    LaunchedEffect(meNow) {
        val p = meNow?.let { com.onpath.app.routing.GeoPoint(it.latitude, it.longitude) }
        if (p == null) labelMe = null else if (LocateDistance.moved(labelMe, p, farMetres())) labelMe = p
    }
    // derived: the camera changes the view every frame, the label's text only now and then – only that recomposes
    val locateLabel by remember(gps, hasFix) { derivedStateOf { farMetres()?.let(LocateDistance::text) } }
    val onLocate = {
        if (vm.tourSession.mode == AppMode.PLAN && LocateDistance.tap(locateLabel != null) == LocateDistance.Tap.FRAME_BOTH) vm.map.showPlanAndMe()
        else when (PlanGps.tap(vm.tourSession.mode == AppMode.PLAN, gps, hasFix)) {
            PlanGps.Tap.OPEN_SETTINGS -> {
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .onFailure { Problems.warn("ui", "Location settings screen could not be opened", it) }
                Unit
            }
            PlanGps.Tap.ASK_PERMISSION -> onRequestLocation()
            // Plan without a position (owner 2026-11-06 23:35: no GPS warning in Plan): follow it as soon as it comes,
            // and a short neutral note says why nothing moves yet – only because the user tapped
            PlanGps.Tap.WAIT_AND_NOTE -> { vm.map.locate(); vm.map.showPlanGpsNote(PlanGps.Note.SEARCHING) }
            PlanGps.Tap.CENTRE -> vm.map.locate()
        }
    }
    // the neutral note goes after a few seconds, or at once when a position is there
    LaunchedEffect(vm.map.planGpsNoteTick) {
        vm.map.planGpsNote?.let { delay(PlanGps.noteMs(it)); vm.map.planGpsNote = null }
    }
    LaunchedEffect(gps, hasFix) { if (PlanGps.noteGone(vm.map.planGpsNote, gps, hasFix)) vm.map.planGpsNote = null }
    // the map's quiet INFO line goes by itself (MapNote.MS), counted from when it was said
    LaunchedEffect(vm.notices.mapNoteTick) { vm.notices.mapNote?.let { delay(MapNote.MS); vm.notices.mapNote = null } }
    // One grouped control: compass (always, both modes: the session's one orientation – MapOrientation.tap), locate (always,
    // with the GPS dot), eye and whole route (always – grey without something route-like on the map, owner 2026-11-06).
    // [mode] = the layer the footer sits in (not vm.mode: the leaving layer keeps its look while it animates out)
    // derived: following walking-direction-up turns the map every frame, the compass then shows its arrow (no needle)
    val compassBearing by remember { derivedStateOf { CompassSpec.bearingShown(vm.settingsSlice.settings.navHeadingUp && vm.map.followMe, cam.bearing) } }
    val controls = @Composable { mode: AppMode ->
        // the points step leaves little map: only Undo + locate (the compass would sit over ☰)
        val pointsOpen = mode == AppMode.PLAN && vm.planEdit.planSize == PlanSize.FULL && vm.routesFlow.shown == null && vm.placeMenus.cardPlace == null
            // measured, no state: a fit reads it when it happens
            val ctl = vm.controlsIn(mode, pointsOpen)
            Box(if (mode == AppMode.PLAN) Modifier.onSizeChanged { planFab[0] = it.width; planFab[1] = it.height } else Modifier) {
                MapControls(
                    // same in both modes (MapOrientation.tap): north + turned by hand → north; north → walking direction; walking direction → north
                    compass = if (pointsOpen) null
                        else CompassSpec(vm.settingsSlice.settings.navHeadingUp && vm.map.followMe, compassBearing) { vm.map.onCompass(kotlin.math.abs(cam.bearing) > 1f) },
                    // eye, whole route, undo, redo: always there, greyed when they cannot act (MapControlsEnabled)
                    showEye = MapControlsEnabled.eye(ctl),
                    onPeek = { vm.routePeek = it },
                    // under the eye: the whole line + its points (Navigate: + where you are), following stops
                    onFitRoute = if (MapControlsEnabled.wholeRoute(ctl)) vm.map::showWholeRoute else null,
                    gps = gps, following = vm.map.followMe, onLocate = onLocate,
                    locateGreyed = PlanGps.locateGreyed(mode == AppMode.PLAN, gps, hasFix),
                    locateLabel = if (mode == AppMode.PLAN) locateLabel else null,
                    onSettings = if (mode == AppMode.NAVIGATE) vm.settingsSlice::openSettingsOnTheWay else null,
                    plan = mode == AppMode.PLAN, // Plan is blue (owner 2026-11-03), Navigate unchanged
                )
            }
            // Plan: undo / redo in their own pill under the column, only while one of them can act (owner 2026-11-08)
            if (mode == AppMode.PLAN && MapControlsEnabled.undoPill(ctl)) UndoPill(
                onUndo = if (MapControlsEnabled.undo(ctl)) vm.planEdit::undoStep else null,
                onRedo = if (MapControlsEnabled.redo(ctl)) vm.planEdit::redoStep else null)
    }
    // Navigate: scale, © and the controls above the slider as before. Plan (owner 2026-11-06, onpath.private#43): scale
    // and © stay on the sheet's edge, the controls stand at one fixed place (PlanControlsLayer in PlanMode)
    val footer = @Composable { mode: AppMode ->
        MapFooter(cam.scale, onCredits = { vm.settingsSlice.openSettings(SettingsPage.ABOUT) }, demo = demoOn) {
            if (mode == AppMode.NAVIGATE) controls(mode)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapHost(
            vm = vm,
            topInsetPx = topInsetPx,
            onReady = { controller = it },
            onCamera = { map ->
                cam.scale = computeScale(map); cam.tick++; cam.bearing = map.cameraPosition.bearing.toFloat()
                cam.hintKey = try {
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
        // one padding rule for every route fit (FitPaddingRule): clear of the top elements and the sheet / slider
        val planTopBand = planTopBarDp()
        val fitPadding = {
            FitPaddingRule.of(vm.tourSession.mode, density.density, topInsetPx,
                planTopDp = maxOf(if (vm.mapData.backgroundStripShown) vm.mapData.backgroundStripBottomDp else 0, planTopBand), sheetPx = panelHeightPx,
                navTopDp = navCover[0], navBottomDp = navCover[1], fabWidthPx = planFab[0],
                // the column stands at a fixed place above the middle (onpath.private#43), not on the sheet: the fit keeps
                // beside it; the old "above the column" room (fabHeightPx) no longer exists
                fabHeightPx = 0,
                // the activity button top left (hidden over a shown track): beside or below it, never a point under it
                activityRightPx = if (vm.routesFlow.shown == null) planActivity[0] else 0,
                activityBottomPx = if (vm.routesFlow.shown == null) planActivity[1] else 0)
        }
        MapSync(vm, controller, rec, location, fitPadding)
        // hike: keep-on + pocket logic; bike: Settings → "Screen while cycling", pocket after 5 s covered (ScreenPolicy,
        // rest view in NavigateMode)
        val screen = ScreenPolicy.of(vm.settingsSlice.settings, rec?.nav?.session?.activity ?: vm.planRoute.activity)
        KeepScreenOn(screen.keepOn && rec != null && vm.tourFollow.followLine != null && vm.tourSession.mode == AppMode.NAVIGATE, pocketAfterMs = screen.pocketAfterMs)
        // Quiet update check (once a day, only when online; F-OFF-06).
        LaunchedEffect(Unit) { vm.updates.check(manual = false) }

        // Plan ⇄ Navigate: the control layers slide / fade (≈250 ms, ModeSwitch); the map above is not touched.
        ModeSwitch(vm.tourSession.mode,
            plan = {
                PlanMode(vm, rec, { footer(AppMode.PLAN) }, { controls(AppMode.PLAN) }, onStartNavigation, onRequestLocation, pairCamera,
                    // during a tour the same check-and-switch sheet as Navigate: Take new route / Keep
                    onActivity = { if (rec != null) vm.activityCheckSlice.openActivityCheck() else showActivity = true }, onEndTour = { vm.tourEnd.askEndTour() },
                    onPanelHeight = { panelHeightPx = it },
                    onActivityBox = { r, b -> planActivity[0] = r; planActivity[1] = b })
            },
            navigate = { LiveSignLayer(vm) { NavigateMode(vm, rec, location, { cam.bearing }, onCover = { t, b -> navCover[0] = t; navCover[1] = b }) { footer(AppMode.NAVIGATE) } } },
        )
        // "Where I face" arrow at the dot in heading-up following (MapController.Facing)
        // (not over the rest view while cycling)
        if (!vm.screenRest) controller?.let { ctl -> FacingArrow(ctl.facing) { cam.bearing } }
        // hints below the search bar go below the background-download strip while it shows
        // (measured height: a message box is taller than the progress chip)
        val hintTop = if (vm.mapData.backgroundStripShown && vm.tourSession.mode == AppMode.PLAN)
            (vm.mapData.backgroundStripBottomDp + MessagePlacement.GAP_DP).coerceAtLeast(124).dp else planTopBarDp().dp
        // A shared place is being looked up (link + address search take a moment): say so at once.
        val sharedLookup = rememberNoFlash(if (vm.searchFlow.sharedLookup) Unit else null, remember { NoFlash<Unit>() }) != null
        androidx.compose.animation.AnimatedVisibility(sharedLookup,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = hintTop),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            androidx.compose.material3.Surface(shape = OnPathShapes.Card, color = OnPathColors.Paper, modifier = Modifier.warmShadow(OnPathShadow.LOW, OnPathShapes.Card)) { // over the map: paper (owner 2026-11-06)
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
        // the view key is followed here, not read by the screen: a pan changes it many times a second (smooth map, 2026-11-08)
        val overviewView by produceState(false, generation) {
            snapshotFlow { cam.hintKey }.collectLatest { k ->
                val without = k != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    vm.mapData.overviewOnly(k[0], k[1], k[2], k[3])
                }
                // a route computed while the view has no detailed map is waited for (modal)
                vm.mapData.viewWithoutDetail = without
                value = without && k!![4] > 0
            }
        }
        val overviewOnly = rememberNoFlash(if (overviewView) Unit else null, remember { NoFlash<Unit>() }) != null
        // the first-map card (owner 2026-11-08): the map for here still loading / offline after the setup – at the
        // overview pill's place, which it replaces (they would say the same); no flash for a map that comes at once
        val firstCardTarget by rememberDerived(vm.cells.states, vm.cells.installed) { s, i -> vm.firstMapCard(s, i) }
        val firstCard = rememberNoFlash(firstCardTarget?.takeIf { !vm.searchFlow.searchOpen && !sharedLookup },
            remember { NoFlash<FirstMapCard.Ui>(stepKey = { it::class }) })
        if (firstCard != null) {
            val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
            FirstMapCardView(firstCard, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(
                top = OverviewPillPlace.topDp(rowTop, if (vm.mapData.backgroundStripShown) vm.mapData.backgroundStripBottomDp else null).dp,
                start = OverviewPillPlace.startDp(activityButtonShown = vm.routesFlow.shown == null).dp,
                end = OverviewPillPlace.EDGE_DP.dp))
        }
        // not while the strip asks "Detailed map for here?" or the load question is open – they say the same.
        // On the activity button's row, beside it (OverviewPillPlace) – covers nothing (owner 2026-11-06).
        if (overviewOnly && firstCardTarget == null && firstCard == null && vm.tourSession.mode == AppMode.PLAN && !vm.searchFlow.searchOpen && !sharedLookup &&
            vm.mapData.hereAsk == null && vm.mapData.loadAsk == null) {
            val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
            OverviewPill(onLoad = { OverviewHintRule.centre(cam.hintKey)?.let(vm.mapData::mapTapped) },
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(
                    top = OverviewPillPlace.topDp(rowTop, if (vm.mapData.backgroundStripShown) vm.mapData.backgroundStripBottomDp else null).dp,
                    start = OverviewPillPlace.startDp(activityButtonShown = vm.routesFlow.shown == null).dp,
                    end = OverviewPillPlace.EDGE_DP.dp))
        }
        // Plan's only GPS words: a short neutral note after the user's own tap (owner 2026-11-06 23:35, PlanGps)
        vm.map.planGpsNote?.takeIf { vm.tourSession.mode == AppMode.PLAN && !vm.searchFlow.searchOpen }?.let { note ->
            val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
            QuietPill(when (note) {
                    PlanGps.Note.SEARCHING -> str(Res.string.plan_searching_gps)
                    PlanGps.Note.TURN_NEEDS_POSITION -> str(Res.string.msg_no_position_turn)
                    PlanGps.Note.ROUTE_BACK_NO_POSITION -> str(Res.string.msg_route_back_no_position)
                    PlanGps.Note.ONE_POINT_NO_POSITION -> str(Res.string.msg_one_point_no_position)
                }, androidx.compose.material.icons.Icons.Outlined.LocationSearching,
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(
                    top = PlanGps.noteTopDp(rowTop, if (vm.mapData.backgroundStripShown) vm.mapData.backgroundStripBottomDp else null).dp,
                    start = PlanGps.noteStartDp(activityButtonShown = vm.routesFlow.shown == null).dp, end = OverviewPillPlace.EDGE_DP.dp))
        }
        // A short INFO for the map (vm.say → NoticeSurface.MAP_LINE, e.g. "Relief off – the map runs smoother"): the quiet
        // pill at the GPS note's place (PlanGps.noteTopDp / noteStartDp), after the GPS note (a tap of the user's) – goes
        // by itself after MapNote.MS
        vm.notices.mapNote?.takeIf { vm.tourSession.mode == AppMode.PLAN && !vm.searchFlow.searchOpen && vm.map.planGpsNote == null }?.let { note ->
            val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
            QuietPill(note.text, androidx.compose.material.icons.Icons.Outlined.Info,
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(
                    top = PlanGps.noteTopDp(rowTop, if (vm.mapData.backgroundStripShown) vm.mapData.backgroundStripBottomDp else null).dp,
                    start = PlanGps.noteStartDp(activityButtonShown = vm.routesFlow.shown == null).dp, end = OverviewPillPlace.EDGE_DP.dp))
        }
        // Plan: menu of a held POI / route point, following the map.
        MapOverlayHost(vm, controller, { cam.tick },
            topPx = topInsetPx + with(density) { (hintTop + 4.dp).roundToPx() }, // below the search pill + ☰ row (+ strip)
            bottomPx = panelHeightPx + with(density) { 8.dp.roundToPx() }, gps = gps)
        // Plan during a tour: the activity check-and-switch sheet over the plan sheet (Navigate draws its own)
        if (vm.tourSession.mode == AppMode.PLAN) vm.activityCheckSlice.activityCheck?.let {
            androidx.activity.compose.BackHandler { vm.activityCheckSlice.closeActivityCheck() }
            ActivitySwitchSheet(it, vm.activityCheckSlice::chooseActivity, vm.activityCheckSlice::closeActivityCheck, Modifier.align(Alignment.BottomCenter), onHikeLevel = vm.activityCheckSlice::chooseHikeLevel)
        }
    }

    if (showActivity) ActivityDialog(
        vm.planRoute.activity,
        onSelect = { vm.planRoute.selectActivity(it) },
        onDismiss = { showActivity = false },
        sac = vm.planRoute.sac, onSac = vm.planRoute::selectSac,
    )
    if (vm.planEdit.routeBackAsk && rec != null) RouteBackDialog(onYes = { vm.tourEdits.answerRouteBack(true) }, onNo = { vm.tourEdits.answerRouteBack(false) })
    vm.planRun.longRouteAsk?.let { s -> LongRouteDialog(s, vm.planRoute.activity, onPlan = { vm.answerLongRoute(true) }, onCancel = { vm.answerLongRoute(false) }) }
    when (val ask = vm.tour.endAsk) {
        EndAsk.Plain -> EndTourDialog(onSave = { vm.tourEnd.endTour() }, onDelete = { vm.tourEnd.endTourAndDelete() },
            onDismiss = { vm.tour.dismissEndAsk() }, activity = vm.planRoute.activity, stats = rec?.let { EndStats(it.distanceM, it.elapsedS, it.ascentM) })
        is EndAsk.Rides -> RideQuestionDialog(ask.summary, onSave = { vm.tourEnd.endTourWith(ask, it) },
            onDelete = { vm.tourEnd.endTourAndDelete() }, onDismiss = { vm.tour.dismissEndAsk() })
        null -> {}
    }
    if (vm.routesFlow.planFromTrackBusy) PlanFromTrackDialog(onCancel = vm.routesFlow::cancelPlanFromTrack)
    // arrival "Tour beenden" goes the End tour way: a bus / car / lift found → the ride summary, else saved at once
    // Plan is silent (owner 2026-11-03): "Du bist angekommen" waits for Navigate
    if (com.onpath.app.domain.tour.TourEndFlow.arrivalBoxShows(vm.tour.arrivalAsk, rec != null, vm.tourSession.mode == AppMode.NAVIGATE)) ArrivalDialog(onEnd = { vm.tourEnd.askEndTour(saveIfNoRides = true) }, onDelete = vm.tourEnd::endTourAndDelete,
        onKeepRecording = { vm.tourEnd.keepRecordingAfterArrival() },
        activity = vm.planRoute.activity, stats = rec?.let { EndStats(it.distanceM, it.elapsedS, it.ascentM) })
    // Navigate → ⚙ opens Settings "on the way" (owner 2026-11-07) – MainActivity draws it like Plan's Settings
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
    vm: AppViewModel, rec: RecState?, footer: @Composable () -> Unit, controls: @Composable () -> Unit, onStartNavigation: () -> Unit,
    onRequestLocation: () -> Unit, pairCamera: (String) -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit,
    onPanelHeight: (Int) -> Unit, onActivityBox: (right: Int, bottom: Int) -> Unit = { _, _ -> },
) {
    val helpContext = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
        // the paper band behind the search pill and ☰ (owner 2026-11-05 b; paper since 2026-11-06 variant C – no blue
        // surfaces, no blue glow round the screen): from the screen's top edge (status bar) to the bar's bottom; during a
        // tour one row more ("‹ Zur Tour")
        val topBar = planTopBarDp()
        Box(Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().edgeShadow(androidx.compose.ui.graphics.RectangleShape, up = false).background(PlanTopBand.Color)
            .statusBarsPadding().height(topBar.dp))
        PlanTopBar(
            onSearch = vm.searchFlow::openSearch,
            menu = PlanMenu(
                state = vm.routesGo.menuState(recording = rec != null),
                onManage = { vm.routesGo.openRoutes() },
                onSettings = { vm.settingsSlice.openSettings() },
                // plan sync (A-007), owner 2026-10-30: not paired → the camera at once (first time the step line
                // before it); paired / a problem → Settings → Browser sync ([PairEntry])
                onPairBrowser = { vm.pairing.startPairing(pairCamera) },
                // owner 2026-10-30: "Paired with browser ✓ · Pixel 8 ↔ Chrome · 1 min ago"; amber + ☰ dot on a real problem
                sync = SyncMenuUi.of(vm.sync.state.collectAsState().value, vm.sync.deviceName,
                    System.currentTimeMillis()),
                // owner 2026-10-30: red "Phone setup" first while a tour needs something (vm.phone is re-read on resume)
                onPhoneSetup = if (!SettingsSummary.phoneNeeded(com.onpath.app.setup.PhoneCheck.all(vm.setup.phone, vm.setup.vibrationUsed, vm.setup.phoneTaken, vm.setup.voiceUsed))) null
                    else ({ vm.settingsSlice.openSettings(SettingsPage.PHONE) }),
                // owner 2026-10-31: "Save route" on top whenever there is a plan (the sheet has no Save any more); a dot
                // on ☰ while it has unsaved changes
                // the one Save of the plan, also during a tour (owner 2026-11-05: the sheet has no Save)
                onSaveRoute = if (RouteRules.saveInMenu(vm.plan.points.size)) vm::requestSave else null,
                onOpened = vm.routesFlow::refreshTours,
                // owner 2026-11-06: "Neu planen" also in ☰ (the same way as the sheet's ✕ pill) and the plain recording
                onNewPlan = if (vm.plan.points.isNotEmpty()) vm.planEdit::clearPlan else null,
                onRecordWithoutPlan = if (rec == null) ({
                    vm.setup.openSetup(com.onpath.app.setup.Purpose.BEFORE_TOUR) { vm.tourSession.startNavigation(recordOnly = true) } }) else null,
                // no "Check for updates" in the ☰ menu – Settings has the Updates section (owner)
                onUpdates = null,
                // owner 2026-11-08 (father's feedback): "? Hilfe" first – the website's help in the app language
                onHelp = { OnPathLinks.open(helpContext, OnPathLinks.help()) },
            ),
            modifier = Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).fillMaxWidth().statusBarsPadding(),
            editing = rec != null,
            offline = vm.offlineReady?.state,
            onOffline = vm::toggleOfflineInfo,
        )
        // the activity as a round button on the map, top left under the search pill (owner 2026-11-05, R5): the plan
        // sheet has no activity chip any more; not over a shown track / place card (their own panels)
        if (vm.routesFlow.shown == null) ActivityMapButton(vm.planRoute.activity, vm.planRoute.sac, onActivity,
            Modifier.align(Alignment.TopStart).modeEdge(ModeEdge.TOP).statusBarsPadding()
                .padding(top = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = topBar).dp, start = 12.dp)
                // where it really is (badge and font size included): route fits keep clear of it (FitPaddingRule)
                .onGloballyPositioned { val b = it.boundsInRoot(); onActivityBox(b.right.toInt(), b.bottom.toInt()) })
        // "Ohne Netz ✓ / ⟳ / ⚠" (owner 2026-11-03): recomputed off the main thread when the route changes, every 3 s
        // while loading, else every 20 s (a map region joined, the network back) – file checks only, no downloads
        val offlineLine = vm.route?.points
        LaunchedEffect(offlineLine) {
            vm.offlineInfo = false
            while (true) {
                val r = offlineLine?.let { l -> withContext(Dispatchers.IO) { vm.computeOfflineReady(l) } }
                vm.offlineReady = r
                delay(if (r?.state == OfflineReady.State.LOADING) 3_000 else 20_000)
            }
        }
        // The sheet's height changes every frame while it moves; the map only hears the height once it rests (a state
        // read by MapScreen would recompose the whole screen per frame and the sheet stutters).
        val sheetHeight = remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val topLimit = with(density) { WindowInsets.statusBars.getTop(density).toDp() } + topBar.dp
        // the map controls at their fixed place (owner 2026-11-06, onpath.private#43) – under the boxes and the sheet;
        // the sheet's height is read while placing only (no recomposition while it moves)
        PlanControlsLayer({ sheetHeight.intValue }, topLimit, Modifier.modeEdge(ModeEdge.BOTTOM), controls)
        // background downloads (corridor, refresh, routing data): small blue chip under ☰ after 10 s, until all is done;
        // tap → details with "Cancel all" (own composable: its per-second progress recomposes only the chip, not PlanMode)
        PlanMessageHost(vm, rec, onRequestLocation, pairCamera)
        val panelHeight by rememberUpdatedState(onPanelHeight)
        LaunchedEffect(Unit) {
            snapshotFlow { sheetHeight.intValue }.collectLatest { delay(120); panelHeight(it) }
        }
        SheetWithFooter(topLimit, modifier = Modifier.modeEdge(ModeEdge.BOTTOM), footer = {
            Column(Modifier.fillMaxWidth()) {
                // after Install: the download's progress (a state, no box); the question itself is a box at the top
                if (rec == null) UpdateDownloadingCard(vm.updates.update, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                footer()
            }
        }, sheet = {
            Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) {
                // During a tour the way back to Navigate is the green "Navigation ›" at the bottom, in the plan sheet's foot
                // and under a place card / shown tour too (owner 2026-11-06, C + F1: no status card with Weiter / Pause /
                // Beenden over them any more – those are in Navigate).
                // The place card (tapped POI / search result, ui/search) takes the sheet's place while open; so do the
                // round-trip suggestions (ui/suggest, behind the developer switch).
                // while the search runs it waits in its window over the map (form A): the sheet stays as it was under it
                if (vm.suggest.phase != null && !vm.suggest.searching) com.onpath.app.ui.suggest.SuggestHostPanel(vm)
                else if (vm.placeMenus.cardPlace != null) {
                    com.onpath.app.ui.search.PlaceCardHost(vm)
                    rec?.let { r ->
                        BackToNavigationButton({ backToNavigation(vm, r) },
                            Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                else PlanBottom(vm, rec, onStartNavigation, onActivity, onEndTour, onRequestLocation)
            }
        })
        com.onpath.app.ui.suggest.SuggestWishHost(vm) // the wish sheet of the round-trip suggestions while open
        // the round-trip search: the same wait window as a map load or a route (owner 2026-11-19) – its steps, Abbrechen;
        // the map takes no press under it
        com.onpath.app.ui.progress.SuggestWaitWindow(com.onpath.app.data.progressMemoryOf(androidx.compose.ui.platform.LocalContext.current),
            vm.suggest)
        com.onpath.app.ui.search.SearchHost(vm) // full-screen search view while open
    }
}

/**
 * Plan: the one message box at the top (MessageCard, docs/design/ui-style.md) – or, when no box waits, the quiet pill /
 * the background-download chip under ☰ (states) – + the download details dialog. One box at a time (Messages.pick):
 * questions first (pairing step, map questions, map for here?, new version), then the must-know notes (error, paired,
 * a map failure / note). The rest wait in their state and come next.
 */
/**
 * A place shared from another app (Google Maps) without a coordinate, whose name is found only elsewhere – not in the
 * shared town: no ring (never a namesake in another town), this box with the one way out, Search (the search view with
 * [text] typed in). HINWEIS_WARN, stays until tapped.
 */
@Composable
internal fun sharedUnsureMessage(text: String, onSearch: () -> Unit): Message =
    Message(MessageKind.HINWEIS_WARN, str(Res.string.msg_shared_place_unsure), str(Res.string.msg_shared_place_unsure_detail, text),
        listOf(MessageButton(str(Res.string.msg_shared_place_search), onSearch)), tone = ButtonTone.EDIT) // searching a place = planning

/**
 * The plan run reached its time limit (owner 2026-11-03, onpath.private#21 B, [com.onpath.app.routing.SlowPlan]): "Die
 * Route ist lang, die Berechnung dauert. Weiter warten?" – Abbrechen · **Weiter warten** (the filled primary). FRAGE,
 * stays until answered.
 */
@Composable
internal fun slowPlanMessage(onAnswer: (com.onpath.app.routing.SlowPlan.Answer) -> Unit): Message =
    Message(MessageKind.FRAGE, str(Res.string.plan_slow_title), str(Res.string.plan_slow_text),
        buttons = listOf(
            MessageButton(str(Res.string.common_cancel)) { onAnswer(com.onpath.app.routing.SlowPlan.Answer.CANCEL) },
            MessageButton(str(Res.string.plan_slow_wait)) { onAnswer(com.onpath.app.routing.SlowPlan.Answer.KEEP_WAITING) },
        ), tone = ButtonTone.EDIT) // waiting for a plan to compute = planning

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlanMessageHost(vm: AppViewModel, rec: RecState?,
                                                                        onRequestLocation: () -> Unit,
                                                                        pairCamera: (String) -> Unit) {
    // Cell states tick with every downloaded chunk: read only through derived values, so the box recomposes when the
    // card / modal really changes, not per tick (else the sheet moves janky while areas download – CellStateReads.kt).
    val cellCardTarget by rememberDerived(vm.cells.states, vm.cells.installed) { s, i -> vm.mapData.cellCardUi(s, i) }
    // no flash (owner rule): fast steps ("Preparing…" for a moment before "Downloading") are skipped,
    // whatever shows stays readable; a tap answers at once
    val cellGate = remember { NoFlash<CellCardUi>(stepKey = CellCards::stepKey, urgent = CellCards::waitsForUser) }
    val cellCard = rememberNoFlash(cellCardTarget, cellGate)
    fun acted(f: () -> Unit): () -> Unit = { cellGate.userActed(android.os.SystemClock.uptimeMillis()); f() }
    val cellCb = CellCardCallbacks(onMobileYes = acted(vm.mapData::cellCardMobileYes), onWifiOnly = acted(vm.mapData::cellCardWifiOnly),
        onRetry = acted(vm.mapData::cellCardRetry), onClose = acted(vm.mapData::closeCellCard), onCancel = acted(vm.mapData::cancelCellLoading),
        onAllowLocation = acted(onRequestLocation))
    // a map answer / problem (mobile data, failed, offline …) – no second map card in the plan sheet
    val notice = mapNotice(cellCard)
    val bg = rememberBackgroundDownload(vm)
    val here = vm.mapData.hereAsk
    val syncNote = vm.syncNote
    val upd = vm.updates.update as? AppViewModel.UpdateState.Available
    val mapBox = notice?.let { mapNoticeMessage(it, cellCb) }
    val message = if (vm.searchFlow.searchOpen) null else Messages.pick(listOf(
        // the plan run reached its time limit (owner 2026-11-03): "Weiter warten?" – stays until answered
        vm.planRun.slowPlanAsk?.let { slowPlanMessage(onAnswer = vm.planRun::answerSlowPlan) },
        // ☰ → Pair with browser, the first time (owner 2026-10-30): the one step, then the camera
        if (vm.pairing.pairHint) pairHintMessage(onCamera = { vm.pairing.pairHintCamera(pairCamera) }, onCancel = vm.pairing::pairHintCancel) else null,
        mapBox?.takeIf { it.kind == MessageKind.FRAGE },
        // a multi-day tour opened the first time (§7): its maps, asked once with the sizes
        vm.multiDay.tourMapsAsk?.let { tourMapsMessage(it, vm.multiDay::answerTourMaps) },
        // opening the app asks for the map of here, it never loads by itself, e.g. in a car (owner)
        here?.let { hereAskMessage(it.bytes, onLoad = { vm.mapData.answerHereAsk(true) }, onNotNow = { vm.mapData.answerHereAsk(false) }, mustBuild = it.mustBuild) },
        upd?.release?.version?.takeIf { rec == null && it != vm.updates.updateDismissed }?.let { v ->
            updateMessage(v, onInstall = { vm.updates.install() }, onLater = { vm.updates.updateDismissed = v })
        },
        vm.notices.errorCard?.let { errorMessage(it) { vm.notices.errorCard = null } },
        // a shared place with only namesakes elsewhere: no ring, the search with its name + town (field bug 0.13.2)
        vm.searchFlow.sharedUnsure?.let { sharedUnsureMessage(it, onSearch = vm.searchFlow::searchSharedUnsure) },
        // a pairing was just made or ended (owner 2026-10-30): "Paired. …" / "Unpaired. …" with OK, until OK
        syncNote?.let { syncNoteMessage(it, onOk = vm.pairing::syncNoteOk) },
        mapBox?.takeIf { it.kind != MessageKind.FRAGE },
    ))
    // states (no box) only while no box shows: the quiet pill, else plain progress as a small chip under ☰ – neither
    // while the first-map card says it on the map (owner 2026-11-08)
    val firstCard by rememberDerived(vm.cells.states, vm.cells.installed) { s, i -> vm.firstMapCard(s, i) }
    val pill = notice?.takeIf { message == null && !vm.searchFlow.searchOpen && mapNoticeKind(it) == null && firstCard == null }
    val chipOn = bg != null && !vm.searchFlow.searchOpen && message == null && notice == null && firstCard == null
    LaunchedEffect(chipOn, message != null, pill != null) { vm.mapData.backgroundStripShown = chipOn || message != null || pill != null }
    val density = LocalDensity.current
    // the bottom below the status bar: top + measured height (hints under the search bar go below it)
    fun Modifier.measured(topDp: Int) =
        onSizeChanged { vm.mapData.backgroundStripBottomDp = topDp + with(density) { it.height.toDp().value.toInt() } }
    val top = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
    MessageSlot(message, top, Modifier.modeEdge(ModeEdge.TOP), onBottomDp = { vm.mapData.backgroundStripBottomDp = it })
    if (pill != null) {
        MapNoticePill(pill, Modifier.align(Alignment.TopCenter).modeEdge(ModeEdge.TOP).statusBarsPadding()
            .padding(top = top.dp, start = 16.dp, end = 16.dp).measured(top))
    } else if (chipOn) {
        BackgroundDownloadChip(bg!!, onClick = { vm.mapData.downloadDetailsOpen = true },
            modifier = Modifier.align(Alignment.TopEnd).modeEdge(ModeEdge.TOP).statusBarsPadding()
                .padding(top = MessagePlacement.CHIP_TOP_DP.dp, end = 12.dp).measured(MessagePlacement.CHIP_TOP_DP))
    }
    BackgroundDownloadDetails(vm, bg)
}

/** The plan-blue "Weiter geht's" (owner 2026-11-06, C; blue since 2026-11-12): back to Navigate; a paused recording goes on (as "Weiter" did). */
private fun backToNavigation(vm: AppViewModel, r: RecState) {
    if (r.paused) vm.tourEnd.pauseRecording(false)
    vm.tourSession.continueNavigation()
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlanBottom(vm: AppViewModel, rec: RecState?, onStartNavigation: () -> Unit, onActivity: () -> Unit, onEndTour: () -> Unit,
                       onRequestLocation: () -> Unit = {}) {
    val shown = vm.routesFlow.shown
    if (shown != null) {
        TrackPanel(
            shown, isFollowed = vm.tourFollow.followName == shown.name, recording = rec != null, activity = vm.planRoute.activity,
            onFollow = {
                if (rec == null) onStartNavigation()
                else vm.followShownDuringTour()
            },
            onClose = vm.routesFlow::closeShown, onHighlight = { vm.highlight = it },
            onPlan = vm.routesFlow::planFromShown, planBusy = vm.routesFlow.planFromTrackBusy,
            // during a tour (owner 2026-11-06, F1 + C): the way back to Navigate under the track's two buttons
            backToNavigation = rec?.let { r -> { BackToNavigationButton({ backToNavigation(vm, r) }) } },
        )
        return
    }
    // the user waits for the map of their area (a point / search without detailed map): one plain modal with %,
    // time left and Cancel; ticks every second so the bar moves smoothly. Never over the phone setup (MapLoadWait).
    val loadUi by produceState<MapLoadUi?>(null, vm.mapData.cellJob, vm.tourSession.mode) {
        vm.cells.states.collectLatest { states ->
            while (true) { value = vm.mapData.mapLoadUi(states, android.os.SystemClock.uptimeMillis()); delay(1000) }
        }
    }
    // Rows of the Points section, composed with the sheet in every step (StepSheet: it is only revealed). The distances
    // along the route walk the whole line: done once per route off the main thread (PlanPoints.legsCached); until then
    // the previous rows stay.
    val pts = vm.plan.points.toList()
    val line = vm.route?.takeIf { !vm.planRun.routing }?.points
    var legsReady by remember(pts, line) { mutableStateOf(com.onpath.app.routing.PlanPoints.legsReady(pts, line)) }
    LaunchedEffect(pts, line) {
        if (!legsReady) {
            withContext(Dispatchers.Default) { com.onpath.app.routing.PlanPoints.legsCached(pts, line) }
            legsReady = true
        }
    }
    val lastRows = remember { arrayOfNulls<List<com.onpath.app.routing.PlanPointRow>>(1) }
    val rows = lastRows[0]?.takeIf { !legsReady } ?: vm.planPointRows().also { lastRows[0] = it }
    MapLoadModal(MapLoadWait.shown(loadUi, vm.setup.setupOpen), MapLoadCallbacks(onCancel = vm.mapData::cancelLoadModal, onRetry = vm.mapData::cellCardRetry, onClose = vm.mapData::hideLoadModal,
        onLoadOnMobile = vm.mapData::loadModalOnMobile))
    vm.mapData.loadAsk?.let { LoadAskDialog(it.regions, it.bytes, onLoad = { vm.mapData.answerLoadAsk(true) }, onNotNow = { vm.mapData.answerLoadAsk(false) }) }
    // a route that takes a while: the wait window (form A, owner 2026-11-19) – the legs as steps, Cancel; not over a question
    com.onpath.app.ui.progress.RouteWaitWindow(com.onpath.app.data.progressMemoryOf(androidx.compose.ui.platform.LocalContext.current),
        vm.planRun.routing, slow = vm.planRun.routingSlow && vm.planRun.slowPlanAsk == null && vm.planRun.longRouteAsk == null, vm.planRun.routeProgress, vm.planRun.routeShare,
        com.onpath.app.ui.progress.RouteWait.legLabels(rows, vm.plan.points.size, vm.planRun.routeProgress?.second), vm.planRun.routeRun.gen,
        onCancel = vm.planRun::cancelRouting)
    // the "hold to set a point" line goes with any plan change (owner 2026-11-03)
    LaunchedEffect(vm.plan.points.toList()) { vm.tapHoldHint = false }
    PlanPanel(
        PlanUi(vm.plan.points.size, vm.route, vm.planRun.routing, vm.routeError, vm.plan.canUndo, vm.plan.canRedo, vm.planRoute.activity, vm.planEdit.planSize,
            sac = vm.planRoute.sac, tourRunning = rec != null, savedName = vm.plan.savedName, unsaved = vm.plan.unsaved,
            roundTrip = vm.isRoundTrip(), canRoundTrip = vm.planEdit.canRoundTrip(), canReverse = vm.planEdit.canReverse(),
            tour = rec?.let { TourState(it.distanceM, it.elapsedS, it.paused, vm.tourFollow.guidanceEndedByEmptyPlan) },
            points = rows,
            notRouted = vm.planRun.notRouted, gapText = vm.planGapText(), warnText = vm.planRoute.warningLineText(), warnCount = vm.planRoute.warningCount(),
            changedText = vm.planRoute.routeChangedLineText(), syncRoutingText = vm.planRoute.syncRoutingNote, tapHint = vm.tapHoldHint,
            offlineInfo = vm.offlineReady?.takeIf { vm.offlineInfo }?.let { OfflineReady.explain(it) },
            days = vm.multiDay.daysUi(), dayHead = vm.multiDay.dayHeadUi()),
        PlanCallbacks(
            onSheetDrag = { if (it) vm.tapHoldHint = false; vm.mapGesture(it) }, onUndo = vm.planEdit::undoStep, onRedo = vm.planEdit::redoStep, onClear = vm.planEdit::clearPlan,
            onStartNavigation = onStartNavigation, onContinue = vm.tourSession::continueNavigation,
            onReverse = vm.planEdit::reverseRoute, onRoundTrip = vm.planEdit::roundTrip,
            onActivity = onActivity, onTimeDetails = vm.planRoute::openTimeDetails, onDetails = { vm.planEdit.routeDetailsOpen = true },
            onHighlight = { vm.highlight = it },
            onPlanAnyway = vm.planRun::planAnyway,
            onShowGap = vm::showPlanGap,
            onWarnings = vm.planRoute::tapWarningLine,
            onRouteChanged = vm.planRoute::tapRouteChangedLine,
            onResize = { vm.planEdit.planSize = it },
            onPause = vm.tourEnd::pauseRecording, onEndTour = onEndTour,
            onMovePoint = vm.planEdit::movePointInList, onDeletePoint = vm.planEdit::deletePointFromList, onShowPoint = vm::showPointFromList,
            onSearch = vm.searchFlow::openSearch, onMyRoutes = vm.routesGo::openRoutes,
            onDayTap = vm.multiDay::tapDay, onDayHold = vm.multiDay::holdDay, onRideDays = vm.multiDay::rideDays, onAllDays = vm.multiDay::allDays,
            onLoadDayMaps = vm.multiDay::loadDayMaps,
        ),
    )
    if (vm.planRoute.spotsOpen) {
        // the spots of the amber line: tap one → the map shows it
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = vm.planRoute::closeSpots) {
            RouteSpotsList(vm.planRoute.routeSpots, vm.planRoute.sac, onShow = vm::showSpot)
        }
    }
    // names for the Points section (offline place index, off the main thread) whenever the points change – ready before
    // the sheet is pulled up (names arriving mid-pull would rebuild the list)
    LaunchedEffect(vm.plan.points.toList()) { vm.planEdit.refreshPointNames(); vm.placeMenus.refreshPinnedKinds() }
}

/** Keeps the map layers in step with the app state. */
@Composable
private fun MapSync(vm: AppViewModel, c: MapController?, rec: RecState?, fix: android.location.Location?, fitPadding: () -> FitPadding) {
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
    // the plan points behind the user in a running tour (TourSplit): grey (no tick, owner 2026-11-01); re-read every ~20 track points
    var passed by remember { mutableStateOf(emptySet<Int>()) }
    val trackSize = rec?.track?.sumOf { it.size } ?: 0
    LaunchedEffect(vm.plan.points.toList(), trackSize / 20, vm.tourFollow.followLine, rec != null) { passed = vm.tourEdits.tourPassedOnMap() }
    // Planned line only while the plan can be edited (Plan mode, no track shown); markers only in Plan (planLayers).
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(c) {
        // a new route draws itself (owner 2026-11-06, polish 2 – RouteReveal); the first drawing after the map (re)loaded
        // and the same route shown again (back from Navigate, a marker change) appear at once
        var firstDraw = true
        var lastShown: com.onpath.app.routing.PlannedRoute? = null
        // canEditMap is part of the key on its own: Navigate → Plan while the plan is followed must redraw the markers
        snapshotFlow {
            val (line, markers) = planLayers(vm.canEditMap, navigateWithPlan = vm.tourSession.mode == AppMode.NAVIGATE && vm.tourFollow.planGuides)
            // a point being dragged is drawn under the finger; the line follows when it is let go
            val pts = vm.plan.points.toList().let { w ->
                vm.planEdit.dragPreview?.let { (i, p) -> com.onpath.app.routing.PlanPoints.relocate(w, i, p) } ?: w
            }
            PlanDrawing(vm.route, line, markers, pts, vm.planGaps, passed, vm.placeMenus.pinnedPoiKinds())
        }
            .collect { d ->
                val r = if (d.showLine) d.route else null
                val animate = r != null && com.onpath.app.map.RouteReveal.shouldAnimate(firstDraw, r === lastShown,
                    vm.tourSession.mode == AppMode.PLAN, r.points.size, android.animation.ValueAnimator.areAnimatorsEnabled())
                firstDraw = false
                if (r != null) lastShown = r
                if (animate) c.revealPlanned(r!!) {
                    // a short tick when the line has arrived (the phone's touch-feedback setting applies)
                    view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30)
                        android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                } else c.setPlanned(r)
                // unreachable points: grey dashed missing piece + grey hollow marker
                val gaps = if (d.showLine && d.showMarkers) d.gaps else emptyList()
                c.setPlanGaps(gaps)
                c.setWaypoints(if (d.showMarkers) d.points else emptyList(), vm.plan.pinnedIndices(), gaps.map { it.index }.toSet(),
                    if (d.showMarkers) d.passed else emptySet(), d.poiKinds)
            }
    }
    // Navigate draws the followed line, never a track only shown (field bug 2026-11-07: a 0 m done tour opened during the
    // tour left Navigate with the plan's points and no route – gpxLine)
    val gpx = gpxLine(vm.tourSession.mode == AppMode.NAVIGATE, vm.routesFlow.shown?.segments, vm.tourFollow.followLine)
    // the chosen round-trip suggestion's loop – only that one (ui/suggest)
    val loop = vm.suggest.loop
    LaunchedEffect(c, loop) { c.setTrack(MapController.SRC_SUGGEST, listOfNotNull(loop)) }
    LaunchedEffect(c, gpx) {
        c.setTrack(MapController.SRC_GPX, gpx.lines)
        val rides = if (gpx.track) vm.routesFlow.shown?.rides.orEmpty() else emptyList()
        c.setTrack(MapController.SRC_RIDES, rides.filter { it.second == com.onpath.app.rides.RideKind.VEHICLE }.map { it.first })
        c.setTrack(MapController.SRC_LIFTS, rides.filter { it.second == com.onpath.app.rides.RideKind.LIFT }.map { it.first })
    }
    // The followed route is blue – also while the plan is edited during a tour (owner 2026-11-01: no grey route while
    // planning); a track that is only shown keeps its own colour.
    LaunchedEffect(c) {
        snapshotFlow { vm.trackLook() }.collect { c.setTrackLook(it) }
    }
    // the route changed during a tour: the position is the activity's sign (the guidance rests) – the mode visible at
    // the position too (owner 2026-11-01)
    val planPuck = rememberPlanPuck(vm.planRoute.activity)
    val changingRoute = vm.tourSession.mode == AppMode.PLAN && rec != null
    LaunchedEffect(c, changingRoute, planPuck) { c.setPlanPuck(planPuck.takeIf { changingRoute }) }
    // the GPS accuracy circle only in Navigate (owner 2026-11-06: in Plan it only gets in the way)
    LaunchedEffect(c, vm.tourSession.mode) { c.setNavigateMode(vm.tourSession.mode == AppMode.NAVIGATE) }
    LaunchedEffect(c, vm.settingsSlice.settings) { c.layers.apply(vm.settingsSlice.settings) }
    // Re-routing: thin grey original while a new route is followed, dashed proposal while asking, dashed guide to
    // the route when no way was found / the original was kept (see NavigateScreen, navigation/Navigator).
    val nav = rec?.nav
    // rest view while cycling (NavigateMode): the covered map gets no fixes, no direction and no track updates, so it
    // draws no frames; the camera mode is kept, everything is sent again the moment the map shows
    val rest = vm.screenRest
    val location = fix.takeUnless { rest }
    LaunchedEffect(c, rest) { c.setQuiet(rest) }
    // a re-route's thin grey original – in Navigate only; Plan has no grey lines (owner 2026-11-01)
    val pale = paleLines(vm.tourSession.mode == AppMode.PLAN, nav?.original)
    LaunchedEffect(c, pale) { c.setNavOriginals(pale) }
    // Heading-up on the route: the stretch ahead points straight up (HeadingFilter). Only in Navigate, matched to
    // the line, not off route; otherwise null = compass / GPS bearing.
    val navLine = remember(nav?.line) { nav?.line?.let { com.onpath.app.navigation.NavLine(it) } }
    val routeAhead = if (vm.tourSession.mode == AppMode.NAVIGATE && nav != null && nav.prompt == null &&
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
    // no service after a reboot: the interrupted tour's track behind its question (Continue · Delete · Save)
    val recTrack = com.onpath.app.domain.tour.TourEndFlow.trackShown(rec?.track, vm.tour.unfinished != null, vm.tour.unfinishedTrack)
    LaunchedEffect(c, recTrack.sumOf { it.size }.takeUnless { rest }) { if (!rest) c.setTrack(MapController.SRC_REC, recTrack) }
    LaunchedEffect(c, vm.highlight) { c.setHighlight(vm.highlight) }
    // a multi-day tour (stage 4): its day ends on the line and the picked day(s), in Plan
    LaunchedEffect(c) { snapshotFlow { vm.multiDay.dayEndsOnMap() }.distinctUntilChanged().collect { (ends, day) -> c.setDayEnds(ends, day) } }
    // the spot tapped in the route warnings list: its piece of route amber (Plan only)
    LaunchedEffect(c, vm.planRoute.spotLine, vm.tourSession.mode) { c.setSpotLine(vm.planRoute.spotLine?.takeIf { vm.tourSession.mode == AppMode.PLAN }) }
    // the found place's pin – Plan only: hidden in Navigate, back in Plan (owner 2026-11-02); its chip: MapOverlayHost
    LaunchedEffect(c, vm.searchFlow.searchMark, vm.tourSession.mode) {
        val m = vm.searchFlow.searchMark?.takeIf { vm.tourSession.mode == AppMode.PLAN }
        c.setSearchMark(m?.let { com.onpath.app.routing.GeoPoint(it.lat, it.lon) }, m?.title())
    }
    // the candidate's dashed preview line to where it would join the route (Plan, with a route; AppViewModel.candidateJoin)
    LaunchedEffect(c, passed, trackSize / 20) {
        snapshotFlow { vm.placeAdd.candidateJoin() }.distinctUntilChanged().collect { c.setCandidateJoin(it?.first, it?.second) }
    }
    // a route point being dragged: dashed lines from its neighbours to the finger (owner 2026-11-14)
    LaunchedEffect(c) {
        snapshotFlow {
            vm.planEdit.dragPreview?.let { (i, p) -> com.onpath.app.routing.PlanPoints.dragNeighbours(vm.plan.points.toList(), i).map { it to p } }
                ?: emptyList()
        }.distinctUntilChanged().collect { c.setDragPreview(it) }
    }
    // Google Maps place → go there (owner): the ring's menu opens by itself once the camera has arrived there (and the
    // map-download modal for it is gone) – checked on every camera stop while it waits (SharedPlaceMenu)
    LaunchedEffect(c, vm.searchFlow.sharedMenuWaiting, vm.searchFlow.cameraIdleTick, vm.mapData.loadModalOpen) {
        if (!vm.searchFlow.sharedMenuWaiting) return@LaunchedEffect
        delay(SharedPlaceMenu.SETTLE_MS)
        val t = try { c.map.cameraPosition.target } catch (e: Exception) {
            Problems.warn("map", "Camera position unreadable; shared place menu waits", e); null
        }
        vm.searchFlow.openSharedMenuIfArrived(t?.latitude, t?.longitude, c.metersPerPixel(), c.dpToPx(SharedPlaceMenu.ARRIVED_DP).toDouble())
    }
    // Marked route networks always show (owner): the band of the current activity.
    LaunchedEffect(c, vm.planRoute.activity, vm.planRoute.sac) { c.setOverlay(vm.planRoute.activity, true, vm.planRoute.sac) }
    // hiking route band above the T level from Settings: grey dashes (the router forbids those trails)
    LaunchedEffect(c, vm.planRoute.sac) { c.setSacLimit(vm.planRoute.sac) }
    val snapLine = remember(rec?.nav?.line) { rec?.nav?.line?.let { com.onpath.app.navigation.NavLine(it) } }
    LaunchedEffect(c, location) {
        location?.let { loc ->
            // "Position on the route": the dot on the followed line while close to it – the drawn
            // dot only; everything else uses the real fix
            val n = rec?.nav
            val on = com.onpath.app.navigation.SnapToRoute.position(vm.settingsSlice.settings.positionOnRoute && vm.tourSession.mode == AppMode.NAVIGATE,
                snapLine, n?.shownAlongM, n?.offM, n?.prompt)
            c.updateLocation(if (on == null) loc else android.location.Location(loc).apply { latitude = on.lat; longitude = on.lon })
            vm.map.checkFreeLook(loc.latitude, loc.longitude)
        }
    }
    // App start / back to the app in Plan: centre on the position (first fix, else last known), unless the user
    // moved the map (StartCamera; restarted by vm.onAppStart).
    LaunchedEffect(c, vm.map.startCameraGen) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        while (!vm.map.startCameraDone) {
            val loc = vm.tracker.location.value
            val step = StartCamera.decide(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - t0,
                modeKnown = vm.map.startModeKnown,
                plan = vm.tourSession.mode == AppMode.PLAN && vm.recState.value == null && !vm.map.followMe,
                userMoved = vm.map.userMovedMap,
                fixAgeS = loc?.let { com.onpath.app.recording.LocationTracker.ageSeconds(it) },
                hasPlan = vm.plan.points.isNotEmpty(),
            )
            when (step) {
                StartCamera.Step.WAIT -> delay(250)
                StartCamera.Step.SHOW_PLAN -> { vm.map.showPlanWhole(PlanCamera.Moment.APP_START); vm.map.startCameraDone = true }
                StartCamera.Step.CENTRE -> { loc?.let { c.centreOn(it.latitude, it.longitude) }; vm.map.startCameraDone = true }
                StartCamera.Step.GIVE_UP -> vm.map.startCameraDone = true
            }
        }
    }
    // Camera: follow the position, walking direction up or north up (both modes); next-turn arrow (see NavigateScreen).
    // (navStartZoom is no key: it is set with the mode / by locate (with recentreTick) and cleared below – a second run right away would cancel the
    // zoom transition just started; field A-019)
    LaunchedEffect(c, vm.map.followMe, vm.tourSession.mode, vm.settingsSlice.settings.navHeadingUp, vm.map.recentreTick) {
        val startZoom = vm.tourSession.navStartZoom.takeIf { vm.tourSession.mode == AppMode.NAVIGATE && vm.map.followMe }
        val zoomed = c.setCamera(vm.map.followMe, vm.tourSession.mode == AppMode.NAVIGATE, vm.settingsSlice.settings.navHeadingUp, startZoom) { vm.map.onMapGesture() }
        if (startZoom != null && zoomed) vm.tourSession.navStartZoom = null
    }
    LaunchedEffect(c, vm.map.northTick) { if (vm.map.northTick > 0) c.northUp() }
    LaunchedEffect(c, vm.navTurn) { c.setTurnArrow(vm.navTurn) }
    LaunchedEffect(c, vm.events.size) {
        val pending = vm.events.toList()
        vm.events.clear()
        pending.forEach { e ->
            when (e) {
                is UiEvent.Fit -> c.fitTo(e.points, fitPadding())
                is UiEvent.CenterOn -> c.flyTo(e.point, e.zoom, fitPadding())
                is UiEvent.PanTo -> c.panTo(e.point)
                is UiEvent.Share -> runCatching { context.startActivity(e.intent) }
                    .onFailure { Problems.warn("ui", "Share screen could not be opened (no app?)", it) }
            }
        }
    }
}

/**
 * What the camera changes on every frame of a pan, zoom or follow (MapHost onCamera). Read only where it is shown – the
 * scale bar, the compass, the menus that follow the map, the "where I face" arrow, the off-route arrow – or through
 * derived / flow readers, never in the map screen's own body: else every camera frame recomposed the whole screen and
 * the plan sheet or the Navigate cards with it (owner's Pixel 8, 2026-11-08: "die Karte wackelt").
 */
@androidx.compose.runtime.Stable
internal class MapCameraState {
    var scale by mutableStateOf<Pair<String, Dp>?>(null)
    /** Changes on every camera move: overlays anchored to a map point re-read the projection. */
    var tick by mutableIntStateOf(0)
    var bearing by mutableFloatStateOf(0f)
    /** Visible map box (south, west, north, east; rounded ≈ 100 m) + 1 at z ≥ 10 / 0 below – "Overview only" hint, modal rule. */
    var hintKey by mutableStateOf<List<Double>?>(null)
}

/** Scale bar ([ScaleRule]): the projection gives metres per dp – never multiplied by the density again. */
private fun computeScale(map: org.maplibre.android.maps.MapLibreMap): Pair<String, Dp>? = try {
    val lat = map.cameraPosition.target?.latitude ?: 0.0
    ScaleRule.of(map.projection.getMetersPerPixelAtLatitude(lat))?.let { (label, widthDp) -> label to widthDp.dp }
} catch (e: Exception) {
    Problems.warn("map", "Scale bar could not be computed; hidden", e)
    null
}

/** Farther from the followed line than this, heading-up uses the compass / GPS bearing instead of the route. */
private const val ROUTE_UP_MAX_OFF_M = 30.0

/**
 * The thin grey line under the followed line: a re-route's original ([navOriginal]) – in Navigate only. In Plan nothing
 * is grey (owner 2026-11-01: "das Planen mit der grauen Route klappt überhaupt nicht – wir lassen die blaue Route").
 */
internal fun paleLines(plan: Boolean, navOriginal: List<com.onpath.app.routing.GeoPoint>?): List<List<com.onpath.app.routing.GeoPoint>> =
    if (plan) emptyList() else listOfNotNull(navOriginal)

/** What the [MapController.SRC_GPX] line draws ([gpxLine]); [track] = a shown track (its rides go with it). */
internal data class GpxLine(
    val lines: List<List<com.onpath.app.routing.GeoPoint>>,
    val look: MapController.TrackLook,
    val track: Boolean,
)

/**
 * The one map line for a followed route or a shown track. A shown track (a done tour opened from My routes, a GPX
 * import) takes the line in Plan, where its sheet is open, and wherever nothing is followed; in Navigate the followed
 * line always stays. Field bug 2026-11-07 (owner's S10, "Wir haben Punkte ohne Route"): during the tour a 0 m done tour
 * opened from My routes (no line to follow – only shown) replaced the followed line; back in Navigate the plan's points
 * showed and no route, while the guidance ran on along it.
 */
internal fun gpxLine(navigate: Boolean, shown: List<List<com.onpath.app.routing.GeoPoint>>?,
                     follow: List<com.onpath.app.routing.GeoPoint>?): GpxLine =
    if (shown != null && !(navigate && follow != null)) GpxLine(shown, MapController.TrackLook.TRACK, true)
    else GpxLine(listOfNotNull(follow), MapController.TrackLook.FOLLOW, false)

/**
 * Which parts of the plan the map draws: (planned line, numbered point markers) – both whenever the plan can be edited
 * (Plan mode, no track shown), also during a tour: the plan's route is blue as without a tour, never hidden, so the blue
 * line can never vanish (owner 2026-11-01; before, the line was hidden while "followed" and only a pale copy showed it –
 * fix-tour-edit-line.md cause 2). Navigate shows the followed line and – while the tour follows the plan
 * ([navigateWithPlan]) – the plan's numbered points, passed ones grey (owner 2026-11-01: "sonst weiß man gar nicht,
 * wo man einen Punkt gemacht hat"); not tappable there (MapHost edits only when canEditMap).
 */
internal fun planLayers(canEditMap: Boolean, navigateWithPlan: Boolean = false): Pair<Boolean, Boolean> =
    canEditMap to (canEditMap || navigateWithPlan)

/** What the plan drawing on the map depends on (MapSync). */
private data class PlanDrawing(
    val route: com.onpath.app.routing.PlannedRoute?,
    val showLine: Boolean,
    val showMarkers: Boolean,
    val points: List<com.onpath.app.routing.GeoPoint>,
    val gaps: List<PlanGap> = emptyList(),
    val passed: Set<Int> = emptySet(),
    /** Pinned points on a POI: their kind by index (the POI symbol with a blue ring, owner 2026-11-10). */
    val poiKinds: Map<Int, String> = emptyMap(),
)
