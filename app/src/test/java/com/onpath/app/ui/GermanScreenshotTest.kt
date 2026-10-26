package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * A few screens in German (owner 2026-10-26, EN + DE) to see that the longer German texts fit: plan sheet, route
 * hints, activity picker, Navigate, My routes, settings. Same pictures as the English tests of the same name – the
 * test body is borrowed from there and drawn with this class's Paparazzi under a German default locale.
 */
class GermanScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420)),
        showSystemUi = false,
    )

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** [t] drawing into this class's Paparazzi (its own rule is not running). */
    private fun <T : Any> borrow(t: T): T = t.also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    private val shots get() = borrow(ScreenshotTest())

    @Test fun plan() = shots.plan()
    @Test fun planPointsFull() = shots.planPointsFull()
    @Test fun planFerryHint() = shots.planFerryHint()
    @Test fun routeSpotsListHints() = shots.routeSpotsListHints()
    @Test fun planMenuOpen() = shots.planMenuOpen()
    @Test fun planChipLongBikeTour() = shots.planChipLongBikeTour()
    @Test fun navigate() = shots.navigate()
    @Test fun navigateRerouteQuestion() = shots.navigateRerouteQuestion()
    @Test fun arrival() = shots.arrival()
    @Test fun tours() = shots.tours()
    @Test fun unfinishedTour() = shots.unfinishedTour()
    @Test fun pickerHikeT2() = borrow(ActivityPickerScreenshotTest()).pickerHikeT2()
    @Test fun pickerMtb() = borrow(ActivityPickerScreenshotTest()).pickerMtb()
    @Test fun settingsList() = borrow(SettingsScreenshotTest()).list()
    @Test fun settingsPace() = borrow(SettingsScreenshotTest()).pace()
    @Test fun settingsOfflineMaps() = borrow(SettingsScreenshotTest()).offlineMaps()
}
