package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 10-minute health line in the event log (Health.line). */
class HealthLineTest {
    @Test fun fullLine() {
        val l = Health.line(Health.Sample(
            pssKb = 2_200_000, nativePssKb = 1_570_000, javaPssKb = 100_000, graphicsKb = 210_000,
            nativeHeapBytes = 1_580_000_000, cpuMs = 204_000, wallMs = 600_000, frames = 12_000, jankyFrames = 576,
            storeChanges = 37, cells = 37, cellQueue = 20, routingQueue = 3,
            swapKb = 420_000, javaUsedBytes = 96_000_000, mapGeoJsonBytes = 23_400_000, searchEntries = 167_202,
            styleLoads = 2, styleData = "g000093",
        ))
        assertEquals("Health: PSS 2.10 GB (swap 0.40; native 1.50, java 0.10, gfx 0.20), native heap 1.47 GB allocated, " +
            "java used 0.09 GB, map GeoJSON 22 MB, search 167202 entries, style loads 2 (g000093), CPU 0.34 cores " +
            "(204 s in 10 min), janky 4.8 % of 12000 frames, store changes 37 / 37 cells, queue 20 regions + 3 routing parts", l)
    }

    @Test fun unknownsAndNoFrames() {
        val l = Health.line(Health.Sample(null, null, null, null, 0, 0, 0, 0, 0, null, null, null, null))
        assertTrue(l, l.startsWith("Health: PSS ? (swap ?; native ?, java ?, gfx ?)"))
        assertTrue(l, l.contains("map GeoJSON ?, search not built, style loads 0 (–)"))
        assertTrue(l, l.contains("no frames drawn"))
        assertTrue(l, l.contains("queue 0 regions + 0 routing parts"))
    }
}
