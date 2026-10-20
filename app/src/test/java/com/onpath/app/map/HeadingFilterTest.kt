package com.onpath.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HeadingFilterTest {
    @Test fun firstSensorReadingIsTakenAsIs() {
        val f = HeadingFilter()
        assertEquals(90.0, f.onSensor(90.0, 0)!!, 1e-9)
    }

    @Test fun standingStillJitterIsIgnored() {
        val f = HeadingFilter()
        f.onSensor(90.0, 0)
        // ±4° sensor noise around 90°: smoothed changes stay inside the dead band
        var emitted = 0
        for (i in 1..200) if (f.onSensor(if (i % 2 == 0) 94.0 else 86.0, i * 60L) != null) emitted++
        assertEquals(0, emitted)
        assertEquals(90.0, f.heading!!, 1e-9)
    }

    @Test fun sensorTurnIsFollowedAcrossNorth() {
        val f = HeadingFilter()
        f.onSensor(350.0, 0)
        repeat(100) { f.onSensor(20.0, it * 60L) }
        assertTrue(abs(HeadingFilter.diff(f.heading!!, 20.0)) < HeadingFilter.DEADBAND_DEG + 0.1)
    }

    @Test fun movingUsesGpsBearingAndIgnoresCompass() {
        val f = HeadingFilter()
        f.onSensor(0.0, 0)
        assertNotNull(f.onGps(180f, 1.5f, 1_000))
        assertTrue(f.usingGps(1_500))
        assertNull(f.onSensor(0.0, 1_500))           // phone pointing elsewhere: ignored while walking
        repeat(10) { f.onGps(180f, 1.4f, 2_000L + it * 1000) }
        assertEquals(180.0, f.heading!!, HeadingFilter.DEADBAND_DEG)
    }

    @Test fun slowOrStaleGpsFallsBackToCompass() {
        val f = HeadingFilter()
        f.onGps(180f, 1.5f, 0)
        f.onGps(180f, 0.8f, 1_000)                   // hysteresis: still moving above 0.6 m/s
        assertTrue(f.usingGps(1_200))
        f.onGps(180f, 0.3f, 2_000)                   // stopped
        assertFalse(f.usingGps(2_100))
        assertNotNull(f.onSensor(90.0, 2_100))
        // moving, but the last fix is old (GPS lost): compass again
        val g = HeadingFilter()
        g.onGps(180f, 2f, 0)
        assertFalse(g.usingGps(HeadingFilter.GPS_VALID_MS + 1))
    }

    @Test fun noBearingNoCrash() {
        val f = HeadingFilter()
        assertNull(f.onGps(null, null, 0))
        assertNull(f.onSensor(Double.NaN, 0))
        assertNull(f.heading)
    }

    @Test fun routeDirectionWinsOverCompassAndGps() {
        val f = HeadingFilter()
        f.onSensor(80.0, 0)
        assertNotNull(f.onRoute(0.0, 1_000))
        // compass says 80°, GPS (moving, slightly off) says 15°: neither moves the map off the route direction
        assertNull(f.onSensor(80.0, 1_100))
        assertNull(f.onGps(15f, 1.2f, 1_200))
        repeat(20) { f.onRoute(0.0, 2_000L + it * 1_000) }
        assertTrue(abs(HeadingFilter.diff(f.heading!!, 0.0)) < HeadingFilter.DEADBAND_DEG)
        assertTrue(f.usingRoute(21_000))
    }

    @Test fun walkingAgainstTheRouteFallsBackToGps() {
        val f = HeadingFilter()
        f.onRoute(0.0, 0)
        f.onGps(180f, 1.3f, 500)
        assertFalse(f.usingRoute(600))
        repeat(10) { f.onGps(180f, 1.3f, 1_000L + it * 1_000) }
        assertTrue(abs(HeadingFilter.diff(f.heading!!, 180.0)) < HeadingFilter.DEADBAND_DEG)
    }

    @Test fun routeDirectionExpiresAndClears() {
        val f = HeadingFilter()
        f.onRoute(90.0, 0)
        assertTrue(f.usingRoute(HeadingFilter.ROUTE_VALID_MS))
        assertFalse(f.usingRoute(HeadingFilter.ROUTE_VALID_MS + 1))
        f.onRoute(90.0, 20_000)
        f.onRoute(null, 20_100)
        assertFalse(f.usingRoute(20_200))
        assertNotNull(f.onSensor(200.0, 20_300))
    }
}
