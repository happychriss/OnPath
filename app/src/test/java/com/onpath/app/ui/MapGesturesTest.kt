package com.onpath.app.ui

import com.onpath.app.ui.MapGestures.Action
import com.onpath.app.ui.MapGestures.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGesturesTest {
    private fun plan(press: Press, marker: Int = -1, poi: Boolean = false, onLine: Boolean = false,
                     overlay: Boolean = false, hinted: Boolean = false) =
        MapGestures.decide(true, press, overlay, marker, poi, onLine, hinted)

    // --- empty map / route line ---
    @Test fun quickTapOnEmptyMapOnlyHintsOnce() {
        assertEquals(Action.HoldHint, plan(Press.TAP))
        assertEquals(Action.None, plan(Press.TAP, hinted = true))
    }
    @Test fun holdAddsPoint() = assertEquals(Action.AddPoint, plan(Press.HOLD))
    @Test fun holdOnLineAddsPointToo() = assertEquals(Action.AddPoint, plan(Press.HOLD, onLine = true))
    @Test fun longPressOnLineDragsTheLine() = assertEquals(Action.LineDrag, plan(Press.LONG_PRESS, onLine = true))
    @Test fun longPressOnEmptyMapAdds() = assertEquals(Action.AddPoint, plan(Press.LONG_PRESS))

    // --- POI ---
    @Test fun poiTapOpensGoogleBubble() = assertEquals(Action.PoiBubble, plan(Press.TAP, poi = true))
    @Test fun poiHoldAddsPoi() = assertEquals(Action.AddPoi, plan(Press.HOLD, poi = true))
    @Test fun poiLongPressAddsPoi() = assertEquals(Action.AddPoi, plan(Press.LONG_PRESS, poi = true, onLine = true))
    @Test fun poiThatIsAStopActsLikeItsMarker() {
        // a POI already on the route has its marker on top of it: never added twice
        assertEquals(Action.PointOverlay(2), plan(Press.HOLD, marker = 2, poi = true))
        assertEquals(Action.PointOverlay(2), plan(Press.TAP, marker = 2, poi = true))
    }

    // --- markers win over POIs and the line ---
    @Test fun markerTapOpensDeleteOverlay() = assertEquals(Action.PointOverlay(1), plan(Press.TAP, marker = 1, poi = true, onLine = true))
    @Test fun markerHoldDoesNotAdd() = assertEquals(Action.PointOverlay(0), plan(Press.HOLD, marker = 0, onLine = true))
    @Test fun markerLongPressDrags() = assertEquals(Action.DragPoint(3), plan(Press.LONG_PRESS, marker = 3, poi = true))

    // --- an overlay is open ---
    @Test fun tapElsewhereClosesOverlay() {
        assertEquals(Action.CloseOverlay, plan(Press.TAP, overlay = true))
        assertEquals(Action.CloseOverlay, plan(Press.HOLD, overlay = true))
        assertEquals(Action.CloseOverlay, plan(Press.HOLD, poi = true, overlay = true))
        assertEquals(Action.CloseOverlay, plan(Press.LONG_PRESS, onLine = true, overlay = true))
    }
    @Test fun tapOnOtherTargetSwitchesOverlay() {
        assertEquals(Action.PointOverlay(1), plan(Press.TAP, marker = 1, overlay = true))
        assertEquals(Action.PoiBubble, plan(Press.TAP, poi = true, overlay = true))
        assertEquals(Action.DragPoint(1), plan(Press.LONG_PRESS, marker = 1, overlay = true))
    }

    // --- Navigate / shown track: read-only ---
    @Test fun navigateIsReadOnly() {
        for (p in Press.entries) {
            assertEquals(Action.None, MapGestures.decide(false, p, false, 1, false, true, false))
            assertEquals(Action.None, MapGestures.decide(false, p, false, -1, false, false, false))
        }
        assertEquals(Action.PoiDetails, MapGestures.decide(false, Press.TAP, false, -1, true, false, false))
        assertEquals(Action.None, MapGestures.decide(false, Press.HOLD, false, -1, true, false, false))
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
