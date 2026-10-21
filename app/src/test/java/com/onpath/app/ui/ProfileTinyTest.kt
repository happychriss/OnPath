package com.onpath.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.GeoPoint
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/**
 * Field crash 2026-10-21: the profile drawn while the sheet animates is only a few pixels high – the label range became
 * "0 .. negative" and coerceIn threw. Drawing at tiny sizes must never crash.
 */
class ProfileTinyTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val pts = (0..80).map { i -> GeoPoint(46.67 + i * 0.0004, 11.16 + i * 0.0003, 400.0 + 500 * sin(i / 20.0)) }

    @Test fun tinySizesDoNotCrash() = paparazzi.snapshot {
        OnPathTheme {
            Column {
                for (h in listOf(1, 6, 14, 24, 30)) ElevationProfile(pts, height = h.dp)
                Box(Modifier.width(50.dp)) { ElevationProfile(pts, height = 60.dp) }
                Box(Modifier.width(70.dp)) { ElevationProfile(pts, height = 60.dp) }
            }
        }
    }
}
