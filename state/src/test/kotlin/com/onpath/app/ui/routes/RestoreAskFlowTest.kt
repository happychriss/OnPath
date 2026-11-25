package com.onpath.app.ui.routes

import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.SharedPlan
import com.onpath.app.ui.TrackThen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Deinen alten Plan wiederherstellen?" (owner 2026-11-24, [RestoreAsk]) as flows of the REAL slices ([RoutesFlowBase]): a new
 * plan that replaces the plan on the map outside a tour asks; Behalten leaves everything as it is; Wiederherstellen swaps – the
 * old plan back with its activity, T level, pinned points, detour points and saved link, the new one under Zuletzt with its own;
 * a newer replacement asks about its own old plan; the question goes with its entry; nothing is lost either way.
 */
class RestoreAskFlowTest : RoutesFlowBase() {
    private val anchor = GeoPoint(46.690, 11.130) // between a and b, at the precision the store keeps
    private val own = mapOf(1 to Anchors(before = listOf(anchor)))
    private val d = GeoPoint(46.720, 11.090)

    /** The old plan: a hike T3 over a, b, c with a detour point before b and b pinned – never saved. */
    private fun App.oldHike() {
        planRoute.activity = Activity.HIKE; planRoute.sac = 3
        plan.pin(b)
        plan.arriving(listOf(a, b, c), AnchorSet.of(own, Activity.HIKE, 3))
        plan.edit { clear(); addAll(listOf(a, b, c)) }
    }

    /** My routes' list as the box reads it (the app reads it again after every keep). */
    private fun App.listed() { slice.refreshTours(); settle() }

    private fun App.box() = RestoreAsk.box(save.restoreAsk, routes.tours, touring = touring)

    private fun link(vararg pts: GeoPoint, activity: Activity = Activity.BIKE_TOURING, sac: Int = 2) =
        SharedPlan(pts.toList(), activity, sac, emptySet(), emptyMap(), "From link")

    @Test fun aPlanLinkThatReplacesThePlanAsksAndRestoreSwapsBack() {
        val app = App()
        app.oldHike()
        app.go.openSharedPlan(link(a, c, d)); app.settle(); app.listed()
        val ask = app.save.restoreAsk!!
        val old = app.drafts().single()
        assertEquals("the question is about the old plan's Zuletzt entry", old.id, ask.entryId)
        assertEquals(RestoreBox("Route 31 Oct, 10:12", underRecent = true), app.box())
        assertEquals(listOf(a, c, d), app.plan.points.toList())

        app.go.answerRestore(true); app.settle(); app.listed()
        assertNull("answered: the box goes, and the way back asks nothing again", app.save.restoreAsk)
        assertEquals("the old plan is back", listOf(a, b, c), app.plan.points.toList())
        assertEquals(Activity.HIKE, app.planRoute.activity); assertEquals(3, app.planRoute.sac)
        assertEquals("its pinned point", setOf(1), app.plan.pinnedIndices())
        assertEquals("its detour points", own, app.plan.anchorsFor(Activity.HIKE, 3))
        // the link's plan went under Zuletzt with ITS own activity; the old plan's entry waits in the bin while it is on the map
        val kept = app.draftRows.last()
        assertEquals("BIKE_TOURING", kept.activity)
        assertEquals(listOf(a, c, d).map { it.lat to it.lon }, kept.waypoints.map { it.lat to it.lon })
        assertEquals("the new plan is the Zuletzt plan now", listOf(app.routes.recentId), app.drafts().map { it.id })
        assertTrue("the old entry is not lost: in the recycle bin while its plan is on the map", db.listDeleted().any { it.id == old.id })

        // the old plan leaves the map again unchanged: its entry comes back out – one entry, no copy
        app.save.keepAsDraft("cleared"); app.settle()
        assertTrue(db.listDeleted().none { it.id == old.id })
        assertEquals(1, db.listTours().count { it.draft && it.waypoints.map { w -> w.lat to w.lon } == listOf(a, b, c).map { p -> p.lat to p.lon } })
    }

