package com.onpath.app.domain.plan

import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ganz neu berechnen" in the session (docs/design/detour-stitch.md §2): one undoable step, same points, no anchors. */
class PlanSessionClearAnchorsTest {
    private fun g(lat: Double) = GeoPoint(lat, 11.0)
    private val a = g(46.10); private val x = g(46.15); private val p = g(46.20); private val y = g(46.25); private val c = g(46.30)
    private val pts = listOf(a, p, c)
    private val anchors = mapOf(1 to Anchors(listOf(x), listOf(y)))
    private val set = AnchorSet.of(anchors, Activity.HIKE, 2)

    private fun session() = PlanSession(pts.toMutableList(), anchorsAtStart = set)

    @Test fun clearKeepsThePointsAndDropsTheAnchorsInOneStep() {
        val s = session()
        assertTrue(s.clearAnchors())
        assertEquals(pts, s.points.toList())
        assertEquals(AnchorSet.NONE, s.anchorSet)
        assertTrue(s.canUndo)
    }

    @Test fun clearWithoutAnchorsRecordsNothing() {
        val s = PlanSession(pts.toMutableList())
        assertFalse(s.clearAnchors())
        assertFalse(s.canUndo)
    }

    @Test fun undoBringsTheAnchorsBackWithTheirProfileAndRedoDropsThemAgain() {
        val s = session()
        s.clearAnchors()
        s.replace(s.undo()!!)
        assertEquals(pts, s.points.toList())
        assertEquals(set, s.anchorSet) // anchors and the profile they lie on
        assertEquals(anchors, s.anchorsFor(Activity.HIKE, 2))
        s.replace(s.redo()!!)
        assertEquals(AnchorSet.NONE, s.anchorSet)
        assertFalse(s.canRedo)
        s.replace(s.undo()!!)
        assertEquals(set, s.anchorSet)
    }

    @Test fun anEditAfterTheClearStitchesNormallyAndUndoWalksBack() {
        val s = session()
        s.clearAnchors()
        s.edit { removeAt(1) }
        assertEquals(emptyMap<Int, Anchors>(), s.anchors) // nothing to carry along
        s.replace(s.undo()!!) // the cleared plan
        assertEquals(AnchorSet.NONE, s.anchorSet)
        s.replace(s.undo()!!) // before the clear
        assertEquals(set, s.anchorSet)
        assertEquals(pts, s.points.toList())
    }

    @Test fun aNewEditDropsTheRedoStepOfTheClear() {
        val s = session()
        s.clearAnchors()
        s.replace(s.undo()!!)
        s.edit { add(g(46.4)) }
        assertFalse(s.canRedo)
    }
}
