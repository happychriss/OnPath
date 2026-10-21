package com.onpath.app.ui

import com.onpath.app.ui.MapGestures.Action
import com.onpath.app.ui.MapGestures.Buzz
import com.onpath.app.ui.MapGestures.PointAction
import com.onpath.app.ui.MapGestures.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGesturesTest {
    private fun plan(press: Press, marker: Int = -1, poi: Boolean = false, menu: Boolean = false, empty: Boolean = false) =
        MapGestures.decide(true, press, menu, marker, poi, empty)

    // --- quick taps never change anything ---
    @Test fun quickTapDoesNothing() {
        assertEquals(Action.None, plan(Press.TAP))
        assertEquals(Action.None, plan(Press.TAP, poi = true))
        assertEquals(Action.None, plan(Press.TAP, marker = 1, poi = true))
    }
    @Test fun quickTapClosesAnOpenMenu() {
        assertEquals(Action.CloseMenu, plan(Press.TAP, menu = true))
        assertEquals(Action.CloseMenu, plan(Press.TAP, poi = true, menu = true))
        assertEquals(Action.CloseMenu, plan(Press.TAP, marker = 2, menu = true))
    }

    // --- hold ---
    @Test fun holdOnMapOrLineAddsPoint() = assertEquals(Action.AddPoint, plan(Press.HOLD))
    @Test fun holdOnPoiOpensPoiMenu() = assertEquals(Action.PoiMenu, plan(Press.HOLD, poi = true))
    @Test fun holdOnMarkerOpensPointMenu() = assertEquals(Action.PointMenu(1), plan(Press.HOLD, marker = 1))
    @Test fun poiThatIsAStopActsLikeItsMarker() =
        // a POI already on the route has its marker on top of it: never added twice
        assertEquals(Action.PointMenu(2), plan(Press.HOLD, marker = 2, poi = true))

    @Test fun holdNearAPointAsks() {
        assertEquals(Action.NearPointChoice(3), MapGestures.decide(true, Press.HOLD, false, -1, false, false, near = 3))
        assertEquals(Action.PointMenu(1), MapGestures.decide(true, Press.HOLD, false, 1, false, false, near = 3))
        assertEquals(Action.PoiMenu, MapGestures.decide(true, Press.HOLD, false, -1, true, false, near = 3))
        assertEquals(Action.None, MapGestures.decide(true, Press.TAP, false, -1, false, false, near = 3))
        assertEquals(Buzz.ONE, MapGestures.buzz(true, false, -1, false)) // near zone: 1× like the map
    }

    // --- empty plan: the first hold asks how to start ---
    @Test fun firstHoldOnEmptyPlanOpensFirstPointMenu() {
        assertEquals(Action.FirstPointMenu, plan(Press.HOLD, empty = true))
        assertEquals(Action.PoiMenu, plan(Press.HOLD, poi = true, empty = true)) // a POI keeps its own menu
        assertEquals(Action.None, plan(Press.TAP, empty = true))
        assertEquals(Action.CloseMenu, plan(Press.HOLD, menu = true, empty = true)) // dismissing adds nothing
    }
    @Test fun fromMyLocationNeedsAFreshFix() {
        assertEquals("No location permission", MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.NO_PERMISSION, false))
        assertEquals("Location off", MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.OFF, true))
        assertEquals("Searching GPS…", MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.SEARCHING, true))
        assertEquals("Searching GPS…", MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.GOOD, false))
        assertEquals(null, MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.WEAK, true))
        assertEquals(null, MapGestures.fromMeBlocked(com.onpath.app.recording.GpsStatus.GOOD, true))
    }

    // --- a menu is open ---
    @Test fun holdOnEmptyMapOnlyClosesTheMenu() = assertEquals(Action.CloseMenu, plan(Press.HOLD, menu = true))
    @Test fun holdOnOtherTargetSwitchesMenu() {
        assertEquals(Action.PointMenu(1), plan(Press.HOLD, marker = 1, menu = true))
        assertEquals(Action.PoiMenu, plan(Press.HOLD, poi = true, menu = true))
    }

    // --- vibration at the moment the hold is recognised (owner 2026-10-21: on the press, never after the action) ---
    @Test fun holdVibratesOnceOnMapLineOrPoi() {
        assertEquals(Buzz.ONE, MapGestures.buzz(true, false, -1, false)) // empty map / line → add point, first-point menu
        assertEquals(Buzz.ONE, MapGestures.buzz(true, false, -1, true))  // POI menu
        assertEquals(Buzz.ONE, MapGestures.buzz(true, true, -1, true))   // POI with another menu open
    }
    @Test fun holdOnRoutePointVibratesTwice() {
        assertEquals(Buzz.TWO, MapGestures.buzz(true, false, 0, false))
        assertEquals(Buzz.TWO, MapGestures.buzz(true, false, 0, true))   // a POI that is a stop acts like its marker
        assertEquals(Buzz.TWO, MapGestures.buzz(true, true, 3, false))   // switching from another menu
    }
    @Test fun noRingWhereTheHoldDoesNothing() {
        assertEquals(Buzz.NONE, MapGestures.buzz(true, true, -1, false)) // only closes the menu
        assertEquals(Buzz.NONE, MapGestures.buzz(false, false, 1, true)) // Navigate
    }

    // --- point menu: only sensible options ---
    @Test fun pointMenuOnStart() {
        assertEquals(listOf(PointAction.DELETE), MapGestures.pointMenu(0, 4, isPlace = false))
        assertEquals(listOf(PointAction.GOOGLE, PointAction.DELETE), MapGestures.pointMenu(0, 4, isPlace = true))
    }
    @Test fun pointMenuOnMiddlePoint() = assertEquals(
        listOf(PointAction.MAKE_START, PointAction.END_HERE, PointAction.GOOGLE, PointAction.DELETE),
        MapGestures.pointMenu(1, 4, isPlace = true))
    @Test fun pointMenuOnDestination() =
        assertEquals(listOf(PointAction.DELETE), MapGestures.pointMenu(3, 4, isPlace = false))
    @Test fun pointMenuRoundTrip() {
        assertEquals(listOf(PointAction.LOOP_START, PointAction.DELETE), MapGestures.pointMenu(2, 5, isPlace = false, roundTrip = true))
        assertEquals(listOf(PointAction.DELETE), MapGestures.pointMenu(0, 5, isPlace = false, roundTrip = true))
        assertEquals(listOf(PointAction.DELETE), MapGestures.pointMenu(4, 5, isPlace = false, roundTrip = true))
    }
    @Test fun pointMenuOnSinglePoint() = assertEquals(listOf(PointAction.DELETE), MapGestures.pointMenu(0, 1, isPlace = false))

    // --- Navigate / shown track: read-only ---
    @Test fun navigateIsReadOnly() {
        for (p in Press.entries) {
            assertEquals(Action.None, MapGestures.decide(false, p, false, 1, false, false))
            assertEquals(Action.None, MapGestures.decide(false, p, false, -1, false, true))
        }
        assertEquals(Action.PoiDetails, MapGestures.decide(false, Press.TAP, false, -1, true, false))
        assertEquals(Action.None, MapGestures.decide(false, Press.HOLD, false, -1, true, false))
    }

    // --- nearest marker ---
    @Test fun nearestMarkerWithinRadius() {
        val xs = floatArrayOf(100f, 130f, 400f)
        val ys = floatArrayOf(100f, 100f, 400f)
        assertEquals(1, MapGestures.nearestMarker(xs, ys, 120f, 102f, 30f)) // overlapping: nearest wins
        assertEquals(0, MapGestures.nearestMarker(xs, ys, 105f, 98f, 30f))
        assertEquals(2, MapGestures.nearestMarker(xs, ys, 420f, 410f, 30f))
        assertEquals(-1, MapGestures.nearestMarker(xs, ys, 250f, 250f, 30f))
        assertEquals(-1, MapGestures.nearestMarker(FloatArray(0), FloatArray(0), 0f, 0f, 30f))
    }
    @Test fun sameSpotPicksTopMarker() = assertEquals(1, MapGestures.nearestMarker(floatArrayOf(50f, 50f), floatArrayOf(50f, 50f), 50f, 50f, 30f))
    @Test fun radiusEdgeCounts() = assertEquals(0, MapGestures.nearestMarker(floatArrayOf(0f), floatArrayOf(0f), 30f, 0f, 30f))

    // --- overlay placement ---
    @Test fun overlaySitsCentredAboveAnchor() =
        assertEquals(400 to 340, MapGestures.placeOverlay(500f, 500f, 200, 100, 0, 0, 1000, 2000, 60))
    @Test fun overlayGoesBelowWhenNoRoomAbove() =
        assertEquals(400 to 160, MapGestures.placeOverlay(500f, 100f, 200, 100, 0, 50, 1000, 2000, 60))
    @Test fun overlayKeptInsideHorizontally() {
        assertEquals(16, MapGestures.placeOverlay(10f, 500f, 200, 100, 16, 0, 1000, 2000, 60).first)
        assertEquals(784, MapGestures.placeOverlay(990f, 500f, 200, 100, 16, 0, 984, 2000, 60).first)
    }
    @Test fun overlayClampedWhenNeitherFits() {
        val (_, y) = MapGestures.placeOverlay(500f, 150f, 200, 100, 0, 100, 1000, 260, 60)
        assertTrue(y in 100..160)
    }
    @Test fun anchorVisibility() {
        assertTrue(MapGestures.anchorVisible(10f, 10f, 100, 100))
        assertFalse(MapGestures.anchorVisible(-1f, 10f, 100, 100))
        assertFalse(MapGestures.anchorVisible(10f, 101f, 100, 100))
    }
}
