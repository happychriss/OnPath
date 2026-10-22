package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.gpx.GpxParser
import com.onpath.app.rides.RideDetector
import com.onpath.app.rides.RideView
import com.onpath.app.routing.Activity
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/** End tour with rides found: the owner's Taser tour (gondola + car), summary with the four choices. */
class RideSummaryScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
        ),
        showSystemUi = false,
    )

    private val zone = ZoneId.of("Europe/Rome")
    private val segs = GpxParser.parse(javaClass.classLoader!!.getResource("tracks/owner-2026-10-22-taser/verdins-schenna.gpx")!!.readText()).allSegments

    @Test fun endTourWithRides() = paparazzi.snapshot {
        // gondola, a minute on foot, then the car at the end → "Save only until 16:04" preselected; no split (nothing after)
        val s = RideView.summary(segs, RideDetector.detect(segs, Activity.HIKE), onFoot = true, zone = zone)!!
        OnPathTheme { RideSummaryDialog(s, onSave = {}, onDelete = {}, onDismiss = {}, zone = zone) }
    }
}
