package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.recording.GpsStatus
import com.onpath.app.routing.Activity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The first-map card (owner 2026-11-08, sketch A2) in Plan right after the phone setup: "Karte wird geladen" with the
 * thin bar and "34 % · ca. 18 MB", offline "Noch keine Karte · Lädt, sobald du online bist". At font 2.0 the bar and
 * "34 %" share a line (no MB) and the card stays clear of the plan sheet. EN, DE, DE at the largest font.
 */
abstract class FirstMapCardScreenshotTest(private val locale: Locale, fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    @Test fun loading() = frame(FirstMapCard.Ui.Loading(34, 18L * 1_048_576))
    @Test fun offline() = frame(FirstMapCard.Ui.Offline)

    /** Plan with an empty plan, as ScreenshotTest's Frame (map = placeholder); the card where MapScreen puts it. */
    private fun frame(ui: FirstMapCard.Ui) = paparazzi.snapshot {
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
                            PlanPanel(PlanUi(0, null, false, null, canUndo = false, canRedo = false, activity = Activity.HIKE,
                                size = PlanSize.COMPACT), PlanCallbacks())
                        } })
                    FirstMapCardView(ui, Modifier.padding(top = OverviewPillPlace.topDp(rowTop, null).dp,
                        start = OverviewPillPlace.startDp(activityButtonShown = true).dp, end = OverviewPillPlace.EDGE_DP.dp))
                }
            }
        }
    }
}

class FirstMapCardEnglishTest : FirstMapCardScreenshotTest(Locale.US, 1f)
class FirstMapCardGermanTest : FirstMapCardScreenshotTest(Locale.GERMANY, 1f)
class FirstMapCardLargeFontGermanTest : FirstMapCardScreenshotTest(Locale.GERMANY, 2f)
