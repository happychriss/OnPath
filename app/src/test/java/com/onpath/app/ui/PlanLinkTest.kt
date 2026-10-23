package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field test 0.10.1 (owner 2026-10-23): a new plan in Hilden still showed "Gasthof Taser Alm → Gasthof Greitererhof
 * · changed, not saved". A plan that no longer shares a point with the loaded / saved route is a new, unsaved plan;
 * real edits keep the link; Undo brings it back. Steps as AppViewModel takes them (PlanHistory, RouteRules).
 */
class PlanLinkTest {
    private val taser = GeoPoint(46.6801, 11.1902)
    private val via = GeoPoint(46.6850, 11.2000)
    private val greiterer = GeoPoint(46.6900, 11.2101)
    private val loaded = listOf(taser, via, greiterer)
    private val hilden1 = GeoPoint(51.1679, 6.9230)
    private val hilden2 = GeoPoint(51.1700, 6.9300)

    /** What the plan sheet shows as the saved name (AppViewModel.savedName). */
    private fun shownName(points: List<GeoPoint>) = "Gasthof Taser Alm → Gasthof Greitererhof"
        .takeIf { RouteRules.linkedToSaved(points, loaded) }

    @Test fun loadedRouteIsLinked() {
        assertTrue(RouteRules.linkedToSaved(loaded, loaded))
        assertFalse(RouteRules.isUnsaved(loaded, loaded))
        assertFalse(RouteRules.linkedToSaved(loaded, null))
        assertFalse(RouteRules.linkedToSaved(emptyList(), loaded))
    }

    @Test fun realEditsKeepTheName() {
        val moved = listOf(taser, GeoPoint(46.6860, 11.2010), greiterer)
        val added = loaded + GeoPoint(46.70, 11.22)
        val deleted = listOf(taser, greiterer)
        val ele = listOf(taser.copy(ele = 900.0), via, greiterer)
        for (p in listOf(moved, added, deleted)) {
            assertTrue(RouteRules.linkedToSaved(p, loaded))
            assertTrue("· changed, not saved", RouteRules.isUnsaved(p, loaded))
            assertEquals("Gasthof Taser Alm → Gasthof Greitererhof", shownName(p))
        }
        assertTrue(RouteRules.linkedToSaved(ele, loaded)) // elevation does not count
    }

    @Test fun clearThenNewPlanFarAwayIsUnsavedAndUnnamed() {
        val h = PlanHistory()
        var plan = loaded
        h.record(plan); plan = emptyList() // Clear
        assertEquals(null, shownName(plan))
        h.record(plan); plan = listOf(hilden1) // first point in Hilden
        h.record(plan); plan = listOf(hilden1, hilden2)
        assertEquals(null, shownName(plan))
        assertTrue(RouteRules.isUnsaved(plan, loaded))
        // Save asks for a new name (the id of the old route is not used while unlinked)
        val id = if (RouteRules.linkedToSaved(plan, loaded)) 7L else -1L
        assertEquals(SaveMode.NAME_DIALOG, RouteRules.saveMode(id, shownName(plan), listOf(7L)))
        // Undo back to the loaded route: the link and the name are back, nothing unsaved
        plan = h.undo(plan)!!; plan = h.undo(plan)!!; plan = h.undo(plan)!!
        assertEquals(loaded, plan)
        assertEquals("Gasthof Taser Alm → Gasthof Greitererhof", shownName(plan))
        assertFalse(RouteRules.isUnsaved(plan, loaded))
        assertEquals(SaveMode.OVERWRITE_OR_NEW, RouteRules.saveMode(7L, shownName(plan), listOf(7L)))
    }

    @Test fun everyPointReplacedEndsTheLink() {
        var plan = loaded
        plan = listOf(hilden1, via, greiterer)
        assertTrue(RouteRules.linkedToSaved(plan, loaded))
        plan = listOf(hilden1, hilden2)
        assertFalse(RouteRules.linkedToSaved(plan, loaded))
    }
}
