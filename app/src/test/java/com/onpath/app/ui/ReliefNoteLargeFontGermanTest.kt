package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * "Relief aus – die Karte läuft so flüssiger" (map/ReliefAuto) at the phone's largest font (2.0), in German (the longer
 * text): the quiet pill wraps inside the screen. Same picture as [ScreenshotTest.planReliefOffNote].
 */
class ReliefNoteLargeFontGermanTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun planReliefOffNote() = shots.planReliefOffNote()
}
