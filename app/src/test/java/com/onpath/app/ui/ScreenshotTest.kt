package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.data.DataFiles
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Share
import com.onpath.app.data.AppSettings
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/** JVM screenshots of the non-map UI at 390 dp width (map replaced by a placeholder). */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
        ),
        showSystemUi = false,
    )

    private val routePts = (0..120).map { i ->
        GeoPoint(46.67 + i * 0.0004, 11.16 + i * 0.0003, 350.0 + 600 * sin(i / 38.0) + (i % 7))
    }
    private val route = PlannedRoute(listOf(PlannedLeg(routePts, false)), 12_480.0, 842.0, 310.0, 4.6 * 3600, null,
        wayTypes = listOf(Share("path", 6100.0), Share("track", 3300.0), Share("quiet_road", 2200.0), Share("road", 700.0), Share("steps", 180.0)),
        surfaces = listOf(Share("natural_trail", 5200.0), Share("gravel", 3900.0), Share("asphalt", 3000.0), Share("unknown", 380.0)))
    private fun planUi(n: Int = 3, r: PlannedRoute? = route, routing: Boolean = false, error: String? = null,
                       size: PlanSize = PlanSize.COMPACT, tourRunning: Boolean = false) =
        PlanUi(n, r, routing, error, canUndo = n > 0, canRedo = false, activity = Activity.HIKE, size = size, tourRunning = tourRunning)

    @Composable
    private fun Footer(gps: GpsStatus = GpsStatus.GOOD, following: Boolean = false, compass: CompassSpec? = null,
                       eye: Boolean = false, peeking: Boolean = false) =
        MapFooter("500 m" to 64.dp) {
            MapControls(compass, showEye = eye, onPeek = {}, gps = gps, following = following, onLocate = {}, peeking = peeking)
        }

    /** Plan mode: search bar + ☰ menu, footer, then [bottom]. Top padding stands in for the status bar. */
    @Composable
    private fun Frame(gps: GpsStatus = GpsStatus.GOOD, aboveFooter: @Composable () -> Unit = {}, bottom: @Composable () -> Unit) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        aboveFooter()
                        Footer(gps, compass = CompassSpec(null, 0f) {}) // Plan: compass always shown
                        bottom()
                    }
                }
            }
        }
    }

    @Composable
    private fun NavFrame(ui: NavUi, slide: Float = 0f) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    NavigateOverlay(ui, footer = { Footer(following = true, compass = CompassSpec(ui.headingUp, 0f) {}, eye = true) },
                        onSlideToPlan = {}, slideStartFraction = slide)
                }
            }
        }
    }

    private val navUi = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0, descentM = 310.0, profile = routePts),
        recordedM = 5_320.0, elapsedS = 5025, activity = Activity.HIKE)

    /** Navigate: tapped "to go" card – elevation profile of the rest of the route, ↑ and ↓. */
    @Test fun navigateProfile() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp)) { NavStatusCard(navUi, onResume = {}, profileOpen = true) }
            }
        }
    }

    /** Grouped map controls: compass (map turned), eye held (route hidden), locate with GPS dot. */
    @Test fun mapControlsAll() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter)) {
                    Footer(compass = CompassSpec(null, 35f) {}, eye = true, peeking = true)
                }
            }
        }
    }

    /** Plan, map north up: the compass stays in the group (owner: always visible), no eye without a route. */
    @Test fun mapControlsPlanNorth() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter)) { Footer(compass = CompassSpec(null, 0f) {}) }
            }
        }
    }

    /** App start without a plan: Plan is home; no points, Start navigation = free recording. GPS searching. */
    @Test fun planEmpty() = paparazzi.snapshot {
        Frame(gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
    }

    @Test fun plan() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** "Elevation & surfaces" opened: profile, gradient legend, way type / surface bars. */
    @Test fun planExpanded() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.FULL), PlanCallbacks()) }
    }

    /** Drop-down menus are popups (not drawn by Paparazzi): draw their items where the menu opens. */
    @Composable
    private fun MenuCard(modifier: Modifier, items: @Composable () -> Unit) {
        androidx.compose.material3.Surface(modifier.width(260.dp), shape = androidx.compose.material3.MaterialTheme.shapes.extraSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
            Column(Modifier.padding(vertical = 8.dp)) { items() }
        }
    }

    @Composable
    private fun TopMenu(state: RouteMenuState, bottom: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Frame { bottom() }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp)) { PlanMenuItems(PlanMenu(state, onUpdates = {})) {} } }
        }
    }

    /** ☰ with a route planned: My routes on, Start recording greyed. */
    @Test fun planMenuOpen() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false)) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    /** ☰ on an empty plan: My routes and Start recording on. */
    @Test fun planMenuEmpty() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = false, pointCount = 0, savedCount = 0, navigating = false, recording = false)) {
            PlanPanel(planUi(n = 0, r = null), PlanCallbacks())
        }
    }

    /** ☰ while a tour runs (looked at from Plan): Start recording greyed. */
    @Test fun planMenuNavigating() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = true, recording = true)) {
            TourRunningBar(6_400.0, 5025, paused = false, onEnd = {})
            PlanPanel(planUi(tourRunning = true), PlanCallbacks())
        }
    }

    /** The ⋯ menu of the plan sheet: the rare route actions. */
    @Test fun planSheetMenuOpen() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            Frame { PlanPanel(planUi(), PlanCallbacks()) }
            OnPathTheme { MenuCard(Modifier.align(Alignment.BottomEnd).padding(bottom = 150.dp, end = 12.dp)) { SheetMenuItems(planUi(), PlanCallbacks()) {} } }
        }
    }

    /** Back in Plan while the tour records: status bar (End tour) and "Continue navigation" as the primary button. */
    @Test fun planTourRunning() = paparazzi.snapshot {
        Frame {
            TourRunningBar(6_400.0, 5025, paused = false, onEnd = {})
            PlanPanel(planUi(size = PlanSize.COMPACT, tourRunning = true), PlanCallbacks())
        }
    }

    @Test fun endTourConfirm() = paparazzi.snapshot {
        OnPathTheme {
            Frame {
                TourRunningBar(6_400.0, 5025, paused = false, onEnd = {})
                PlanPanel(planUi(size = PlanSize.COMPACT, tourRunning = true), PlanCallbacks())
            }
            EndTourDialog({}, {})
        }
    }

    /** Navigate: what is left on top, recording chip, slide-to-plan at the bottom. */
    @Test fun navigate() = paparazzi.snapshot { NavFrame(navUi) }

    /** Slider dragged half way (springs back on release). */
    @Test fun navigateSlideHalf() = paparazzi.snapshot { NavFrame(navUi, slide = 0.5f) }

    // --- Re-routing while navigating (owner: ask every time, nothing disappears by itself) ---
    private val hint = com.onpath.app.navigation.RouteHint(120.0, 45.0, GeoPoint(46.68, 11.13))
    private val question = com.onpath.app.navigation.NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0)

    /** Off route confirmed, the way back is being computed (usually < 1 s). */
    @Test fun navigateOffRouteSearching() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.Searching, hint)))
    }

    /** The question: stays until answered or back on the route; big buttons in thumb reach above the map controls. */
    @Test fun navigateRerouteQuestion() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question, hint)))
    }

    /** A shorter way back that starts on a way 300 m away. */
    @Test fun navigateRerouteQuestionShorter() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(extraM = -800.0, rejoinInM = 1_250.0, startOffM = 300.0), hint)))
    }

    /** No way back found: arrow + distance to the route (arrow relative to the map). */
    @Test fun navigateOffRouteNoWay() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.NoWay(com.onpath.app.navigation.Rerouter.Reason.NO_WAY),
            hint, arrowDeg = 45f)))
    }

    /** Outside the routing data (R-12). */
    @Test fun navigateOffRouteNoData() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.NoWay(com.onpath.app.navigation.Rerouter.Reason.NO_DATA),
            hint.copy(distanceM = 640.0, bearingDeg = 250.0), arrowDeg = -110f)))
    }

    /** "Keep original" chosen: arrow banner, asked again only much later. */
    @Test fun navigateOffRouteKept() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.Kept, hint, arrowDeg = 160f)))
    }

    /** Next-turn hint above the remaining strip; heading-up orientation button (navigation arrow). */
    @Test fun navigateTurn() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.RIGHT, 120.0)))
    }

    /** North-up orientation button ("N" needle) and a slight-left hint close by. */
    @Test fun navigateNorthUp() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.SLIGHT_LEFT, 40.0), headingUp = false))
    }

    /** Free recording (no route), paused from the notification. */
    @Test fun navigateFreePaused() = paparazzi.snapshot { NavFrame(NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = true)) }

    // --- Activity while navigating (owner: a wrong activity is a disaster) ---
    private fun fit(a: Activity, problems: List<String>, timeS: Double, extraM: Double = 0.0) =
        com.onpath.app.navigation.ActivityFit(a, 3, problems, 7_400.0 + extraM, timeS, extraM, emptyList())

    private val checkUi = ActivityCheckUi(Activity.HIKE, 3, listOf(
        ActivityRow(Activity.BIKE_TOURING, 3, ActivityRowState.Ready(fit(Activity.BIKE_TOURING,
            listOf("2.3 km not suited for touring bikes", "1.1 km unpaved"), 1.6 * 3600, 3_100.0))),
        ActivityRow(Activity.BIKE_TREKKING, 3, ActivityRowState.Ready(fit(Activity.BIKE_TREKKING,
            listOf("1.4 km not suited for trekking bikes", "steps 40 m"), 1.4 * 3600, 900.0))),
        ActivityRow(Activity.EBIKE, 3, ActivityRowState.Failed("No route found for it")),
        ActivityRow(Activity.MTB, 3, ActivityRowState.Ready(fit(Activity.MTB, emptyList(), 38 * 60.0))),
        ActivityRow(Activity.EMTB, 3, ActivityRowState.Checking),
    ))

    /** Tap the activity icon: what the rest of the route means for each other activity (fits / problems / checking). */
    @Test fun navigateActivitySheet() = paparazzi.snapshot { NavFrame(navUi.copy(activityCheck = checkUi)) }

    /** Opened a moment ago: every row still being checked. */
    @Test fun navigateActivitySheetChecking() = paparazzi.snapshot {
        NavFrame(navUi.copy(activity = Activity.BIKE_TREKKING, activityCheck = ActivityCheckUi(Activity.BIKE_TREKKING, 3,
            Activity.entries.filter { it != Activity.BIKE_TREKKING }.map { ActivityRow(it, 3, ActivityRowState.Checking) },
            note = "Waiting for GPS…")))
    }

    /** MTB chosen: the dashed proposal with the usual question (stays until answered). */
    @Test fun navigateActivitySwitchQuestion() = paparazzi.snapshot {
        val proposed = com.onpath.app.navigation.NavSession.start(com.onpath.app.navigation.NavKind.PLAN, "Route", Activity.MTB, 3, routePts)
        val q = question.copy(extraM = 1_200.0, legM = 8_600.0, startOffM = 10.0,
            switch = com.onpath.app.navigation.ActivitySwitch(Activity.MTB, 3, proposed, 38 * 60.0))
        NavFrame(navUi.copy(offRoute = OffRouteUi(q)))
    }

    /** The plan's primary button names the activity. */
    @Test fun planStartTrekking() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(activity = Activity.BIKE_TREKKING), PlanCallbacks()) }
    }

    @Test fun arrival() = paparazzi.snapshot {
        OnPathTheme {
            NavFrame(navUi.copy(toGo = ToGo(30.0, 0.0, 60.0, 5.0)))
            ArrivalDialog({}, {})
        }
    }

    /** Way types & surfaces opened by the user. */
    @Test fun breakdownExpanded() = paparazzi.snapshot {
        Frame { Column(Modifier.padding(16.dp)) { RouteBreakdown(route.wayTypes, route.surfaces, initiallyExpanded = true) } }
    }

    /** Plan panel without breakdown data (older engine / straight lines): bars are not shown. */
    @Test fun planNoBreakdown() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(wayTypes = emptyList(), surfaces = emptyList())), PlanCallbacks()) }
    }

    @Test fun planError() = paparazzi.snapshot {
        Frame {
            PlanPanel(planUi(2, route.copy(legs = listOf(PlannedLeg(routePts.take(2), true)), wayTypes = emptyList(), surfaces = emptyList()),
                routing = true,
                error = "Leg 1→2: No route found near this point. Shown as a straight dashed line. Try moving the point or changing the activity."),
                PlanCallbacks())
        }
    }

    /** Swiped down twice: one line with the key numbers. */
    @Test fun planMini() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI), PlanCallbacks()) }
    }

    /** Map overlay over the plan screen, anchored at a fake marker / POI dot drawn at [anchorDp]. */
    @Composable
    private fun OverlayFrame(dotColor: androidx.compose.ui.graphics.Color?, anchorDp: androidx.compose.ui.unit.DpOffset, empty: Boolean = false,
                             content: @Composable () -> Unit) {
        Frame { PlanPanel(if (empty) planUi(n = 0, r = null) else planUi(), PlanCallbacks()) }
        val d = androidx.compose.ui.platform.LocalDensity.current
        val a = with(d) { androidx.compose.ui.geometry.Offset(anchorDp.x.toPx(), anchorDp.y.toPx()) }
        if (dotColor != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color.White, 13.dp.toPx(), a)
            drawCircle(dotColor, 11.dp.toPx(), a)
        }
        AnchoredOverlay(a, topPx = with(d) { 160.dp.roundToPx() }, bottomPx = with(d) { 300.dp.roundToPx() }, content = content)
    }

    /** First hold on an empty plan: Start here · Go here from my location (position known). */
    @Test fun firstPointMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(200.dp, 430.dp), empty = true) {
                FirstPointMenu(null, onStartHere = {}, onFromMe = {})
            }
        }
    }

    /** Same without a position: "Go here from my location" disabled with the reason. */
    @Test fun firstPointMenuNoGps() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(200.dp, 430.dp), empty = true) {
                FirstPointMenu("Searching GPS…", onStartHere = {}, onFromMe = {})
            }
        }
    }

    /** Hold on a POI in Plan: its menu "Add to route · Open in Google Maps". */
    @Test fun poiMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Rec, androidx.compose.ui.unit.DpOffset(110.dp, 400.dp)) {
                PoiMenu(com.onpath.app.poi.Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {})
            }
        }
    }

    /** Hold on a POI with an empty plan: same choice as the first-point menu. */
    @Test fun poiMenuEmptyPlan() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Rec, androidx.compose.ui.unit.DpOffset(110.dp, 400.dp)) {
                PoiMenu(com.onpath.app.poi.Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {},
                    emptyPlan = true)
            }
        }
    }

    /** Hold on the start (unnamed): only Delete – it already is the start, it can't be the end of the route. */
    @Test fun pointMenuStart() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Good, androidx.compose.ui.unit.DpOffset(120.dp, 420.dp)) {
                PointMenu(0, 4, null) {}
            }
        }
    }

    /** Hold on a middle point that is a POI (near the right edge): every option. */
    @Test fun pointMenuMiddlePoi() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Route, androidx.compose.ui.unit.DpOffset(340.dp, 470.dp)) {
                PointMenu(1, 4, "Leiter Alm") {}
            }
        }
    }

    /** Destination near the top: the menu goes below it; Make starting point + Delete (no End route here). */
    @Test fun pointMenuDestination() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Bad, androidx.compose.ui.unit.DpOffset(180.dp, 170.dp)) {
                PointMenu(3, 4, null) {}
            }
        }
    }

    @Test fun confirmClear() = paparazzi.snapshot {
        OnPathTheme { Frame { PlanPanel(planUi(), PlanCallbacks()) }; ConfirmClearDialog(3, {}, {}) }
    }

    @Test fun settings() = paparazzi.snapshot {
        OnPathTheme {
            SettingsScreen(
                AppSettings(hiddenPoiKinds = PoiKinds.defaultHidden), {},
                listOf(DataFiles.Check("base.pmtiles", true, "Base map"), DataFiles.Check("pois.geojson", false, "Points of interest")),
                "/storage/emulated/0/Android/data/com.onpath.app/files/onpath", "0.2.0 (build 2)", {},
                initiallyExpanded = setOf(PoiGroup.FOOD),
            )
        }
    }

    /** Whole settings page on a tall canvas (all sections). */
    @Test fun settingsFull() {
        paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 4600, xdpi = 428, ydpi = 428, density = Density.create(420)))
        paparazzi.snapshot {
            OnPathTheme {
                SettingsScreen(
                    AppSettings(hiddenPoiKinds = PoiKinds.defaultHidden, contours = false, offRouteM = 25), {},
                    listOf(DataFiles.Check("base.pmtiles", true, "Base map"), DataFiles.Check("terrain.pmtiles", true, "Relief / elevation"),
                        DataFiles.Check("segments/*.rd5", false, "Routing data")),
                    "/storage/emulated/0/Android/data/com.onpath.app/files/onpath", "0.2.0 (build 2)", {},
                    themes = listOf("classic", "high-contrast"),
                )
            }
        }
    }

    @Test fun track() = paparazzi.snapshot {
        Frame {
            TrackPanel(ShownTrack(1, "Waalweg Algund – Meran", listOf(routePts), emptyList(), 12_480.0, 842.0, 310.0, 4.6 * 3600),
                isFollowed = false, recording = false, {}, {}, {}, {})
        }
    }

    private val viaPts = listOf(com.onpath.app.gpx.GpxWaypoint(46.67, 11.16, null), com.onpath.app.gpx.GpxWaypoint(46.70, 11.20, null))
    private val tourList = listOf(
        Tour(1, TourKind.PLANNED, "Route Meran – Hirzer", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done", viaPts),
        Tour(2, TourKind.RECORDED, "Tour 12 Oct 2026, 09:14", 1_760_200_000_000, "MTB", 3, 32_800.0, 940.0, 940.0, 3.2 * 3600, "done", emptyList()),
        Tour(3, TourKind.IMPORTED, "Komoot – Waalweg Algund", 1_760_400_000_000, "HIKE", 3, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
    )

    /** My routes: one list of planned / recorded / imported, Import GPX, per item ⋮ (Rename, Export GPX, Delete). */
    @Test fun tours() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}) }
    }

    /** Delete asks first and stays until answered. */
    @Test fun routesDeleteConfirm() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialConfirmDelete = tourList[0]) }
    }

    /** Loading over an unsaved plan: Save first / Replace (tap outside = cancel). */
    @Test fun replacePlanAsk() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}); ReplacePlanDialog({}, {}, {}) }
    }

    /** Sheet ⋯ → Save route on a plan loaded from a saved route: Save (overwrite) or Save as new… (tap outside = cancel). */
    @Test fun saveChoice() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi().copy(savedName = "Route Meran – Hirzer", unsaved = true), PlanCallbacks()) }
            SaveChoiceDialog("Route Meran – Hirzer", unsaved = true, {}, {}, {})
        }
    }

    /** My routes → ⋮ → Rename: name dialog prefilled with the current name. */
    @Test fun routesRename() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialRename = tourList[0]) }
    }

    /** After End tour → "Tour saved" → Delete: asks first, stays until answered. */
    @Test fun finishedTourDeleteAsk() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
            FinishedTourDialogs(tourList[1], {}, {}, {}, initialConfirm = true)
        }
    }

    /** The sheet shows the save state instead of a message. */
    @Test fun planSaved() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(savedName = "Route Meran – Hirzer"), PlanCallbacks()) }
    }

    @Test fun missingData() = paparazzi.snapshot {
        OnPathTheme {
            MissingDataScreen(
                listOf(
                    DataFiles.Check("base.pmtiles", true, "Base map"),
                    DataFiles.Check("terrain.pmtiles", false, "Relief / elevation"),
                    DataFiles.Check("routes.geojson", true, "Marked route networks"),
                    DataFiles.Check("trails.geojson", false, "Trail difficulty"),
                    DataFiles.Check("segments/*.rd5", false, "Routing data"),
                ),
                "/storage/emulated/0/Android/data/com.onpath.app/files/onpath",
                {}, {}, {},
            )
        }
    }

    private val release = com.onpath.app.update.AppUpdater.Release(
        "0.4.1", "- Trail names along the paths\n- Update button in Settings", "https://x/onpath.apk", 32_000_000, "https://x")

    @Test fun updateBanner() = paparazzi.snapshot {
        Frame(aboveFooter = { UpdateBanner(AppViewModel.UpdateState.Available(release), {}, {}, Modifier.padding(12.dp)) }) {
            PlanPanel(planUi(n = 0, r = null), PlanCallbacks())
        }
    }

    @Test fun updateSettings() = paparazzi.snapshot {
        Frame { Column(Modifier.padding(16.dp)) { UpdateSectionContent(AppViewModel.UpdateState.Available(release), "0.4.0", {}, {}) } }
    }
}
