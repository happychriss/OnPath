package com.onpath.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner 2026-11-06: "funktioniert das Ausgrauen immer zuverlässig im Planmodus?" – every state the map column meets, and
 * what eye / whole route / undo / redo do in it (true = can act, false = greyed). [MapControlsEnabled].
 */
class MapControlsEnabledTest {
    private val P = AppMode.PLAN
    private val N = AppMode.NAVIGATE

    /** eye, whole route, undo, redo */
    private fun check(why: String, s: MapControlsEnabled.In, eye: Boolean, whole: Boolean, undo: Boolean, redo: Boolean) =
        assertEquals(why, listOf(eye, whole, undo, redo),
            listOf(MapControlsEnabled.eye(s), MapControlsEnabled.wholeRoute(s), MapControlsEnabled.undo(s), MapControlsEnabled.redo(s)))

    @Test fun everyPlanState() {
        check("empty plan, fresh start: nothing on the map, no history",
            MapControlsEnabled.In(P), eye = false, whole = false, undo = false, redo = false)
        check("one point: no line yet; the point is an edit – Undo takes it back",
            MapControlsEnabled.In(P, canUndo = true), eye = false, whole = false, undo = true, redo = false)
        check("two points, route ready: a line to hide and to show whole",
            MapControlsEnabled.In(P, planLine = true, canUndo = true), eye = true, whole = true, undo = true, redo = false)
        check("a new route is calculated: the old line stays on the map (AppViewModel keeps route) – nothing flickers grey",
            MapControlsEnabled.In(P, planLine = true, canUndo = true), eye = true, whole = true, undo = true, redo = false)
        check("routing failed without a tour: no line (route = null), the points stay – Undo still works",
            MapControlsEnabled.In(P, planLine = false, canUndo = true), eye = false, whole = false, undo = true, redo = false)
        check("a track from My routes shown (no plan): its line; Undo would change the hidden plan behind it",
            MapControlsEnabled.In(P, shownLine = true, canUndo = true), eye = true, whole = true, undo = false, redo = false)
        check("a shown track without a line (empty recording): nothing to hide or fit",
            MapControlsEnabled.In(P, shownOpen = true), eye = false, whole = false, undo = false, redo = false)
        check("Plan during a tour that follows a track, plan empty: the followed line",
            MapControlsEnabled.In(P, followLine = true), eye = true, whole = true, undo = false, redo = false)
        check("Plan during a free tour (record only): no line",
            MapControlsEnabled.In(P), eye = false, whole = false, undo = false, redo = false)
        check("Plan during a tour with a planned route: plan line and followed line",
            MapControlsEnabled.In(P, planLine = true, followLine = true, canUndo = true), eye = true, whole = true, undo = true, redo = false)
        check("undone back to empty: nothing to undo, Redo brings the point back",
            MapControlsEnabled.In(P, canRedo = true), eye = false, whole = false, undo = false, redo = true)
        check("after Neu planen: the plan is gone, Undo brings it back",
            MapControlsEnabled.In(P, canUndo = true), eye = false, whole = false, undo = true, redo = false)
        check("the Points step (points are deleted there): Undo stays; eye / whole route have no map to act on",
            MapControlsEnabled.In(P, pointsOpen = true, planLine = true, canUndo = true), eye = false, whole = false, undo = true, redo = false)
    }

    @Test fun everyNavigateState() {
        check("free tour (record only): no line; Navigate has no undo / redo",
            MapControlsEnabled.In(N, canUndo = true), eye = false, whole = false, undo = false, redo = false)
        check("led to the start (way there): the followed line includes the lead",
            MapControlsEnabled.In(N, followLine = true), eye = true, whole = true, undo = false, redo = false)
        check("following the planned route",
            MapControlsEnabled.In(N, planLine = true, followLine = true), eye = true, whole = true, undo = false, redo = false)
    }

    /**
     * Owner 2026-11-08 (father's feedback: ↶ ↷ read as "turn the map"): undo / redo in their own pill, shown only when
     * there is something to undo or redo – hidden otherwise.
     */
    @Test fun theUndoPillShowsOnlyWithSomethingToUndoOrRedo() {
        fun pill(s: MapControlsEnabled.In) = MapControlsEnabled.undoPill(s)
        assertEquals("fresh plan: hidden", false, pill(MapControlsEnabled.In(P)))
        assertEquals("an edit: shown", true, pill(MapControlsEnabled.In(P, canUndo = true)))
        assertEquals("undone back to empty: Redo – shown", true, pill(MapControlsEnabled.In(P, canRedo = true)))
        assertEquals("both", true, pill(MapControlsEnabled.In(P, canUndo = true, canRedo = true)))
        assertEquals("a track sheet open: hidden", false, pill(MapControlsEnabled.In(P, shownLine = true, canUndo = true, canRedo = true)))
        assertEquals("Navigate: never", false, pill(MapControlsEnabled.In(N, canUndo = true, canRedo = true)))
    }
}
