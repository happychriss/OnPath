package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Second read 2026-11-27: a drift fix dropped after a long gap must not use the gap up – the next kept fix starts the piece. */
class GapSplitTest {
    private val last = TrackPoint(51.1692447, 6.9254626, null, 0)

    @Test fun aDroppedDriftFixKeepsTheSplitForTheNextFix() {
        val g = GapSplit()
        // 10 min gap, the first fix 33 m off at 49 m while still: drift, dropped – the split waits
        val first = g.split(wasLost = true, bridged = false, hasPrev = true)
        assertTrue(first)
        val drift = TrackPoint(51.1693648, 6.9259077, null, 600_000)
        val (v1, _) = PointFilter.judge(last, drift, 49.4, continuous = !first, still = true)
        assertEquals(PointFilter.Verdict.STANDING_STILL, v1)
        g.judged(first, kept = false)
        // the next fix comes within seconds (no gap by time) and is a real move: it is still the new piece, nothing counted across
        val next = g.split(wasLost = false, bridged = false, hasPrev = true)
        assertTrue("the gap is not used up", next)
        val moved = TrackPoint(51.1692447, 6.9297, null, 605_000)
        val (v2, d2) = PointFilter.judge(last, moved, 40.0, continuous = !next, still = false)
        assertEquals(PointFilter.Verdict.ACCEPT, v2); assertEquals("no distance across the gap", 0.0, d2, 0.0)
        g.judged(next, kept = true)
        assertFalse("after the kept fix the track goes on as one piece", g.split(wasLost = false, bridged = false, hasPrev = true))
    }

    @Test fun aBridgedOrNoGapIsNoSplit() {
        val g = GapSplit()
        assertFalse(g.split(wasLost = true, bridged = true, hasPrev = true))
        assertFalse(g.split(wasLost = false, bridged = false, hasPrev = true))
        assertFalse("no last point (start, after a pause)", g.split(wasLost = true, bridged = false, hasPrev = false))
    }
}
