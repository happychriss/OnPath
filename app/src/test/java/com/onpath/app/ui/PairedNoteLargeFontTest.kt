package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * "Paired. …" with OK at the top of the Plan map (owner 2026-10-30) at the phone's largest font (2.0): the whole
 * sentence wraps, OK stays a 48 dp target. Same picture as [ScreenshotTest.planPairedNote], drawn with this class's
 * Paparazzi.
 */
open class PairedNoteLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun planPairedNote() = shots.planPairedNote()
}

/** The same in German. */
class PairedNoteLargeFontGermanTest : PairedNoteLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
