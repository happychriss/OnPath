package com.onpath.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.data.AppSettings
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * ⚙ on the way, V1 (Chriss 2026-11-28 10:59; item 2026-11-28-settings-on-the-way): every row that left the list is still
 * on the Navigation page / in the topic list behind „Alle Einstellungen“ and still changes its setting; the list's own
 * part fits a Pixel 7 without scrolling at the default font.
 */
class SettingsOnTheWayV1Test {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420)),
        showSystemUi = false,
    )
    private fun src(p: String) = File("src/main/java/com/onpath/app/$p").readText()

    @Test fun everyRowThatLeftIsStillReachableAndWorks() {
        val nav = src("ui/SettingsScreen.kt").substringAfter("fun NavigationSection(").substringBefore("\n}\n")
        for (change in listOf("settings.copy(offRouteM = it)", "settings.copy(offRouteVibrate = it)", "settings.copy(positionOnRoute = it)",
                "settings.copy(keepScreenOn = it)", "BikeScreenRow(settings, onChange)", "HoldTimeRow(settings, onChange)",
                "settings_nav_voice_test", "JunctionGuideRow(settings, onChange)", "settings.copy(offRouteWarning = it)", "settings.copy(batterySave = it)"))
            assertTrue("the Navigation page still has $change", nav.contains(change))
        // „Alle Einstellungen“ on the way opens the whole topic list: Navigation is in it, Dein Tempo opens from Plan,
        // Offline-Karten from Karte
        val all = SettingsSummary.groups(SettingsPage.entries.toList(), null).flatMap { it.second }
        assertTrue(SettingsPage.NAVIGATION in all)
        assertTrue(SettingsPage.PACE.landing in all)
        assertTrue(SettingsPage.OFFLINE_MAPS.parent in all)
        assertTrue(src("ui/SettingsScreen.kt").contains("AllSettingsRow(onAllSettings)"))
    }

    /** Captain 2026-11-28: always exactly one screen row – the one of the tour's activity, also on a recording without a route. */
    @Test fun alwaysOneScreenRowTheToursOne() {
        for (a in Activity.entries) assertEquals("$a", if (a == Activity.HIKE) ScreenRow.HIKE else ScreenRow.BIKE, ScreenRow.of(a))
        // a followed route: its activity wins over the app's; a recording without a route: the app's (it was started with it)
        assertEquals(Activity.BIKE_TOURING, tourActivity(Activity.BIKE_TOURING, Activity.HIKE))
        assertEquals(Activity.MTB, tourActivity(null, Activity.MTB))
        // the screen gets the same activity as Navigate's card
        assertTrue(src("MainActivity.kt").contains("activity = com.onpath.app.ui.tourActivity(vm.recState.collectAsState().value?.nav?.session?.activity, vm.planRoute.activity)"))
        assertTrue(src("ui/NavigateScreen.kt").contains("activity = rec?.nav?.session?.activity ?: vm.planRoute.activity"))
        val section = src("ui/SettingsOnTheWay.kt").substringAfter("fun OnTheWaySection(")
        assertTrue("one row or the other, never both, never none", section.contains("if (ScreenRow.of(activity) == ScreenRow.HIKE) SwitchRow(") &&
            section.contains("} else BikeScreenRow(settings, onChange)"))
    }

    @Test fun theListFitsAPixel7WithoutScrolling() {
        var h = 0
        val before = Locale.getDefault(); Locale.setDefault(Locale.GERMANY)
        try {
            paparazzi.snapshot { OnPathTheme {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).onSizeChanged { h = it.height }) {
                    OnTheWaySection(AppSettings(), {}, Activity.HIKE, onPhoneSetup = {})
                }
            } }
        } finally { Locale.setDefault(before) }
        val dp = h / 2.625f
        // the top bar (64), the „Auch unterwegs“ header (32), Karte + Interessante Orte (2 × 64), Alle Einstellungen (56),
        // the gaps (3 × 12) – on a Pixel 7: 915 dp minus the status and navigation bars (24 + 48)
        val rest = 64 + 32 + 2 * 64 + 56 + 36
        assertTrue("the on-the-way rows take $dp dp, the whole list ${dp + rest} dp of 843", dp + rest <= 915 - 24 - 48)
    }
}
