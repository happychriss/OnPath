package com.onpath.app.recording

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-01: "Navigation Stopp" (restaurant) = the phone is quiet; walking off brings the guidance back. */
class StopQuietTest {
    private fun north(m: Double) = GeoPoint(51.1673 + m / 111_320.0, 6.9274)

    @Test fun stoppedIsQuietAlsoWithTheScreenOff() {
        val q = StopQuiet()
        assertFalse(q.quiet)
        q.set(stopped = true)
        assertTrue(q.quiet)
        // sitting in the restaurant, screen off, indoor drift up to 60 m: still quiet
        for (m in listOf(0.0, 20.0, 60.0, 35.0, 10.0)) assertFalse(q.onFix(north(m), 15f, appVisible = false))
        assertTrue(q.quiet)
    }

    @Test fun walkingOffWithThePhonePocketedBringsTheGuidanceBack() {
        val q = StopQuiet()
        q.set(stopped = true)
        q.onFix(north(0.0), 10f, appVisible = false)
        assertFalse(q.onFix(north(100.0), 10f, appVisible = false))
        assertTrue("150 m away: back", q.onFix(north(155.0), 10f, appVisible = false))
        assertFalse(q.quiet)
        assertFalse("said once", q.onFix(north(200.0), 10f, appVisible = false))
    }

    @Test fun badFixesAndTheAppOnScreenDoNotCount() {
        val q = StopQuiet()
        q.set(stopped = true)
        q.onFix(north(0.0), 10f, appVisible = false)
        assertFalse("indoor jump of 345 m accuracy", q.onFix(north(400.0), 345f, appVisible = false))
        assertFalse("planning on screen while walking", q.onFix(north(400.0), 10f, appVisible = true))
        assertTrue(q.quiet)
    }

    @Test fun continueEndsTheQuietAndANewStopStartsAfresh() {
        val q = StopQuiet()
        q.set(stopped = true); q.onFix(north(0.0), 10f, false); q.onFix(north(200.0), 10f, false)
        q.set(stopped = false)
        assertFalse(q.quiet)
        q.set(stopped = true)
        assertTrue(q.quiet)
        assertFalse("measured from the new stop", q.onFix(north(210.0), 10f, false))
    }
}
