package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.TurnKind
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The rest view while cycling (Smart screen, owner 2026-10-30): black, the next turn's sign large, distance, direction,
 * time left and arrival in dim grey, "Tap for the map". English, German and the largest phone font.
 */
open class RestViewScreenshotTest {
    @get:Rule open val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, showSystemUi = false)

    private fun shot(ui: RestUi) = paparazzi.snapshot { OnPathTheme { RestView(ui, onTap = {}) } }

    @Test fun turn() = shot(RestUi(RestEvent(4200.0, TurnKind.SHARP_RIGHT), 1_240.0, toGoS = 2 * 3600 + 50 * 60.0, arriveAt = "16:40"))
    @Test fun nearTurn() = shot(RestUi(RestEvent(4200.0, TurnKind.SLIGHT_LEFT), 430.0, toGoS = 25 * 60.0, arriveAt = "9:05"))
    @Test fun roundabout() = shot(RestUi(RestEvent(4200.0, TurnKind.LEFT, roundabout = 3, angle = -80f), 860.0,
        toGoS = 48 * 60.0, arriveAt = "12:15"))
    @Test fun destination() = shot(RestUi(RestEvent(9000.0, null), 3_400.0, toGoS = 11 * 60.0, arriveAt = "18:02"))
    /** No time known (no estimate yet): only the sign, distance and the tap hint. */
    @Test fun withoutTime() = shot(RestUi(RestEvent(4200.0, TurnKind.U_TURN_LEFT), 2_000.0))
}

/** The same in German. */
class RestViewScreenshotGermanTest : RestViewScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** The phone's font size at its largest (2.0): nothing is cut, the sign stays large. */
class RestViewScreenshotLargeFontTest : RestViewScreenshotTest() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f), showSystemUi = false)
}
