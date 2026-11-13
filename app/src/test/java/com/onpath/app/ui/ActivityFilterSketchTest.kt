package com.onpath.app.ui

import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.Activity
import com.onpath.app.ui.routes.ActivityFilterSketch
import com.onpath.app.ui.routes.ActivityFilterSketch.Mode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * SKETCH (owner 2026-11-13 „man kann nicht nach Art filtern, Wandern oder Fahrradfahren“): (1) toggle chips Wandern / Rad
 * / MTB after ☆ Favoriten, (2) one chip "Alle Arten ▾" with a menu of the six activities. German, normal + largest font.
 */
abstract class ActivityFilterSketchBase {
    abstract val paparazzi: Paparazzi
    /** Where option 2's menu opens at this font (under the chip row, from its chip). */
    abstract val menuTop: androidx.compose.ui.unit.Dp
    abstract val menuStart: androidx.compose.ui.unit.Dp
    private val shots get() = ScreenshotTest().also {
        it.javaClass.getDeclaredField("paparazzi").apply { isAccessible = true }.set(it, paparazzi)
    }
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() { Locale.setDefault(before); ActivityFilterSketch.mode = Mode.NONE; ActivityFilterSketch.chosen = emptySet() }

    private fun draw(mode: Mode, chosen: Set<Activity>, menu: Boolean = false) {
        ActivityFilterSketch.mode = mode; ActivityFilterSketch.chosen = chosen
        shots.activitySketch(if (menu) menuTop else null, menuStart)
    }
    private val bike = ActivityFilterSketch.Group.BIKE.members

    @Test fun chipsNone() = draw(Mode.CHIPS, emptySet())
    @Test fun chipsBike() = draw(Mode.CHIPS, bike)
    @Test fun menuClosed() = draw(Mode.MENU, emptySet())
    @Test fun menuOpenBike() = draw(Mode.MENU, bike, menu = true)
    @Test fun menuHike() = draw(Mode.MENU, setOf(Activity.HIKE))
}

class ActivityFilterSketchTest : ActivityFilterSketchBase() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)
    override val menuTop = 248.dp
    override val menuStart = 140.dp
}

class ActivityFilterSketchLargeFontTest : ActivityFilterSketchBase() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f, screenHeight = 3600), showSystemUi = false)
    override val menuTop = 240.dp
    override val menuStart = 100.dp
}
