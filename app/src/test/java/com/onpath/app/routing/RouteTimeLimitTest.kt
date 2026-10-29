package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing time limit by distance and profile instead of a flat 30 s. */
class RouteTimeLimitTest {
    private fun old(km: Double) = (15_000 + km * 500).toLong().coerceIn(15_000, 30_000)

    @Test fun shortRoutesKeepAShortLimit() {
        for (a in Activity.entries) {
            assertEquals(RouteTimeLimit.MIN_MS, RouteTimeLimit.forKm(0.0, a))
            assertTrue(RouteTimeLimit.forKm(5.0, a) <= 20_000)
        }
    }

    @Test fun neverShorterThanTheOldRule() {
        for (a in Activity.entries) for (km in listOf(0.0, 3.0, 10.0, 30.0, 60.0, 150.0, 400.0))
            assertTrue("$a $km km", RouteTimeLimit.forKm(km, a) >= old(km))
    }

    @Test fun longBikeRoutesGetMoreWithACap() {
        assertEquals(120_000L, RouteTimeLimit.forKm(260.0, Activity.MTB))
        assertEquals(90_000L, RouteTimeLimit.forKm(260.0, Activity.BIKE_TOURING))
        assertEquals(90_000L, RouteTimeLimit.forKm(260.0, Activity.BIKE_TREKKING))
        assertEquals(45_000L, RouteTimeLimit.forKm(260.0, Activity.HIKE))
        assertEquals(115_000L, RouteTimeLimit.forKm(100.0, Activity.EMTB))
        for (a in Activity.entries) assertTrue(RouteTimeLimit.forKm(10_000.0, a) <= 120_000)
    }

    @Test fun distanceIsTheStraightLineSum() {
        val pts = listOf(GeoPoint(46.0, 11.0), GeoPoint(46.1, 11.0), GeoPoint(46.2, 11.0))
        assertEquals(22.2, RouteTimeLimit.straightKm(pts), 0.1)
        assertEquals(RouteTimeLimit.forKm(RouteTimeLimit.straightKm(pts), Activity.MTB), RouteTimeLimit.forRoute(pts, Activity.MTB))
    }
}
