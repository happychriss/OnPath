package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.onpath.app.map.MapController
import org.junit.Rule
import org.junit.Test

/**
 * The "where I face" arrow at the position dot (heading-up): blue with a sure compass, grey while the compass is
 * unsure (map/CompassTrust, owner 2026-10-30 – grey, never hidden). A stand-in dot on a map-like background.
 */
class FacingArrowScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 400, screenHeight = 400),
        showSystemUi = false)

    private fun shot(unsure: Boolean) = paparazzi.snapshot {
        val f = MapController.Facing().apply {
            visible = true; x = 200f; y = 200f; viewHeading = 30f; this.unsure = unsure
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFF2EFE6))) {
            // the dot: same screen point (px → dp at 440 dpi: 200 px ≈ 72.7 dp)
            Box(Modifier.offset((200f / 2.75f - 9).dp, (200f / 2.75f - 9).dp).size(18.dp)
                .background(Color.White, CircleShape))
            Box(Modifier.offset((200f / 2.75f - 7).dp, (200f / 2.75f - 7).dp).size(14.dp)
                .background(OnPathColors.Position, CircleShape))
            FacingArrow(f) { 0f }
        }
    }

    @Test fun compassSure() = shot(unsure = false)
    @Test fun compassUnsure() = shot(unsure = true)
}
