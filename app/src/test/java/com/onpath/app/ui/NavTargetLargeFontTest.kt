package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * The destination under Navigate's arrival clock at the phone's largest font (2.0): the clock and the name grow
 * together, the name wraps word by word, nothing overlaps. Same picture as [TimeScreenshotTest.navigateTargetNames],
 * drawn with this class's Paparazzi.
 */
class NavTargetLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [TimeScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = TimeScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigateTargetNames() = shots.navigateTargetNames()
}
