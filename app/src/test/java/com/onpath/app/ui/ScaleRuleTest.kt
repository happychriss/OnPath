package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field A-019 add-on (09e): after "Continue" Navigate showed "50 m" and looked far too close. The zoom was the hike
 * standard 17 (AppMode.navStartZoom) – the scale bar was wrong: MapLibre's projection gives metres per dp, the bar
 * multiplied by the density once more and read 2–3 × too long.
 */
class ScaleRuleTest {
    private val meran = 46.65

    @Test fun hikeStartZoomReadsTwentyMetres() {
        // zoom 17 at Meran: ~0.41 m per dp → 100 dp ≈ 41 m → a 20 m bar of ~49 dp
        val (label, width) = ScaleRule.of(ScaleRule.metersPerDp(meran, AppMode.navStartZoom(com.onpath.app.routing.Activity.HIKE)))!!
        assertEquals("20 m", label)
        assertEquals(48.8, width, 0.5)
    }

    @Test fun bikeStartZoomReadsFiftyMetres() {
        val (label, width) = ScaleRule.of(ScaleRule.metersPerDp(meran, AppMode.navStartZoom(com.onpath.app.routing.Activity.BIKE_TOURING)))!!
        assertEquals("50 m", label)
        assertTrue(width in 50.0..100.0)
    }

    /** The field picture: the bar showed "50 m" at ~54 dp on a 2.225 density screen – exactly zoom 17 times the density. */
    @Test fun theOldBarWasTheDensityTooLong() {
        val mPerDp = ScaleRule.metersPerDp(meran, 17.0)
        val old = ScaleRule.of(mPerDp * 2.225)!! // what multiplying by the density did
        assertEquals("50 m", old.first)
        assertFalse(ScaleRule.of(mPerDp)!!.first == old.first)
    }

    @Test fun kilometresAndBrokenScales() {
        assertEquals("500 m", ScaleRule.of(ScaleRule.metersPerDp(meran, 13.0))!!.first)
        assertEquals("1 km", ScaleRule.of(12.0)!!.first)
        assertEquals("2 km", ScaleRule.of(25.0)!!.first)
        assertNull(ScaleRule.of(0.0))
        assertNull(ScaleRule.of(Double.NaN))
        assertNull(ScaleRule.of(Double.POSITIVE_INFINITY))
    }

}
