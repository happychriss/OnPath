package com.onpath.app.recording

import com.onpath.app.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Review M2: points that could not be written wait and are written later, in order – none lost while in memory. */
class PointQueueTest {
    private fun pt(i: Int) = PointQueue.Pending(1L, i, 0, TrackPoint(46.0 + i * 1e-4, 11.0, null, null), 5f)

    @Test fun aStoreThatFailsThreeTimesLosesNothing() {
        val q = PointQueue()
        val stored = mutableListOf<Int>()
        var failures = 3
        val write: (PointQueue.Pending) -> Unit = { if (failures > 0) { failures--; throw IllegalStateException("disk full") } else stored += it.seq }
        for (i in 0 until 6) { q.add(pt(i)); q.flush(write) }
        assertEquals((0 until 6).toList(), stored)
        assertEquals(0, q.size)
    }

    @Test fun stopsAtTheFirstFailureAndKeepsTheRest() {
        val q = PointQueue()
        (0 until 3).forEach { q.add(pt(it)) }
        val stored = mutableListOf<Int>()
        assertNotNull(q.flush { if (it.seq == 1) throw IllegalStateException("locked") else stored += it.seq })
        assertEquals(listOf(0), stored)
        assertEquals(2, q.size)
        assertNull(q.flush { stored += it.seq })
        assertEquals(listOf(0, 1, 2), stored)
    }

    @Test fun boundedMemory() {
        val q = PointQueue(max = 2)
        (0 until 5).forEach { q.add(pt(it)) }
        assertEquals(2, q.size); assertEquals(3, q.dropped)
    }
}
