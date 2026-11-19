package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The hold menu of a spot ([HoldAddOverlay], owner 2026-11-15) in German at the phone's largest font (2.0): the menu sits
 * beside the spot and its labels wrap rather than being cut – the arrangement at the largest font, so this one stays a
 * whole-screen picture. Same picture as the [ScreenshotTest] test of the same name, drawn with this class's Paparazzi.
 * The rows of the add menus per language and font (1.0 / 1.3 / 2.0) are in [PlaceMenusGalleryTest].
 */
class PlaceAddMenuLargeFontGermanTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun holdAddMenu() = shots.holdAddMenu()
}
