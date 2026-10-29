package com.onpath.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 10-minute health line: readable in one glance, unknown values never break it. */
class HealthLineTest {
    @Test fun fullLine() {
        val l = Health.line(Health.Sample(
            pssKb = 2_200_000, nativePssKb = 1_570_000, javaPssKb = 100_000, graphicsKb = 210_000,
            nativeHeapBytes = 1_580_000_000, cpuMs = 204_000, wallMs = 600_000, frames = 12_000, jankyFrames = 576,
            storeChanges = 37, cells = 37, cellQueue = 20, routingQueue = 3,
        ))
        assertEquals("Health: PSS 2.10 GB (native 1.50, java 0.10, gfx 0.20), native heap 1.47 GB, CPU 0.34 cores " +
            "(204 s in 10 min), janky 4.8 % of 12000 frames, store changes 37 / 37 cells, queue 20 regions + 3 routing parts", l)
    }

    @Test fun unknownsAndNoFrames() {
        val l = Health.line(Health.Sample(null, null, null, null, 0, 0, 0, 0, 0, null, null, null, null))
        assertTrue(l, l.startsWith("Health: PSS ? (native ?, java ?, gfx ?)"))
        assertTrue(l, l.contains("no frames drawn"))
        assertTrue(l, l.contains("queue 0 regions + 0 routing parts"))
    }
}
