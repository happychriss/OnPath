package com.onpath.app.ui

import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Field bug (owner 2026-10-30): "Start navigation · Hike" with no route – the tour then started with nothing to follow.
 * One rule ([PlanStart]) for the words and for what Start does: without a line the button is honest,
 * "Start recording · Hike" (a plain recording, the same as ☰ → Start recording); with a line (also a straight-line
 * fallback) it stays "Start navigation · …". Before the fix the label ignored the line: the plan sheet's label said
 * "Start navigation" for 0 / 1 point (the sheet shows its button only from two points – PlanBar), and the track panel
 * said it for a track without a line (an empty recorded tour, a GPX with one point) and then followed nothing.
 * The phone check before a tour runs for both (MainActivity wraps the one start callback).
 */
class PlanStartTest {
    private val a = GeoPoint(46.67, 11.16)
    private val b = GeoPoint(46.70, 11.20)
    private val routed = PlannedRoute(listOf(PlannedLeg(listOf(a, b), false)), 4_000.0, 300.0, 0.0, 3_600.0, null)
    private val straight = PlannedRoute(listOf(PlannedLeg(listOf(a, b), true)), 4_000.0, 0.0, 0.0, 3_600.0, null)

    private fun ui(n: Int, r: PlannedRoute?, routing: Boolean = false, running: Boolean = false, act: Activity = Activity.HIKE) =
        PlanUi(n, r, routing, null, canUndo = false, canRedo = false, activity = act, tourRunning = running)

    @Test fun noPointIsARecordingNotANavigation() {
        assertEquals("Start recording · Hike", primaryLabel(ui(0, null)))
        assertTrue(ui(0, null).canStart)
        assertEquals(PlanStart.RECORD_ONLY, ui(0, null).start)
    }

    @Test fun onlyAStartPointIsARecordingToo() {
        assertEquals("Start recording · MTB", primaryLabel(ui(1, null, act = Activity.MTB)))
        assertTrue(ui(1, null).canStart)
        assertEquals(PlanStart.RECORD_ONLY, ui(1, null).start)
    }

    @Test fun aRoutedLineIsNavigated() {
        assertEquals("Start navigation · Hike", primaryLabel(ui(2, routed)))
        assertEquals(PlanStart.NAVIGATE_PLAN, ui(2, routed).start)
        // the straight-line fallback (no router / a leg not found) is a line to follow too ("Navigate anyway?" asks)
        assertEquals("Start navigation · Hike", primaryLabel(ui(2, straight)))
        assertEquals(PlanStart.NAVIGATE_PLAN, ui(2, straight).start)
    }

    /** Two points without a line yet (calculating, cancelled, failed): the button waits, as before – no recording. */
    @Test fun twoPointsWithoutALineWait() {
        for (routing in listOf(true, false)) {
            val u = ui(2, null, routing = routing)
            assertFalse(u.canStart)
            assertEquals(PlanStart.WAIT_FOR_ROUTE, u.start)
            assertEquals("Start navigation · Hike", primaryLabel(u))
        }
    }

    @Test fun aRunningTourContinues() {
        assertEquals("Continue navigation · Hike", primaryLabel(ui(0, null, running = true)))
        assertEquals("Continue navigation · Hike", primaryLabel(ui(2, routed, running = true)))
    }

    /** The view model's start (AppViewModel.startNavigation) follows the same rule; a shown track is followed first. */
    @Test fun whatStartDoes() {
        assertEquals(PlanStart.FOLLOW_TRACK, PlanStart.of(trackPoints = 120, hasRoute = false, pointCount = 0))
        assertEquals(PlanStart.FOLLOW_TRACK, PlanStart.of(trackPoints = 2, hasRoute = true, pointCount = 3))
        assertEquals(PlanStart.NAVIGATE_PLAN, PlanStart.of(trackPoints = null, hasRoute = true, pointCount = 2))
        assertEquals(PlanStart.WAIT_FOR_ROUTE, PlanStart.of(trackPoints = null, hasRoute = false, pointCount = 3))
        assertEquals(PlanStart.RECORD_ONLY, PlanStart.of(trackPoints = null, hasRoute = false, pointCount = 1))
        assertEquals(PlanStart.RECORD_ONLY, PlanStart.of(trackPoints = null, hasRoute = false, pointCount = 0))
        // ☰ → Start recording is offered exactly when the sheet's button records
        for (n in 0..3) for (r in listOf(true, false)) {
            val menu = RouteRules.menu(hasRoute = r, pointCount = n, savedCount = 0, navigating = false, recording = false)
            if (n >= 2 || !r) assertEquals("n=$n route=$r", PlanStart.of(null, r, n) == PlanStart.RECORD_ONLY, menu.startRecording)
        }
    }

    /**
     * A shown track without a line – a recorded tour ended before its first point, a GPX with one point – said
     * "Start navigation · Hike" in the track panel and followed nothing. Now it records only; during a tour the
     * button is off (nothing to switch to).
     */
    @Test fun aTrackWithoutALineOnlyRecords() {
        for (n in 0..1) assertEquals(PlanStart.RECORD_ONLY, PlanStart.of(trackPoints = n, hasRoute = true, pointCount = 3))
        assertEquals("Start recording · Hike", trackFollowLabel(recording = false, Activity.HIKE, line = false))
        assertEquals("Start navigation · Hike", trackFollowLabel(recording = false, Activity.HIKE, line = true))
        assertEquals("Navigate this track · Hike", trackFollowLabel(recording = true, Activity.HIKE, line = true))
    }

    @Test fun germanLabels() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Aufzeichnung starten · Wandern", primaryLabel(ui(0, null)))
            assertEquals("Aufzeichnung starten · Wandern", primaryLabel(ui(1, null)))
            assertEquals("Navigation starten · Wandern", primaryLabel(ui(2, routed)))
        } finally { Locale.setDefault(before) }
    }
}
