package com.onpath.app.ui.routes

import com.onpath.app.routing.Activity
import com.onpath.app.routing.AnchorSet
import com.onpath.app.routing.Anchors
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanCode
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.SharedPlan
import com.onpath.app.ui.TrackThen
import com.onpath.app.data.TourKind
import com.onpath.app.gpx.GpxWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Another plan takes the map's place (docs/design/detour-stitch.md): the old one is kept whole, the new one brings its own anchors. */
class RoutesAnchorsFlowTest : RoutesFlowBase() {
    private val anchor = GeoPoint((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
    private val own = mapOf(1 to Anchors(before = listOf(anchor)))

    private fun App.withStitchedHike() {
        planRoute.activity = Activity.HIKE; planRoute.sac = 2
        plan.arriving(listOf(a, b, c), AnchorSet.of(own, Activity.HIKE, 2))
        plan.edit { clear(); addAll(listOf(a, b, c)) }
    }

    @Test fun aRouteOfAnotherProfileLeavesTheOldPlanWholeUnderZuletzt() {
        val app = App()
        app.withStitchedHike()
        app.slice.openTour(planned("Rad", "BIKE_TOURING", 3, a, c)); app.settle()
        val row = app.draftRows.single()
        assertEquals("its own activity, not the new route's", "HIKE", row.activity)
        assertEquals(2, row.sac)
        assertEquals(own, row.anchors)
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
    }

    @Test fun aPlanLinkLeavesTheOldPlanWholeAndBringsItsOwnAnchors() {
        val app = App()
        app.withStitchedHike()
        val other = GeoPoint(c.lat + 0.01, c.lon)
        val theirs = mapOf(1 to Anchors(after = listOf(GeoPoint(c.lat + 0.005, c.lon))))
        val link = PlanCode.decode(PlanCode.encode(SharedPlan(listOf(a, c, other), Activity.BIKE_TOURING, 2, anchors = theirs)))!!
        app.go.openSharedPlan(link); app.settle()
        val row = app.draftRows.single()
        assertEquals("HIKE", row.activity); assertEquals(own, row.anchors)
        assertEquals(theirs, app.plan.anchorsFor(Activity.BIKE_TOURING, 2))
    }

    @Test fun aRouteOpenedDuringATourOnAnotherProfileRoutesWithoutItsAnchors() {
        val app = App()
        app.planRoute.activity = Activity.BIKE_TOURING
        val t = planned("Alm", "HIKE", 2, a, b, c).copy(anchors = own)
        app.slice.openTour(t, duringTour = true); app.settle()
        assertEquals(emptyMap<Int, Anchors>(), app.plan.anchorsFor(app.planRoute.activity, app.planRoute.sac))
    }

    /** Finding 1 of the review: "Als Plan übernehmen" of a bike track switched the activity BEFORE the old plan went under Zuletzt. */
    @Test fun aTrackOfAnotherProfileMadeAPlanLeavesTheOldPlanWholeUnderZuletzt() {
        val app = App()
        app.withStitchedHike()
        app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3), trackThen = TrackThen.PLAN); app.settle()
        val row = app.draftRows.single()
        assertEquals("its own activity, not the track's", "HIKE", row.activity)
        assertEquals(2, row.sac)
        assertEquals("with its detour points", own, row.anchors)
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
    }

