package com.onpath.app.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Perf report 2026-10-22 finding 9: the elevation profile draws about the pixel width, not every point. */
class ProfileColumnsTest {
    @Test fun fewPointsAllDrawn() {
        val d = doubleArrayOf(0.0, 10.0, 20.0)
        assertArrayEquals(intArrayOf(0, 1, 2), columnIndices(d, doubleArrayOf(1.0, 2.0, 3.0), 20.0, 300))
    }

    @Test fun longRouteDownsampledKeepingPeaks() {
        val n = 10_001 // 250 km, a point every 25 m
        val d = DoubleArray(n) { it * 25.0 }
        val e = DoubleArray(n) { 500 + 300 * kotlin.math.sin(it / 300.0) + (if (it % 7 == 0) 3.0 else 0.0) }
        e[4_321] = 3_000.0 // a summit
        e[8_765] = 10.0 // a valley
        val keep = columnIndices(d, e, d.last(), 300)
        assertTrue("${keep.size}", keep.size <= 4 * 300)
        assertEquals(0, keep.first()); assertEquals(n - 1, keep.last())
        assertTrue(4_321 in keep && 8_765 in keep)
        for (k in 1 until keep.size) assertTrue(keep[k] > keep[k - 1]) // in order, no repeats
        // the drawn range is the real range
        assertEquals(e.max(), keep.maxOf { e[it] }, 0.0)
        assertEquals(e.min(), keep.minOf { e[it] }, 0.0)
    }
}
