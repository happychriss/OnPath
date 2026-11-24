package com.onpath.app.ui.plan

import com.onpath.app.data.MapPrefs
import com.onpath.app.data.Prefs
import com.onpath.app.routing.GeoPoint
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/**
 * The plan on the map as one object ([PlanDocument], cleanup piece 4) on the app's real preferences ([Prefs] over an
 * in-memory SharedPreferences): what the view model's source text was pinned for – every edit writes the points, the
 * next start reads them (process death / an app update mid-plan) – as behaviour, for every way the points change, the
 * pins and the saved link. What an edit means for the route, the tour and the map stays with the view model
 * (PlanFlowPropertyTest, TourFlowPropertyTest for the rules; the E2E tests for the whole).
 */
class PlanDocumentTest {
    @get:Rule val composeState = OwnComposeSnapshot()
    @get:Rule val problems = ProblemsRule()

    private val sp = MapPrefs()
    private val dir = Files.createTempDirectory("plan-doc").toFile()
    /** The app starts (again): a new document on what the preferences hold. */
    private fun start() = PlanDocument(PrefsPlanStore(Prefs(sp, dir)))

    private val a = GeoPoint(46.67171, 11.15003)
    private val b = GeoPoint(46.68512, 11.1347)
    private val c = GeoPoint(46.6902, 11.1611)
    private val here = GeoPoint(46.6600, 11.1400)

    // ---------------- the plan survives the process ----------------

    /** A history handed in (the planner keeps its own across a fresh routing worker) is the document's: its steps show at once and Undo takes them. */
    @Test fun aHistoryHandedInIsUsedAndItsStepsShowAtOnce() {
        val store = PrefsPlanStore(Prefs(sp, dir)).also { it.points = listOf(a, b) }
        val history = com.onpath.app.domain.PlanHistory().apply { record(listOf(a)) }
        val plan = PlanDocument(store, history)
        assertTrue("the step from before shows", plan.canUndo); assertFalse(plan.canRedo)
        assertEquals("Undo hands back the plan of that step", listOf(a), plan.undo())
        assertFalse(plan.canUndo); assertTrue(plan.canRedo)
        assertEquals("a step taken here is in the history outside", listOf(listOf(a, b)), history.snapshot().second)
    }

    @Test fun aFirstStartHasNoPlan() {
        val plan = start()
        assertTrue(plan.points.isEmpty()); assertTrue(plan.pinned.isEmpty())
        assertNull(plan.savedName); assertEquals(-1L, plan.savedId); assertNull(plan.savedPoints)
        assertFalse(plan.unsaved); assertFalse(plan.canUndo); assertFalse(plan.canRedo)
    }

    /** Process death / app update mid-plan: every edit writes the points, the next start reads them. */
    @Test fun everyEditIsStoredAndTheNextStartReadsIt() {
        val plan = start()
        assertEquals("the points before the edit", emptyList<GeoPoint>(), plan.edit { add(a); add(b) })
        assertEquals(listOf(a, b), start().points.toList())
        plan.edit { add(1, c) }
        assertEquals(listOf(a, c, b), start().points.toList())
        plan.edit { clear() }
        assertTrue(start().points.isEmpty())
    }

    /** Not only edits: Undo / Redo, the one-point rule's two outcomes and the router's snap write the points too. */
    @Test fun everyOtherWayThePointsChangeIsStoredToo() {
        val plan = start()
        plan.edit { add(a); add(b) }
        plan.edit { add(c) }
        plan.replace(plan.undo()!!)
        assertEquals(listOf(a, b), start().points.toList())
        plan.replace(plan.redo()!!)
        assertEquals(listOf(a, b, c), start().points.toList())
        val snapped = listOf(a, GeoPoint(46.68520, 11.13480), c)
        plan.moveSnapped(routed = listOf(a, b, c), snapped = snapped)
        assertEquals(snapped, plan.points.toList()); assertEquals(snapped, start().points.toList())
        plan.edit { clear(); add(c) }
        plan.fromHere(here, c)
        assertEquals(listOf(here, c), start().points.toList())
        assertEquals(here, plan.oneLeftStart)
        plan.empty()
        assertTrue(plan.points.isEmpty()); assertTrue(start().points.isEmpty()); assertNull(plan.oneLeftStart)
    }

    // ---------------- Undo / Redo ----------------

    @Test fun undoAndRedoAreStateTheButtonsFollow() {
        val plan = start()
        plan.edit { add(a) }
        assertTrue(plan.canUndo); assertFalse(plan.canRedo)
        plan.edit { add(b) }
        val prev = plan.undo()
        assertEquals(listOf(a), prev)
        assertEquals("taken off the stack, applied by the view model's step", listOf(a, b), plan.points.toList())
        plan.replace(prev!!)
        assertTrue(plan.canUndo); assertTrue(plan.canRedo)
        plan.replace(plan.undo()!!)
        assertFalse(plan.canUndo); assertTrue(plan.canRedo)
        assertNull("nothing left to undo", plan.undo())
        plan.replace(plan.redo()!!); plan.replace(plan.redo()!!)
        assertEquals(listOf(a, b), plan.points.toList())
        assertTrue(plan.canUndo); assertFalse(plan.canRedo); assertNull(plan.redo())
        plan.replace(plan.undo()!!)
        plan.edit { add(c) } // a new edit: Redo is gone
        assertFalse(plan.canRedo)
        assertEquals(setOf(a, c), plan.historyPoints() + plan.points)
    }

