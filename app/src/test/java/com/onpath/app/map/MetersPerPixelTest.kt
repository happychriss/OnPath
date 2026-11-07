package com.onpath.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * onpath.private#6 (owner 2026-11-02, A): a hold counted as "on the planned line" from ~60 dp away instead of 24 dp.
 * MapLibre's `getMetersPerPixelAtLatitude` gives metres per **dp** (see ui/ScaleRule); MapController.metersPerPixel()
 * returned that as metres per screen pixel, so `dpToPx(24) × metersPerPixel()` was 24 × density dp (63 dp on the
 * Pixel 8, density 2.625). The shared place's "arrived" check (pixels) was off by the same factor.
 */
class MetersPerPixelTest {
    private val pixel8 = 2.625f

    @Test fun twentyFourDpAreTwentyFourDp() {
        val metersPerDp = 1.0 // e.g. zoom ~17 at 46° N
        val snapM = 24f * pixel8 * MapController.metersPerScreenPx(metersPerDp, pixel8)
        assertEquals("24 dp of line tolerance = 24 m at 1 m/dp", 24.0, snapM, 1e-9)
        // the old reading: metres per dp taken as metres per screen pixel → 63 m = 63 dp
        assertEquals(63.0, 24.0 * pixel8 * metersPerDp, 1e-9)
    }

    @Test fun aBrokenDensityNeverDividesByZero() {
        assertTrue(MapController.metersPerScreenPx(1.0, 0f).isFinite())
    }

}
