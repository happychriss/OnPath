package com.onpath.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Elevator
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.onpath.app.gpx.GpxParser
import com.onpath.app.rides.Ride
import com.onpath.app.rides.RideDetector
import com.onpath.app.rides.RideKind
import com.onpath.app.rides.RideView
import com.onpath.app.routing.Activity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

private val taser = GpxParser.parse(RideIconTest::class.java.classLoader!!
    .getResource("tracks/owner-2026-10-22-taser/verdins-schenna.gpx")!!.readText()).allSegments

/**
 * End tour with rides found: the owner's Taser tour (gondola + car), summary with the four choices. The dialog's icon
 * follows the rides (owner 2026-10-30): gondola + car → neutral ride icon, the gondola alone → lift, the car alone → bus.
 */
open class RideSummaryScreenshotTest {
    @get:Rule
    open val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1024, screenHeight = 2400, xdpi = 428, ydpi = 428, density = Density.create(420),
        ),
        showSystemUi = false,
    )

    private val zone = ZoneId.of("Europe/Rome")
    private val rides get() = RideDetector.detect(taser, Activity.HIKE)

    private fun shot(rides: List<Ride>) = paparazzi.snapshot {
        val s = RideView.summary(taser, rides, onFoot = true, zone = zone)!!
        OnPathTheme { RideSummaryDialog(s, onSave = {}, onDelete = {}, onDismiss = {}, zone = zone) }
    }

    // gondola, a minute on foot, then the car at the end → "Save only until 16:04" preselected; no split (nothing after)
    @Test fun endTourWithRides() = shot(rides)
    /** Only the gondola (the car ride left out): the lift icon, not the bus. */
    @Test fun endTourLiftOnly() = shot(rides.filter { it.kind == RideKind.LIFT })
    /** Only the car: the bus icon. */
    @Test fun endTourVehicleOnly() = shot(rides.filter { it.kind == RideKind.VEHICLE })
}

/** The same in German. */
class RideSummaryScreenshotGermanTest : RideSummaryScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** Which icon the End-tour dialog (and "Edit rides") shows for the rides found. */
class RideIconTest {
    private val lift = Ride(1, 5, RideKind.LIFT)
    private val car = Ride(8, 12, RideKind.VEHICLE)

    @Test fun iconFollowsTheRides() {
        assertEquals(Icons.Filled.Elevator, rideIcon(listOf(lift)))
        assertEquals(Icons.Filled.Elevator, rideIcon(listOf(lift, lift.copy(from = 20, to = 25))))
        assertEquals(Icons.Filled.DirectionsBus, rideIcon(listOf(car)))
        assertEquals(Icons.Filled.Commute, rideIcon(listOf(lift, car)))
    }

    @Test fun taserTourHasBoth() =
        assertEquals(setOf(RideKind.LIFT, RideKind.VEHICLE), RideDetector.detect(taser, Activity.HIKE).map { it.kind }.toSet())
}
