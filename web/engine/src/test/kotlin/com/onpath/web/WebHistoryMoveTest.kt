package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page stops a long plan by starting a fresh engine worker (web-planner.md §26); the undo / redo history goes
 * along as text (Engine.historyExport → historyImport) and works on exactly as before – an empty plan step included.
 */
class WebHistoryMoveTest {
    @Test fun undoAndRedoGoOnTheSameInTheFreshEngine() {
        Engine.historyImport("") // a clean history (the object is shared by the tests)
        Engine.historyRecord("")
        Engine.historyRecord("46.670000,11.160000")
        Engine.historyRecord("46.670000,11.160000;46.680000,11.200000")
        assertEquals("46.670000,11.160000;46.680000,11.200000",
            Engine.historyStep("undo", "46.670000,11.160000;46.680000,11.200000;46.690000,11.210000"))
        val moved = Engine.historyExport()
        assertEquals("~|46.670000,11.160000#46.670000,11.160000;46.680000,11.200000;46.690000,11.210000", moved)

        Engine.historyImport("") // what a fresh worker has
        assertEquals("{\"canUndo\":false,\"canRedo\":false}", Engine.historyState())
        Engine.historyImport(moved)
        assertEquals("{\"canUndo\":true,\"canRedo\":true}", Engine.historyState())
        assertEquals("46.670000,11.160000", Engine.historyStep("undo", "46.670000,11.160000;46.680000,11.200000"))
        assertEquals("", Engine.historyStep("undo", "46.670000,11.160000"))
        assertEquals("{\"canUndo\":false,\"canRedo\":true}", Engine.historyState())
    }
}
