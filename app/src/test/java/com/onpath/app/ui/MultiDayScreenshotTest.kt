package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Multi-day tours on the phone (stage 4, sketch round 2 steps 9–12, symbols round 3 variant A): the plan sheet's overview
 * (title · N Tage, profile with day borders, one row per day, "Tag 4 starten"), a followed day's head ("Tag 4 von 6 ·
 * ‹ Alle Tage" + missing maps) and the maps question at the first opening. EN + DE (lengths).
 */
abstract class MultiDayScreenshotTest(private val locale: Locale) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val line = (0..300).map { i -> GeoPoint(47.5 - i * 0.0063, 11.6 + i * 0.002, 550.0 + 700 * sin(i / 40.0) + 300 * sin(i / 9.0)) }
    private val route = PlannedRoute(listOf(PlannedLeg(line, false)), 564_000.0, 6_480.0, 6_900.0, 34.5 * 3600, null)
    private val days = DaysUi("München – Venedig", 6, line,
        listOf(0.16f to DayEndKind.PROPOSAL, 0.33f to DayEndKind.HOTEL, 0.47f to DayEndKind.OVERNIGHT, 0.64f to DayEndKind.PROPOSAL,
            0.82f to DayEndKind.PROPOSAL),
        listOf(
            DayRowUi(1, "München – Bad Tölz", 92_000.0, 640.0, DayEndKind.PROPOSAL, false),
            DayRowUi(2, "Bad Tölz – Gasthof Krone", 96_000.0, 1_420.0, DayEndKind.HOTEL, true),
            DayRowUi(3, "Gasthof Krone – Brixen", 79_000.0, 980.0, DayEndKind.OVERNIGHT, false),
            DayRowUi(4, null, 95_000.0, 420.0, DayEndKind.PROPOSAL, false),
            DayRowUi(5, null, 101_000.0, 1_120.0, DayEndKind.PROPOSAL, false),
            DayRowUi(6, "Bassano – Venedig", 101_000.0, 220.0, null, false),
        ), selected = 4..4)

    private fun sheet(ui: PlanUi) = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) { PlanPanel(ui, PlanCallbacks()) }
            }
        }
    }

    @Test fun overview() = sheet(PlanUi(8, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
        size = PlanSize.COMPACT, savedName = "München – Venedig", days = days))

    @Test fun overviewNoDayPicked() = sheet(PlanUi(8, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
        size = PlanSize.COMPACT, savedName = "München – Venedig", days = days.copy(selected = null)))

    @Test fun dayFollowed() = sheet(PlanUi(3, route, false, null, canUndo = false, canRedo = false, activity = Activity.BIKE_TOURING,
        size = PlanSize.MINI, savedName = "München – Venedig",
        dayHead = DayHeadUi(com.onpath.strings.Txt.s(com.onpath.strings.Res.string.multiday_day_of, "4", "6"), true, 62_000_000L)))

    @Test fun mapsQuestion() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.fillMaxWidth().padding(12.dp, 80.dp)) {
                    MessageCard(tourMapsMessage(com.onpath.app.ui.plan.MultiDaySlice.TourMapsAsk("u", 340_000_000L, 1, 62_000_000L, onWifi = false,
                        whole = listOf(com.onpath.app.cells.CellId(1, 1)), dayCells = listOf(com.onpath.app.cells.CellId(1, 1)),
                        line = emptyList(), dayLine = emptyList())) {})
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
