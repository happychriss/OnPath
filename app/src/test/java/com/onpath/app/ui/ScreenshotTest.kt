package com.onpath.app.ui

import com.onpath.strings.*
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.sin
import com.onpath.app.domain.plan.LivePlan
import com.onpath.app.domain.plan.LivePlanRows
import com.onpath.app.domain.tour.StopAhead
import com.onpath.app.domain.tour.StopRow

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
                       eye: Boolean = false, peeking: Boolean = false, large: Boolean = false,
                       /** whole route: with a line (= the eye) unless a test says otherwise – the greying rule (MapControlsEnabled) */
                       fit: Boolean = eye,
                       plan: Boolean = false) =
        MapFooter("500 m" to 64.dp) {
            MapControls(compass, showEye = eye, onPeek = {}, gps = gps, following = following, onLocate = {}, peeking = peeking,
                onFitRoute = if (fit) ({}) else null, onSettings = if (large) ({}) else null, plan = plan)
        }

    /**
     * Plan mode: search bar + ☰ menu, footer on the sheet's top edge (SheetWithFooter: never above the top bar), then
     * [bottom]. Top padding stands in for the status bar.
     */
    @Composable
    private fun Frame(gps: GpsStatus = GpsStatus.GOOD, aboveFooter: @Composable () -> Unit = {},
                      top: @Composable () -> Unit = {},
                      /**
                       * The plan has a route (owner 2026-11-06: the pictures show the greying rule): eye, whole route and
                       * undo can act; false = an empty / unrouted plan – they are greyed ([MapControlsEnabled]).
                       */
                      route: Boolean = true,
                      /** Plan's map controls – at their fixed place (onpath.private#43, PlanControlsLayer), not on the sheet's edge. */
                      controls: @Composable () -> Unit = {
                          MapControls(CompassSpec(null, 0f) {}, showEye = route, onPeek = {}, gps = gps, following = false, onLocate = {},
                              onFitRoute = if (route) ({}) else null, plan = true)
                          // undo / redo in their own pill, only while there is something to undo (owner 2026-11-08)
                          if (route) UndoPill(onUndo = {}, onRedo = null)
                      },
                      menu: PlanMenu = PlanMenu(onUpdates = {}),
                      /** "Plan ändern" during a tour: the mode label and the blue position sign in the map's middle. */
                      editing: Boolean = false,
                      /** "Ohne Netz ✓ / ⟳ / ⚠" in the mode pill (owner 2026-11-03). */
                      offline: OfflineReady.State? = null,
                      /** The activity button on the map: activity + T level (the plan's). */
                      act: Activity = Activity.HIKE, sac: Int = 2,
                      bottom: @Composable () -> Unit) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                if (editing) androidx.compose.foundation.Image(rememberPlanPuck(Activity.HIKE).asImageBitmap(), null,
                    Modifier.align(Alignment.Center))
                // the paper band behind the search pill and ☰ (owner 2026-11-05; paper since 2026-11-06 C) – as MapScreen draws it
                Box(Modifier.fillMaxWidth().edgeShadow(androidx.compose.ui.graphics.RectangleShape, up = false).background(PlanTopBand.Color).padding(top = 32.dp).height(planTopBarDp().dp))
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, menu, Modifier.fillMaxWidth(), editing = editing, offline = offline)
                    // the activity as a button on the map (owner 2026-11-05, R5) – as MapScreen places it
                    ActivityMapButton(act, sac, {}, Modifier.padding(top = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp()).dp, start = 12.dp))
                    // as PlanMode: the controls under the sheet and the boxes, the sheet's height read while placing
                    val sheetHeight = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    PlanControlsLayer({ sheetHeight.intValue }, planTopBarDp().dp, controls = controls)
                    SheetWithFooter(planTopBarDp().dp, footer = {
                        Column(Modifier.fillMaxWidth()) { aboveFooter(); MapFooter("500 m" to 64.dp) {} }
                    }, sheet = { Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) { bottom() } })
                    Box(Modifier.fillMaxWidth().padding(top = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp()).dp,
                        start = MessagePlacement.GAP_DP.dp, end = MessagePlacement.GAP_DP.dp)) { top() }
                }
            }
        }
    }

    @Composable
    private fun NavFrame(ui: NavUi, slide: Float = 0f, fit: Boolean = false, actions: Boolean = false, lock: Boolean = true,
                         newPlan: NewPlanNote? = null) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    // after "Show whole route" following is off: compass needle, locate not filled
                    NavigateOverlay(ui, footer = { Footer(following = !fit, compass = CompassSpec(ui.headingUp.takeUnless { fit }, 0f) {},
                        eye = true, large = true, fit = fit) },
                        onSlideToPlan = {}, slideStartFraction = slide, actionsOpen = actions, lock = lock, newPlan = newPlan)
                }
            }
        }
    }

    /**
     * The stopped frames (owner 2026-11-07, "Anhalten" v5): "Angehalten seit 14:32" (the stop began at 14:32 – unit tests
     * run in UTC) and, as on a phone, a moving time: "Bisher 5,3 km · in Bewegung 1:12".
     */
    private val STOPPED_AT = 1_794_061_920_000L
    private val pausedUi get() = navUi.copy(paused = true, pausedSinceMs = STOPPED_AT, movingS = 4_320.0)

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

    /** The opened card with the next stops (owner 2026-11-05): sign, name, distance, arrival; the signs on the profile. */
    fun navigateProfileStops() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp)) {
                    NavStatusCard(navUi.copy(toGo = navUi.toGo!!.copy(stops = listOf(
                        StopRow(StopAhead(2, "Leiteralm", false, 1_240.0), "13:05"),
                        StopRow(StopAhead(3, null, false, 3_480.0), "14:20"),
                        StopRow(StopAhead(4, "Gasthof Hochmuth am Tirolersteig", true, 7_420.0), "15:55")))),
                        onResume = {}, profileOpen = true)
                }
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

    /** App start without a plan: Plan is home; no points, no primary button (free recording = the green start button, ☰ has no Start recording since 2026-11-02). GPS searching. */
    fun planEmpty() = paparazzi.snapshot {
        Frame(route = false, gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
    }

    /** The empty plan rolled up (owner 2026-11-07): as small as before the guide – the title only. */
    fun planEmptyCollapsed() = paparazzi.snapshot {
        Frame(route = false, gps = GpsStatus.GOOD) { PlanPanel(planUi(n = 0, r = null, size = PlanSize.COLLAPSED), PlanCallbacks()) }
    }

    /** The empty plan pulled up (owner 2026-11-07, guide A): the three steps and "Meine Touren öffnen". */
    fun planEmptyFull() = paparazzi.snapshot {
        Frame(route = false, gps = GpsStatus.GOOD) { PlanPanel(planUi(n = 0, r = null, size = PlanSize.FULL), PlanCallbacks()) }
    }

    /** Only a start point (owner 2026-10-30): no line to follow, no "Start navigation" (free recording = the green start button, ☰ has no Start recording since 2026-11-02). */
    fun planOnePoint() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planUi(n = 1, r = null), PlanCallbacks()) }
    }

    /** Meran pictures D04: at the low (summary) step "Halte die Karte gedrückt, um das Ziel zu setzen." lay behind the foot. */
    fun planOnePointLow() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planUi(n = 1, r = null, size = PlanSize.MINI), PlanCallbacks()) }
    }

    fun plan() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Plan far away, my position off the map (owner 2026-11-07, variant 3): the distance under the locate icon. */
    fun planLocateFar() = paparazzi.snapshot {
        Frame(controls = {
            MapControls(CompassSpec(null, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD, following = false, onLocate = {},
                onFitRoute = {}, plan = true, locateLabel = LocateDistance.text(59_200.0))
            UndoPill(onUndo = {}, onRedo = null)
        }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** A tap on the open map (owner 2026-11-03): "Hold to set a point here." in the summary step the sheet opens at. */
    fun planTapHoldHint() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(tapHint = true), PlanCallbacks()) }
    }

    /** Owner 2026-11-03: the route works without a network – "PLANEN · Ohne Netz ✓", the tap's line in the sheet. */
    fun planOfflineReady() = paparazzi.snapshot {
        val r = OfflineReady.Result(OfflineReady.State.READY, 0.0, false, 0)
        Frame(offline = r.state) { PlanPanel(planUi(size = PlanSize.MINI).copy(offlineInfo = OfflineReady.explain(r)), PlanCallbacks()) }
    }

    /** Still loading: "PLANEN · Ohne Netz ⟳", "Karte und Wege werden geladen (12 MB)." */
    fun planOfflineLoading() = paparazzi.snapshot {
        val r = OfflineReady.Result(OfflineReady.State.LOADING, 0.0, true, 12L * 1_048_576)
        Frame(offline = r.state) { PlanPanel(planUi(size = PlanSize.MINI).copy(offlineInfo = OfflineReady.explain(r)), PlanCallbacks()) }
    }

    /** Something missing, nothing loading: "PLANEN · Ohne Netz ⚠", "Für 3,0 km dieser Route fehlt die Karte …". */
    fun planOfflineMissing() = paparazzi.snapshot {
        val r = OfflineReady.Result(OfflineReady.State.MISSING, 3_000.0, false, 0)
        Frame(offline = r.state) { PlanPanel(planUi(size = PlanSize.MINI).copy(offlineInfo = OfflineReady.explain(r)), PlanCallbacks()) }
    }

    /** A long press on a map control names it (owner 2026-11-03): the eye's tooltip open. */
    fun planMapControlTip() = paparazzi.snapshot {
        Frame(controls = {
            run {
                MapControls(CompassSpec(null, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD, following = false, plan = true,
                    onLocate = {}, onFitRoute = {}, tipShown = 2) // Plan order: compass · locate · eye (2) · …
                UndoPill(onUndo = {}, onRedo = null)
            }
        }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** "Show whole route" (⛶) under the eye: compass · eye · whole route · Undo · locate on the sheet's edge. */
    fun planWholeRoute() = paparazzi.snapshot {
        Frame(controls = {
            run {
                MapControls(CompassSpec(null, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD, following = false, plan = true,
                    onLocate = {}, onFitRoute = {})
                UndoPill(onUndo = {}, onRedo = null)
            }
        }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Navigate after "Show whole route": the big buttons gear · compass · eye · whole route · locate, following off. */
    fun navigateWholeRoute() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.RIGHT, 120.0)), fit = true)
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
    @Test fun planPointsSmall() = planPointsSmallShot()
    /** The summary step – where the plan's first route leaves the sheet (owner 2026-11-08 evening, SheetRise). */
    fun planPointsSmallShot() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.MINI), PlanCallbacks()) } }

    /** Step 1: summary + the large elevation profile (finger ruler); details via the difficulty badge. */
    @Test fun planPointsHalf() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.COMPACT), PlanCallbacks()) } }

    /** Step 2 = the point editor: summary, profile, then Points (≡ marker name +distance ✕); no way types. */
    fun planPointsFull() = paparazzi.snapshot { Frame { PlanPanel(planWithPoints(PlanSize.FULL), PlanCallbacks()) } }

    /**
     * With the points open, all four map controls (compass, eye, undo, locate) do not fit
     * between the top bar and the sheet – they stay below the top bar and fade under the sheet instead of covering ☰.
     */
    @Test fun planPointsFullControlsNeverOverTheTopBar() = paparazzi.snapshot {
        Frame(controls = {
            run {
                MapControls(CompassSpec(null, 0f) {}, showEye = true, onPeek = {}, gps = GpsStatus.GOOD, following = false, plan = true,
                    onLocate = {})
                UndoPill(onUndo = {}, onRedo = null)
            }
        }) { PlanPanel(planWithPoints(PlanSize.FULL), PlanCallbacks()) }
    }

    /** What the app shows with the points open: the controls collapse to Undo + locate (MapScreen), below the sheet's edge. */
    @Test fun planPointsFullCollapsedControls() = paparazzi.snapshot {
        Frame(controls = {
            run {
                MapControls(null, showEye = false, onPeek = {}, gps = GpsStatus.GOOD, following = false, onLocate = {}, plan = true)
                UndoPill(onUndo = {}, onRedo = {})
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
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true), PlanCallbacks()) }
    }

    /** A long calculation shows "Calculating the route… Cancel". */
    @Test fun planRoutingSlowCancel() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true).copy(routingSlow = true), PlanCallbacks()) }
    }

    /**
     * A long plan routed leg by leg (owner 2026-11-05): the wait card counts the legs the router works on –
     * "Calculating section 2 of 4…" over a bar a quarter full, Cancel beside it.
     */
    fun planRoutingSlowProgress() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true)
            .copy(routingSlow = true, routeProgress = 1 to 4, routeShare = 0.25f), PlanCallbacks()) }
    }

    /** One long leg (a fresh 100 km plan, owner 2026-11-05): the bar moves within the leg – "Calculating the route…", 40 %. */
    fun planRoutingSlowOneLeg() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(line = null), routing = true)
            .copy(routingSlow = true, routeProgress = 0 to 1, routeShare = 0.4f), PlanCallbacks()) }
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
        Frame(route = false) { PlanPanel(planUi(n = 2, r = null).copy(notRouted = NotRouted.LONG), PlanCallbacks()) }
    }

    /**
     * Round trip: start = goal shown once with ↺ and the way back; "↻ Rundtour" is on (filled, ✓ – owner 2026-11-06,
     * #40: a switch, a tap takes the way back out).
     */
    @Test fun planPointsRoundTrip() = paparazzi.snapshot {
        val loop = stops.take(3) + stops[0]
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(loop, null), r = null, roundTrip = true)
            .copy(pointCount = 4), PlanCallbacks()) }
    }

    /** A round trip in the summary step (owner 2026-11-06, #40): "↻ Rundtour" on – filled plan blue with ✓. */
    fun planRoundTripOn() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(roundTrip = true, canRoundTrip = true), PlanCallbacks()) }
    }

    /** Out and back over one point (A → B → A): Rundtour on, "⇅ Umkehren" greyed out – backwards is the same (#40). */
    fun planOutAndBack() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(roundTrip = true, canReverse = false), PlanCallbacks()) }
    }

    /** One point only: Start, Reverse / Round trip disabled. */
    @Test fun planPointsOne() = paparazzi.snapshot {
        Frame(route = false) { PlanPanel(planWithPoints(PlanSize.FULL, pointRows(stops.take(1), null), r = null), PlanCallbacks()) }
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
                        unsaved: Boolean = false, newPlan: Boolean = state.save, record: Boolean = true,
                        bottom: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            // [sync] is built inside the composition: its texts follow the test's language
            // Save route is the top item whenever there is a plan; [unsaved] = the "Nicht gespeichert ›" sign
            val menu = PlanMenu(state, onUpdates = {}, onPairBrowser = {}, onPhoneSetup = if (phoneSetup) ({}) else null, onHelp = {},
                sync = sync?.invoke() ?: SyncMenuUi(), onSaveRoute = if (state.save) ({}) else null,
                // owner 2026-11-06: "Neu planen" with a plan, "Nur aufzeichnen" while no tour runs
                onNewPlan = if (newPlan) ({}) else null, onRecordWithoutPlan = if (record) ({}) else null)
            Frame(menu = menu) { bottom() }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp)) {
                PlanMenuItems(menu) {}
            } }
        }
    }

    /** ☰ with a route planned: My routes on, Start recording greyed. */
    fun planMenuOpen() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false)) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    /** ☰ while the plan has unsaved changes: "Save route" on top (the one Save); the sign beside the label, no dot on ☰. */
    fun planMenuUnsaved() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false), unsaved = true) {
            PlanPanel(planUi().copy(unsaved = true), PlanCallbacks())
        }
    }

    /** ☰ while a tour needs something from the phone (location off …): red "Phone setup" first (owner 2026-10-30). */
    fun planMenuPhoneSetup() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false), phoneSetup = true) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    private val syncNow = 1_761_150_000_000L
    private val paired = com.onpath.app.plansync.PlanSync.State(paired = true, peer = "Chrome on Linux", lastOkMs = syncNow - 60_000,
        sinceMs = syncNow - 86_400_000, checkedMs = syncNow - 60_000)

    /** ☰ paired with a browser (owner 2026-10-30): "Paired with browser ✓" + "Pixel 8 ↔ Chrome on Linux · synced 1 min ago". */
    fun planMenuSyncPaired() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            sync = { SyncMenuUi.of(paired, "Pixel 8", syncNow) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** ☰ with a sync problem worth a look (mailbox full): amber dot on ☰, the item amber with the reason. */
    fun planMenuSyncProblem() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            sync = { SyncMenuUi.of(paired.copy(problem = com.onpath.app.plansync.PlanSync.Problem.Refused(507, "full")), "Pixel 8", syncNow) }) {
            PlanPanel(planUi(), PlanCallbacks())
        }
    }

    /** ☰ when the browser unpaired itself (field bug 2026-10-31): "Pair with browser" + grey "Unpaired in the browser", no dot. */
    fun planMenuSyncUnpairedInBrowser() = paparazzi.snapshot {
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = false, recording = false),
            sync = { SyncMenuUi.of(paired.copy(peer = null, browser = com.onpath.app.plansync.BrowserLink.UNPAIRED), "Pixel 8", syncNow) }) {
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
        TopMenu(RouteRules.menu(hasRoute = true, pointCount = 3, savedCount = 3, navigating = true, recording = true), record = false) {
            PlanPanel(planUi(tourRunning = true), PlanCallbacks())
        }
    }

    /**
     * The plan during a tour, profile step (owner 2026-11-05, R5): plan row ✕ Neu planen · ⇅ Umkehren · ↻ Rundtour, the
     * facts, the profile, at the bottom the green "▶ Weiter geht's".
     */
    fun planTourRunning() = paparazzi.snapshot {
        Frame(editing = true) {
            PlanPanel(planUi(size = PlanSize.COMPACT, tourRunning = true).copy(tour = TourState(1_700.0, 878, paused = false)),
                PlanCallbacks())
        }
    }

    /**
     * After "Neu planen" during a tour (owner 2026-11-03): the plan is empty, the recording goes on – the summary step
     * shows how to begin again above "▶ Weiter geht's".
     */
    fun planTourNewPlan() = paparazzi.snapshot {
        Frame(editing = true) {
            PlanPanel(planUi(n = 0, r = null, size = PlanSize.MINI, tourRunning = true)
                .copy(tour = TourState(1_700.0, 878, paused = false, recordOnly = true)), PlanCallbacks())
        }
    }

    /**
     * The plan during a tour rolled up (owner 2026-11-05, R5): one row "4 h 36 min · 12,5 km · ↑842 ↓310" with a compact
     * green "▶ Weiter geht's" – the map almost free.
     */
    fun planTourRolledUp() = paparazzi.snapshot {
        Frame(editing = true) {
            PlanPanel(planUi(size = PlanSize.COLLAPSED, tourRunning = true).copy(tour = TourState(1_700.0, 878, paused = false)),
                PlanCallbacks())
        }
    }

    /**
     * The plan during a tour as it opens (summary step): plan row, facts – and at the bottom, where "Los geht's" is
     * without a tour, the green "● 1,7 km · 0:14  ▲ Navigation ›" back to Navigate (owner 2026-11-06, C; no "←" in the band).
     */
    fun planTourRunningCollapsed() = paparazzi.snapshot {
        Frame(editing = true) {
            PlanPanel(planUi(size = PlanSize.MINI, tourRunning = true).copy(tour = TourState(1_700.0, 878, paused = false)),
                PlanCallbacks())
        }
    }

    @Test fun endTourConfirm() = paparazzi.snapshot {
        OnPathTheme {
            // the sheet opens from Navigate ("Tour beenden" in the unlocked status card)
            NavFrame(navUi, actions = true, lock = true)
            // variant A (owner 2026-11-06): numbers in one line, Save, Weiterfahren | Löschen – no scrolling; nothing about
            // the plan (owner 2026-11-06: the tour and the plan are two things)
            EndTourDialog({}, {}, {}, activity = Activity.HIKE, stats = EndStats(12_480.0, 16_560, 842.0), inline = true)
        }
    }

    /** Navigate: what is left on top, recording chip, slide-to-plan at the bottom. */
    fun navigate() = paparazzi.snapshot { NavFrame(navUi) }

    /** Back while a tour records (owner 2026-11-06, flow map N4): "App verlassen?" · Bleiben / Verlassen in Navigate's one box. */
    fun navigateLeaveAsk() = paparazzi.snapshot {
        OnPathTheme {
            NavFrame(navUi)
            Box(Modifier.fillMaxSize()) {
                MessageSlot(leaveAppMessage({}, {}), MessagePlacement.topDp(MessageScreen.NAVIGATE, 150))
            }
        }
    }

    /** Navigate without a recording (e.g. an unfinished tour being asked about): the slider says only "Change route". */
    fun navigateNotRecording() = paparazzi.snapshot { NavFrame(navUi.copy(recordedM = null)) }

    /** Slider dragged half way (springs back on release). */
    @Test fun navigateSlideHalf() = paparazzi.snapshot { NavFrame(navUi, slide = 0.5f) }

    // --- Re-routing while navigating (owner: ask every time, nothing disappears by itself) ---
    private val hint = com.onpath.app.navigation.RouteHint(120.0, 45.0, GeoPoint(46.68, 11.13))
    private val question = com.onpath.app.navigation.NavPrompt.Question(1, 350.0, 600.0, 20.0, emptyList(), 900.0, 2_000.0, offM = 118.0)

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
    fun navigateApproximatePosition() = paparazzi.snapshot {
        NavFrame(navUi.copy(recordedM = 0.0, elapsedS = 40, arriveAt = "16:40",
            approx = approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WIFI, 80))))
    }

    /** Notifications off during a tour: the small amber chip under the status card (tap → setup, OK → gone this tour). */
    fun navigateNotificationsOff() = paparazzi.snapshot {
        NavFrame(navUi.copy(arriveAt = "16:40", notificationsOff = true))
    }

    /** Started indoors with notifications off: the approximate-position line and the chip at once, both readable. */
    fun navigateNotificationsOffApproximate() = paparazzi.snapshot {
        NavFrame(navUi.copy(recordedM = 0.0, elapsedS = 40, arriveAt = "16:40", notificationsOff = true,
            approx = approxLine(com.onpath.app.navigation.Provisional.Fix(com.onpath.app.navigation.Provisional.Source.WIFI, 80)),
            turn = TurnHint(com.onpath.app.routing.TurnKind.LEFT, 180.0)))
    }

    /** The question: stays until answered or back on the route; big buttons in thumb reach above the map controls. */
    fun navigateRerouteQuestion() = paparazzi.snapshot {
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
    fun navigateRerouteSkipStop() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(skipStop = 2, via = question.copy(extraM = question.extraM + 1_400.0)), hint)))
    }

    /** The same for a named stop: "Yes, skip Gasthof Hochmuth" wraps to two lines (at a large font: "stop 3"). */
    fun navigateRerouteSkipStopNamed() = paparazzi.snapshot {
        NavFrame(navUi.copy(offRoute = OffRouteUi(question.copy(skipStop = 2, skipName = "Gasthof Hochmuth",
            via = question.copy(extraM = question.extraM + 1_400.0)), hint)))
    }

    /**
     * Never on the route yet, 31 km from its start (owner 2026-10-31, field: tour started at home): a calm question –
     * no vibration, no off-route card – "Route starts 31 km away" · "south-west (direct) · recording" · Just record / Guide me.
     * The status card says the same number: "Not on the route yet · Start 31 km away" (not the line's nearest 28.4 km).
     */
    private val farUi = navUi.copy(toGo = ToGo(0.0, 0.0, 0.0, 28_400.0, state = com.onpath.app.time.EstimateState.NOT_ON_ROUTE,
        startM = 30_600.0),
        recordedM = 0.0, elapsedS = 40, activity = Activity.BIKE_TOURING)
    private val farHint = com.onpath.app.navigation.RouteHint(30_600.0, 225.0, GeoPoint(46.4, 10.9))

    fun navigateFarStart() = paparazzi.snapshot {
        NavFrame(farUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.FarStart(1, 30_600.0, 225.0), farHint, arrowDeg = -135f)))
    }

    /** "Guide me" tapped: the way to the start is being computed (spinner, "Guide me" waits). */
    @Test fun navigateFarStartSearching() = paparazzi.snapshot {
        NavFrame(farUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.FarStart(1, 30_600.0, 225.0, searching = true), farHint, arrowDeg = -135f)))
    }

    /** "Guide me" found no way: the red note with OK where the question was; recording goes on. */
    fun navigateLeadFailed() = paparazzi.snapshot {
        NavFrame(farUi.copy(offRoute = OffRouteUi(com.onpath.app.navigation.NavPrompt.LeadFailed(2), farHint, arrowDeg = -135f)))
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

    /** The way word the voice says, on the card too (owner idea 2026-11-01): "Left onto the cycle path". */
    fun navigateTurnOnto() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.LEFT, 150.0, onto = com.onpath.app.navigation.WayKind.CYCLEWAY)))
    }

    /** The next stop before the next turn (owner 2026-11-03): "1,2 km · Stopp 2 · Leiteralm" with the stop's sign. */
    fun navigateNextStop() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.SLIGHT_RIGHT, 1_200.0,
            stop = StopAhead(2, "Leiteralm", false, 1_200.0))))
    }

    /** The goal next, named: "Ziel · Gasthof Hochmuth" with the dark red flag sign. */
    fun navigateNextGoal() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.SLIGHT_RIGHT, 650.0,
            stop = StopAhead(4, "Gasthof Hochmuth", true, 650.0))))
    }

    /** North-up orientation button ("N" needle) and a slight-left hint close by. */
    @Test fun navigateNorthUp() = paparazzi.snapshot {
        NavFrame(navUi.copy(turn = TurnHint(com.onpath.app.routing.TurnKind.SLIGHT_LEFT, 40.0), headingUp = false))
    }

    /** Free recording (no route), paused from the notification. */
    /** After the slide (owner 2026-11-02, #17 C): green Pause · blue Change route · red End, ✕ above on the right. */
    /** Unlocked (owner 2026-11-05, Navigate C): Pause / Tour beenden in the status card, "Plan ändern" where the slider was. */
    fun navigateActionsOpen() = paparazzi.snapshot { NavFrame(navUi, actions = true) }

    /**
     * Stopped inside the unlock window (owner 2026-11-07: one held Anhalten opens the bar, 10 s since 2026-11-08): pale =
     * hold, strong = tap – green "▶ Weiter", dark green "■ Tour beenden", blue "✎ Planen", all on a plain tap
     * ([com.onpath.app.domain.tour.UnlockWindow.needsHold]).
     */
    fun navigatePausedOpen() = paparazzi.snapshot { NavFrame(pausedUi, actions = true) }

    /**
     * Locked and stopped (owner 2026-11-07, "Anhalten" variant 5): the card's head "⏸ Angehalten seit 14:32 –
     * Aufzeichnung und Navigation ruhen", a thin line, the numbers, "Bisher 5,3 km · in Bewegung 1:12"; at the bottom the
     * pale held buttons "▶ Weiter" (pale green too, owner 2026-11-08) over "■ Tour beenden | ✎ Planen" (EN in one row).
     */
    @Test fun navigatePausedLocked() = paparazzi.snapshot { NavFrame(pausedUi) }
    /** Stopped as on a phone – with the arrival clock ([StatusCardPhoneScreenshotTest]). */
    fun navigatePausedLockedArrive() = paparazzi.snapshot { NavFrame(pausedUi.copy(arriveAt = "16:40")) }
    /** Moving, with the arrival clock ([StatusCardPhoneScreenshotTest]). */
    fun navigateArrive() = paparazzi.snapshot { NavFrame(navUi.copy(arriveAt = "16:40")) }

    /**
     * Locked, right after a short tap on "Anhalten" (owner 2026-11-07, 09:30): "Gedrückt halten" in a strong walnut pill
     * right above the tapped button (+ a tick on the phone). At rest nothing is over the light buttons (the other frames).
     */
    fun navigateLockedHint() = paparazzi.snapshot {
        androidx.compose.runtime.CompositionLocalProvider(LocalHoldHintShown provides true) { NavFrame(navUi) }
    }

    /** Without a recording, unlocked: only "Plan ändern". */
    fun navigateNotRecordingOpen() = paparazzi.snapshot { NavFrame(navUi.copy(recordedM = null), actions = true) }

    /** Settings → Navigation "Sperre beim Navigieren" off (owner 2026-11-05): no slider, the three actions at once. */
    /** The lock off (owner 2026-11-06, variant 2): no buttons in the card, "⏸ Anhalten | ✎ Planen" at the bottom. */
    fun navigateLockOff() = paparazzi.snapshot { NavFrame(navUi, lock = false) }
    /** The lock off, stopped: "▶ Weiter · ■ Tour beenden · ✎ Planen" as plain buttons – ending only out of a stop. */
    fun navigateLockOffPaused() = paparazzi.snapshot { NavFrame(pausedUi, lock = false) }

    /** A free tour stopped: "Aufzeichnung ruht" (no route to stand still); no moving time → "seit 0:30 h unterwegs". */
    fun navigateFreePaused() = paparazzi.snapshot {
        NavFrame(NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = true, pausedSinceMs = STOPPED_AT))
    }

    /** A tour without a route (owner 2026-11-05): "Freie Tour · nur Aufnahme" instead of an empty route line. */
    fun navigateFreeTour() = paparazzi.snapshot { NavFrame(NavUi(recordedM = 2_150.0, elapsedS = 1820, paused = false)) }

    /**
     * The empty plan during a tour (owner 2026-11-12, "Tour läuft noch – neues Ziel festlegen"): the title "● Tour läuft
     * noch", the row "Neues Ziel festlegen ›" (search or hold → Hierhin) and "Weiter geht's" back to the tour.
     */
    @Test fun planEmptyDuringTour() = paparazzi.snapshot {
        Frame(editing = true, route = false) {
            PlanPanel(PlanUi(0, null, false, null, canUndo = true, canRedo = false, activity = Activity.HIKE, tourRunning = true,
                tour = TourState(1_700.0, 878, paused = false), size = PlanSize.MINI), PlanCallbacks())
        }
    }

    // --- Activity while navigating (owner: a wrong activity is a disaster) ---
    // owner 2026-11-07: nothing is routed before a row is chosen – E-bike tapped (no route found), then MTB (routing)
    private val checkUi = ActivityCheckUi(Activity.HIKE, 3, listOf(
        ActivityRow(Activity.BIKE_TOURING, 3, ActivityRowState.Idle),
        ActivityRow(Activity.BIKE_TREKKING, 3, ActivityRowState.Idle),
        ActivityRow(Activity.EBIKE, 3, ActivityRowState.Failed(Txt.s(Res.string.msg_check_no_route))),
        ActivityRow(Activity.MTB, 3, ActivityRowState.Checking),
        ActivityRow(Activity.EMTB, 3, ActivityRowState.Idle),
    ))

    /** Tap the activity icon, then a row: only the tapped row is routed (spinner); one found no route. */
    fun navigateActivitySheet() = paparazzi.snapshot { NavFrame(navUi.copy(activityCheck = checkUi)) }

    /** Riding a bike during a tour: Hike's T1–T5 under its row (owner 2026-11-06, flow map K1). */
    fun navigateActivitySheetHikeLevels() = paparazzi.snapshot {
        // T3 tapped: the Hike row alone is routed for it
        NavFrame(navUi.copy(activity = Activity.BIKE_TREKKING, activityCheck = ActivityCheckUi(Activity.BIKE_TREKKING, 3, listOf(
            ActivityRow(Activity.BIKE_TOURING, 3, ActivityRowState.Idle),
            ActivityRow(Activity.MTB, 3, ActivityRowState.Idle),
            ActivityRow(Activity.HIKE, 3, ActivityRowState.Checking),
        ))))
    }

    /** Opened a moment ago: nothing routed yet (owner 2026-11-07), every row plain until one is chosen. */
    @Test fun navigateActivitySheetChecking() = paparazzi.snapshot {
        NavFrame(navUi.copy(activity = Activity.BIKE_TREKKING, activityCheck = ActivityCheckUi(Activity.BIKE_TREKKING, 3,
            Activity.entries.filter { it != Activity.BIKE_TREKKING }.map { ActivityRow(it, 3, ActivityRowState.Idle) },
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
    fun planChipLongBikeTour() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(distanceM = 112_480.0, durationS = 12.6 * 3600))
            .copy(activity = Activity.BIKE_TREKKING), PlanCallbacks()) }
    }

    /**
     * The hiking chip names the activity in every language ("Hike · T3" / "Wandern · T3", owner 2026-10-30) – a long
     * day hike must still show time AND distance in full beside it.
     */
    fun planChipLongHike() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(r = route.copy(distanceM = 28_740.0, durationS = 10.4 * 3600)).copy(sac = 3), PlanCallbacks()) }
    }

    /**
     * "Du bist angekommen." (owner 2026-11-07): the numbers, "Weiter aufzeichnen" | "Tour beenden" in one row (confirm
     * right), the quiet red "Tour löschen" with the bin note under them.
     */
    @Test fun arrival() = paparazzi.snapshot {
        OnPathTheme {
            NavFrame(navUi.copy(toGo = ToGo(30.0, 0.0, 60.0, 5.0)))
            ArrivalDialog({}, {}, {}, activity = Activity.HIKE, stats = EndStats(12_480.0, 16_560, 842.0), inline = true)
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

    /** The summary step (where a plan opens): chip · time · km, the route lines, the icon row, the 48 dp Start. */
    @Test fun planMini() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI), PlanCallbacks()) }
    }

    private val bikeRoute get() = route.copy(distanceM = 94_200.0, durationS = 4 * 3600.0 + 40 * 60)
    private val bikeHints = listOf(RouteSpot(SpotKind.PUSH, 13, 20, 0.0, 210.0), RouteSpot(SpotKind.STEPS, 30, 31, 3000.0, 3010.0),
        RouteSpot(SpotKind.STEPS, 50, 51, 5000.0, 5010.0))
    private fun bikePlan(size: PlanSize) = planUi(r = bikeRoute, size = size).copy(activity = Activity.BIKE_TOURING,
        warnText = RouteWarnings.text(bikeHints, 2), warnCount = RouteWarnings.count(bikeHints))

    /** Owner 2026-10-31: the tightened summary step – icon + word side by side, "Close", Start 48 dp. */
    fun planSummaryBike() = paparazzi.snapshot { Frame { PlanPanel(bikePlan(PlanSize.MINI), PlanCallbacks()) } }

    /** Owner 2026-10-31: rolled up – ONE row "🚲 4 h 40 min · 94.2 km ⚠2 [▶ Start]", the map almost free. */
    fun planCollapsed() = paparazzi.snapshot { Frame { PlanPanel(bikePlan(PlanSize.COLLAPSED), PlanCallbacks()) } }

    /** Rolled up with a point the route does not reach: the red ✕ beside the ⚠ count. */
    @Test fun planCollapsedGap() = paparazzi.snapshot {
        Frame { PlanPanel(bikePlan(PlanSize.COLLAPSED).copy(gapText = "Last 180 m to “Taser Alm”: no way for Bike"), PlanCallbacks()) }
    }

    /** The profile step and the points step of the same bike plan. */
    fun planProfileBike() = paparazzi.snapshot { Frame { PlanPanel(bikePlan(PlanSize.COMPACT), PlanCallbacks()) } }

    /**
     * A point the route does not reach, and what does (owner 2026-11-07, "Meran → Mutkopf" at Hike T1): the red line
     * "② erst ab T2 erreichbar" with a › (tap = the map shows the section), no button. Texts from [PlanGaps.reachText].
     */
    private fun reachPlan(fix: com.onpath.app.routing.PlanReach.Fix, a: Activity, more: Boolean = false): PlanUi {
        val g = PlanGap(1, GeoPoint(46.71077, 11.13787), GeoPoint(46.7058, 11.1374), 552.0)
        val gaps = if (more) listOf(g, g.copy(index = 2)) else listOf(g)
        return planUi(n = 2, size = PlanSize.MINI).copy(activity = a, sac = 1,
            gapText = PlanGaps.reachText(gaps, com.onpath.app.routing.PlanReach(1, fix, emptyList())))
    }
    private fun reachShot(fix: com.onpath.app.routing.PlanReach.Fix, a: Activity, more: Boolean = false) =
        paparazzi.snapshot { Frame(act = a, sac = 1) { PlanPanel(reachPlan(fix, a, more), PlanCallbacks()) } }
    @Test fun planReachHike() = reachShot(com.onpath.app.routing.PlanReach.Fix.Level(2), Activity.HIKE)
    /** The longest text: a bike plan only walking reaches; with a second gap "(+1 more)". */
    @Test fun planReachOnFoot() = reachShot(com.onpath.app.routing.PlanReach.Fix.OnFoot(2), Activity.MTB, more = true)
    @Test fun planReachBike() = reachShot(com.onpath.app.routing.PlanReach.Fix.Bike(Activity.BIKE_TREKKING), Activity.BIKE_TOURING)

    /** A point the route does not reach – one red bold line in the small sheet (no message box). */
    @Test fun planGapSmall() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(activity = com.onpath.app.routing.Activity.BIKE_TREKKING,
            gapText = "Last 180 m to “Taser Alm”: no way for Gravel bike"), PlanCallbacks()) }
    }

    /** Route hints: a ferry on the route – amber line first, before the way warnings. */
    fun planFerryHint() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(size = PlanSize.MINI).copy(activity = Activity.BIKE_TOURING,
            warnText = RouteWarnings.text(listOf(RouteSpot(SpotKind.FERRY, 40, 41, 6200.0, 6516.0),
                RouteSpot(SpotKind.FORD, 60, 60, 9100.0, 9100.0)), 3)), PlanCallbacks()) }
    }

    /** The list behind the amber line with the route hints: ferry, construction, ford, stile. */
    fun routeSpotsListHints() = paparazzi.snapshot {
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

    /**
     * Owner 2026-11-06 (Pixel): Reverse / Round trip – while the route is calculated the sheet keeps its height: the old
     * profile and the lines stay faded, the bar runs in the plan row's hairline. [planRecalculating] and
     * [planRecalculatingBefore] must have their sheet edge on the same pixel row.
     */
    fun planRecalculatingBefore() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(warnText = "1.2 km pushing · steps",
            changedText = RouteWarnings.changedText(com.onpath.app.routing.Activity.HIKE, 2)), PlanCallbacks()) }
    }
    fun planRecalculating() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(routing = true).copy(warnText = "1.2 km pushing · steps", routeShare = 0.4f,
            changedText = RouteWarnings.changedText(com.onpath.app.routing.Activity.HIKE, 2)), PlanCallbacks()) }
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
                            // owner #7 A: a paved footway without a bicycle tag
                            RouteSpot(SpotKind.FOOTWAY, 23, 25, 820.0, 980.0),
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
        Frame(route = false) { PlanPanel(if (empty) planUi(n = 0, r = null) else planUi(), PlanCallbacks()) }
        val d = androidx.compose.ui.platform.LocalDensity.current
        val a = with(d) { androidx.compose.ui.geometry.Offset(anchorDp.x.toPx(), anchorDp.y.toPx()) }
        if (dotColor != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color.White, 13.dp.toPx(), a)
            drawCircle(dotColor, 11.dp.toPx(), a)
        }
        // the found place's pin – the map's own drawing (MapController.setSearchMark) – and its name chip (MapOverlayHost)
        if (ring) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    com.onpath.app.map.CandidateMarker.draw(c.nativeCanvas, a.x, a.y, density,
                        android.graphics.Color.parseColor(com.onpath.app.map.MapController.ROUTE_COLOR))
                }
            }
            PinChipAt(a, topPx = with(d) { 160.dp.roundToPx() }, bottomPx = with(d) { 300.dp.roundToPx() }) {
                PinChip(shared.title(), str(if (empty) Res.string.plan_go_here else Res.string.plan_pin_add), onAction = {}, onMore = {}, emptyPlan = empty)
            }
        }
        // the pin's menu above the pin's head, else under its name chip (MapOverlayHost: lift / drop)
        val lift = if (ring) with(d) { com.onpath.app.map.CandidateMarker.PIN_HEIGHT_DP.dp.toPx() } else 0f
        val drop = if (ring) with(d) { PIN_CHIP_BELOW_DP.dp.toPx() } else 0f
        AnchoredOverlay(a.copy(y = a.y - lift), topPx = with(d) { 160.dp.roundToPx() }, bottomPx = with(d) { 300.dp.roundToPx() },
            belowY = a.y + drop, loose = ring, content = content)
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

    /** Hold on a POI in Plan, a doubtful case (owner 2026-11-07): "Add as a stop · Make it the destination · Open in Google Maps". */
    @Test fun poiMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Rec, androidx.compose.ui.unit.DpOffset(110.dp, 400.dp)) {
                PoiMenu(com.onpath.app.poi.Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {},
                    menu = PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.ADD_STOP, PlaceAddMenu.Item.NEW_DESTINATION)))
            }
        }
    }

    /** A clear new goal (far beyond the route): only "Make it the destination", the stop waits under "More". */
    @Test fun poiMenuClearGoal() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(OnPathColors.Rec, androidx.compose.ui.unit.DpOffset(110.dp, 400.dp)) {
                PoiMenu(com.onpath.app.poi.Poi("n1", "hut", "Meraner Hütte", 46.68, 11.25, ele = 1960), onAdd = {}, onGoogle = {},
                    menu = PlaceAddMenu.Menu(listOf(PlaceAddMenu.Item.NEW_DESTINATION), listOf(PlaceAddMenu.Item.ADD_STOP)))
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
    fun sharedPlaceMenu() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(195.dp, 430.dp), ring = true) {
                PoiMenu(shared.toPoi(), onAdd = {}, onGoogle = {}, onRemovePin = {},
                    addLabel = com.onpath.app.search.AddPreview.label(
                        com.onpath.app.routing.WaypointInsertion.Decision(3, com.onpath.app.routing.WaypointInsertion.Kind.DESTINATION), 3))
            }
        }
    }

    /** A found place (search hit / shared place, owner 2026-11-02): the blue pin and its name chip "Gelateria Costantin ›". */
    @Test fun foundPlacePin() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(195.dp, 430.dp), ring = true) {}
        }
    }

    /** Shared place, empty plan: "Start here · Go here · Open in Google Maps". */
    fun sharedPlaceMenuEmptyPlan() = paparazzi.snapshot {
        OnPathTheme {
            OverlayFrame(null, androidx.compose.ui.unit.DpOffset(195.dp, 430.dp), empty = true, ring = true) {
                PoiMenu(shared.toPoi(), onAdd = {}, onGoogle = {}, emptyPlan = true, onRemovePin = {})
            }
        }
    }

    /**
     * Bug 02: a point just held far away – while its route is computed a ring turns around it on the map (the sheet
     * shows its thin progress line as before).
     */
    @Test fun planRoutingSpinnerAtNewPoint() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi(n = 4, routing = true), PlanCallbacks()) }
            val d = androidx.compose.ui.platform.LocalDensity.current
            val a = with(d) { androidx.compose.ui.geometry.Offset(150.dp.toPx(), 420.dp.toPx()) }
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawCircle(androidx.compose.ui.graphics.Color.White, 13.dp.toPx(), a)
                drawCircle(OnPathColors.Bad, 11.dp.toPx(), a)
            }
            RoutingRing(a, stillProgress = 0.3f)
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


    /**
     * After "Tour speichern" (owner 2026-11-06, onpath.private#35): the slim box "Tour gespeichert" – name, numbers, moving
     * time / breaks / planned vs. needed, "In Meine Touren ›", OK. No delete, no export.
     */
    fun savedTourBox() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) { MapPlaceholder(Modifier.fillMaxSize()) }
            SavedTourDialog(Tour(2, TourKind.RECORDED, "Algund → Meran", 1_760_200_000_000, "HIKE", 3, 12_800.0, 840.0, 830.0,
                3.55 * 3600, "done", emptyList()),
                com.onpath.app.time.TourTimes(172 * 60.0, 41 * 60.0, 190 * 60.0, 172 * 60.0, startMs = 1_793_527_920_000L,
                    endMs = 1_793_527_920_000L + 245 * 60_000L), {}, {})
        }
    }

    /** A recorded tour ended before its first point (owner 2026-10-30): nothing to follow – "Start recording · Hike". */
    fun trackWithoutLine() = paparazzi.snapshot {
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

    /**
     * Lines for My routes' small previews (owner 2026-11-07), put into the cache the rows read: a loop per tour, shaped by
     * its id; tour 4 (a demo without points) keeps the kind's sign – the quiet placeholder.
     */
    private fun previewLines(tours: List<Tour>) = tours.forEach { t ->
        RouteLineCache.put(t, if (t.id == 4L) emptyList() else listOf(sampleLine(t.id)))
    }

    private fun sampleLine(id: Long): List<com.onpath.app.routing.GeoPoint> = (0..80).map { i ->
        val a = i / 80.0 * 1.7 * Math.PI
        val r = 1 + 0.25 * Math.sin(3 * a + id)
        com.onpath.app.routing.GeoPoint(46.67 + 0.012 * r * Math.sin(a) * (1 + id % 3) / 2, 11.16 + 0.016 * r * Math.cos(a))
    }

    /** My routes, tab Planned (default): planned routes + imported GPX, Import GPX, per item ⋮ (Rename, Export GPX, Delete). */
    private val t0Recent = 1_761_000_000_000L

    /** The one plan kept by itself under Zuletzt (owner 2026-11-12: exactly one) – pale route sign, "Plan · date". */
    private val oneDraft = Tour(100L, TourKind.PLANNED, "Algund → Meran", t0Recent, "HIKE", 2, 2_000.0, 40.0, 30.0, 1800.0,
        "done", viaPts, draft = true)

    /**
     * My routes → Planned with "Zuletzt" (owner 2026-11-06 / 2026-11-12: exactly one row there): the plan on the map,
     * "Aktueller Plan · In Bearbeitung" with Speichern (owner 2026-11-06 23:00) – the draft kept before waits behind it,
     * unseen; then "Gespeichert" with one just saved.
     */
    @Test fun toursRecent() = paparazzi.snapshot {
        val live = LivePlan(name = null, points = 3, distanceM = 4_200.0, ascentM = 180.0, durationS = 4_500.0, linkedId = -1L,
            key = listOf(46.67 to 11.16, 46.68 to 11.15, 46.69 to 11.14), unsaved = true)
        previewLines(listOf(oneDraft) + tourList)
        RouteLineCache.put(Tour(LivePlanRows.LIVE_ID, TourKind.PLANNED, "", t0Recent, null, 0, 4_200.0, 180.0, 0.0, 4_500.0, "done",
            emptyList()), listOf(sampleLine(9)))
        OnPathTheme { RoutesScreen(listOf(oneDraft) + tourList, {}, {}, {}, {}, {}, justSavedId = 1L, live = live, liveNowMs = t0Recent,
            recentId = oneDraft.id) }
    }

    /**
     * The map empty: the one plan kept by itself is the Zuletzt row – pale route sign, "Plan · date", Planen · Speichern ·
     * Löschen on the card (owner 2026-11-07); then "Gespeichert".
     */
    @Test fun toursRecentDraft() = paparazzi.snapshot {
        previewLines(listOf(oneDraft) + tourList)
        OnPathTheme { RoutesScreen(listOf(oneDraft) + tourList, {}, {}, {}, {}, {}, recentId = oneDraft.id) }
    }

    /**
     * The map empty and the plan worked on last a saved route (owner 2026-11-12): it is the Zuletzt row with the small
     * "Gespeichert" tag and stays under Gespeichert, where "✓ Gerade gespeichert" says it was just saved from Zuletzt.
     */
    @Test fun toursRecentSaved() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, justSavedId = 1L, recentId = 1L) }
    }

    fun tours() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}) }
    }

    /** Five saved routes that come in a different order for every sort key (RouteSort). */
    private val sortList = listOf(
        Tour(11, TourKind.PLANNED, "Hirzer über Klammeben", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 1210.0, 6.1 * 3600, "done", viaPts),
        Tour(12, TourKind.PLANNED, "Ifinger Steig", 1_781_400_000_000, "HIKE", 4, 9_600.0, 1350.0, 1350.0, 5.2 * 3600, "done", viaPts),
        Tour(13, TourKind.PLANNED, "Etschradweg Meran – Bozen", 1_787_200_000_000, "BIKE_TOURING", 2, 31_500.0, 120.0, 330.0, 2.0 * 3600, "done", viaPts),
        Tour(14, TourKind.IMPORTED, "Waalweg Algund", 1_789_800_000_000, "HIKE", 1, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
        Tour(15, TourKind.PLANNED, "Vigiljoch MTB-Runde", 1_794_000_000_000, "MTB", 2, 22_400.0, 980.0, 980.0, 3.4 * 3600, "done", viaPts),
    )

    /**
     * My routes sorted (owner 2026-11-12, variant C): the chip row under the search, "Länge ↓" chosen and scrolled into
     * view with "Älteste zuerst" peeking in, the longest route first.
     */
    @Test fun routesSortedByLength() = paparazzi.snapshot {
        previewLines(sortList)
        OnPathTheme { RoutesScreen(sortList, {}, {}, {}, {}, {},
            initialSort = com.onpath.app.ui.routes.RouteSort.Choice(com.onpath.app.ui.routes.RouteSort.Key.LENGTH)) }
    }

    /** The same turned round (a second tap on "Länge"): the shortest first, the arrow up. */
    fun routesSortedShortestFirst() = paparazzi.snapshot {
        previewLines(sortList)
        OnPathTheme { RoutesScreen(sortList, {}, {}, {}, {}, {},
            initialSort = com.onpath.app.ui.routes.RouteSort.Choice(com.onpath.app.ui.routes.RouteSort.Key.LENGTH, biggestFirst = false)) }
    }

    /**
     * A row tapped in My routes (owner 2026-11-07 00:30): the large overview first – name, facts, the route fitted (here
     * the drawn shape; on the phone the map picture under it) – a planned route (owner 2026-11-07): "▶ Los geht's" full
     * width green, under it "Abbrechen" | "Planen".
     */
    @Test fun routesOverview() = paparazzi.snapshot {
        OnPathTheme { RouteOverview(tourList[0], listOf(sampleLine(1)), touring = false, snapshot = null, onPrimary = {},
            onCancel = {}, onGo = {}) }
    }

    /**
     * A recorded tour that followed a plan (owner 2026-11-07): the same foot as a planned route – "▶ Los geht's", under it
     * "Abbrechen" | "Planen" (both load the plan it followed) – and that plan's line thin in plan blue under the green track.
     */
    @Test fun routesOverviewDoneWithPlan() = paparazzi.snapshot {
        val track = sampleLine(2)
        // the plan: the same loop, a detour left out where the track went its own way
        val plan = track.mapIndexed { i, p -> if (i in 30..45) com.onpath.app.routing.GeoPoint(p.lat - 0.004, p.lon + 0.002) else p }
        OnPathTheme { RouteOverview(tourList[1].copy(hasPlan = true), listOf(track), touring = false, snapshot = null, onPrimary = {},
            onCancel = {}, onGo = {}, planLine = plan) }
    }

    /** The same during a tour: "Folgen" (green) – followed at once, the recording goes on (owner 2026-11-06 22:05). */
    fun routesOverviewTouring() = paparazzi.snapshot {
        OnPathTheme { RouteOverview(tourList[2], listOf(sampleLine(3)), touring = true, snapshot = null, onPrimary = {},
            onCancel = {}) }
    }

    /**
     * Planned tab after a sync: a route new from the browser (long name: it ellipsizes, the tag stays whole) and a
     * changed imported track – a "New" / "Changed" tag after the name until opened (RouteTag).
     */
    fun toursFromBrowser() = paparazzi.snapshot {
        val list = tourList.mapIndexed { i, t -> t.copy(uuid = "u-$i") } +
            Tour(5, TourKind.PLANNED, "Meran → Vellau → Leiter Alm → Hochganghaus", 1_760_600_000_000, "MTB", 2, 11_300.0, 690.0, 40.0, 1.9 * 3600, "done", viaPts, uuid = "u-new")
        previewLines(list)
        OnPathTheme {
            RoutesScreen(list.sortedByDescending { it.createdMs }, {}, {}, {}, {}, {},
                marks = mapOf("u-new" to com.onpath.app.plansync.SyncMark.NEW, "u-2" to com.onpath.app.plansync.SyncMark.CHANGED))
        }
    }

    /** My routes, tab Done: recorded tours (a demo tour too); no Import GPX here. */
    fun toursDone() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
    }

    /** Meine Touren with a search (owner 2026-11-06): only the matching tours of the tab. */
    fun routesSearch() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialQuery = tourList.first { it.kind != TourKind.RECORDED }.name.take(4)) }
    }

    /** A search that finds nothing: "Nichts mit „…“ hier". */
    fun routesSearchNothing() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialQuery = "Zugspitze") }
    }

    /** Planned tab with nothing planned yet (recorded tours exist). */
    fun toursEmptyPlanned() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList.filter { it.kind == TourKind.RECORDED }, {}, {}, {}, {}, {}) }
    }

    /** Done tab with nothing recorded yet. */
    fun toursEmptyDone() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList.filter { it.kind != TourKind.RECORDED }, {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
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
        OnPathTheme { Frame(route = false) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }; RestoreOfferDialog({}, {}) }
    }

    /**
     * A plan tapped in My routes during a tour is followed at once (owner 2026-11-06 22:05, no question any more):
     * Navigate says "New plan – your recording goes on" above the bottom bar, "Undo" brings the plan from before.
     */
    fun navigateNewPlan() = paparazzi.snapshot { NavFrame(navUi, newPlan = NewPlanNote(undo = true, id = 1)) }


    /** My routes → ⋮ → Rename: name dialog prefilled with the current name. */
    @Test fun routesRename() = paparazzi.snapshot {
        previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialRename = tourList[0]) }
    }

    /** Interrupted recording at start: Continue · Delete · Save like End tour. */
    fun unfinishedTour() = paparazzi.snapshot {
        OnPathTheme {
            Frame(route = false) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
            UnfinishedDialog(tourList[1], {}, {}, {})
        }
    }


    /** The sheet shows the save state instead of a message – a plain line (renamed in Save's dialog / My routes). */
    fun planSaved() = paparazzi.snapshot {
        Frame { PlanPanel(planUi().copy(savedName = "Route Meran – Hirzer"), PlanCallbacks()) }
    }

    /** Save (owner 2026-10-31: always asks): the name dialog over the sheet, the proposal selected – Cancel / Save. */
    fun planSaveDialog() = paparazzi.snapshot {
        OnPathTheme {
            Frame { PlanPanel(planUi().copy(unsaved = true), PlanCallbacks()) }
            SaveNameDialog(SaveAsk.Kind.NEW, "Vellau → Leiteralm", {}, {})
        }
    }

    /** My routes → ⋮ → Save as copy: the same dialog, titled "Save as copy", proposing "<name> (2)". */
    fun planSaveCopyDialog() = paparazzi.snapshot {
        OnPathTheme {
            RoutesScreen(tourList, {}, {}, {}, {}, {})
            SaveNameDialog(SaveAsk.Kind.COPY, "Route Meran – Hirzer (2)", {}, {})
        }
    }

    /**
     * My routes → ⋮ of a planned route (owner 2026-10-31): "Planen" (blue) and "Los geht's" (green) on top (owner
     * 2026-11-07), then Rename, Save as copy, Export GPX, Share plan, Delete.
     */
    @Test fun routesItemMenuPlanned() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            previewLines(tourList)
        OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}) }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 256.dp, end = 12.dp)) { // under the sort chips
                TourMenuItems(tourList.first { it.kind == com.onpath.app.data.TourKind.PLANNED }, {}, {}, {}, null, {}, {},
                    onPlan = {}, onGo = {}) {}
            } }
        }
    }

    /**
     * Gemacht → ⋮ of a recorded tour that followed a plan (owner 2026-11-07): "Planen" (blue) and "Los geht's" (green) on
     * top as for a saved planned route, then Rename, Export GPX, Edit rides, Delete – no "Als Plan übernehmen".
     */
    @Test fun routesItemMenuDoneWithPlan() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            previewLines(tourList)
            OnPathTheme { RoutesScreen(tourList, {}, {}, {}, {}, {}, initialTab = RoutesTab.DONE) }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 256.dp, end = 12.dp)) { // under the sort chips
                TourMenuItems(tourList[1].copy(hasPlan = true), {}, {}, {}, onEditRides = {}, onCopy = null, onShare = null,
                    onPlan = {}, onGo = {}) {}
            } }
        }
    }

    /** A new route, not saved yet (owner 2026-10-30): green "Save" beside ⋯ in the sheet's first line. */
    /** Unsaved (owner 2026-11-06, #41 B): "💾 Nicht gespeichert ›" beside "PLANEN · Ohne Netz ✓", no dot on ☰. */
    fun planUnsaved() = paparazzi.snapshot {
        Frame(menu = unsavedMenu, offline = OfflineReady.State.READY) { PlanPanel(planUi().copy(unsaved = true), PlanCallbacks()) }
    }

    /** The unsaved plan's ☰ menu: Save route on top (the sign's tap does the same). */
    private val unsavedMenu get() = PlanMenu(onUpdates = {}, onSaveRoute = {})

    /** A saved route changed since: "Save" beside ⋯ again, "… changed, not saved" below. */
    fun planChangedNotSaved() = paparazzi.snapshot {
        Frame(menu = unsavedMenu) { PlanPanel(planUi().copy(savedName = "Route Meran – Hirzer", unsaved = true), PlanCallbacks()) }
    }

    /** Save beside ⋯ on a long day hike: time and distance still in full (their own line when they do not fit). */
    /** Right after Pair (owner 2026-10-30): back on the Plan map, "Paired. …" with OK at the top until OK. */
    fun planPairedNote() = paparazzi.snapshot {
        Frame(top = { MessageCard(syncNoteMessage(SyncNote.PAIRED, onOk = {})) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Right after Unpair (owner 2026-10-30, as simple as pairing): back on the Plan map, "Unpaired. …" with OK. */
    fun planUnpairedNote() = paparazzi.snapshot {
        Frame(top = { MessageCard(syncNoteMessage(SyncNote.UNPAIRED, onOk = {})) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Unpaired, the server not reached: the same strip asks to unpair the browser there too. */
    fun planUnpairedOfflineNote() = paparazzi.snapshot {
        Frame(top = { MessageCard(syncNoteMessage(SyncNote.UNPAIRED_OFFLINE, onOk = {})) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** ☰ → Pair with browser, the first time (owner 2026-10-30): the one step line + Open camera, then the camera. */
    fun planPairHint() = paparazzi.snapshot {
        Frame(top = { MessageCard(pairHintMessage(onCamera = {}, onCancel = {})) }) { PlanPanel(planUi(), PlanCallbacks()) }
    }

    fun planUnsavedLongHike() = paparazzi.snapshot {
        Frame(menu = unsavedMenu, offline = OfflineReady.State.READY) { PlanPanel(planUi(r = route.copy(distanceM = 28_740.0, durationS = 10.4 * 3600)).copy(sac = 3, unsaved = true),
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
        Frame(top = { MessageCard(updateMessage(release.version, {}, {})) }) {
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

    /** What the map needs from the user shows at the top (a box, or the grey pill for a state), not as a card in the sheet. */
    @Composable
    private fun CardFrame(card: CellCardUi?, ui: PlanUi, gps: GpsStatus = GpsStatus.GOOD) =
        Frame(gps = gps, route = ui.route != null, top = {
            mapNotice(card)?.let { n -> mapNoticeMessage(n, CellCardCallbacks())?.let { MessageCard(it) } ?: MapNoticePill(n) }
        }) { PlanPanel(ui, PlanCallbacks()) }

    /**
     * The plan run reached its time limit (owner 2026-11-03, onpath.private#21 B): "This route is long and takes a while.
     * Keep waiting?" – Cancel · **Keep waiting** – the points stay, the line as it was.
     */
    fun planSlowAsk() = paparazzi.snapshot {
        Frame(top = { MessageCard(slowPlanMessage {}) }) { PlanPanel(planUi(4, null), PlanCallbacks()) }
    }

    /** The app opened where there is no detailed map – the box asks, nothing loads by itself. */
    @Test fun hereAsk() = paparazzi.snapshot {
        Frame(route = false, top = { MessageCard(hereAskMessage(21L * 1_048_576, {}, {})) }) { PlanPanel(planWithCard(n = 0), PlanCallbacks()) }
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

    /**
     * Zoomed in (z ≥ 10) where only the overview map exists: the paper pill "Nur Übersichtskarte · Detailkarte laden"
     * beside the round activity button – as MapScreen places it (OverviewPillPlace), covering nothing.
     */
    fun overviewHint() = paparazzi.snapshot {
        Box {
            Frame(route = false, gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
            OnPathTheme {
                val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    OverviewPill({}, Modifier.padding(top = OverviewPillPlace.topDp(rowTop, null).dp,
                        start = OverviewPillPlace.startDp(activityButtonShown = true).dp, end = OverviewPillPlace.EDGE_DP.dp))
                }
            }
        }
    }

    /**
     * The app switched relief off on a slow phone (map/ReliefAuto, owner 2026-11-07): the quiet line on the map
     * (vm.say → NoticeSurface.MAP_LINE), where Plan's GPS note stands – no box, goes by itself.
     */
    @Test fun planReliefOffNote() = planNote(Res.string.maps_relief_auto_off, androidx.compose.material.icons.Icons.Outlined.Info)

    /**
     * Plan's GPS note after a tap on locate without a position (PlanGps): beside the activity button, clear of its T badge
     * (it stood under the row and covered the badge once that grew, 2026-11-07) – the same place as the map's INFO line.
     */
    @Test fun planGpsNote() = planNote(Res.string.plan_searching_gps, androidx.compose.material.icons.Icons.Outlined.LocationSearching)

    /** The longest GPS note (two sentences, an edit during a tour without a position) at the same place. */
    @Test fun planGpsNoteLong() =
        planNote(Res.string.msg_route_back_no_position, androidx.compose.material.icons.Icons.Outlined.LocationSearching)

    /**
     * A recording ended with less than 50 m of track (owner 2026-11-12): no "Tour gespeichert" – the quiet line on the
     * Plan map "Kurze Aufzeichnung (12 m) – in den Papierkorb gelegt." with "Rückgängig" (MapScreen's INFO pill with its
     * action), over the empty plan the end leaves behind.
     */
    @Test fun planShortRecordingNote() = planNoteText(
        { str(Res.string.routes_short_recording_binned, com.onpath.app.data.Format.distance(12.0)) },
        androidx.compose.material.icons.Icons.Outlined.Info, { NoticeAction(str(Res.string.plan_undo)) {} }, planUi = planUi(0))

    /** A quiet note on the Plan map where MapScreen puts it (PlanGps.noteTopDp / noteStartDp), over a Hike T2 plan. */
    private fun planNote(text: org.jetbrains.compose.resources.StringResource, icon: androidx.compose.ui.graphics.vector.ImageVector) =
        planNoteText({ str(text) }, icon)

    private fun planNoteText(text: @Composable () -> String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                             action: @Composable () -> NoticeAction? = { null }, planUi: PlanUi = planUi()) =
        paparazzi.snapshot {
            Box {
                Frame { PlanPanel(planUi, PlanCallbacks()) }
                OnPathTheme {
                    val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
                    Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                        QuietPill(text(), icon, Modifier.padding(top = PlanGps.noteTopDp(rowTop, null).dp,
                            start = PlanGps.noteStartDp(activityButtonShown = true).dp, end = OverviewPillPlace.EDGE_DP.dp), action = action())
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
