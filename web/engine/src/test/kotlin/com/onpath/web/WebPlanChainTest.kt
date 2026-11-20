package com.onpath.web

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The app's plan-editing chain builds and runs over the planner's ports (WebPlanChain.probe, the same call the Wasm check makes). */
class WebPlanChainTest {
    @Test
    fun aCancelledEditIsUndone() {
        val out = WebPlanChain.probe()
        assertFalse(out, out.contains("\"error\""))
        assertTrue(out, out.contains("\"afterEdit\":3,\"points\":2,\"canRedo\":true"))
        assertTrue(out, out.contains("recalc"))
    }
}
