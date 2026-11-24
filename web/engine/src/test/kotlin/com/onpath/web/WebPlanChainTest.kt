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

    private val a = com.onpath.app.routing.GeoPoint(50.0, 7.0)
    private val b = com.onpath.app.routing.GeoPoint(50.1, 7.1)
    private val c = com.onpath.app.routing.GeoPoint(50.05, 7.2)

    /** The page's commit + Undo / Redo through the shared PlanEditState, on the worker's one history. */
    @Test
    fun recordUndoRedo() {
        val h = com.onpath.app.domain.PlanHistory()
        val chain = WebPlanChain(history = h)
        chain.record(listOf(a, b), listOf(a, c, b))
        assertTrue(h.canUndo)
        org.junit.Assert.assertEquals(listOf(a, b), chain.step(undo = true, now = listOf(a, c, b)))
        org.junit.Assert.assertEquals(listOf(a, c, b), chain.step(undo = false, now = listOf(a, b)))
        org.junit.Assert.assertNull(chain.step(undo = false, now = listOf(a, c, b))) // nothing to redo
    }

    /** Cancel takes back the change that started the run (PlanStatus.onCancel via PlanEditState.cancelRun). */
    @Test
    fun cancelTakesBackTheCause() {
        val h = com.onpath.app.domain.PlanHistory()
        val chain = WebPlanChain(history = h)
        chain.record(listOf(a, b), listOf(a, c, b))
        org.junit.Assert.assertEquals("UNDO" to listOf(a, b), chain.cancel("edit", listOf(a, c, b), null, 2))
        // the undo of that edit, cancelled: redone
        org.junit.Assert.assertEquals("REDO" to listOf(a, c, b), chain.cancel("undo", listOf(a, b), null, 2))
        org.junit.Assert.assertEquals("RESTORE_ACTIVITY" to null,
            chain.cancel("activity", listOf(a, c, b), com.onpath.app.routing.Activity.HIKE, 3))
        org.junit.Assert.assertEquals("KEEP_POINTS" to null, chain.cancel("other", listOf(a, c, b), null, 2))
    }

    /** A history restored in a fresh worker (historyImport) is what a new chain reads: Cancel can undo across it. */
    @Test
    fun aRestoredHistoryCanBeUndone() {
        val h = com.onpath.app.domain.PlanHistory()
        WebPlanChain(history = h).record(listOf(a, b), listOf(a, c, b))
        val (u, r) = h.snapshot()
        val h2 = com.onpath.app.domain.PlanHistory().apply { restore(u, r) }
        org.junit.Assert.assertEquals("UNDO" to listOf(a, b), WebPlanChain(history = h2).cancel("edit", listOf(a, c, b), null, 2))
    }
}
