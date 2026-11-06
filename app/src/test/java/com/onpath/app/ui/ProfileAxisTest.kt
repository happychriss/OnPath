package com.onpath.app.ui

import androidx.compose.foundation.layout.Column
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp

/** Owner's Pixel 2026-11-06: on a flat profile the axis wrote "47 m" over "46 m" – one label when they would touch. */
class ProfileAxisTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test fun bothLabelsWhenApart() = assertEquals(listOf(341.0, 954.0), ProfileAxis.labels(341.0, 954.0, 90f, 6f, 16f))
    @Test fun oneLabelWhenTheyWouldTouch() = assertEquals(listOf(47.0), ProfileAxis.labels(46.0, 47.0, 40f, 30f, 16f))
    @Test fun oneLabelWhenTheSameAsWritten() = assertEquals(listOf(46.9), ProfileAxis.labels(46.2, 46.9, 90f, 6f, 16f))

    /** A 700 m town ride: 46–47 m – one height on the axis, nothing on top of each other. */
    @Test fun flatProfile() = paparazzi.snapshot {
        val flat = (0..60).map { i -> GeoPoint(51.17 + i * 0.0001, 6.93 + i * 0.0001, 46.0 + (i % 7) / 6.0) }
        OnPathTheme { androidx.compose.material3.Surface { Column { ElevationProfile(flat, height = 84.dp, gradient = true); ElevationProfile(flat, height = 56.dp, gradient = true) } } }
    }
}
