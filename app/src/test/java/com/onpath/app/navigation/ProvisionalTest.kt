package com.onpath.app.navigation

import com.onpath.app.routing.ArrivalGate
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core feature C5: a tour started indoors (owner) – the app's Wi-Fi / fused fix stands in until the first GPS fix,
 * with a warning; never recorded (TourGpsOnlyTest), no arrival question on it, gone with the first GPS fix.
 */
class ProvisionalTest {
    private fun of(provider: String? = "network", acc: Float = 80f, ageMs: Long = 3_000, hadGps: Boolean = false,
                   tour: Boolean = true, paused: Boolean = false, hasAcc: Boolean = true) =
        Provisional.of(tour, paused, hadGps, provider, hasAcc, acc, ageMs)

    @Test fun wifiFixBeforeTheFirstGpsFixIsTheProvisionalPosition() =
        assertEquals(Provisional.Fix(Provisional.Source.WIFI, 80), of())

    @Test fun fusedFixCountsAsWifi() = assertEquals(Provisional.Source.WIFI, of(provider = "fused")?.source)

    @Test fun firstGpsFixEndsItSilently() = assertNull(of(hadGps = true))

    @Test fun noTourOrPausedNoProvisional() {
        assertNull(of(tour = false))
        assertNull(of(paused = true))
    }

    @Test fun cellGuessOldFixOrNoAccuracyIsNoStartPoint() {
        assertNull(of(acc = 900f))
        assertNull(of(ageMs = 3 * 60_000L))
        assertNull(of(hasAcc = false))
        assertEquals(150, of(acc = 150f, ageMs = 120_000)?.accuracyM)
    }

    @Test fun gpsFixTheServiceTakesIsNeverProvisional() = assertNull(of(provider = "gps", acc = 12f))

    @Test fun weakGpsTheServiceDropsIsProvisional() =
        assertEquals(Provisional.Fix(Provisional.Source.WEAK_GPS, 80), of(provider = "gps", acc = 80f))

    // battery: the fused request runs only from the start until the first GPS fix, never while paused or after the end
    @Test fun fusedIsAskedOnlyUntilTheFirstGpsFix() {
        assertTrue(Provisional.asksPosition(tourRunning = true, paused = false, serviceHadGps = false))
        assertFalse(Provisional.asksPosition(tourRunning = true, paused = false, serviceHadGps = true))
        assertFalse(Provisional.asksPosition(tourRunning = true, paused = true, serviceHadGps = false))
        assertFalse(Provisional.asksPosition(tourRunning = false, paused = false, serviceHadGps = false))
    }

    @Test fun farFromTheLineIsNotOnRoute() {
        val fix = Provisional.Fix(Provisional.Source.WIFI, 80)
        assertTrue(Provisional.onRoute(120.0, fix))
        assertFalse(Provisional.onRoute(400.0, fix))
    }

    /** The arrival gate alone lets a ±40 m Wi-Fi fix through – so Navigate does not ask it on a provisional fix. */
    @Test fun noArrivalQuestionOnAProvisionalPosition() {
        val dest = GeoPoint(46.67, 11.20)
        val gate = ArrivalGate()
        gate.onFix(GeoPoint(46.67, 11.21), dest, 800.0, 10.0) // away first (≈ 760 m)
        assertTrue("the gate alone would ask on a ±40 m fix at the end", gate.onFix(dest, dest, 0.0, 40.0))
        assertFalse(Provisional.arrivalCheck(Provisional.Fix(Provisional.Source.WIFI, 40)))
        assertTrue(Provisional.arrivalCheck(null))
    }
}
