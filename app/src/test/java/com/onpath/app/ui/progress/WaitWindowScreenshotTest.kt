package com.onpath.app.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.domain.WaitSteps
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import com.onpath.app.ui.ActivityMapButton
import com.onpath.app.ui.CompassSpec
import com.onpath.app.ui.FirstMapCard
import com.onpath.app.ui.FirstMapCardView
import com.onpath.app.ui.MapControls
import com.onpath.app.ui.MapFooter
import com.onpath.app.ui.MapLoadCallbacks
import com.onpath.app.ui.MapLoadPanel
import com.onpath.app.ui.MapLoadUi
import com.onpath.app.ui.MapPlaceholder
import com.onpath.app.ui.MessagePlacement
import com.onpath.app.ui.MessageScreen
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.OverviewPillPlace
import com.onpath.app.ui.PlanCallbacks
import com.onpath.app.ui.PlanControlsLayer
import com.onpath.app.ui.PlanMenu
import com.onpath.app.ui.PlanPanel
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.PlanTopBand
import com.onpath.app.ui.PlanTopBar
import com.onpath.app.ui.PlanUi
import com.onpath.app.ui.SheetWithFooter
import com.onpath.app.ui.planTopBarDp
import com.onpath.strings.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Form A, the wait window (owner 2026-11-19 „A, aber nie mehr als 3 Häkchen“) as it is built: one frame over the plan
 * screen – the first map of the area while the server prepares it, the map + routing data loading (one row, never a 4th),
 * the map joined in, a route's legs (a moving window of 3, „prüfen“ last); offline / Wi-Fi / failed in the same frame;
 * the first map offline stays the small card. EN, DE, DE at the largest font (2.0).
 */
abstract class WaitWindowShots(private val locale: Locale, fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val mb = 1_048_576L

    /** The first map of the area: the server prepares it (12 %, its time left). */
    @Test fun mapPreparing() = map(MapLoadUi(MapLoadUi.Kind.PREPARING, 12, 180, regions = 1, bytes = 9 * mb))

    /** The map and the routing data load (34 %): one row „lädt Karte + Routendaten“, never a 4th. */
    @Test fun mapLoading() = map(MapLoadUi(MapLoadUi.Kind.LOADING, 34, 20, regions = 2, bytes = 27 * mb, routing = true))

    /** Joined into the map: „fast fertig“, the bar on at 91 % – never standing. */
    @Test fun mapFinishing() = map(MapLoadUi(MapLoadUi.Kind.FINISHING, 91, 8, regions = 2, bytes = 27 * mb, routing = true))

    /** A route of 4 legs: the 3rd runs – the last done, the running one, the next; „prüfen“ comes as the last window. */
    @Test fun route() = shot(points = 5) { Window(routeUi.window, listOf(WaitButton(str(Res.string.common_cancel)) {})) }

    /** The round-trip search (owner 2026-11-19, form A): its three steps – reading done, loops being found –, Cancel. */
    @Test fun roundTrips() = shot(points = 0) {
        val ui = SuggestWaitUi(48, WaitSteps.of(listOf(Res.string.wait_step_suggest_read, Res.string.wait_step_suggest_search,
            Res.string.wait_step_suggest_judge).map { Txt.s(it) to null }, 1), 5)
        Window(ui.window, listOf(WaitButton(str(Res.string.common_cancel)) {}))
    }

    /** Offline: the bar stands grey with its number, the line says why; Cancel. */
    @Test fun offline() = map(MapLoadUi(MapLoadUi.Kind.OFFLINE, 47, null, regions = 2, bytes = 18 * mb))

    /** "Maps only on Wi-Fi": the bar stands, Cancel · load once over mobile data (the setting stays). */
    @Test fun waitingForWifi() = map(MapLoadUi(MapLoadUi.Kind.WIFI, 30, null, regions = 2, bytes = 17 * mb))

    /** Failed: plain words, Close · Try again – no bar. */
    @Test fun failed() = map(MapLoadUi(MapLoadUi.Kind.FAILED, 12, null, Txt.s(Res.string.maps_load_failed_retry), retryable = true))

    /** The first map cannot load without network: the small card on the map (nothing to wait for). */
    @Test fun firstMapOffline() = shot(points = 0, dim = false) {
        val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
        FirstMapCardView(FirstMapCard.Ui.Offline, Modifier.padding(top = (32 + OverviewPillPlace.topDp(rowTop, null)).dp,
            start = OverviewPillPlace.startDp(activityButtonShown = true).dp, end = OverviewPillPlace.EDGE_DP.dp))
    }

    private val routeUi: RouteWaitUi get() {
        val names = listOf("Algund → Stopp 2", "Stopp 2 → Leiter Alm", "Leiter Alm → Vellau", "Vellau → Meran")
        return RouteWaitUi(55, WaitSteps.of(names.map { it to null } + (Txt.s(Res.string.wait_step_check) to null), 2), 10)
    }

    /** Never more than 3 rows: the window moves along the legs. */
    @Test fun threeRowsAtMost() {
        assertEquals(listOf("Stopp 2 → Leiter Alm", "Leiter Alm → Vellau", "Vellau → Meran"), WaitSteps.shown(routeUi.steps).map { it.label })
        assertEquals(3, MapLoadUi(MapLoadUi.Kind.LOADING, 34, 20, routing = true).steps.size)
        assertEquals(2, MapLoadUi(MapLoadUi.Kind.LOADING, 34, 20, regions = 0, routing = true).steps.size)
    }

    private fun map(ui: MapLoadUi) = shot(points = 0) { Window(ui) }

    @Composable private fun Window(ui: MapLoadUi) = Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        MapLoadPanel(ui, MapLoadCallbacks())
    }

    @Composable private fun Window(ui: WaitWindowUi, buttons: List<WaitButton>) =
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { WaitWindowView(ui, buttons) }

    /** The plan screen (as the app draws it, the map a placeholder), dimmed as Android's dialog does, then [over]. */
    private fun shot(points: Int, dim: Boolean = true, over: @Composable () -> Unit) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().background(PlanTopBand.Color).padding(top = 32.dp).height(planTopBarDp().dp))
                Box(Modifier.fillMaxSize().padding(top = 32.dp)) {
                    PlanTopBar({}, PlanMenu(onUpdates = {}), Modifier.fillMaxWidth())
                    val rowTop = MessagePlacement.topDp(MessageScreen.PLAN, planTopDp = planTopBarDp())
                    ActivityMapButton(Activity.HIKE, 2, {}, Modifier.padding(top = rowTop.dp, start = 12.dp))
                    val sheetHeight = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    PlanControlsLayer({ sheetHeight.intValue }, planTopBarDp().dp) {
                        MapControls(CompassSpec(null, 0f) {}, showEye = false, onPeek = {}, gps = GpsStatus.GOOD, following = true,
                            onLocate = {}, onFitRoute = null, plan = true)
                    }
                    SheetWithFooter(planTopBarDp().dp, footer = { Column(Modifier.fillMaxWidth()) { MapFooter("500 m" to 64.dp) {} } },
                        sheet = { Column(Modifier.fillMaxWidth().onSizeChanged { sheetHeight.intValue = it.height }) {
                            PlanPanel(PlanUi(points, null, points >= 2, null, canUndo = points > 0, canRedo = false,
                                activity = Activity.HIKE, size = PlanSize.COMPACT), PlanCallbacks())
                        } })
                }
                if (dim) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                over()
            }
        }
    }
}

class WaitWindowGermanTest : WaitWindowShots(Locale.GERMANY, 1f)
class WaitWindowLargeFontGermanTest : WaitWindowShots(Locale.GERMANY, 2f)
