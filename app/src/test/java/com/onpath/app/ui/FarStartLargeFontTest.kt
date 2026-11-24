package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** The far-start box (warning line, then "Cancel" while searching) at the largest font (2.0): nothing cut off, no ragged buttons. */
/** English frames dropped (test audit 2026-11-07: the German twin at the same font size is the check); the subclasses draw. */
abstract class FarStartLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigateFarStart() = shots.navigateFarStart()
    @Test fun navigateFarStartSearching() = shots.navigateFarStartSearching()
}

/** The same in German. */
class FarStartLargeFontGermanTest : FarStartLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