    /** A take that does not happen (no routing data, no way to re-trace) leaves Zuletzt as it was: the plan stays on the map (review problem 1). */
    @Test fun aTakeThatDoesNotHappenKeepsNothingUnderZuletzt() {
        for (case in listOf("no routing data", "failed fit")) {
            val app = App()
            app.withStitchedHike()
            // an older entry that a keep would push into the recycle bin (takePlace)
            val older = db.insertTour(TourKind.PLANNED, "Older", "HIKE", 2, listOf(seg(c, a)), listOf(c, a).map { GpxWaypoint(it.lat, it.lon, null) }, draft = true)
            if (case == "no routing data") app.missingParts = listOf("part") else app.router.straight = true
            app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3), trackThen = TrackThen.PLAN); app.settle()
            assertTrue("$case: nothing was kept", app.draftRows.isEmpty())
            assertEquals("$case: the plan stays", listOf(a, b, c), app.plan.points.toList())
            assertTrue("$case: nothing went to the recycle bin", db.listDeleted().isEmpty())
            assertEquals("$case: the older entry stays", listOf(older), db.listTours().filter { it.draft }.map { it.id })
            db.deleteTour(older)
        }
    }

    /** The track only SHOWN switches the activity at once; its later "Als Plan übernehmen" still keeps the plan under its own profile and anchors (problem 6). */
    @Test fun aTrackShownFirstAndTakenLaterKeepsThePlanUnderItsOwnProfile() {
        val app = App()
        app.withStitchedHike()
        app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3), trackThen = TrackThen.SHOW); app.settle()
        assertEquals(Activity.BIKE_TOURING, app.planRoute.activity)
        assertTrue("shown only: nothing kept yet", app.draftRows.isEmpty())
        app.slice.planFromShown(); app.settle()
        val row = app.draftRows.single()
        assertEquals("HIKE", row.activity); assertEquals(2, row.sac); assertEquals(own, row.anchors)
    }

    /** The user changed the activity again after the track switched it: then the plan's current profile counts (the note is stale). */
    @Test fun aProfileChangedAfterTheSwitchIsNotOverwrittenByTheNote() {
        val app = App()
        app.withStitchedHike()
        app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3), trackThen = TrackThen.SHOW); app.settle()
        app.planRoute.selectActivity(Activity.HIKE)
        app.slice.planFromShown(); app.settle()
        assertEquals("HIKE", app.draftRows.single().activity)
    }

    private fun route(km: Double) = PlannedRoute(listOf(PlannedLeg(listOf(a, b, c), false)), km * 1000, 0.0, 0.0, km * 600, null)

    /**
     * Second review of the readers (2026-11-24), finding 1: the track's switch re-routes the old plan on the new profile at once.
     * The plan under Zuletzt must carry ITS route (as it was before the switch), not the new profile's – also without anchors.
     */
    @Test fun aTrackTakenAfterTheSwitchKeepsTheOldPlanWithItsOwnRoute() {
        val app = App()
        app.planRoute.activity = Activity.HIKE; app.planRoute.sac = 2
        app.plan.edit { clear(); addAll(listOf(a, b, c)) }; app.routeNow = route(10.0)
        app.slice.openTour(recorded("Ride", "BIKE_TOURING", 3), trackThen = TrackThen.SHOW); app.settle()
        app.routeNow = route(30.0) // the recalc the switch asked for
        app.slice.planFromShown(); app.settle()
        val row = app.draftRows.single()
        assertEquals("HIKE", row.activity)
        assertEquals("the hike's route, not the bike's", 10_000.0, row.distanceM, 0.1)
    }

    /** The same for a round trip's take (the caller switched the profile before the fit). */
    @Test fun aRoundTripTakenAfterTheSwitchKeepsTheOldPlanWithItsOwnRoute() {
        val app = App()
        app.planRoute.activity = Activity.HIKE; app.planRoute.sac = 2
        app.plan.edit { clear(); addAll(listOf(a, b, c)) }; app.routeNow = route(10.0)
        val own = app.slice.leavingNow()
        app.planRoute.selectActivity(Activity.BIKE_TOURING)
        app.routeNow = route(30.0)
        app.slice.planFromLine(listOf(a, b, c), listOf("part"), Activity.BIKE_TOURING, 2, own) { }; app.settle()
        val row = app.draftRows.single()
        assertEquals("HIKE", row.activity); assertEquals(10_000.0, row.distanceM, 0.1)
    }

    /** Finding 2: the note of a switch holds only while the plan is the one it was taken of – an edited plan keeps its current profile. */
    @Test fun aSwitchNoteOfAnEditedPlanDoesNotRelabelIt() {
        val app = App()
        app.planRoute.activity = Activity.HIKE; app.planRoute.sac = 2
        app.plan.edit { clear(); addAll(listOf(a, b, c)) }
        val t = recorded("Ride", "BIKE_TOURING", 3)
        app.slice.openTour(t, trackThen = TrackThen.SHOW); app.settle(); app.slice.closeShown()
        app.plan.edit { add(GeoPoint(c.lat + 0.01, c.lon)) }
        app.slice.openTour(t, trackThen = TrackThen.PLAN); app.settle()
        assertEquals("BIKE_TOURING", app.draftRows.single().activity)
    }
}
