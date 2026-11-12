package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The distance at the locate button while I am off the map in Plan (owner 2026-11-07, variant 3). */
class LocateDistanceTest {
    private val meran = LocateDistance.View(46.62, 11.10, 46.72, 11.22)
    private val hilden = GeoPoint(51.17, 6.94)

    @Test fun farAwayInPlanShowsTheDistance() {
        val l = LocateDistance.label(plan = true, located = true, me = hilden, view = meran)
        assertNotNull(l)
        assertTrue("about 590 km Hilden → Meran (straight line): $l", l!!.endsWith(" km") && l.removeSuffix(" km").toInt() in 560..620)
    }

    @Test fun noLabelWhenIAmInView() {
        assertNull(LocateDistance.label(true, true, GeoPoint(46.67, 11.16), meran))
    }

    @Test fun noLabelWithoutAPositionOrOutsidePlan() {
        assertNull("no position: Plan is quiet about GPS", LocateDistance.label(true, located = false, me = hilden, view = meran))
        assertNull(LocateDistance.label(true, true, me = null, view = meran))
        assertNull("Navigate unchanged", LocateDistance.label(plan = false, located = true, me = hilden, view = meran))
        assertNull("view unknown", LocateDistance.label(true, true, hilden, null))
    }

    @Test fun calmText() {
        assertEquals("850 m", LocateDistance.text(847.0))
        assertEquals("50 m", LocateDistance.text(54.0))
        assertTrue(LocateDistance.text(3_240.0).let { it == "3,2 km" || it == "3.2 km" })
        assertEquals("790 km", LocateDistance.text(790_400.0))
        assertEquals("whole km from 10 km – fits in the round button", "59 km", LocateDistance.text(59_200.0))
        assertEquals("10 km", LocateDistance.text(9_960.0))
    }

    @Test fun throttleNeeds50mOr5Percent() {
        val a = GeoPoint(51.17, 6.94)
        assertTrue("first position", LocateDistance.moved(null, a, null))
        assertFalse("20 m, label 600 km", LocateDistance.moved(a, GeoPoint(51.17018, 6.94), 600_000.0))
        assertTrue("≥ 50 m", LocateDistance.moved(a, GeoPoint(51.1705, 6.94), 600_000.0))
        assertTrue("≥ 5 % of a short distance (30 m of 400 m)", LocateDistance.moved(a, GeoPoint(51.17027, 6.94), 400.0))
    }

    @Test fun tapFramesBothOnlyWhileTheLabelShows() {
        assertEquals(LocateDistance.Tap.FRAME_BOTH, LocateDistance.tap(labelShown = true))
        assertEquals(LocateDistance.Tap.AS_BEFORE, LocateDistance.tap(labelShown = false))
    }

    @Test fun viewFromTheCameraKeyAndAcrossTheDateLine() {
        assertEquals(LocateDistance.View(1.0, 2.0, 3.0, 4.0, ), LocateDistance.View.of(listOf(1.0, 2.0, 3.0, 4.0, 1.0)))
        assertNull(LocateDistance.View.of(listOf(1.0)))
        val dateLine = LocateDistance.View(-10.0, 170.0, 10.0, -170.0)
        assertTrue(LocateDistance.inView(GeoPoint(0.0, 179.0), dateLine))
        assertTrue(LocateDistance.inView(GeoPoint(0.0, -175.0), dateLine))
        assertFalse(LocateDistance.inView(GeoPoint(0.0, 0.0), dateLine))
        assertEquals(180.0, kotlin.math.abs(dateLine.centre.lon), 1e-9)
    }
}
