package com.onpath.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test

/**
 * The system start surface (Android 12+, owner 2026-11-07) as the platform lays it out: paper, the app icon in the
 * 240 dp circle in the middle, the branding image `splash_wordmark` (200×80 dp) 60 dp above the bottom. The phone draws
 * the real one itself from `values-v31/themes.xml`; this frame checks the wordmark drawable renders and reads on paper.
 */
class SplashScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2400, xdpi = 420, ydpi = 420, density = Density.create(420)),
        showSystemUi = false,
    )

    @Test fun splash() = paparazzi.snapshot {
        Box(Modifier.fillMaxSize().background(OnPathColors.Paper)) {
            Box(Modifier.align(Alignment.Center).size(160.dp).clip(CircleShape).background(Brand.Tile),
                contentAlignment = Alignment.Center) {
                Image(painterResource(com.onpath.app.R.drawable.ic_launcher_foreground), null, Modifier.size(240.dp))
            }
            Image(painterResource(com.onpath.app.R.drawable.splash_wordmark), "OnPath",
                Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).size(200.dp, 80.dp))
        }
    }
}
