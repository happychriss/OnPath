package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * My routes' sort chips (owner 2026-11-12) at the phone's largest font (2.0): the chosen chip is in view, no chip label is
 * cut or broken, the row scrolls sideways. Same pictures as [ScreenshotTest.routesSortedByLength], drawn with this
 * class's Paparazzi; only the German frames (the longer words), as [RouteTagLargeFontTest].
 */
abstract class RouteSortLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f, screenHeight = 3600), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun routesSortedByLength() = shots.routesSortedByLength()
    @Test fun routesSortedShortestFirst() = shots.routesSortedShortestFirst()
    @Test fun tours() = shots.tours()
}

/** The same in German. */
class RouteSortLargeFontGermanTest : RouteSortLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
