package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.sin
import com.onpath.strings.multiday_day_of

/**
 * Multi-day tours on the phone (docs/design/multi-day-tours.md §0, owner 2026-11-28; symbols round 3 variant A): a followed
 * day's head („Tag 4 von 6 · ‹ Alle Tage“, MD-2), its line when start or goal moved (MD-4), the map loading window while the
 * maps of all days load (MD-11), the day-end signs. German, at the normal and the largest font (2.0).
 */
abstract class MultiDayScreenshotTest(private val locale: Locale, fontScale: Float = 1.0f) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
            fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val line = (0..300).map { i -> GeoPoint(47.5 - i * 0.0063, 11.6 + i * 0.002, 550.0 + 700 * sin(i / 40.0) + 300 * sin(i / 9.0)) }
    private val route = PlannedRoute(listOf(PlannedLeg(line, false)), 96_000.0, 1_420.0, 1_100.0, 7.9 * 3600, null)
    private val label get() = com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_day_of, "4", "6")

    private fun sheet(ui: PlanUi) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) { PlanPanel(ui, PlanCallbacks()) }
            }
        }
    }

    /** The day picked in My routes is the plan: „Tag 4 von 6 · ‹ Alle Tage“ (MD-2). */
    @Test fun dayFollowed() = sheet(PlanUi(3, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
        size = PlanSize.MINI, savedName = "München – Venedig", dayHead = DayHeadUi(label, true, canPrev = true, canNext = true)))

    /** The day's goal moved (MD-4): the line before saving. */
    @Test fun dayLeavesTheTrip() = sheet(PlanUi(3, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
        size = PlanSize.MINI, savedName = "München – Venedig", unsaved = true, dayHead = DayHeadUi(label, true, leavesTrip = true)))

    /** MD-11: the normal map loading window while the maps of all days load, with its multi-day line. */
    @Test fun allMapsLoading() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                    MapLoadPanel(MapLoadUi(MapLoadUi.Kind.LOADING, 34, 140, regions = 9, bytes = 340_000_000L, routing = true, regionsDone = 2,
                        here = false, multiDay = true), MapLoadCallbacks())
                }
            }
        }
    }

    @Test fun signs() = paparazzi.snapshot {
        OnPathTheme {
            androidx.compose.foundation.layout.Row(Modifier.padding(16.dp)) {
                for (k in listOf(DayEndKind.PROPOSAL, DayEndKind.OVERNIGHT, DayEndKind.HOTEL, null))
                    Box(Modifier.padding(8.dp)) { DayEndSign(k, 44.dp) }
            }
        }
    }
}

class MultiDayGermanTest : MultiDayScreenshotTest(Locale.GERMANY)
class MultiDayLargeFontGermanTest : MultiDayScreenshotTest(Locale.GERMANY, 2.0f)
