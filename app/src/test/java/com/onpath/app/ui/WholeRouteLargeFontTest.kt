package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * "Show whole route" at the phone's largest font (2.0) on a normal phone: the map buttons (Plan: five in the pill;
 * Navigate: five big ones) still fit between the top elements and the sheet / slider. Same pictures as the
 * [ScreenshotTest] tests of the same name, drawn with this class's Paparazzi.
 */
open class WholeRouteLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun planWholeRoute() = shots.planWholeRoute()
    @Test fun navigateWholeRoute() = shots.navigateWholeRoute()
}

/** The same in German. */
class WholeRouteLargeFontGermanTest : WholeRouteLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
