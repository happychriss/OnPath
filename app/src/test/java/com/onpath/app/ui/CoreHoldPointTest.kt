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
    private val density = 2.75f // Pixel 8
    private val radius = MapGestures.MARKER_RADIUS_DP * density
    private val nearRadius = MapGestures.NEAR_RADIUS_DP * density

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
                      downMarker: Int = -1, menuOpen: Boolean = false, poi: Boolean = false): Action {
        val m = MapGestures.markerUnderFinger(points, cam::toScreen, finger.first, finger.second, press, downMarker, radius)
        val near = if (m >= 0) -1 else MapGestures.markerUnderFinger(points, cam::toScreen, finger.first, finger.second, press, -1, nearRadius)
        return MapGestures.decide(true, press, menuOpen, m, poi = poi, planEmpty = points.isEmpty(), near = near)
    }

    /** A finger [dp] away from point [i], in direction [deg]. */
    private fun beside(cam: Camera, i: Int, dp: Float, deg: Double = 0.0): Pair<Float, Float> {
        val (x, y) = cam.toScreen(plan[i])
        val r = dp * density
        return (x + r * cos(Math.toRadians(deg))).toFloat() to (y + r * sin(Math.toRadians(deg))).toFloat()
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

    /**
     * Field 2026-10-21 (Pixel 8, 0.8.5): 4 of 21 holds were 68 dp from the point ("felt on the point", about 1 cm) and
     * added a new point. Catch zone: up to 48 dp -> that point's menu; 48-80 dp -> ask (its options / new point here);
     * beyond -> new point.
     */
    @Test fun catchZoneAt30_45_68_90dp() {
        val cam = Camera(mPerPx = 1.0) // points >= 160 dp apart: only point 2 is in reach
        for (deg in listOf(0.0, 90.0, 200.0, 315.0)) {
            assertEquals("30 dp at $deg", Action.PointMenu(1), press(plan, cam, beside(cam, 1, 30f, deg)))
            assertEquals("45 dp at $deg", Action.PointMenu(1), press(plan, cam, beside(cam, 1, 45f, deg)))
            assertEquals("68 dp at $deg", Action.NearPointChoice(1), press(plan, cam, beside(cam, 1, 68f, deg)))
            assertEquals("90 dp at $deg", Action.AddPoint, press(plan, cam, beside(cam, 1, 90f, deg)))
        }
    }

    @Test fun catchZoneNearestPointWins() {
        // two points 70 dp apart on screen: a finger 30 dp from point 1 (40 dp from point 2) -> point 1
        val pts = listOf(GeoPoint(46.672, 11.165), GeoPoint(46.672, 11.165 + 70 * density * 2.0 / (111_320 * cos(Math.toRadians(46.672)))))
        val cam = Camera(mPerPx = 2.0)
        val (x0, y0) = cam.toScreen(pts[0])
        assertEquals(Action.PointMenu(0), press(pts, cam, x0 + 30 * density to y0))
        assertEquals(Action.PointMenu(1), press(pts, cam, x0 + 40 * density to y0))
        // 60 dp left of point 1 (130 dp from point 2): ask about point 1
        assertEquals(Action.NearPointChoice(0), press(pts, cam, x0 - 60 * density to y0))
    }

    @Test fun catchZoneCountsForPinnedPoiStopsAndBeatsAnotherPoi() {
        // a pinned POI stop is a route point like any other (MapHost passes the whole plan list)
        val cam = Camera(mPerPx = 1.0)
        assertEquals(Action.PointMenu(1), press(plan, cam, beside(cam, 1, 40f), poi = true))
        // a different POI right under the finger wins over a point 68 dp away (the POI is what the finger is on)
        assertEquals(Action.PoiMenu, press(plan, cam, beside(cam, 1, 68f), poi = true))
    }

    @Test fun nearChoiceOnlyForAHold() {
        val cam = Camera(mPerPx = 1.0)
        assertEquals(Action.None, press(plan, cam, beside(cam, 1, 68f), press = Press.TAP))
        // with a menu open a hold near a point only closes it (dismissing never adds or asks)
        assertEquals(Action.CloseMenu, press(plan, cam, beside(cam, 1, 68f), menuOpen = true))
    }

    @Test fun holdOnEmptyMapAddsAPoint() {
        val cam = Camera(); val (x, y) = cam.toScreen(plan[1])
        assertEquals(Action.AddPoint, press(plan, cam, x + 2 * nearRadius to y))
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
