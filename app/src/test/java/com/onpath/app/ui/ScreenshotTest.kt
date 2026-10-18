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
                       size: PlanSize = PlanSize.COMPACT, tourRunning: Boolean = false, names: List<String?> = emptyList()) =
        PlanUi(n, r, routing, error, canUndo = n > 0, canRedo = false, activity = Activity.HIKE, size = size, tourRunning = tourRunning,
            stopNames = names)

    @Composable
    private fun Footer(gps: GpsStatus = GpsStatus.GOOD, following: Boolean = false) =
        MapFooter("500 m" to 64.dp) { LocateButton(gps, following, onClick = {}) }

    /** Plan mode: search bar (⋮ menu), activity chip, footer, then [bottom]. Top padding stands in for the status bar. */
    @Composable
    private fun Frame(gps: GpsStatus = GpsStatus.GOOD, aboveFooter: @Composable () -> Unit = {}, bottom: @Composable () -> Unit) {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar(Activity.HIKE, 3, {}, {}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        aboveFooter()
                        Footer(gps)
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
                    NavigateOverlay(ui, footer = { Footer(following = true) }, onSlideToPlan = {}, slideStartFraction = slide)
                }
            }
        }
    }

    private val navUi = NavUi(toGo = ToGo(7_420.0, 512.0, 2.9 * 3600, 12.0), recordedM = 5_320.0, elapsedS = 5025)

    /** App start without a plan: Plan is home; no points, Start navigation = free recording. GPS searching. */
    @Test fun planEmpty() = paparazzi.snapshot {
        Frame(gps = GpsStatus.SEARCHING) { PlanPanel(planUi(n = 0, r = null), PlanCallbacks()) }
    }

    @Test fun plan() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(), PlanCallbacks()) }
    }

    /** Plan with a named route: stop chips use the search / POI names. */
    @Test fun planNamedStops() = paparazzi.snapshot {
        Frame { PlanPanel(planUi(n = 3, names = listOf("Algund", "Meraner Hütte", null)), PlanCallbacks()) }
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

    /** The ⋮ menu of the search bar: My tours, Import GPX, Settings, Updates. */
    @Test fun planMenuOpen() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize()) {
            Frame { PlanPanel(planUi(), PlanCallbacks()) }
            OnPathTheme { MenuCard(Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp)) { PlanMenuItems(PlanMenu(onUpdates = {})) {} } }
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

    /** Slider dragged half way (springs back on release) and the off-route chip. */
    @Test fun navigateSlideHalf() = paparazzi.snapshot { NavFrame(navUi.copy(offRouteM = 63), slide = 0.5f) }

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

    @Test fun planNotice() = paparazzi.snapshot {
        Frame {
            PlanNoticeBar(PlanNotice("Added as stop 2 of 4", "Make it the destination", NoticeAction.MAKE_DESTINATION), {}, {})
            PlanPanel(planUi(4), PlanCallbacks())
        }
    }

    @Test fun pointMenu() = paparazzi.snapshot {
        OnPathTheme { PointMenuDialog(1, 4, {}, {}, {}, {}) }
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

    @Test fun tours() = paparazzi.snapshot {
        OnPathTheme {
            ToursScreen(
                listOf(
                    Tour(1, TourKind.PLANNED, "Route Meran – Hirzer", 1_760_000_000_000, "HIKE", 3, 14_200.0, 1210.0, 300.0, 6.1 * 3600, "done", emptyList()),
                    Tour(2, TourKind.RECORDED, "Tour 12 Oct 2026, 09:14", 1_760_200_000_000, "MTB", 3, 32_800.0, 940.0, 940.0, 3.2 * 3600, "done", emptyList()),
                    Tour(3, TourKind.IMPORTED, "Komoot – Waalweg Algund", 1_760_400_000_000, "HIKE", 3, 8_100.0, 210.0, 190.0, 2.1 * 3600, "done", emptyList()),
                ),
                {}, {}, {}, {}, {},
            )
        }
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
