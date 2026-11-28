package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** „Zu lange Route für die Berechnung“ (owner 2026-11-28 11:09, his words): German at the normal and the largest font. */
abstract class TooLongShots(fontScale: Float) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428,
            density = Density.create(420), fontScale = fontScale),
        showSystemUi = false,
    )
    private val before = Locale.getDefault()
    @Before fun lang() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)

    @Test fun tooLong() = paparazzi.snapshot {
        val ask = com.onpath.app.routing.LongAsk(Double.POSITIVE_INFINITY, 0, 475.0)
        OnPathTheme {
            Box(Modifier.fillMaxSize()) { MapPlaceholder(Modifier.fillMaxSize()) }
            LongRouteDialog(ask.estimateS, com.onpath.app.routing.Activity.HIKE, {}, {}, leg = LongLegNames(ask, "Hilden", "München"))
        }
    }
}

class TooLongGermanTest : TooLongShots(1f)
class TooLongLargeFontGermanTest : TooLongShots(2f)
