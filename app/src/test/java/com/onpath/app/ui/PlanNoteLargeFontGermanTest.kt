package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The quiet notes on the Plan map – "Relief aus – die Karte läuft so flüssiger" (map/ReliefAuto) and the GPS notes
 * (PlanGps) – at the phone's largest font (2.0), in German (the longer texts): beside the activity button, clear of its T
 * badge, wrapped inside the screen. Same pictures as [ScreenshotTest.planReliefOffNote] / [ScreenshotTest.planGpsNote].
 */
class PlanNoteLargeFontGermanTest {
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
    @Test fun planGpsNote() = shots.planGpsNote()
    @Test fun planGpsNoteLong() = shots.planGpsNoteLong()
}
