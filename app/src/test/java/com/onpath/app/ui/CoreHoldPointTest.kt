package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.MapGestures.Action
import com.onpath.app.ui.MapGestures.PointAction
import com.onpath.app.ui.MapGestures.Press
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * CORE FEATURE (docs/core-features.md C1): hold a route point → its menu → Delete. Broke twice in the field
 * (0.7.19: the map turned under the resting finger; 0.8.3: a new point was added instead of the menu). Each scenario
 * below is one way it can break; the flow runs through the same pure functions MapHost uses.
 */
class CoreHoldPointTest {
    private val plan = listOf(GeoPoint(46.670, 11.160), GeoPoint(46.672, 11.165), GeoPoint(46.675, 11.170))
    private val radius = MapGestures.MARKER_RADIUS_DP * 2.75f // px on a 2.75 density phone

    /** A tiny map camera: centre, metres per px, rotation (deg). */
    private class Camera(var lat: Double = 46.672, var lon: Double = 11.165, var mPerPx: Double = 1.0, var bearing: Double = 0.0) {
        fun toScreen(p: GeoPoint): Pair<Float, Float> {
            val dx = (p.lon - lon) * 111_320 * cos(Math.toRadians(lat)) / mPerPx
            val dy = -(p.lat - lat) * 111_320 / mPerPx
            val b = Math.toRadians(-bearing)
            return (540 + dx * cos(b) - dy * sin(b)).toFloat() to (1000 + dx * sin(b) + dy * cos(b)).toFloat()
        }
    }

    /** What MapHost does with a press: marker under the finger (plan list + finger-down fallback) → decision. */
    private fun press(points: List<GeoPoint>, cam: Camera, finger: Pair<Float, Float>, press: Press = Press.HOLD,
                      downMarker: Int = -1, menuOpen: Boolean = false): Action {
        val m = MapGestures.markerUnderFinger(points, cam::toScreen, finger.first, finger.second, press, downMarker, radius)
        return MapGestures.decide(true, press, menuOpen, m, poi = false, planEmpty = points.isEmpty())
    }

    @Test fun holdOnEveryPointOpensItsMenuWithDelete() {
        val cam = Camera()
        for (i in plan.indices) {
            assertEquals(Action.PointMenu(i), press(plan, cam, cam.toScreen(plan[i])))
            assertTrue(PointAction.DELETE in MapGestures.pointMenu(i, plan.size, isPlace = false))
        }
    }

    @Test fun holdSlightlyBesideThePointStillHitsIt() {
        val cam = Camera(); val (x, y) = cam.toScreen(plan[1])
        assertEquals(Action.PointMenu(1), press(plan, cam, x + 0.9f * radius to y))
    }

    @Test fun holdOnEmptyMapAddsAPoint() {
        val cam = Camera(); val (x, y) = cam.toScreen(plan[1])
        assertEquals(Action.AddPoint, press(plan, cam, x + 3 * radius to y))
    }

    @Test fun rotatedAndZoomedMap() {
        for (bearing in listOf(0.0, 45.0, -131.0, 180.0)) for (m in listOf(0.3, 1.0, 8.0)) {
            val cam = Camera(mPerPx = m, bearing = bearing)
            assertEquals("bearing $bearing, $m m/px", Action.PointMenu(2), press(plan, cam, cam.toScreen(plan[2])))
        }
    }

    /** 0.7.19: the map moves / turns while the finger rests (following, compass) – the point from finger-down wins. */
    @Test fun mapMovesDuringTheHold() {
        val cam = Camera()
        val finger = cam.toScreen(plan[1])
        val down = MapGestures.markerUnderFinger(plan, cam::toScreen, finger.first, finger.second, Press.HOLD, -1, radius)
        cam.lat += 0.0005; cam.bearing = 30.0 // GPS fix + compass turn under the finger
        assertEquals(Action.PointMenu(1), press(plan, cam, finger, downMarker = down))
        // a quick tap never uses the finger-down point (and never edits)
        assertEquals(Action.None, press(plan, cam, finger, press = Press.TAP, downMarker = down))
    }

    /** 0.8.3: the copy kept with the map drawing lagged behind the plan (empty / old) – the plan's list decides. */
    @Test fun drawingCopyOutOfStepDoesNotMatter() {
        val cam = Camera()
        val stale = emptyList<GeoPoint>()
        assertEquals("with a stale copy the hold adds a point (the bug)", Action.FirstPointMenu, press(stale, cam, cam.toScreen(plan[1])))
        assertEquals(Action.PointMenu(1), press(plan, cam, cam.toScreen(plan[1]))) // MapHost passes vm.waypoints
    }

    @Test fun projectionNotReadyIsNoCrashAndNoMarker() {
        val m = MapGestures.markerUnderFinger(plan, { null }, 540f, 1000f, Press.HOLD, -1, radius)
        assertEquals(-1, m)
    }

    @Test fun deleteRemovesExactlyThatPoint() {
        val after = plan.toMutableList().apply { removeAt(1) }
        assertEquals(listOf(plan[0], plan[2]), after)
        // the menu of the remaining destination still offers Delete
        assertTrue(PointAction.DELETE in MapGestures.pointMenu(1, after.size, isPlace = false))
    }
}
