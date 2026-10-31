package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
                       eye: Boolean = false, peeking: Boolean = false, large: Boolean = false) =
        MapFooter("500 m" to 64.dp) {
            MapControls(compass, showEye = eye, onPeek = {}, gps = gps, following = following, onLocate = {}, peeking = peeking, large = large, onSettings = if (large) ({}) else null)
        }

    /**
     * Plan mode: search bar + ☰ menu, footer on the sheet's top edge (SheetWithFooter: never above the top bar), then
     * [bottom]. Top padding stands in for the status bar.
     */
    @Composable
    private fun Frame(gps: GpsStatus = GpsStatus.GOOD, aboveFooter: @Composable () -> Unit = {},
                      top: @Composable () -> Unit = {},
                      footer: @Composable () -> Unit = { Footer(gps, compass = CompassSpec(null, 0f) {}) }, // Plan: compass shown
                      menu: PlanMenu = PlanMenu(onUpdates = {}),
                      bottom: @Composable () -> Unit) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, menu, Modifier.fillMaxWidth())
                    SheetWithFooter(TOP_BAR_BOTTOM, footer = {
                        Column(Modifier.fillMaxWidth()) { aboveFooter(); footer() }
                    }, sheet = bottom)
                    Box(Modifier.fillMaxWidth().padding(top = MessagePlacement.STRIP_TOP_DP.dp, start = 16.dp, end = 16.dp)) { top() }
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
                    NavigateOverlay(ui, footer = { Footer(following = true, compass = CompassSpec(ui.headingUp, 0f) {}, eye = true, large = true) },
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

    /** Settings → Diagnostics → Developer tools → time estimate: slope bands, factors, result, formulas. */
    @Test fun timeDetails() = paparazzi.snapshot {
        OnPathTheme {
            TimeDetailsScreen(TimeDetailsUi(
                com.onpath.app.time.TimeBreakdown.of(routePts.mapIndexed { i, p ->
                    p.copy(way = when {
                        i < 20 -> com.onpath.app.routing.WayAttrs("quiet_road", "asphalt")
                        i < 50 -> com.onpath.app.routing.WayAttrs("track", "gravel")
                        i < 90 -> com.onpath.app.routing.WayAttrs("path", "natural_trail", sac = 2)
                        i < 110 -> com.onpath.app.routing.WayAttrs("path", "alpine", sac = 4)
                        else -> com.onpath.app.routing.WayAttrs("path", "natural_trail", sac = 3, visibility = "bad")
                    })
                }, Activity.HIKE, com.onpath.app.time.Factors(0.95, 1.0, 1.1)),
                "Average", 1.0, technical = true), onBack = {})
        }
    }

    /** Route sheet ⋯ → Time details: only plain lines – moving time, with breaks, pace level. */
    @Test fun timeDetailsPlain() = paparazzi.snapshot {
        OnPathTheme {
            TimeDetailsScreen(TimeDetailsUi(com.onpath.app.time.TimeBreakdown.of(routePts, Activity.HIKE,
                com.onpath.app.time.Factors(1.0, 1.0, 1.0)), "Average", 1.0), onBack = {})
        }
    }

    /** App start without a plan: Plan is home; no points, no primary button (free recording = ☰ → Start recording). GPS searching. */
    @Test fun planEmpty() = paparazzi.snapshot {
        Frame(gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
    }

    /** Only a start point (owner 2026-10-30): no line to follow, no "Start navigation" (free recording = ☰ → Start recording). */
    @Test fun planOnePoint() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(n = 1, r = null), PlanCallbacks()) }
    }

    @Test fun plan() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(), PlanCallbacks()) }
    }

    // ------------------------------------------------ Points section: three sheet sizes with points
    private val stops = listOf(routePts[0], routePts[40], routePts[80], routePts[120])
    private val stopNames = listOf("Algund", null, "Leiter Alm", "Hochmuth")
    private fun pointRows(plan: List<GeoPoint> = stops, line: List<GeoPoint>? = routePts) =
        com.onpath.app.routing.PlanPoints.rows(plan, line) { stopNames.getOrNull(it) }
    private fun planWithPoints(size: PlanSize, rows: List<com.onpath.app.routing.PlanPointRow> = pointRows(),
                               r: PlannedRoute? = route, routing: Boolean = false, roundTrip: Boolean = false) =
        planUi(n = rows.size, r = r, routing = routing, size = size).copy(points = rows, roundTrip = roundTrip)

    /** The summary line only (+ the primary button). */
    @Test fun planPointsSmall() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.MINI), PlanCallbacks()) } }

    /** Step 1: summary + the large elevation profile (finger ruler); details via the difficulty badge. */
    @Test fun planPointsHalf() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.COMPACT), PlanCallbacks()) } }

    /** Step 2 = the point editor: summary, profile, then Points (≡ marker name +distance ✕); no way types. */
    @Test fun planPointsFull() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.FULL), PlanCallbacks()) } }

    /**
     * With the points open, all four map controls (compass, eye, undo, locate) do not fit
     * between the top bar and the sheet – they stay below the top bar and fade under the sheet instead of covering ☰.
     */
    @Test fun planPointsFullControlsNeverOverTheTopBar() = paparazzi.snapshot {
        Frame(footer = {
            MapFooter("500 m" to 64.dp) {
                MapControls(CompassSpec(null, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD, following = false,
                    onLocate = {}, onUndo = {})
            }
        }) { PlanPanel(planWithPoints(PlanSize.FULL), PlanCallbacks()) }
    }

    /** What the app shows with the points open: the controls collapse to Undo + locate (MapScreen), below the sheet's edge. */
    @Test fun planPointsFullCollapsedControls() = paparazzi.snapshot {
        Frame(footer = {
            MapFooter("500 m" to 64.dp) {
                MapControls(null, showEye = false, onPeek = {}, gps = GpsStatus.GOOD, following = false, onLocate = {}, onUndo = {}, onRedo = {})
            }
        }) { PlanPanel(planWithPoints(PlanSize.FULL), PlanCallbacks()) }
    }

    /** A map notice (here: no downloads in this build) sits in the blue strip at the top; the points keep their room. */
    @Test fun planPointsFullWithMapCard() = paparazzi.snapshot {
        CardFrame(CellCardUi.NotAvailable, planWithPoints(PlanSize.FULL))
    }

    /**
     * An unreachable point's red line + the amber warnings line + a map card
     * never squeeze the points away in the point editor.
     */
    @Test fun planPointsFullWithGapAndWarnings() = paparazzi.snapshot {
        Frame { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(plan = stops.take(2))).copy(
            gapText = "Last 180 m to “Taser Alm”: no way for Gravel bike",
            warnText = "1.2 km pushing · steps · 2 steep sections"), PlanCallbacks()) }
    }

    /** Full while the route is calculated: the Points list right away, straight-line distances (~). */
    @Test fun planPointsFullRouting() = paparazzi.snapshot {
        Frame { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true), PlanCallbacks()) }
    }

    /** A long calculation shows "Calculating the route… Cancel". */
    @Test fun planRoutingSlowCancel() = paparazzi.snapshot {
        Frame { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true).copy(routingSlow = true), PlanCallbacks()) }
    }

    /** A plan much longer than a day is asked about before it is routed. */
    @Test fun longRouteQuestion() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi(size = PlanSize.COMPACT), PlanCallbacks()) }
            LongRouteDialog(29 * 24 * 3600.0, com.onpath.app.routing.Activity.HIKE, {}, {})
        }
    }

    /** The destination deleted during a tour: verb buttons "Route back" / "Just record". */
    @Test fun routeBackQuestion() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi(size = PlanSize.COMPACT), PlanCallbacks()) }
            RouteBackDialog({}, {})
        }
    }

    /**
     * Field bug 0.9.3: Cancel at the long-route question for a plan restored at start left "Calculating the route…"
     * forever. Now: the points stay, a plain notice says the route is not calculated, with "Plan anyway".
     */
    @Test fun planNotRoutedLong() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(n = 2, r = null).copy(notRouted = NotRouted.LONG), PlanCallbacks()) }
    }

    /** Round trip: start = goal shown once with ↺ and the way back; Round trip greyed out. */
    @Test fun planPointsRoundTrip() = paparazzi.snapshot {
        val loop = stops.take(3) + stops[0]
        Frame { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(loop, null), r = null, roundTrip = true)
            .copy(pointCount = 4), PlanCallbacks()) }
    }

    /** One point only: Start, Reverse / Round trip disabled. */
    @Test fun planPointsOne() = paparazzi.snapshot {
        Frame { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(stops.take(1), null), r = null), PlanCallbacks()) }
    }

    /** Route details (tap the profile or the difficulty badge): summary, large profile, way types / surfaces. */
    @Test fun routeDetails() = paparazzi.snapshot { OnPathTheme { RouteDetailsScreen(route, Activity.HIKE, onBack = {}) } }

    /** A short hike with a T3 section: "Moderate · T3" and why, though 4.2 km / 280 m is Easy. */
    private val t3Route = PlannedRoute(listOf(PlannedLeg(routePts.mapIndexed { i, p ->
        p.copy(way = com.onpath.app.routing.WayAttrs("path", "natural_trail", if (i in 60..64) 3 else 2))
    }, false)), 4_200.0, 280.0, 280.0, 1.6 * 3600, null)

    @Test fun routeDetailsTechnique() = paparazzi.snapshot { OnPathTheme { RouteDetailsScreen(t3Route, Activity.HIKE, onBack = {}) } }

    @Test fun planTechniqueBadge() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.COMPACT, r = t3Route), PlanCallbacks()) } }

    /** Drop-down menus are popups (not drawn by Paparazzi): draw their items where the menu opens. */
    @Composable
    private fun MenuCard(modifier: Modifier, items: @Composable () -> Unit) {
        androidx.compose.material3.Surface(modifier.width(260.dp), shape = androidx.compose.material3.MaterialTheme.shapes.extraSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
            Column(Modifier.padding(vertical = 8.dp)) { items() }
        }
    }

    @Composable
    private fun TopMenu(state: RouteMenuState, phoneSetup: Boolean = false, sync: (() -> SyncMenuUi)? = null,
                        unsaved: Boolean = false, bottom: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            // [sync] is built inside the composition: its texts follow the test's language
            val menu = PlanMenu(state, onUpdates = {}, onPairBrowser = {}, onPhoneSetup = if (phoneSetup) ({}) else null,
                sync = sync?.invoke() ?: SyncMenuUi(), onSaveRoute = if (unsaved) ({}) else null)
            Frame(menu = menu) { bottom() }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp)) {
                PlanMenuItems(menu) {}
            } }
        }
    }

    /** ☰ with a route planned: My routes on, Start recording greyed. */
    @Test fun planMenuOpen() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false)) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    /** ☰ while the plan has unsaved changes (owner 2026-10-30): "Save route" on top, the sheet shows Save beside ⋯. */
    @Test fun planMenuUnsaved() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false), unsaved = true) {
            PlanPanel(planUi().copy(unsaved = true), PlanCallbacks())
        }
    }

    /** ☰ while a tour needs something from the phone (location off …): red "Phone setup" first (owner 2026-10-30). */
    @Test fun planMenuPhoneSetup() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false), phoneSetup = true) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    private val syncNow = 1_761_150_000_000L
    private val paired = com.onpath.app.plansync.PlanSync.State(paired = true, peer = "Chrome on Linux", lastOkMs = syncNow - 60_000,
        sinceMs = syncNow - 86_400_000, checkedMs = syncNow - 60_000)

    /** ☰ paired with a browser (owner 2026-10-30): "Paired with browser ✓" + "Pixel 8 ↔ Chrome on Linux · 1 min ago". */
    @Test fun planMenuSyncPaired() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            sync = { SyncMenuUi.of(paired, "Pixel 8", syncNow) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** ☰ with a sync problem worth a look (mailbox full): amber dot on ☰, the item amber with the reason. */
    @Test fun planMenuSyncProblem() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            sync = { SyncMenuUi.of(paired.copy(problem = com.onpath.app.plansync.PlanSync.Problem.Refused(507, "full")), "Pixel 8", syncNow) }) {
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
            TourRunningBar(6_400.0, 5025, paused = false, onEnd = {}, activity = Activity.BIKE_TREKKING)
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

    /** Back in Plan while the tour records: one sheet – tour status + Continue / Pause / End on top, then the route. */
    @Test fun planTourRunning() = paparazzi.snapshot {
        Frame {
            PlanPanel(planUi(size = PlanSize.COMPACT, tourRunning = true).copy(tour = TourState(6_400.0, 5025, paused = false)),
                PlanCallbacks())
        }
    }

    @Test fun endTourConfirm() = paparazzi.snapshot {
        OnPathTheme {
            Frame {
                TourRunningBar(6_400.0, 5025, paused = false, onEnd = {}, activity = Activity.BIKE_TREKKING)
                PlanPanel(planUi(size = PlanSize.COMPACT, tourRunning = true), PlanCallbacks())
            }
            EndTourDialog({}, {}, {})
        }
    }

    /** Navigate: what is left on top, recording chip, slide-to-plan at the bottom. */
    @Test fun navigate() = paparazzi.snapshot { NavFrame(navUi) }

    /** Navigate without a recording (e.g. an unfinished tour being asked about): the slider says only "Change route". */
    @Test fun navigateNotRecording() = paparazzi.snapshot { NavFrame(navUi.copy(recordedM = null)) }

    /** Slider dragged half way (springs back on release). */
    @Test fun navigateSlideHalf() = paparazzi.snapshot { NavFrame(navUi, slide = 0.5f) }

    // --- Re-routing while navigating (owner: ask every time, nothing disappears by itself) ---
    private val hint = com.onpath.app.navigation.RouteHint(120.0, 45.0, GeoPoint(46.68, 11.13))
    private val question = com.onpath.app.navigation.NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0)

    /** While off route the service says "not on route" (no stale time): the status card keeps only the recording line. */
    private val offUi = navUi.copy(toGo = ToGo(0.0, 0.0, 0.0, 120.0, state = com.onpath.app.time.EstimateState.NOT_ON_ROUTE))

    /** Off route confirmed, the way back is being computed (usually < 1 s): ONE amber card (owner C1). */
    @Test fun navigateOffRouteSearching() = paparazzi.snapshot {
        NavFrame(offUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.Searching, hint, arrowDeg = 45f)))
    }

    /** No fix for 4 min: the amber "No GPS · position 4 min old" line, the time greyed "Time paused". */
    @Test fun navigateNoGps() = paparazzi.snapshot {
        NavFrame(navUi.copy(toGo = navUi.toGo!!.copy(state = com.onpath.app.time.EstimateState.PAUSED,
            pausedBecause = com.onpath.app.time.PauseReason.NO_GPS.text), arriveAt = "16:40", waitingGps = true, fixAgeS = 250))
    }

    /** Tour started indoors: what is left counts from the Wi-Fi fix, the amber line says how rough it is (owner). */
    @Test fun navigateApproximatePosition() = paparazzi.snapshot {
        NavFrame(navUi.copy(recordedM = 0.0, elapsedS = 40, arriveAt = "16:40",
            approx = approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WIFI, 80))))
    }

    /** Notifications off during a tour: the small amber chip under the status card (tap → setup, OK → gone this tour). */
    @Test fun navigateNotificationsOff() = paparazzi.snapshot {
        NavFrame(navUi.copy(arriveAt = "16:40", notificationsOff = true))
    }

    /** Started indoors with notifications off: the approximate-position line and the chip at once, both readable. */
    @Test fun navigateNotificationsOffApproximate() = paparazzi.snapshot {
        NavFrame(navUi.copy(recordedM = 0.0, elapsedS = 40, arriveAt = "16:40", notificationsOff = true,
            approx = approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WIFI, 80)),
            turn = TurnHint(com.onpath.app.routing.TurnKind.LEFT, 180.0)))
    }

    /** The question: stays until answered or back on the route; big buttons in thumb reach above the map controls. */
    @Test fun navigateRerouteQuestion() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question, hint)))
    }

    /** A shorter way back that starts on a way 300 m away. */
    @Test fun navigateRerouteQuestionShorter() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(extraM = -800.0, rejoinInM = 1_250.0, startOffM = 300.0), hint)))
    }

    /** The way back starts by turning round: U-turn sign + "turn round" in the question. */
    @Test fun navigateRerouteQuestionTurnRound() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(turnRound = true), hint)))
    }

    /**
     * The next route point now lies behind (StopSkip, borderline): "Off route – re-plan?" · "Via stop 3: +1.4 km" –
     * Yes / Yes, skip stop 3 + Keep original (owner 2026-10-30).
     */
    @Test fun navigateRerouteSkipStop() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(skipStop = 2, via = question.copy(extraM = question.extraM + 1_400.0)), hint)))
    }

    /** The same for a named stop: "Yes, skip Gasthof Hochmuth" wraps to two lines (at a large font: "stop 3"). */
    @Test fun navigateRerouteSkipStopNamed() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(skipStop = 2, skipName = "Gasthof Hochmuth",
            via = question.copy(extraM = question.extraM + 1_400.0)), hint)))
    }

    /** No way back found: arrow + distance to the route (arrow relative to the map). */
    @Test fun navigateOffRouteNoWay() = paparazzi.snapshot {
        NavFrame(offUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.NoWay(com.onpath.app.navigation.Rerouter.Reason.NO_WAY),
            hint, arrowDeg = 45f)))
    }

    /** Outside the routing data (R-12). */
    @Test fun navigateOffRouteNoData() = paparazzi.snapshot {
        NavFrame(offUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.NoWay(com.onpath.app.navigation.Rerouter.Reason.NO_DATA),
            hint.copy(distanceM = 640.0, bearingDeg = 250.0), arrowDeg = -110f)))
    }

    /** "Keep original" chosen: arrow banner, asked again only much later. */
    @Test fun navigateOffRouteKept() = paparazzi.snapshot {
        NavFrame(offUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.Kept, hint, arrowDeg = 160f)))
    }

    /** Roundabout with its exit: roundabout sign + "Roundabout · exit 2 in 120 m". */
    @Test fun navigateRoundabout() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.RIGHT, 120.0, roundabout = 2, roundaboutAngle = 5f)))
    }

    /** The drawn roundabout for other exits: 1st right, 3rd left. */
    @Test fun navigateRoundaboutExits() = paparazzi.snapshot {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            for ((e, a) in listOf(1 to 80f, 2 to 0f, 3 to -85f, 4 to -170f)) RoundaboutSign(e, a, Modifier.size(64.dp))
        }
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
            listOf("2.3 km not suited for bikes", "1.1 km unpaved"), 1.6 * 3600, 3_100.0))),
        ActivityRow(Activity.BIKE_TREKKING, 3, ActivityRowState.Ready(fit(Activity.BIKE_TREKKING,
            listOf("1.4 km not suited for gravel bikes", "steps 40 m"), 1.4 * 3600, 900.0))),
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

    /**
     * Bike chips show a short name; a long day tour must still show time AND distance in full – with
     * the longest bike name, Gravel bike (its chip says "Gravel" so the distance fits).
     */
    @Test fun planChipLongBikeTour() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(distanceM = 112_480.0, durationS = 12.6 * 3600))
            .copy(activity = Activity.BIKE_TREKKING), PlanCallbacks()) }
    }

    /**
     * The hiking chip names the activity in every language ("Hike · T3" / "Wandern · T3", owner 2026-10-30) – a long
     * day hike must still show time AND distance in full beside it.
     */
    @Test fun planChipLongHike() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(distanceM = 28_740.0, durationS = 10.4 * 3600)).copy(sac = 3), PlanCallbacks()) }
    }

    @Test fun arrival() = paparazzi.snapshot {
        OnPathTheme {
            NavFrame(navUi.copy(toGo = ToGo(30.0, 0.0, 60.0, 5.0)))
            ArrivalDialog({}, {}, {})
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
                error = "Point 1 → 2: no path found"),
                PlanCallbacks())
        }
    }

    /** Fresh install: routing data still loading – a plain notice, never a file path. */
    @Test fun planRoutingDataLoading() = paparazzi.snapshot {
        Frame {
            PlanPanel(planUi(2, route.copy(legs = listOf(PlannedLeg(routePts.take(2), true)), wayTypes = emptyList(), surfaces = emptyList()),
                error = com.onpath.app.data.RoutingDataNotice.LOADING), PlanCallbacks())
        }
    }

    /** Swiped down twice: one line with the key numbers. */
    @Test fun planMini() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI), PlanCallbacks()) }
    }

    /** A point the route does not reach – one red bold line in the small sheet (no message box). */
    @Test fun planGapSmall() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(activity = com.onpath.app.routing.Activity.BIKE_TREKKING,
            gapText = "Last 180 m to “Taser Alm”: no way for Gravel bike"), PlanCallbacks()) }
    }

    /** Route hints: a ferry on the route – amber line first, before the way warnings. */
    @Test fun planFerryHint() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(activity = Activity.BIKE_TOURING,
            warnText = RouteWarnings.text(listOf(RouteSpot(SpotKind.FERRY, 40, 41, 6200.0, 6516.0),
                RouteSpot(SpotKind.FORD, 60, 60, 9100.0, 9100.0)), 3)), PlanCallbacks()) }
    }

    /** The list behind the amber line with the route hints: ferry, construction, ford, stile. */
    @Test fun routeSpotsListHints() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                androidx.compose.material3.Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 1.dp) {
                    Column(Modifier.padding(top = 24.dp)) {
                        RouteSpotsList(listOf(
                            RouteSpot(SpotKind.CONSTRUCTION, 3, 5, 800.0, 840.0),
                            RouteSpot(SpotKind.FERRY, 40, 41, 6200.0, 6516.0),
                            RouteSpot(SpotKind.FORD, 60, 60, 9100.0, 9100.0),
                            RouteSpot(SpotKind.BARRIER, 70, 70, 11_430.0, 11_430.0, RouteWarnings.BARRIERS.indexOf("stile")),
                        ), sac = 2, onShow = {})
                    }
                }
            }
        }
    }

    /** Safety audit batch B: the amber warning line, under the red "not possible" line. */
    @Test fun planWarningsWithGap() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(activity = com.onpath.app.routing.Activity.BIKE_TREKKING,
            gapText = "Last 180 m to “Taser Alm”: no way for Gravel bike",
            warnText = "1.2 km pushing · steps · 2 steep sections"), PlanCallbacks()) }
    }

    /** The grey INFO line after an activity change. */
    @Test fun planRouteChanged() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(activity = com.onpath.app.routing.Activity.BIKE_TREKKING,
            changedText = RouteWarnings.changedText(com.onpath.app.routing.Activity.BIKE_TREKKING, 3)), PlanCallbacks()) }
    }

    /** The list behind the amber line (shown in a bottom sheet over the map). */
    @Test fun routeSpotsList() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                androidx.compose.material3.Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 1.dp) {
                    Column(Modifier.padding(top = 24.dp)) {
                        RouteSpotsList(listOf(
                            RouteSpot(SpotKind.PUSH, 13, 20, 0.0, 350.0),
                            RouteSpot(SpotKind.STEEP, 0, 5, 1200.0, 1350.0, 28),
                            RouteSpot(SpotKind.ABOVE_T, 6, 9, 3420.0, 3640.0, 4),
                            RouteSpot(SpotKind.ABOVE_T, 10, 12, 5100.0, 5180.0, 5),
                            RouteSpot(SpotKind.STEPS, 21, 22, 7020.0, 7060.0),
                        ), sac = 2, onShow = {})
                    }
                }
            }
        }
    }

    /** Map overlay over the plan screen, anchored at a fake marker / POI dot drawn at [anchorDp]. */
    @Composable
    private fun OverlayFrame(dotColor: androidx.compose.ui.graphics.Color?, anchorDp: androidx.compose.ui.unit.DpOffset, empty: Boolean = false,
                             ring: Boolean = false, content: @Composable () -> Unit) {
        Frame { PlanPanel(if (empty) planUi(n = 0, r = null) else planUi(), PlanCallbacks()) }
        val d = androidx.compose.ui.platform.LocalDensity.current
        val a = with(d) { androidx.compose.ui.geometry.Offset(anchorDp.x.toPx(), anchorDp.y.toPx()) }
        if (dotColor != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color.White, 13.dp.toPx(), a)
            drawCircle(dotColor, 11.dp.toPx(), a)
        }
        // the orange ring of a search result / shared place (MapController.setSearchMark: radius 22, stroke 3.5)
        if (ring) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color(0xFFF28C00), 22.dp.toPx(), a,
                style = androidx.compose.ui.graphics.drawscope.Stroke(3.5.dp.toPx()))
        }
        AnchoredOverlay(a, topPx = with(d) { 160.dp.roundToPx() }, bottomPx = with(d) { 300.dp.roundToPx() }, content = content)
    }

    /** First hold on an empty plan: Start here · Go here (from my location) (position known). */
    @Test fun firstPointMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(200.dp, 430.dp), empty = true) {
                FirstPointMenu(null, onStartHere = {}, onFromMe = {})
            }
        }
    }

    /** Same without a position: "Go here" disabled with the reason. */
    @Test fun firstPointMenuNoGps() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(200.dp, 430.dp), empty = true) {
                FirstPointMenu("Searching GPS…", onStartHere = {}, onFromMe = {})
            }
        }
    }

    /** Hold on a POI in Plan: its menu "Add as stop 2 · Open in Google Maps". */
    @Test fun poiMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Rec, androidx.compose.ui.unit.DpOffset(110.dp, 400.dp)) {
                PoiMenu(com.onpath.app.poi.Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {},
                    addLabel = "Add as stop 2")
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

    /** A place shared from Google Maps: once the map is there its ring's menu opens by itself (SharedPlaceMenu). */
    private val shared = com.onpath.app.search.Place("shared:46.6712,11.1598", "Gelateria Costantin", "other", 46.6712, 11.1598)

    /** Shared place, plan with points: "Add as new destination" (AddPreview.label, like a hold on the ring). */
    @Test fun sharedPlaceMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(195.dp, 430.dp), ring = true) {
                PoiMenu(shared.toPoi(), onAdd = {}, onGoogle = {},
                    addLabel = com.onpath.app.search.AddPreview.label(
                        com.onpath.app.routing.WaypointInsertion.Decision(3, com.onpath.app.routing.WaypointInsertion.Kind.DESTINATION), 3))
            }
        }
    }

    /** Shared place, empty plan: "Start here · Go here · Open in Google Maps". */
    @Test fun sharedPlaceMenuEmptyPlan() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(195.dp, 430.dp), empty = true, ring = true) {
                PoiMenu(shared.toPoi(), onAdd = {}, onGoogle = {}, emptyPlan = true)
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



    @Test fun track() = paparazzi.snapshot {
        Frame {
            TrackPanel(ShownTrack(1, "Waalweg Algund – Meran", listOf(routePts), emptyList(), 12_480.0, 842.0, 310.0, 4.6 * 3600),
                isFollowed = false, recording = false, activity = Activity.HIKE, {}, {}, onPlan = {}, onHighlight = {})
        }
    }

    /** A recorded tour ended before its first point (owner 2026-10-30): nothing to follow – "Start recording · Hike". */
    @Test fun trackWithoutLine() = paparazzi.snapshot {
        Frame {
            TrackPanel(ShownTrack(1, "Tour 30 Oct, 09:14", emptyList(), emptyList(), 0.0, 0.0, 0.0, 0.0),
                isFollowed = false, recording = false, activity = Activity.HIKE, {}, {}, onHighlight = {})
        }
    }

    /** A Komoot import switched the activity: one grey INFO line says so. */
    @Test fun trackImportedActivityNote() = paparazzi.snapshot {
        Frame {
            TrackPanel(ShownTrack(1, "Komoot – Hirzer Bergtour", listOf(routePts), emptyList(), 12_480.0, 842.0, 310.0, 4.6 * 3600,
                note = "Activity set to Hike T3 from the file"),
                isFollowed = false, recording = false, activity = Activity.HIKE, {}, {}, {})
        }
    }

    private val viaPts = listOf(com.onpath.app.gpx.GpxWaypoint(46.67, 11.16, null), com.onpath.app.gpx.GpxWaypoint(46.70, 11.20, null))
    private val tourList = listOf(
        Tour(1, TourKind.PLANNED, "Route Meran – Hirzer", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done", viaPts),
        Tour(2, TourKind.RECORDED, "Tour 12 Oct 2026, 09:14", 1_760_200_000_000, "MTB", 3, 32_800.0, 940.0, 940.0, 3.2 * 3600, "done", emptyList()),
        Tour(3, TourKind.IMPORTED, "Komoot – Waalweg Algund", 1_760_400_000_000, "HIKE", 3, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
        Tour(4, TourKind.RECORDED, "Demo · Algund", 1_760_500_000_000, "HIKE", 2, 5_300.0, 120.0, 120.0, 1.4 * 3600, "done", emptyList()),
    )

    /** My routes, tab Planned (default): planned routes + imported GPX, Import GPX, per item ⋮ (Rename, Export GPX, Delete). */
    @Test fun tours() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}) }
    }

    /** Planned tab after a sync: a route new from the browser and a changed imported track – bold + grey line until opened. */
    @Test fun toursFromBrowser() = paparazzi.snapshot {
        val list = tourList.mapIndexed { i, t -> t.copy(uuid = "u-$i") } +
            Tour(5, TourKind.PLANNED, "Meran → Vellau", 1_760_600_000_000, "MTB", 2, 11_300.0, 690.0, 40.0, 1.9 * 3600, "done", viaPts, uuid = "u-new")
        OnPathTheme {
            RoutesScreen(list.sortedByDescending { it.createdMs }, {}, {}, {}, {}, {},
                marks = mapOf("u-new" to com.onpath.app.plansync.SyncMark.NEW, "u-2" to com.onpath.app.plansync.SyncMark.CHANGED))
        }
    }

    /** My routes, tab Done: recorded tours (a demo tour too); no Import GPX here. */
    @Test fun toursDone() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
    }

    /** Planned tab with nothing planned yet (recorded tours exist). */
    @Test fun toursEmptyPlanned() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList.filter { it.kind == TourKind.RECORDED }, {}, {}, {}, {}, {}) }
    }

    /** Done tab with nothing recorded yet. */
    @Test fun toursEmptyDone() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList.filter { it.kind != TourKind.RECORDED }, {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
    }

    /** Delete asks first and stays until answered. */
    @Test fun routesDeleteConfirm() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialConfirmDelete = tourList[0]) }
    }

    /** Recycle bin (C11): deleted entries with days left, Restore / Delete now. */
    @Test fun recycleBin() = paparazzi.snapshot {
        val now = 1_761_000_000_000
        OnPathTheme {
            RecycleBinScreen(listOf(tourList[1].copy(deletedMs = now - 2 * 86_400_000L), tourList[0].copy(deletedMs = now - 29 * 86_400_000L - 1)),
                {}, {}, {}, nowMs = now)
        }
    }

    /** Recycle bin → Delete now asks first. */
    @Test fun recycleBinDeleteAsk() = paparazzi.snapshot {
        OnPathTheme { RecycleBinScreen(listOf(tourList[1].copy(deletedMs = 1_760_900_000_000)), {}, {}, {}, nowMs = 1_761_000_000_000,
            initialConfirm = tourList[1]) }
    }

    /** Settings → Backup: folder, last backup, count, last failure; another folder chosen. */
    @Test fun settingsBackup() = withTimeZone("UTC") { settingsBackupShot() } // owner: tests in UTC (he works from changing places)

    private fun withTimeZone(id: String, block: () -> Unit) {
        val old = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
        try { block() } finally { java.util.TimeZone.setDefault(old) }
    }

    private fun settingsBackupShot() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
              androidx.compose.foundation.layout.Box(Modifier.padding(12.dp)) {
                BackupSection(BackupUi("Nextcloud/OnPath", 1_761_000_000_000, 28, "storage full", custom = true), BackupCallbacks())
              }
            }
        }
    }

    /** First start on an empty phone: restore offer. */
    @Test fun restoreOffer() = paparazzi.snapshot {
        OnPathTheme { Frame { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }; RestoreOfferDialog({}, {}) }
    }

    /** Loading over an unsaved plan: Save first / Replace (tap outside = cancel). */
    @Test fun replacePlanAsk() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}); ReplacePlanDialog({}, {}, {}) }
    }

    /**
     * My routes during a tour → a planned route tapped: nothing loads, the blue box "End the running tour first, then this
     * route can be loaded." with OK over the list (owner 2026-10-31); the list itself looks as always.
     */
    @Test fun routesTourBlocked() = paparazzi.snapshot {
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, tourBlocked = true) }
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

    /** Interrupted recording at start: Continue · Delete · Save like End tour. */
    @Test fun unfinishedTour() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
            UnfinishedDialog(tourList[1], {}, {}, {})
        }
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

    /** A new route, not saved yet (owner 2026-10-30): green "Save" beside ⋯ in the sheet's first line. */
    @Test fun planUnsaved() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(unsaved = true), PlanCallbacks()) }
    }

    /** A saved route changed since: "Save" beside ⋯ again, "… changed, not saved" below. */
    @Test fun planChangedNotSaved() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(savedName = "Route Meran – Hirzer", unsaved = true), PlanCallbacks()) }
    }

    /** Save beside ⋯ on a long day hike: time and distance still in full (their own line when they do not fit). */
    /** Right after Pair (owner 2026-10-30): back on the Plan map, "Paired. …" with OK at the top until OK. */
    @Test fun planPairedNote() = paparazzi.snapshot {
        Frame(top = { SyncNoteStrip(SyncNote.PAIRED, onOk = {}) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Right after Unpair (owner 2026-10-30, as simple as pairing): back on the Plan map, "Unpaired. …" with OK. */
    @Test fun planUnpairedNote() = paparazzi.snapshot {
        Frame(top = { SyncNoteStrip(SyncNote.UNPAIRED, onOk = {}) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Unpaired, the server not reached: the same strip asks to unpair the browser there too. */
    @Test fun planUnpairedOfflineNote() = paparazzi.snapshot {
        Frame(top = { SyncNoteStrip(SyncNote.UNPAIRED_OFFLINE, onOk = {}) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** ☰ → Pair with browser, the first time (owner 2026-10-30): the one step line + Open camera, then the camera. */
    @Test fun planPairHint() = paparazzi.snapshot {
        Frame(top = { PairHintStrip(onCamera = {}, onCancel = {}) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    @Test fun planUnsavedLongHike() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(distanceM = 28_740.0, durationS = 10.4 * 3600)).copy(sac = 3, unsaved = true),
            PlanCallbacks()) }
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

    // ---------------- map cells: card in the plan sheet, overview hint, Settings → Offline maps (map-cells.md §9) ----

    private val cOwn = com.onpath.app.cells.CellId(543, 361)
    private val cEast = com.onpath.app.cells.CellId(544, 361)
    private val cNorth = com.onpath.app.cells.CellId(543, 360)
    private val cSouth = com.onpath.app.cells.CellId(543, 362)
    private val cJob = com.onpath.app.cells.CellJob(listOf(cOwn, cEast, cNorth, cSouth, com.onpath.app.cells.CellId(544, 360)), cOwn,
        com.onpath.app.cells.CellWhy.PLANNED)
    private val mbB = 1_048_576L
    private fun cellCard(states: Map<com.onpath.app.cells.CellId, com.onpath.app.cells.CellState>, sizes: Map<com.onpath.app.cells.CellId, Long> = emptyMap(),
                         job: com.onpath.app.cells.CellJob = cJob, declined: Boolean = false, available: Boolean = true,
                         wifiOnly: Boolean = false) =
        CellCards.build(job, states, sizes, available, wifiOnly = wifiOnly, mobileDeclined = declined)

    private fun planWithCard(n: Int = 1, r: PlannedRoute? = null, size: PlanSize = PlanSize.COMPACT) = planUi(n = n, r = r, size = size)

    /** What the map needs from the user shows in the blue strip at the top, not as a card in the sheet. */
    @Composable
    private fun CardFrame(card: CellCardUi?, ui: PlanUi, gps: GpsStatus = GpsStatus.GOOD) =
        Frame(gps = gps, top = { mapNotice(card)?.let { MapNoticeStrip(it, CellCardCallbacks()) } }) { PlanPanel(ui, PlanCallbacks()) }

    /** The app opened where there is no detailed map – the strip asks, nothing loads by itself. */
    @Test fun hereAsk() = paparazzi.snapshot {
        Frame(top = { HereAskStrip(2, 21L * 1_048_576, {}, {}) }) { PlanPanel(planWithCard(n = 0), PlanCallbacks()) }
    }

    /** T1: a start point set outside the detailed map – the server builds the cells. */
    @Test fun cellCardPreparing() = paparazzi.snapshot {
        CardFrame(cellCard(mapOf(cOwn to com.onpath.app.cells.CellState.Building("base", 0.4, 300),
                cEast to com.onpath.app.cells.CellState.Queued(2, 360), cNorth to com.onpath.app.cells.CellState.Queued(3, 360),
                cSouth to com.onpath.app.cells.CellState.Requested, com.onpath.app.cells.CellId(544, 360) to com.onpath.app.cells.CellState.Requested)), planWithCard())
    }

    /** Own cell downloading first; the route is a straight line until the routing data is there (existing note). */
    @Test fun cellCardDownloading() = paparazzi.snapshot {
        CardFrame(cellCard(mapOf(cOwn to com.onpath.app.cells.CellState.Ready("2026-10-19"),
                cEast to com.onpath.app.cells.CellState.Downloading(4 * mbB, 10 * mbB), cNorth to com.onpath.app.cells.CellState.Building("overlays", 0.7, 60),
                cSouth to com.onpath.app.cells.CellState.Queued(1, 120), com.onpath.app.cells.CellId(544, 360) to com.onpath.app.cells.CellState.Queued(2, 180)),
                sizes = mapOf(cOwn to 10 * mbB)), planWithCard(n = 2,
                r = route.copy(legs = listOf(PlannedLeg(routePts.take(2), true)), wayTypes = emptyList(), surfaces = emptyList())
                ).copy(error = "Routes can't be calculated right now – straight lines shown."))
    }

    /** Mobile data: own area loaded, the others ask once with their size – stays until answered. T3 (own position). */
    @Test fun cellCardMobileQuestion() = paparazzi.snapshot {
        val job = com.onpath.app.cells.CellJob(listOf(cOwn, cEast, cNorth, cSouth), cOwn, com.onpath.app.cells.CellWhy.HERE)
        CardFrame(cellCard(mapOf(cOwn to com.onpath.app.cells.CellState.Ready("v"),
                cEast to com.onpath.app.cells.CellState.WaitingForWifi(9 * mbB), cNorth to com.onpath.app.cells.CellState.WaitingForWifi(8 * mbB),
                cSouth to com.onpath.app.cells.CellState.WaitingForWifi(8 * mbB)), sizes = mapOf(cOwn to 10 * mbB), job = job), planWithCard(n = 0), gps = GpsStatus.GOOD)
    }

    /** "Maps only on Wi-Fi" on: the strip says the areas wait and offers to load them once over mobile data (owner Q6). */
    @Test fun cellCardWifiOnlyWaiting() = paparazzi.snapshot {
        val job = com.onpath.app.cells.CellJob(listOf(cOwn, cEast, cNorth), cOwn, com.onpath.app.cells.CellWhy.HERE)
        CardFrame(cellCard(mapOf(cOwn to com.onpath.app.cells.CellState.Ready("v"),
                cEast to com.onpath.app.cells.CellState.WaitingForWifi(9 * mbB), cNorth to com.onpath.app.cells.CellState.WaitingForWifi(8 * mbB)),
                sizes = mapOf(cOwn to 10 * mbB), job = job, wifiOnly = true), planWithCard(n = 0))
    }

    @Test fun cellCardOffline() = paparazzi.snapshot {
        val off = com.onpath.app.cells.CellState.Offline("No connection – the detailed map for this area will load when you are online.")
        CardFrame(cellCard(cJob.cells.associateWith { off }), planWithCard())
    }

    @Test fun cellCardFailed() = paparazzi.snapshot {
        CardFrame(cellCard(cJob.cells.associateWith { com.onpath.app.cells.CellState.Ready("v") } +
                (cEast to com.onpath.app.cells.CellState.Failed("Map for this area failed")),
                sizes = cJob.cells.associateWith { 9 * mbB }), planWithCard(n = 3, r = route))
    }

    @Test fun cellCardDone() = paparazzi.snapshot {
        CardFrame(cellCard(cJob.cells.associateWith { com.onpath.app.cells.CellState.Ready("v") },
                sizes = cJob.cells.associateWith { 8_600_000L }), planWithCard(n = 3, r = route))
    }

    /** Small sheet: the card is one line; an open question stays visible. */
    @Test fun cellCardMini() = paparazzi.snapshot {
        CardFrame(cellCard(mapOf(cOwn to com.onpath.app.cells.CellState.Ready("v"),
                cEast to com.onpath.app.cells.CellState.Downloading(2 * mbB, 9 * mbB))), planWithCard(n = 3, r = route, size = PlanSize.MINI))
    }

    @Test fun cellCardNotAvailable() = paparazzi.snapshot {
        CardFrame(cellCard(emptyMap(), available = false), planWithCard())
    }

    /** Fresh install, no map yet, location not allowed: the card says what brings the detailed map (no timeout). */
    @Test fun cellCardFirstStart() = paparazzi.snapshot {
        CardFrame(CellCards.firstStart(null, available = true, noDetailedMap = true,
                waitingForPosition = true, hidden = false, permission = false), planWithCard(n = 0), gps = GpsStatus.NO_PERMISSION)
    }

    /** Fresh install, location allowed, waiting for the first fix. */
    @Test fun cellCardFirstStartWaiting() = paparazzi.snapshot {
        CardFrame(CellCardUi.NeedPosition(permission = true), planWithCard(n = 0), gps = GpsStatus.SEARCHING)
    }

    /** Zoomed in (z ≥ 10) where only the overview map exists: a small hint below the search bar. */
    @Test fun overviewHint() = paparazzi.snapshot {
        Box {
            Frame(gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
            OnPathTheme {
                Box(Modifier.fillMaxSize().padding(top = 32.dp + 76.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
                    OverviewHint()
                }
            }
        }
    }

    /** Settings → Offline maps: areas with size + date, one asking "Delete?", total, Wi-Fi only, Update now. */
    @Test fun settingsOfflineMaps() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(12.dp)) {
                    val ui = OfflineMapsUi(true, listOf(
                        OfflineArea("10-543-361", "Meran", 10_400_000, "19 Oct 2026"),
                        OfflineArea("10-544-361", "near Bozen", 12_100_000, "19 Oct 2026", status = "downloading 40 %"),
                        OfflineArea("10-543-362", "46.43° N, 11.07° E", 7_800_000, "12 Oct 2026"),
                    ), wifiOnly = false, loading = 2)
                    OfflineMapsSection(ui, OfflineMapsCallbacks(), initialConfirm = "10-543-362")
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(6.dp))
                    OfflineMapsSection(OfflineMapsUi(false, emptyList(), wifiOnly = true), OfflineMapsCallbacks())
                }
            }
        }
    }
}
