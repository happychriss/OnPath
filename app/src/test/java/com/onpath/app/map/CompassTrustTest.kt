package com.onpath.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compass accuracy → arrow colour / map turning (owner 2026-10-30): an unsure compass greys the "where I face" arrow
 * (never hides it) and does not turn the map in heading-up.
 */
class CompassTrustTest {
    // SensorManager.SENSOR_STATUS_*
    private val noContact = -1; private val unreliable = 0; private val low = 1; private val medium = 2; private val high = 3

    @Test fun fineUntilToldOtherwise() {
        val t = CompassTrust()
        assertTrue(t.reliable(0))
        assertNull(t.why(0))
    }

    @Test fun unreliableAndLowAreUnsureMediumAndHighFine() {
        val t = CompassTrust()
        for ((s, ok) in listOf(noContact to false, unreliable to false, low to false, medium to true, high to true)) {
            t.onStatus(s)
            assertEquals("status $s", ok, t.reliable(1_000))
        }
        t.onStatus(low)
        assertEquals("sensor accuracy LOW", t.why(1_000))
        t.onStatus(high)
        assertTrue(t.reliable(1_000))   // back to normal as soon as accuracy recovers
    }

    @Test fun headingAccuracyEstimateWithHysteresis() {
        val t = CompassTrust()
        t.onReading(90.0, 20.0, 0); assertTrue(t.reliable(0))
        t.onReading(90.0, 34.0, 60); assertTrue(t.reliable(60))
        t.onReading(90.0, 40.0, 120); assertFalse(t.reliable(120))
        t.onReading(90.0, 30.0, 180); assertFalse("between the limits: stays unsure", t.reliable(180))
        t.onReading(90.0, 22.0, 240); assertTrue(t.reliable(240))
        t.onReading(90.0, 30.0, 300); assertTrue("between the limits: stays fine", t.reliable(300))
    }

    @Test fun noEstimateLeavesItToTheStatus() {
        val t = CompassTrust()
        t.onReading(90.0, null, 0); t.onReading(91.0, -57.0, 60)
        assertTrue(t.reliable(60))
    }

    @Test fun headingJumpIsUnsureForAFewSeconds() {
        val t = CompassTrust()
        t.onReading(10.0, null, 0)
        t.onReading(80.0, null, 60)              // 70° in 60 ms – no hand turns a phone that fast
        assertFalse(t.reliable(60))
        assertEquals("heading jumped", t.why(1_000))
        assertFalse(t.reliable(60 + CompassTrust.JUMP_HOLD_MS))
        assertTrue(t.reliable(61 + CompassTrust.JUMP_HOLD_MS))
    }

    @Test fun normalTurnOfThePhoneIsNoJump() {
        val t = CompassTrust()
        // a brisk turn: 180° in half a second, readings every 60 ms (~22° each)
        var h = 0.0
        for (i in 0..8) { t.onReading(h, null, i * 60L); h += 22.0 }
        assertTrue(t.reliable(600))
        // a big change after a pause (sensor off, rest view) is no jump either
        t.onReading(300.0, null, 5_000)
        assertTrue(t.reliable(5_000))
    }

    @Test fun jumpAcrossNorthCountsTheShortWay() {
        val t = CompassTrust()
        t.onReading(350.0, null, 0)
        t.onReading(20.0, null, 60)              // 30° the short way: no jump
        assertTrue(t.reliable(60))
    }

    @Test fun arrowGreyOnlyWhenTheCompassDrivesIt() {
        val t = CompassTrust()
        t.onStatus(unreliable)
        assertTrue("standing: the arrow is the compass – grey", t.arrowGrey(viewFromGps = false, nowMs = 0))
        assertFalse("moving: the arrow is the GPS bearing – blue", t.arrowGrey(viewFromGps = true, nowMs = 0))
        t.onStatus(medium)
        assertFalse(t.arrowGrey(viewFromGps = false, nowMs = 0))
    }

    // ---- the map direction with an unsure compass (HeadingFilter.onUntrustedSensor) ----

    @Test fun unsureCompassHoldsTheLastGoodMapDirection() {
        val f = HeadingFilter()
        f.onSensor(90.0, 0)
        repeat(50) { assertNull(f.onUntrustedSensor(100L + it * 60)) }
        assertEquals(90.0, f.heading!!, 1e-9)
    }

    @Test fun unsureCompassUsesTheDirectionYouReallyMoved() {
        val f = HeadingFilter()
        f.onSensor(90.0, 0)
        // slow uphill northwards (below the GPS-bearing speed): 20 m walked → movement direction ~0°
        f.onPosition(46.6700, 11.1600, 5.0, 0)
        f.onPosition(46.6702, 11.1600, 5.0, 20_000)
        var last: Double? = null
        repeat(60) { f.onUntrustedSensor(20_000L + it * 60)?.let { h -> last = h } }
        assertNotNull(last)
        assertTrue(kotlin.math.abs(HeadingFilter.diff(f.heading!!, 0.0)) < HeadingFilter.DEADBAND_DEG + 0.1)
        // that direction is old after MOVE_VALID_MS: held again
        val held = f.heading
        assertNull(f.onUntrustedSensor(20_000L + HeadingFilter.MOVE_VALID_MS + 1))
        assertEquals(held!!, f.heading!!, 1e-9)
    }

    @Test fun unsureCompassIsIgnoredWhileGpsOrRouteDrive() {
        val f = HeadingFilter()
        f.onPosition(46.6700, 11.1600, 5.0, 0)
        f.onPosition(46.6702, 11.1600, 5.0, 1_000)
        f.onGps(180f, 1.5f, 1_000)
        assertNull(f.onUntrustedSensor(1_500))
    }
}
