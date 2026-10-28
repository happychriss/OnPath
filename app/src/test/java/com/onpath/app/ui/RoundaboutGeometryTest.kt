package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The drawn roundabout (owner 2026-10-28): in from below, anticlockwise to the exit in the turn's direction. */
class RoundaboutGeometryTest {
    @Test fun straightOnSecondExit() {
        val a = RoundaboutGeometry.arms(2, 0f)
        assertEquals(0f, a.exitDeg, 0.1f); assertEquals(180f, a.sweepDeg, 0.1f)
        assertEquals("one exit passed, on the right", listOf(90f), a.passedDeg)
    }
    @Test fun rightFirstExit() {
        val a = RoundaboutGeometry.arms(1, 90f)
        assertEquals(90f, a.exitDeg, 0.1f); assertEquals(90f, a.sweepDeg, 0.1f); assertEquals(emptyList<Float>(), a.passedDeg)
    }
    @Test fun leftThirdExit() {
        val a = RoundaboutGeometry.arms(3, -90f)
        assertEquals(-90f, a.exitDeg, 0.1f); assertEquals(270f, a.sweepDeg, 0.1f)
        assertEquals(listOf(90f, 0f), a.passedDeg)
    }
    @Test fun unknownAngleIsStraightOn() = assertEquals(0f, RoundaboutGeometry.arms(0, null).exitDeg, 0.1f)
    @Test fun neverAFullCircle() = assertEquals(340f, RoundaboutGeometry.arms(4, -175f).sweepDeg.coerceAtMost(340f), 0.1f)
}
