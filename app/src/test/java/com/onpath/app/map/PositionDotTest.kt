package com.onpath.app.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening the phone in Navigate: the dot is where I am, it does not glide from the old place (owner). */
class PositionDotTest {
    @Test fun firstPositionJumps() = assertTrue(PositionDot.jumps(0L, 5_000))
    @Test fun afterTheScreenWasLockedItJumps() = assertTrue(PositionDot.jumps(10_000, 70_000))
    @Test fun everySecondItGlidesSmoothly() = assertFalse(PositionDot.jumps(10_000, 11_000))
    @Test fun dotIsLargerThanMapLibresDefault() = assertTrue(PositionDot.MAX_SCALE > 1f && PositionDot.MIN_SCALE > 0.6f)

    /** Owner 2026-11-06: the GPS accuracy circle only in Navigate, never while planning. */
    @Test fun accuracyCircleOnlyInNavigate() {
        assertTrue(PositionDot.accuracyAlpha(navigate = true) > 0f)
        assertTrue(PositionDot.accuracyAlpha(navigate = false) == 0f)
    }

    /**
     * Plan sign during a tour on the native location layer (bug 2026-11-13: the blue dot stayed): the sign goes under
     * both names the layer draws the dot from (fresh and stale), every other part of the dot becomes empty.
     */
    @Test fun planSignCoversEveryImageOfTheNativeLayer() {
        val m = PositionDot.planSignImages(planSign = true)
        assertTrue(m["mapbox-location-icon"] == true && m["mapbox-location-stale-icon"] == true)
        for (other in listOf("mapbox-location-bearing-icon", "mapbox-location-bearing-stale-icon", "mapbox-location-stroke-icon",
            "mapbox-location-background-stale-icon", "mapbox-location-shadow-icon")) assertTrue(other, m[other] == false)
        assertTrue(m.size == 7)
    }

    @Test fun withoutPlanSignMapLibresOwnDotStays() = assertTrue(PositionDot.planSignImages(planSign = false).isEmpty())
}
