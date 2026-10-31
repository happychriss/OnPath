package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * My routes with the "New" / "Changed" tags (RouteTag) at the phone's largest font (2.0), English and German: the tag
 * is never cut – beside the name while the name keeps a fair share of the row, else under it. Same picture as
 * [ScreenshotTest.toursFromBrowser], drawn with this class's Paparazzi.
 */
open class RouteTagLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f, screenHeight = 3600), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun toursFromBrowser() = shots.toursFromBrowser()
}

/** The same in German ("Geändert" is the longer tag). */
class RouteTagLargeFontGermanTest : RouteTagLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
