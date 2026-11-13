package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.ui.routes.RoundTripSketch
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * SKETCH (owner 2026-11-13 „Sollte man in der Routenliste nicht auch anzeigen, wenn es eine Rundtour ist?“): the L1 list
 * of [ScreenshotTest.routesMapRows] with Vigiljoch MTB-Runde and Waalweg Algund as round trips – A "· ↻ Rundtour" in the
 * activity line, B a ↻ badge on the map tile's corner. German, normal and the largest font.
 */
abstract class RoundTripSketchBase {
    abstract val paparazzi: Paparazzi
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() { Locale.setDefault(before); RoundTripSketch.mode = RoundTripSketch.Mode.NONE; RoundTripSketch.ids = emptySet() }

    private fun draw(mode: RoundTripSketch.Mode) {
        RoundTripSketch.mode = mode; RoundTripSketch.ids = setOf(15L, 14L)
        shots.routesMapRows()
    }
    @Test fun a() = draw(RoundTripSketch.Mode.A)
    @Test fun b() = draw(RoundTripSketch.Mode.B)
}

class RoundTripSketchTest : RoundTripSketchBase() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)
}

class RoundTripSketchLargeFontTest : RoundTripSketchBase() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f, screenHeight = 3600), showSystemUi = false)
}
