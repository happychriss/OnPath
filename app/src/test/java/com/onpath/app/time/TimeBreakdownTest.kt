package com.onpath.app.time

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeBreakdownTest {
    // 2 km due north: first km flat, second km climbing 150 m (15 %)
    private val pts = (0..40).map { i -> GeoPoint(46.0 + i * 0.00045, 11.0, if (i <= 20) 500.0 else 500.0 + (i - 20) * 7.5) }

    @Test fun bandsAddUpToTheModelTime() {
        val r = TimeBreakdown.of(pts, Activity.HIKE, Factors.ONE)
        assertEquals(RouteTimes.of(pts, Activity.HIKE).total(), r.baseS, 1.0)
        assertEquals(r.baseS, r.finalS, 1e-6)
        assertEquals(RouteTimes.of(pts, Activity.HIKE).lengthM, r.rows.sumOf { it.distanceM }, 1.0)
    }

    @Test fun climbLandsInTheUpBand() {
        val r = TimeBreakdown.of(pts, Activity.HIKE, Factors.ONE)
        val up = r.rows.first { it.label.startsWith("↑ ") }
        assertTrue(up.distanceM > 500)
        assertTrue(up.upM > 100)
    }

    @Test fun factorsApplyPerGroup() {
        val r = TimeBreakdown.of(pts, Activity.HIKE, Factors(down = 1.0, flat = 1.0, up = 2.0))
        val up = r.rows.first { it.label.startsWith("↑ ") }
        assertEquals(2.0, up.factor, 1e-6)
        assertTrue(r.finalS > r.baseS)
    }
}
