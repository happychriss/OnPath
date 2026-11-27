package com.onpath.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.DayEndKind
import com.onpath.app.routing.DayLimitKind
import com.onpath.app.routing.DayLimits
import com.onpath.app.routing.GeoPoint
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.sin

/**
 * The app's multi-day day table like the planner's (owner 2026-11-27, `mehrtage-tabelle.png`; night plan stage 7) and the
 * multi-day panel's parts (head with Speichern + save state + „Gesamte Reise“, „Planung“ + chips, „Höhenprofil · gesamte
 * Reise“ with the day borders, „Höhenprofil · Tag N“ only that day). German; a Pixel 5 (393 dp), a small phone (360 dp) and the
 * small phone at the largest font (2.0). [tableOneRow] = the developer switch's layout (all five columns in one row) – at 360 dp
 * it squeezes „Tag N“ / „von → nach“, which is why Σ km goes under km by default (night answers 27./28.11.).
 */
abstract class DayTableScreenshotTest(fontScale: Float, widthPx: Int) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale, screenWidth = widthPx, screenHeight = 2600),
        showSystemUi = false)
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val days = listOf(
        Triple("Hilden" to "Bonn", 87_100.0, 36.0), Triple("Bonn" to "Koblenz", 85_200.0, 44.0), Triple("Koblenz" to "Bingen", 82_000.0, 159.0),
        Triple("Bingen" to "Worms", 87_900.0, 18.0), Triple("Worms" to "Speyer", 69_700.0, 612.0), Triple("Speyer" to "Rastatt", 73_900.0, 403.0),
        Triple("Rastatt" to "Kehl", 80_600.0, 262.0), Triple("Kehl" to "Breisach", 79_700.0, 249.0),
    )
    private val table by lazy { DayTables.of(days.mapIndexed { i, (_, km, up) ->
        val n = i + 1
        DayTables.day(n, km, up, if (n == 5) 5 * 3600.0 + 5 * 60 else 4 * 3600.0 + 10 * 60,
            when (n) { 8 -> null; 3 -> DayEndKind.HOTEL; 7 -> DayEndKind.PROPOSAL; else -> DayEndKind.OVERNIGHT },
            if (n == 5) setOf(DayLimitKind.TIME, DayLimitKind.ASCENT) else emptySet())
    }, days.map { (p, _, _) -> com.onpath.app.routing.DayRowText.places(p.first, p.second) }) }

    private val line = (0..300).map { i -> GeoPoint(51.17 - i * 0.0101, 6.94 + i * 0.0055, 80.0 + 200 * sin(i / 30.0) + 60 * sin(i / 7.0)) }
    private val daysUi by lazy { DaysUi("Rhein-Radweg in 8 Tagen", 8, line, (1..7).map { it / 8f to DayEndKind.OVERNIGHT }, emptyList(), null, table,
        DayLimits(270, 500)) }

    private fun shot(content: @androidx.compose.runtime.Composable () -> Unit) = paparazzi.snapshot {
        OnPathTheme { Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) { content() } }
    }

    @Test fun table() = shot { DayTableView(table, oneRow = false, picked = { it == 2 }, onTap = {}) }

    @Test fun tableOneRow() = shot { DayTableView(table, oneRow = true, picked = { it == 2 }, onTap = {}) }

    @Test fun panel() = shot {
        MultiDayHead(MultiDayPanel.head("Rhein-Radweg in 8 Tagen", "Rhein-Radweg in 8 Tagen", true, 646_100.0, 34 * 3600.0 + 20 * 60, 1_783.0, 1_650.0),
            onSave = {})
        PlanningChips(MultiDayPanel.planning(8, daysUi.limits))
        DayTableView(table, Modifier.padding(top = 6.dp), oneRow = false, onTap = {})
        TripProfile(daysUi, null, onHighlight = {})
        TripProfile(daysUi, 3, onHighlight = {})
    }

    /** The plan sheet's drop-in (MultiDayOverview): Planung + chips, the table, day 3 picked → „Höhenprofil · Tag 3“ only. */
    @Test fun overviewDayPicked() = shot {
        MultiDayOverview(daysUi.copy(selected = 3..3), onTap = {}, onHold = {}, onHighlight = {})
    }

    @Test fun headNotSaved() = shot {
        MultiDayHead(MultiDayPanel.head(null, null, false, null, 0.0, 0.0, 0.0), onSave = null)
    }
}

/** Pixel 5 width (1080 px = 393 dp at 2.75). */
class DayTableGermanTest : DayTableScreenshotTest(1.0f, 1080)
/** A small phone: 990 px = 360 dp. */
class DayTableNarrowGermanTest : DayTableScreenshotTest(1.0f, 990)
class DayTableNarrowLargeFontGermanTest : DayTableScreenshotTest(2.0f, 990)