    @Test fun keepLeavesEverythingAsItIs() {
        val app = App()
        app.oldHike()
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 2, a, c)); app.settle(); app.listed()
        assertNotNull(app.box())
        val before = db.listTours().map { it.id } to db.listDeleted().map { it.id }
        app.go.answerRestore(false); app.settle(); app.listed()
        assertNull(app.save.restoreAsk); assertNull(app.box())
        assertEquals(listOf(a, c), app.plan.points.toList())
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
        assertEquals("nothing written, nothing moved", before, db.listTours().map { it.id } to db.listDeleted().map { it.id })
    }

    /** A saved route left unchanged does not go under Zuletzt: the box says it is saved; Restore opens it again, the new plan goes under Zuletzt. */
    @Test fun aSavedRouteLeftUnchangedIsSavedAndRestoreOpensItAgain() {
        val app = App()
        val alm = planned("Alm", "HIKE", 4, a, b, c)
        app.slice.openTour(alm); app.settle()
        app.go.openSharedPlan(link(a, c, d)); app.settle(); app.listed()
        assertEquals("no draft of a saved route as it is", 0, app.drafts().size)
        assertEquals(RestoreBox("Alm", underRecent = false), app.box())

        app.go.answerRestore(true); app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals(Activity.HIKE, app.planRoute.activity); assertEquals(4, app.planRoute.sac)
        assertEquals("linked to the saved route again", alm.id, app.plan.savedId)
        assertEquals("the link's plan is under Zuletzt", "BIKE_TOURING", app.drafts().single().activity)
    }

    /** A saved route switched to another activity after loading (points unchanged) comes back with the activity it had on the map. */
    @Test fun aSavedRouteSwitchedAfterLoadingComesBackWithItsProfileOnTheMap() {
        val app = App()
        app.slice.openTour(planned("Alm", "HIKE", 4, a, b, c)); app.settle()
        app.planRoute.activity = Activity.MTB
        app.go.openSharedPlan(link(a, c, d)); app.settle(); app.listed()
        app.go.answerRestore(true); app.settle()
        assertEquals(Activity.MTB, app.planRoute.activity)
    }

    /** A saved route changed, then replaced: Restore brings the changed plan back still linked to the route (Save overwrites it, as before). */
    @Test fun aChangedSavedRouteComesBackLinkedToItsRoute() {
        val app = App()
        val alm = planned("Alm", "HIKE", 2, a, b, c)
        app.slice.openTour(alm); app.settle()
        app.plan.edit { add(d) }
        assertTrue(app.plan.unsaved)
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 2, a, c)); app.settle(); app.listed()
        val box = app.box()!!
        assertTrue("the change is under Zuletzt", box.underRecent)

        app.go.answerRestore(true); app.settle()
        assertEquals(listOf(a, b, c, d), app.plan.points.toList())
        assertEquals("linked to the saved route it changed", alm.id, app.plan.savedId)
        assertEquals("Alm", app.plan.savedName)
        assertTrue("still a change of it", app.plan.unsaved)
    }

    @Test fun aNewerReplacementAsksAboutItsOwnOldPlan() {
        val app = App()
        app.oldHike()
        val rad = planned("Rad", "BIKE_TOURING", 2, a, c)
        app.slice.openTour(rad); app.settle()
        val first = app.save.restoreAsk!!
        app.slice.openTour(planned("Berg", "HIKE", 2, b, c)); app.settle(); app.listed()
        val second = app.save.restoreAsk!!
        assertTrue(first.entryId != second.entryId)
        assertEquals("the box asks about the plan just replaced: the saved route Rad", rad.id, second.entryId)
        assertEquals(RestoreBox("Rad", underRecent = false), app.box())
    }

    @Test fun theQuestionGoesWithItsEntry() {
        val app = App()
        app.oldHike()
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 2, a, c)); app.settle(); app.listed()
        val old = app.drafts().single()
        app.recent.deleteDraft(old); app.settle(); app.listed()
        assertNull("deleted: no box", app.box())
        app.go.answerRestore(true); app.settle()
        assertEquals("a late answer restores nothing", listOf(a, c), app.plan.points.toList())
    }

    @Test fun noBoxDuringATourAndNoneForAReplacementDuringOne() {
        val app = App()
        app.oldHike()
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 2, a, c)); app.settle(); app.listed()
        assertNotNull(RestoreAsk.box(app.save.restoreAsk, app.routes.tours, touring = false))
        assertNull("not while a tour runs", RestoreAsk.box(app.save.restoreAsk, app.routes.tours, touring = true))
        app.save.dropRestoreAsk("a tour started")
        assertNull(app.save.restoreAsk)
        // a route opened during a tour: the tour's "Neuer Plan – Rückgängig", no box
        app.touring = true
        app.slice.openTour(planned("Berg", "HIKE", 2, b, c), duringTour = true); app.settle()
        assertNull(app.save.restoreAsk)
    }

    @Test fun anOldPlanOfLessThanTwoPointsAsksNothing() {
        for (n in 0..1) {
            val app = App()
            if (n == 1) app.plan.edit { add(a) }
            app.slice.openTour(planned("Rad", "BIKE_TOURING", 2, a, c)); app.settle()
            assertNull("$n point(s): nothing to restore", app.save.restoreAsk)
        }
    }

    @Test fun clearingThePlanAsksNothing() {
        val app = App()
        app.oldHike()
        app.save.keepAsDraft("cleared"); app.plan.edit { clear() }; app.settle()
        assertNull(app.save.restoreAsk)
    }

    /** A track's plan ("Als Plan übernehmen") replaces the plan too: it asks, and Restore brings the hike back with its own profile. */
    @Test fun aTracksPlanAsksAndRestoreBringsTheOldProfileBack() {
        val app = App()
        app.oldHike()
        app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3, a, c, d), trackThen = TrackThen.PLAN); app.settle(); app.listed()
        assertEquals(RestoreBox("Route 31 Oct, 10:12", underRecent = true), app.box())
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
        app.go.answerRestore(true); app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals(Activity.HIKE, app.planRoute.activity); assertEquals(3, app.planRoute.sac)
        assertEquals(own, app.plan.anchorsFor(Activity.HIKE, 3))
        assertEquals("the track's plan is under Zuletzt with its own activity", "BIKE_TOURING", app.drafts().single().activity)
        assertTrue(db.listTours().none { it.kind == TourKind.PLANNED && it.draft && it.activity == "HIKE" })
    }
}
