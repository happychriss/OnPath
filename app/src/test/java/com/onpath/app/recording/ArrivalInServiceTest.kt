package com.onpath.app.recording

import com.onpath.app.routing.ArrivalGate
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review H2: arrival only worked with the app on screen; in the pocket the rider walked on to the car park and got
 * off-route alarms and re-routes. The service checks it on every GPS fix ([GuidanceGate.arrivalCheck]), then guidance is
 * quiet, recording goes on (E2E: Rundgang flow 2 – a tour to the end, the arrival question).
 */
class ArrivalInServiceTest {
    @Test fun afterTheArrivalTheGuidanceIsQuiet() {
        // before: off-route checks while moving, junction cues, the voice
        assertTrue(GuidanceGate.offRouteCheck(moving = true, arrived = false))
        assertTrue(GuidanceGate.junctionCue(arrived = false))
        assertTrue(GuidanceGate.voiceSteps(voiceOn = true, arrived = false))
        // after: none of them – the track is still recorded (the gate has no say over points)
        assertFalse(GuidanceGate.offRouteCheck(moving = true, arrived = true))
        assertFalse(GuidanceGate.junctionCue(arrived = true))
        assertFalse(GuidanceGate.voiceSteps(voiceOn = true, arrived = true))
        // standing still: no off-route check either (the navigator waits as without GPS)
        assertFalse(GuidanceGate.offRouteCheck(moving = false, arrived = false))
    }

    @Test fun arrivalIsCheckedOnceNotPausedNotInPlan() {
        assertTrue(GuidanceGate.arrivalCheck(arrived = false, paused = false, planQuiet = false))
        assertFalse("asked once", GuidanceGate.arrivalCheck(arrived = true, paused = false, planQuiet = false))
        assertFalse("paused", GuidanceGate.arrivalCheck(arrived = false, paused = true, planQuiet = false))
        assertFalse("Plan is silent", GuidanceGate.arrivalCheck(arrived = false, paused = false, planQuiet = true))
    }

    /** The gate the service uses: away first, then near the end with little left. */
    @Test fun gate() {
        val g = ArrivalGate()
        val end = GeoPoint(46.0, 11.0)
        assertFalse(g.onFix(end, end, 0.0, 5.0)) // never away yet (round trip start)
        assertFalse(g.onFix(GeoPoint(46.01, 11.0), end, 1100.0, 5.0))
        assertTrue(g.onFix(GeoPoint(46.0001, 11.0), end, 10.0, 5.0))
        assertFalse("asked once", g.onFix(end, end, 0.0, 5.0))
    }
}
