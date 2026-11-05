package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The Navigate slider "» Entsperren" and what it unlocks (owner 2026-11-05, Navigate C) at the phone's largest font (2.0) on a
 * normal phone: the small line does not fit and is left out – the main line stays whole, nothing is cut. Same
 * pictures as the [ScreenshotTest] tests of the same name, drawn with this class's Paparazzi.
 */
open class SlideLabelLargeFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)

    /** [ScreenshotTest] drawing into this class's Paparazzi (its own rule is not running). */
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigate() = shots.navigate()
    /** Without a recording: only "Plan ändern" behind it. */
    @Test fun navigateNotRecording() = shots.navigateNotRecording()
    @Test fun navigateActionsOpen() = shots.navigateActionsOpen()
    @Test fun navigatePausedOpen() = shots.navigatePausedOpen()
    @Test fun navigateNotRecordingOpen() = shots.navigateNotRecordingOpen()
}

/** The same in German ("Tour beenden" is the longest button word). */
class SlideLabelLargeFontGermanTest : SlideLabelLargeFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** A moderately larger font (1.3, a common setting): the English small line still fits. */
open class SlideLabelMediumFontTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 1.3f), showSystemUi = false)

    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }

    @Test fun navigate() = shots.navigate()
}

/** The same in German at 1.3. */
class SlideLabelMediumFontGermanTest : SlideLabelMediumFontTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
