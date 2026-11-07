package com.onpath.app.recording

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorded track for the app's state (field 2026-11-07: "Main thread blocked 695 ms" in the recording tick). Before,
 * every new point copied the whole track for [RecState] (`track.map { it.toList() }`) on the main thread – O(points) per
 * second, O(points²) over a tour. A snapshot now costs O(pieces) and never changes once taken.
 */
class GrowingTrackTest {

    private fun p(i: Int) = GeoPoint(51.0 + i * 1e-5, 6.9, i.toDouble())

    @Test fun `snapshot has the pieces and points in order`() {
        val t = GrowingTrack()
        assertTrue(t.isEmpty())
        assertEquals(emptyList<List<GeoPoint>>(), t.snapshot())
        t.newSegment(); (0 until 3).forEach { t.add(p(it)) }
        t.newSegment(); (3 until 1500).forEach { t.add(p(it)) }
        val s = t.snapshot()
        assertEquals(listOf((0 until 3).map(::p), (3 until 1500).map(::p)), s)
        assertEquals(1500, t.pointCount())
        assertEquals(p(1499), s.last().last())
    }

    @Test fun `a point without a piece starts one`() {
        val t = GrowingTrack()
        t.add(p(0))
        assertEquals(listOf(listOf(p(0))), t.snapshot())
    }

    @Test fun `an empty piece stays (as the stored segments are restored)`() {
        val t = GrowingTrack()
        t.newSegment(); t.newSegment(); t.add(p(1))
        assertEquals(listOf(emptyList(), listOf(p(1))), t.snapshot())
    }

    @Test fun `a snapshot taken earlier never changes`() {
        val t = GrowingTrack()
        (0 until 700).forEach { t.add(p(it)) }
        val before = t.snapshot()
        (700 until 5000).forEach { t.add(p(it)) }
        t.newSegment(); t.add(p(9999))
        assertEquals(1, before.size)
        assertEquals(700, before[0].size)
        assertEquals((0 until 700).map(::p), before[0])
        assertEquals((0 until 700).map(::p), before[0].toList()) // iterator too
        assertEquals(2, t.snapshot().size)
    }

    @Test fun `snapshots of the same state are equal, a newer one is not`() {
        val t = GrowingTrack()
        (0 until 10).forEach { t.add(p(it)) }
        val a = t.snapshot(); val b = t.snapshot()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        t.add(p(10))
        assertTrue(a != t.snapshot())
    }

    @Test fun `a snapshot per point of a 100 000 point tour stays cheap`() {
        val t = GrowingTrack()
        t.newSegment()
        val t0 = System.nanoTime()
        var last: List<List<GeoPoint>> = emptyList()
        for (i in 0 until 100_000) {
            t.add(p(i))
            last = t.snapshot() // what publish() does after every point
        }
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(100_000, last[0].size)
        // copying the whole track per point (the old way) is 5·10⁹ element copies here – many seconds; O(1) is a few ms
        assertTrue("100 000 points with a snapshot each took $ms ms", ms < 1500)
    }
}
