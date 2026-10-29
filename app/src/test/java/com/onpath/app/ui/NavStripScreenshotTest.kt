package com.onpath.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.routing.Activity
import org.junit.Rule
import org.junit.Test

/**
 * Navigate strip on a small phone (360 dp wide): time and distance to go side by side at the same
 * size; with long values the distance wraps under the time – never smaller text, never cut.
 */
class NavStripScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 1500, xdpi = 480, ydpi = 480, density = Density.XXHIGH),
        showSystemUi = false,
    )

    private fun ui(m: Double, s: Double, arrive: String) =
        NavUi(toGo = ToGo(m, 512.0, s, 12.0, descentM = 310.0), recordedM = 5_320.0, elapsedS = 5025, arriveAt = arrive,
            activity = Activity.BIKE_TOURING)

    @Test fun smallPhone() = paparazzi.snapshot {
        OnPathTheme {
            Box(Modifier.fillMaxSize()) {
                MapPlaceholder(Modifier.fillMaxSize())
                Column(Modifier.padding(top = 40.dp, start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavStatusCard(ui(7_420.0, 25 * 60.0, "16:40"), onResume = {})
                    NavStatusCard(ui(18_400.0, 3600 + 25 * 60.0, "16:40"), onResume = {})
                    NavStatusCard(ui(123_000.0, 10 * 3600 + 25 * 60.0, "23:55"), onResume = {})
                }
            }
        }
    }
}