    /** History is of this run: after a restart the plan is there, Undo is not (as before). */
    @Test fun aRestartKeepsThePlanNotItsHistory() {
        start().apply { edit { add(a); add(b) }; edit { add(c) } }
        val again = start()
        assertEquals(listOf(a, b, c), again.points.toList())
        assertFalse(again.canUndo); assertFalse(again.canRedo)
    }

    /** The router's snap is no Undo step, and Undo brings back the snapped points, not the tapped ones. */
    @Test fun undoShowsTheSnappedPoints() {
        val plan = start()
        plan.edit { add(a); add(b) }
        val onTheWay = GeoPoint(46.68520, 11.13480)
        plan.moveSnapped(listOf(a, b), listOf(a, onTheWay))
        plan.snappedHistory(mapOf(b to onTheWay))
        plan.edit { add(c) }
        assertEquals(listOf(a, onTheWay), plan.undo())
        plan.replace(listOf(a, onTheWay))
        assertEquals(listOf(a, onTheWay, c), plan.redo())
    }

    // ---------------- the saved route the plan belongs to ----------------

    @Test fun thePlanIsUnsavedUntilItIsSavedAndAgainWhenItChanges() {
        val plan = start()
        plan.edit { add(a) }
        assertFalse("one point is no route yet", plan.unsaved)
        plan.edit { add(b) }
        assertTrue(plan.unsaved); assertNull(plan.savedName); assertEquals(-1L, plan.savedId)
        plan.setSaved("Algund → Meran", 7L, listOf(a, b))
        assertFalse(plan.unsaved); assertEquals("Algund → Meran", plan.savedName); assertEquals(7L, plan.savedId)
        plan.edit { add(c) }
        assertTrue("changed since", plan.unsaved)
        assertEquals("still that route: Save overwrites it", 7L, plan.savedId)
    }

    /** After Clear or with every point replaced it is a new plan; Undo back to the old points restores the link. */
    @Test fun aNewPlanHasNoSavedNameAndUndoBringsTheLinkBack() {
        val plan = start()
        plan.edit { add(a); add(b) }
        plan.setSaved("Algund → Meran", 7L, listOf(a, b))
        plan.edit { clear() }
        assertNull(plan.savedName); assertEquals(-1L, plan.savedId); assertFalse(plan.linked)
        assertEquals("the link itself is kept", 7L, plan.linkId); assertEquals("Algund → Meran", plan.linkName)
        plan.edit { add(c); add(here) }
        assertNull("somewhere else: a new plan", plan.savedName); assertTrue(plan.unsaved)
        plan.replace(plan.undo()!!); plan.replace(plan.undo()!!)
        assertEquals("Algund → Meran", plan.savedName); assertEquals(7L, plan.savedId); assertFalse(plan.unsaved)
    }

    @Test fun theSavedLinkSurvivesTheProcessAndCanBeTakenAway() {
        start().apply { edit { add(a); add(b) }; setSaved("Algund → Meran", 7L, listOf(a, b)) }
        val again = start()
        assertEquals("Algund → Meran", again.savedName); assertEquals(7L, again.savedId)
        assertEquals(listOf(a, b), again.savedPoints); assertFalse(again.unsaved)
        again.setSaved(null, -1, null) // its route was deleted
        assertTrue(again.unsaved); assertNull(again.savedName)
        val third = start()
        assertNull(third.savedName); assertEquals(-1L, third.linkId); assertNull(third.savedPoints); assertTrue(third.unsaved)
    }

    // ---------------- pinned points ----------------

    @Test fun pinsAreStoredAndComeBackWithThePlan() {
        val plan = start()
        plan.pin(b)
        plan.edit { add(a); add(b); add(c) }
        assertEquals(setOf(1), plan.pinnedIndices())
        plan.pin(listOf(c))
        val again = start()
        assertEquals(setOf(b, c), again.pinned); assertEquals(setOf(1, 2), again.pinnedIndices())
        // by value: reversed, the same points are still the pinned ones
        again.edit { reverse() }
        assertEquals(setOf(0, 1), again.pinnedIndices())
    }

    /** A pin goes when its point is neither planned nor reachable by Undo / Redo – and only then. */
    @Test fun aPinIsForgottenOnlyWhenUndoCannotBringItsPointBack() {
        val plan = start()
        plan.pin(b)
        plan.edit { add(a); add(b) }
        plan.edit { remove(b) }
        plan.prunePinned()
        assertEquals("Undo brings the point back pinned", setOf(b), plan.pinned)
        assertEquals(setOf(b), start().pinned)
        val again = start() // no history any more
        again.prunePinned()
        assertTrue(again.pinned.isEmpty())
        assertTrue("stored: the next start does not see it either", start().pinned.isEmpty())
    }
}
