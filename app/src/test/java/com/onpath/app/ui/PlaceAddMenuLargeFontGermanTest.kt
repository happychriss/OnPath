package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The held place's and the held spot's add menus in German at the phone's largest font (2.0). These two stay whole-screen
 * pictures for the PLACEMENT at the largest font: holdAddMenu = the menu beside the ring ([HoldAddOverlay]); poiMenu = the
 * tall menu that fits neither above nor below the held spot and is clamped to the top bound ([AnchoredOverlay]). Same
 * pictures as the [ScreenshotTest] scenes of the same name, drawn with this class's Paparazzi. The rows' text fit per
 * language and font (1.0 / 1.3 / 2.0) is in [PlaceMenusGalleryTest].
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

    @Test fun poiMenu() = shots.poiMenu()
    @Test fun holdAddMenu() = shots.holdAddMenu()
}
