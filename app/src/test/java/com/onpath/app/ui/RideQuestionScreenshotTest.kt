package com.onpath.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.gpx.GpxParser
import com.onpath.app.rides.Ride
import com.onpath.app.rides.RideDetector
import com.onpath.app.rides.RideKind
import com.onpath.app.rides.RideView
import com.onpath.app.routing.Activity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

private val taserTrack = GpxParser.parse(RideIconTest::class.java.classLoader!!
    .getResource("tracks/owner-2026-10-22-taser/verdins-schenna.gpx")!!.readText()).allSegments

/**
 * The question when a tour with a ride is saved (owner 2026-11-21, "zwei Knöpfe"): amber box, the tour's map with the
 * ride dashed, "Ohne Fahrt / Mit Fahrt speichern", Abbrechen · Tour löschen. The owner's Taser tour: gondola in the
 * middle (the car at the end left out) = "Unterwegs", the car alone at the end = "Am Ende", both = the several-rides line.
 */
abstract class RideQuestionScreenshotTest(private val fontScale: Float = 1f, private val locale: Locale = Locale.ENGLISH) {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = fontScale, screenHeight = 2400), showSystemUi = false)

    private val before = Locale.getDefault()
    @Before fun setLocale() = Locale.setDefault(locale)
    @After fun back() = Locale.setDefault(before)

    private val zone = ZoneId.of("Europe/Rome")
    private val rides get() = RideDetector.detect(taserTrack, Activity.HIKE)

    private fun shot(rides: List<Ride>) = paparazzi.snapshot {
        val s = RideView.summary(taserTrack, rides, onFoot = true, zone = zone)!!
        OnPathTheme { RideQuestionDialog(s, onSave = {}, onDelete = {}, onDismiss = {}, zone = zone) }
    }

    @Test fun rideInTheMiddle() = shot(rides.filter { it.kind == RideKind.LIFT })
    @Test fun rideAtTheEnd() = shot(rides.filter { it.kind == RideKind.VEHICLE })
    @Test fun severalRides() = shot(rides)
}

class RideQuestionScreenshotEnglishTest : RideQuestionScreenshotTest()
class RideQuestionScreenshotGermanTest : RideQuestionScreenshotTest(locale = Locale.GERMANY)
/** Large font (2.0), German = the longest texts. */
class RideQuestionScreenshotGermanLargeTest : RideQuestionScreenshotTest(2.0f, Locale.GERMANY)
