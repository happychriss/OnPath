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
                    for (touring in listOf(false, true)) for (hasPlan in listOf(false, true)) {
                        add(RouteRowActions.of(Section.SAVED, kind, rec, rides, dist, wp, marked, canSave, touring, hasPlan))
                        add(RouteRowActions.of(Section.DRAFT, kind, rec, rides, dist, wp, marked, canSave, touring, hasPlan))
                        add(RouteRowActions.of(Section.LIVE, kind, rec, rides, dist, wp, marked, canSave, touring, hasPlan))
                    }
                }
    }

    @Test fun noActionIsOnTheRowAndInTheMenuAtOnce() {
        for (r in allRows()) {
            assertTrue("inline ${r.inline} ∩ menu ${r.menu}", r.inline.intersect(r.menu.toSet()).isEmpty())
            assertEquals("no duplicates in the menu", r.menu.size, r.menu.toSet().size)
        }
    }

    @Test fun zuletztKeepsSaveAndDeleteOnTheRowAndNotInTheMenu() {
        val r = RouteRowActions.of(Section.DRAFT, TourKind.PLANNED, waypoints = 3)
        // owner 2026-11-07: "es muss klar sein, dass man diese Routen bearbeiten kann" – Planen first on the row
        assertEquals(listOf(Action.PLAN, Action.SAVE, Action.DELETE), r.inline)
        assertFalse(Action.SAVE in r.menu); assertFalse(Action.DELETE in r.menu)
        assertEquals(listOf(Action.GO, Action.RENAME, Action.EXPORT_GPX), r.menu)
        assertTrue("a tap opens the overview", r.opensOverview)
    }

    @Test fun savedPlannedRoutesOfferPlanenAndLosGehtsFirstInTheMenu() {
        val plan = RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 3)
        assertEquals(listOf(Action.PLAN, Action.GO), plan.menu.take(2))
        assertFalse("a route without two points has nothing to follow",
            Action.GO in RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 1).menu)
        assertFalse("done tours stay as they were", Action.GO in RouteRowActions.of(Section.SAVED, TourKind.RECORDED).menu)
        assertFalse(Action.PLAN in RouteRowActions.of(Section.SAVED, TourKind.IMPORTED).menu)
    }

    @Test fun duringATourNeitherPlanenNorLosGehtsIsOffered() {
        // a tap / the overview's "Folgen" follows at once (owner 2026-11-06 22:05) – Planen would load it as the followed plan
        for (s in listOf(Section.SAVED, Section.DRAFT)) {
            val r = RouteRowActions.of(s, TourKind.PLANNED, waypoints = 3, touring = true)
            assertFalse(Action.PLAN in r.inline + r.menu); assertFalse(Action.GO in r.inline + r.menu)
        }
        assertEquals(listOf(Action.SAVE, Action.DELETE), RouteRowActions.of(Section.DRAFT, TourKind.PLANNED, waypoints = 3, touring = true).inline)
    }

    @Test fun thePlanOnTheMapGetsNoPlanenOrLosGehts() {
        // a tap goes back to the plan; its sheet has Los geht's
        val tagged = RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 3, liveMarked = true)
        assertFalse(Action.PLAN in tagged.menu); assertFalse(Action.GO in tagged.menu)
    }

    @Test fun savedAndDoneAreTheCleanListWithAChevronAndEverythingInTheMenu() {
        val plan = RouteRowActions.of(Section.SAVED, TourKind.PLANNED, waypoints = 3)
        assertTrue(plan.inline.isEmpty()); assertTrue(plan.chevron); assertTrue(plan.opensOverview)
        assertEquals(listOf(Action.PLAN, Action.GO, Action.RENAME, Action.COPY, Action.EXPORT_GPX, Action.SHARE, Action.DELETE), plan.menu)
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

    /**
     * Owner 2026-11-07: from a recorded tour under Gemacht back to the plan it followed – "Planen" (blue) and "Los geht's"
     * (green) on top of its ⋮, as for a saved planned route; "Als Plan übernehmen" is then left out.
     */
    @Test fun aDoneTourWithItsFollowedPlanOffersPlanenAndLosGehtsFirst() {
        val r = RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasRides = true, hasPlan = true)
        assertEquals(listOf(Action.PLAN, Action.GO, Action.RENAME, Action.EXPORT_GPX, Action.EDIT_RIDES, Action.DELETE), r.menu)
        assertTrue(r.inline.isEmpty()); assertTrue(r.opensOverview)
    }

    /** Old tours and "Nur aufzeichnen" have no plan: "Als Plan übernehmen" (a plan from the track) stays the way back. */
    @Test fun aDoneTourWithoutAPlanKeepsMakeAPlanFromTheTrack() {
        val r = RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasPlan = false)
        assertFalse(Action.PLAN in r.menu); assertFalse(Action.GO in r.menu)
        assertEquals(Action.PLAN_FROM, r.menu.first())
    }

    /** No Planen / Los geht's during a tour, and never on the tour being recorded (the planned routes' rule). */
    @Test fun theFollowedPlanIsNotOfferedDuringATourOrOnTheRecordingTour() {
        for (r in listOf(RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasPlan = true, touring = true),
                RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasPlan = true, recording = true, touring = true),
                RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasPlan = true, recording = true))) {
            assertFalse(Action.PLAN in r.menu); assertFalse(Action.GO in r.menu)
        }
        assertTrue("during a tour the track → plan item stays as before",
            Action.PLAN_FROM in RouteRowActions.of(Section.SAVED, TourKind.RECORDED, hasPlan = true, touring = true).menu)
        assertFalse("an imported track has no followed plan", Action.GO in RouteRowActions.of(Section.SAVED, TourKind.IMPORTED, hasPlan = true).menu)
    }

    @Test fun aValueNeverBreaksInside() {
        val f = RouteRowActions.facts("4,1 km", "82 m", "1 h 15 min")
        assertEquals("4,1 km · ↑ 82 m · 1 h 15 min", f)
        assertEquals("breaks only between values", 2, f.count { it == ' ' } / 2)
    }
}
