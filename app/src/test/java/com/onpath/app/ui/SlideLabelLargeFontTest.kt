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
    /** The end sheet (owner 2026-11-05, V2): it scrolls, Save stays reachable. */
    @Test fun endTourConfirm() = shots.endTourConfirm()
    /** The next stops at a large font: numbers never cut, a long name ends with an ellipsis. */
    @Test fun navigateProfileStops() = shots.navigateProfileStops()
    /** Save plan? at a large font: the stacked full-width buttons. */
    @Test fun planClearDuringTour() = shots.planClearDuringTour()
    /** My routes with Zuletzt: the draft's Speichern · Löschen stay whole with a large font. */
    @Test fun toursRecent() = shots.toursRecent()
    /** The green "Navigation ›" at a large font: the numbers go, the word stays whole (owner 2026-11-06, C). */
    @Test fun planTourRunningCollapsed() = shots.planTourRunningCollapsed()
    /** "Dieser Tour folgen?" and the slim "Tour gespeichert" box at a large font (owner 2026-11-06, F1 / #35). */
    @Test fun routesFollowAsk() = shots.routesFollowAsk()
    @Test fun savedTourBox() = shots.savedTourBox()
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
