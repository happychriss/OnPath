package com.onpath.app.ui.routes

import com.onpath.app.data.TourKind
import com.onpath.app.ui.routes.RouteRowActions.Action
import com.onpath.app.ui.routes.RouteRowActions.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every action in exactly one place per My routes row (owner 2026-11-07 09:40). */
class RouteRowActionsTest {

    private fun allRows() = buildList {
        for (kind in TourKind.entries) for (rec in listOf(false, true)) for (rides in listOf(false, true))
            for (dist in listOf(false, true)) for (wp in listOf(0, 1, 2, 5)) for (marked in listOf(false, true))
                for (canSave in listOf(false, true)) {
                    add(RouteRowActions.of(Section.SAVED, kind, rec, rides, dist, wp, marked, canSave))
                    add(RouteRowActions.of(Section.DRAFT, kind, rec, rides, dist, wp, marked, canSave))
                    add(RouteRowActions.of(Section.LIVE, kind, rec, rides, dist, wp, marked, canSave))
                }
    }

    @Test fun noActionIsOnTheRowAndInTheMenuAtOnce() {
        for (r in allRows()) {
            assertTrue("inline ${r.inline} ∩ menu ${r.menu}", r.inline.intersect(r.menu.toSet()).isEmpty())
            assertEquals("no duplicates in the menu", r.menu.size, r.menu.toSet().size)
        }
    }

    @Test fun zuletztKeepsSaveAndDeleteOnTheRowAndNotInTheMenu() {
        val r = RouteRowActions.of(Section.DRAFT, TourKind.PLANNED)
        assertEquals(listOf(Action.SAVE, Action.DELETE), r.inline)
        assertFalse(Action.SAVE in r.menu); assertFalse(Action.DELETE in r.menu)
        assertTrue(Action.RENAME in r.menu); assertTrue(Action.EXPORT_GPX in r.menu)
        assertTrue("a tap opens the overview", r.opensOverview)
    }

    @Test fun savedAndDoneAreTheCleanListWithAChevronAndEverythingInTheMenu() {
        val plan = RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 3)
        assertTrue(plan.inline.isEmpty()); assertTrue(plan.chevron); assertTrue(plan.opensOverview)
        assertEquals(listOf(Action.RENAME, Action.COPY, Action.EXPORT_GPX, Action.SHARE, Action.DELETE), plan.menu)
        val done = RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasRides = true)
        assertTrue(done.inline.isEmpty()); assertTrue(done.chevron)
        assertEquals(listOf(Action.PLAN_FROM, Action.RENAME, Action.EXPORT_GPX, Action.EDIT_RIDES, Action.DELETE), done.menu)
    }

    @Test fun theTourBeingRecordedIsNeverDeletedOrPlannedFromHere() {
        val r = RouteRowActions.of(Section.SAVED, TourKind.RECORDED, recording = true, hasRides = true)
        assertFalse(Action.DELETE in r.menu); assertFalse(Action.PLAN_FROM in r.menu); assertFalse(Action.EDIT_RIDES in r.menu)
    }

    @Test fun aDoneTourWithoutALineIsNoPlan() {
        assertFalse(Action.PLAN_FROM in RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasDistance = false).menu)
    }

    @Test fun thePlanOnTheMapStaysAsItWas() {
        val top = RouteRowActions.of(Section.LIVE, TourKind.PLANNED, canSaveLive = true)
        assertEquals(listOf(Action.SAVE), top.inline); assertTrue(top.menu.isEmpty())
        assertFalse(top.chevron); assertFalse("a tap goes back to the plan", top.opensOverview)
        assertTrue(RouteRowActions.of(Section.LIVE, TourKind.PLANNED, canSaveLive = false).inline.isEmpty())
        val tagged = RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 3, liveMarked = true, canSaveLive = true)
        assertEquals(listOf(Action.SAVE), tagged.inline); assertFalse(tagged.chevron); assertFalse(tagged.opensOverview)
    }

    @Test fun aValueNeverBreaksInside() {
        val f = RouteRowActions.facts("4,1 km", "82 m", "1 h 15 min")
        assertEquals("4,1 km · ↑ 82 m · 1 h 15 min", f)
        assertEquals("breaks only between values", 2, f.count { it == ' ' } / 2)
    }
}
