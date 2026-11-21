package com.onpath.app.ui.dayrows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.data.Format
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.ui.DayRowUi
import com.onpath.app.ui.DaysUi
import com.onpath.app.ui.LocalDayRowSketch
import com.onpath.app.ui.MapPlaceholder
import com.onpath.app.ui.OnPathTheme
import com.onpath.app.ui.PlanCallbacks
import com.onpath.app.ui.PlanPanel
import com.onpath.app.ui.PlanSize
import com.onpath.app.ui.PlanUi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.sin

/**
 * SKETCH pictures for the owner (2026-11-21): the real plan sheet with the multi-day overview, "today" = the row as it is
 * (a missing place shows as the running km), A and B = the new rows. German, normal font and font 2.0.
 */
abstract class DayRowsSketchShots(private val fontScale: Float, dpi: Int, heightPx: Int, private val screenHeightDp: Int) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 390 * dpi / 160, screenHeight = heightPx, xdpi = dpi, ydpi = dpi, density = Density.create(dpi), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val line = (0..300).map { i -> GeoPoint(47.5 - i * 0.0063, 11.6 + i * 0.002, 550.0 + 700 * sin(i / 40.0) + 300 * sin(i / 9.0)) }
    private val route = PlannedRoute(listOf(PlannedLeg(line, false)), 564_000.0, 6_480.0, 6_900.0, 34.5 * 3600, null)

    /** Today's rows: a missing place = the running km, as MultiDaySlice does it. */
    private fun todayRows(): List<DayRowUi> {
        var at = 0.0
        return sketchDays.map { d ->
            val from = d.from ?: Format.distance(at); at += d.distanceM
            val to = d.to ?: Format.distance(at)
            DayRowUi(d.n, from, to, d.distanceM, d.ascentM, d.end, d.over)
        }
    }

    private fun days() = DaysUi("München – Venedig", 6, line,
        listOf(0.16f to DayEndKind.PROPOSAL, 0.33f to DayEndKind.HOTEL, 0.47f to DayEndKind.OVERNIGHT, 0.64f to DayEndKind.PROPOSAL, 0.82f to DayEndKind.PROPOSAL),
        todayRows(), selected = 4..4)

    internal fun shot(v: Variant) {
        rowHeights.clear()
        paparazzi.snapshot {
            OnPathTheme {
                // the sheet is 62 % of the screen height (FULL): the stated height makes it the size that shows all day rows
                val conf = android.content.res.Configuration(LocalConfiguration.current).apply { screenHeightDp = this@DayRowsSketchShots.screenHeightDp }
                CompositionLocalProvider(LocalDayRowSketch provides rowFor(v), LocalConfiguration provides conf) {
                    Box(Modifier.fillMaxSize()) {
                        MapPlaceholder(Modifier.fillMaxSize())
                        Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                            PlanPanel(PlanUi(8, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
                                size = PlanSize.FULL, savedName = "München – Venedig", days = days()), PlanCallbacks())
                        }
                    }
                }
            }
        }
        // the measured heights (dp) for the report
        File("/tmp/dayrows-heights.txt").appendText("font=$fontScale $v " + rowHeights.toSortedMap().entries.joinToString(" ") { "d${it.key}=${"%.1f".format(Locale.ROOT, it.value)}" } + "\n")
    }
}

class DayRowsSketchGermanTest : DayRowsSketchShots(1.0f, 200, 960, 998) {
    @Test fun today() = shot(Variant.TODAY)
    @Test fun a_valuesRight() = shot(Variant.A)
    @Test fun b_valuesBeside() = shot(Variant.B)
}

class DayRowsSketchLargeFontGermanTest : DayRowsSketchShots(2.0f, 140, 990, 1668) {
    @Test fun today() = shot(Variant.TODAY)
    @Test fun a_valuesRight() = shot(Variant.A)
    @Test fun b_valuesBeside() = shot(Variant.B)
}
