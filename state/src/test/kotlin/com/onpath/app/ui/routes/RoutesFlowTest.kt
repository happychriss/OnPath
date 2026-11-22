package com.onpath.app.ui.routes

import com.onpath.app.data.AppSettings
import com.onpath.app.data.MultiDay
import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.AppMode
import com.onpath.app.domain.plan.RouteRun
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedLeg
import com.onpath.app.routing.PlannedRoute
import com.onpath.app.routing.Rd5Parts
import com.onpath.app.routing.RouteResult
import com.onpath.app.routing.RoutingEngine
import com.onpath.app.routing.StoredLine
import com.onpath.app.testkit.FakeRoutingEngine
import com.onpath.app.testkit.JdbcTours
import com.onpath.app.testkit.ManualExecutor
import com.onpath.app.testkit.OwnComposeSnapshot
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.time.TourTimes
import com.onpath.app.ui.Camera
import com.onpath.app.ui.NoticeBoard
import com.onpath.app.ui.NoticeKind
import com.onpath.app.ui.NoticePlace
import com.onpath.app.ui.ShownTrack
import com.onpath.app.ui.TrackThen
import com.onpath.app.ui.plan.PlanDocument
import com.onpath.app.ui.plan.PlanRouteHost
import com.onpath.app.ui.plan.PlanRoutePrefs
import com.onpath.app.ui.plan.PlanRouteSlice
import com.onpath.app.ui.plan.PlanStore
import com.onpath.app.ui.plan.RouterPort
import com.onpath.app.ui.plan.RoutingThreads
import com.onpath.app.ui.plan.TimesStore
import com.onpath.app.ui.tour.TourController
import com.onpath.app.ui.tour.TourRecorder
import com.onpath.strings.*
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.AbstractExecutorService

/**
 * Routes I and II (rebuild phase 2 pieces 13 and 14: the second half at the end of the file) as flows of the REAL [RoutesSlice] / [RoutesGoSlice]: My routes' list read again, a tapped route or tour
 * opened on the map, a track made a plan, Delete into the recycle bin. Real neighbours: [PlanDocument], [RoutesState] /
 * [RecentPlans], [SaveSlice] (a plan that leaves the map goes under Zuletzt), [TourController], [PlanRouteSlice] (activity,
 * leg cache, model time) and the rules of `:core`; the tables are real ([JdbcTours]). Only the edges are fakes: the
 * view model's host (every call recorded in order WITH the thread it ran on and the state at that moment), the camera, the
 * routing engine, the stored times. Three held executors stand for main / io / the background routing thread: nothing
 * runs until the test lets it, and a call that must run on io is seen to run there.
 */
class RoutesFlowTest : RoutesFlowBase() {
    @get:JRule val composeState = OwnComposeSnapshot()

    // ---------------------------------------------------------------- the list

    @Test fun theListIsReadOnIoAndShownOnMainThenThePicturesAreKicked() {
        val app = App()
        planned("One"); recorded("Two")
        app.slice.refreshTours()
        app.settle()
        // the times first (io), the rows through the model time (io), then main: shown, the pictures kicked – in this order
        assertEquals(listOf("io: store.tourTimes", "io: withMaps", "io: withMaps", "main: kickThumbs"),
            app.log.filter { !it.contains("planPoints") }.map { if (it.contains("withMaps")) it.substringBefore(" withMaps") + " withMaps" else it })
        // only the done tours: a recorded tour in progress is not a row
        assertEquals(setOf("One", "Two"), app.routes.tours.map { it.name }.toSet())
    }

    @Test fun anOlderReadEndingLateDoesNotHideARowAddedSince() {
        val app = App()
        planned("One")
        // read 1 has its list ([One]) when a row is added and read 2 runs to its end inside it: read 2 shows first, read 1 ends LAST
        app.onFirstRow = { planned("Added"); app.slice.refreshTours(); app.ioEx.runNext() }
        app.slice.refreshTours()
        app.settle()
        assertEquals("the newer read decides, the older one ending late is ignored",
            setOf("One", "Added"), app.routes.tours.map { it.name }.toSet())
    }

    @Test fun aRowCarriesTheModelTimeNotTheStoredOne() {
        val app = App()
        val t = planned("One")
        assertEquals(0L, t.durationS.toLong())
        app.slice.refreshTours(); app.settle()
        val modelS = app.planRoute.withModelTime(t).durationS
        assertTrue("the model time of the line, not the stored 0", modelS > 0)
        assertEquals(modelS, app.routes.tours.single().durationS, 0.001)
        assertTrue("the host got the tour WITH its model time: ${app.log}", app.log.contains("io: withMaps ${t.id} duration=${modelS.toLong()}"))
    }

    @Test fun ridesTakenOutLeaveTheAverageToTheOverviewAndPlannedToursHaveNoneAndNoFollowedPlan() {
        val app = App()
        val rec = recorded("Ride")
        val times = TourTimes(movingS = 3600.0, breakS = 0.0, startMs = 0L, endMs = 7_200_000L)
        app.timesOf[rec.id] = times
        app.ridesOf[rec.id] = com.onpath.app.rides.RideRecord(emptyList(), com.onpath.app.rides.RideChoice.UNTIL,
            com.onpath.app.rides.TourStats(3_000.0, 0.0, 0.0, 100.0))
        val plannedTour = planned("Plan")
        app.timesOf[plannedTour.id] = times
        db.exec("INSERT INTO tour_plan(tour_id, activity, sac, waypoints, updated) VALUES (?, 'HIKE', 2, '[]', 0)", plannedTour.id)
        app.plansOf[plannedTour.id] = listOf(a, b, a).map { GpxWaypoint(it.lat, it.lon, null) }
        app.slice.refreshTours(); app.settle()
        assertNull("rides taken out: no stored-times average", app.routes.tours.single { it.id == rec.id }.avgKmh)
        val p = app.routes.tours.single { it.id == plannedTour.id }
        assertNull("a planned route has no recorded average", p.avgKmh)
        assertNull("a planned route's plan points are not read for a round-trip verdict", p.roundTrip)
        assertFalse(app.log.any { it.contains("planPoints ${plannedTour.id}") })
    }

    @Test fun aDoneToursRowGetsItsAverageSpeedFromTheStoredTimes() {
        val app = App()
        val t = recorded("Ride")
        app.timesOf[t.id] = TourTimes(movingS = 3600.0, breakS = 0.0, startMs = 0L, endMs = 7_200_000L)
        app.slice.refreshTours(); app.settle()
        val row = app.routes.tours.single()
        assertNotNull("3 km in 1 h moving", row.avgKmh)
        assertEquals(3.0, row.avgKmh!!, 0.01)
    }

    @Test fun aRecordedToursFollowedPlanTellsARoundTripFromItsPoints() {
        val app = App()
        val t = db.insertTour(TourKind.RECORDED, "Loop", "HIKE", 2, listOf(seg(a, b, a)), listOf(GpxWaypoint(a.lat, a.lon, null)), distance = 5_000.0)
        db.exec("INSERT INTO tour_plan(tour_id, activity, sac, waypoints, updated) VALUES (?, 'HIKE', 2, '[]', 0)", t)
        app.plansOf[t] = listOf(a, b, a).map { GpxWaypoint(it.lat, it.lon, null) }
        app.slice.refreshTours(); app.settle()
        assertEquals(true, app.routes.tours.single().roundTrip)
        assertTrue("read on io", app.log.contains("io: store.planPoints $t"))
    }

    @Test fun aFollowedPlanThatCannotBeReadIsReportedAndTheRowGuesses() {
        val app = App()
        val t = db.insertTour(TourKind.RECORDED, "Loop", "HIKE", 2, listOf(seg(a, b, a)), listOf(GpxWaypoint(a.lat, a.lon, null)), distance = 5_000.0)
        db.exec("INSERT INTO tour_plan(tour_id, activity, sac, waypoints, updated) VALUES (?, 'HIKE', 2, '[]', 0)", t)
        app.failPlanPoints = true
        app.slice.refreshTours(); app.settle()
        assertEquals("the row stays, without a verdict", 1, app.routes.tours.size)
        assertNull(app.routes.tours.single().roundTrip)
        assertTrue(problems.said("routes").any { it.contains("followed plan's points of tour $t not read") })
    }

    // ---------------------------------------------------------------- open a planned route

    /**
     * From SavePathsTest.replacingAPlanKeepsItUnderZuletzt (deleted in the test clean-up G04, 2026-11-19), word for word:
     * Ways 6 + 8 and following a track during a tour (owner 2026-11-06, "Zuletzt"): nothing asks to save any more – the
     * plan they replace goes under My routes → Zuletzt ([AppViewModel.keepAsDraft]) when it is not saved as it is.
     */
    @Test fun aPlannedRouteBecomesThePlanAfterTheOldOneWentUnderZuletzt() {
        val app = App()
        app.draw(c, b)                                   // an unsaved plan on the map
        app.routes.open = true
        val t = planned("Alm", "BIKE_TREKKING", 3, pinned = setOf(1), stopNames = mapOf(0 to "Start"))
        var plannedCalled = ""
        app.slice.openTour(t, planned = { plannedCalled = app.log.last() })
        assertEquals("nothing before the coroutine runs", emptyList<String>(), app.log.filter { it.contains("change") })
        app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals(setOf(b), app.plan.pinned)
        assertEquals(Activity.BIKE_TREKKING, app.planRoute.activity)
        assertEquals(3, app.planRoute.sac)
        assertEquals("the activity is remembered", Activity.BIKE_TREKKING, app.prefs.activity)
        assertEquals("the T level of a plan is only the slice's, not the prefs'", 2, app.prefs.sac)
        assertFalse("My routes closed", app.routes.open)
        assertNull(app.slice.shown)
        assertEquals("the plan that was on the map is a draft now", 1, app.drafts().size)
        assertEquals("Alm", app.plan.savedName)
        assertEquals(t.id, app.plan.savedId)
        val order = app.log.filter { !it.contains("store.") }
        assertEquals(listOf(
            "test: markSeen ${t.id}",
            "main: onTheWay false",
            "main: event NewPlan",
            // the new activity only after the old plan went under Zuletzt: it is kept with its own
            "main: pref activity BIKE_TREKKING",
            "main: change 3 points; shown=null; drafts=1; activity=BIKE_TREKKING/T3; pins=1; routesOpen=false",
            "main: place name Start",
            "main: saved Alm ${t.id}",
            "main: opened ${t.id}",
            "main: fit 5",
            "main: syncRoutingCheck ${t.id}",
        ), order.filter { !it.startsWith("main: standIn") && !it.startsWith("main: event Routed") })
        assertEquals("the planned callback runs last", "main: syncRoutingCheck ${t.id}", plannedCalled)
        assertTrue("the segments came from the store on io", app.log.contains("io: store.loadSegments ${t.id}"))
    }

    @Test fun aPlannedRoutesFreshLegsNeedNoRoutingAndItIsMarkedRoutedBefore() {
        val app = App()
        val t = planned("Alm")
        for ((x, y) in listOf(a to b, b to c))
            app.planRoute.legCache.put(x, y, Activity.HIKE, 2, RouteResult(listOf(x, y), 2_000.0, 10.0, 10.0, 1_800.0))
        app.slice.openTour(t); app.settle()
        assertFalse("no stand-in: the legs are there", app.log.any { it.contains("standIn") })
        val i = app.log.indexOf("main: event RoutedBefore")
        assertTrue("routed before, so no 'very long plan' question", i >= 0)
        assertTrue("…before the new plan event and the points", i < app.log.indexOf("main: event NewPlan"))
        assertTrue(problems.traced("routes").any { it.contains("all 2 legs stored – no routing") })
    }

    @Test fun aPlannedRouteWithoutStoredLegsShowsItsSavedLineAtOnceAsAStandIn() {
        val app = App()
        val t = planned("Alm")
        app.slice.openTour(t); app.settle()
        assertTrue(app.log.toString(), app.log.any { it.startsWith("main: standIn 3 HIKE T2") && it.contains("fromLine=2") })
        assertTrue("then routed-before", app.log.indexOf("main: event RoutedBefore") > app.log.indexOfFirst { it.contains("standIn") })
        assertTrue(problems.traced("routes").any { it.contains("stored line shown at once") })
    }

    @Test fun theRoutesActivityIsTakenOverBeforeItsStoredLineIsCheckedSoTheLineFits() {
        val app = App()
        app.slice.openTour(planned("Alm", activity = "BIKE_TREKKING", sac = 2)); app.settle()
        assertTrue("the plan is a bike plan now, so the saved line is a stand-in", app.log.any { it.startsWith("main: standIn 3 BIKE_TREKKING") })
    }

    @Test fun aRouteWithoutAKnownActivityHasNoStoredLineForThePlansActivity() {
        val app = App()
        app.slice.openTour(planned("Old", activity = null)); app.settle()
        assertFalse(app.log.any { it.contains("standIn") || it.contains("RoutedBefore") })
        assertTrue(problems.traced("routes").any { it.contains("no stored line for these points – routed") })
        assertEquals("it still becomes the plan", 3, app.plan.points.size)
    }

    @Test fun aPlannedRouteWithOnlyOnePointIsShownAsATrackNotLoadedAsAPlan() {
        val app = App()
        app.slice.openTour(planned("One", via = arrayOf(a))); app.settle()
        assertTrue(app.plan.points.isEmpty())
        assertEquals("One", app.slice.shown?.name)
        assertFalse(app.log.any { it.contains("RoutedBefore") || it.contains("change") })
    }

    @Test fun aPlannedRouteTappedDuringATourKeepsTheActivityIsFollowedFromPointOneAndGoesToNavigate() {
        val app = App().also { it.touring = true; it.recordingId = 99 }
        val t = planned("Alm", "BIKE_TREKKING", 4)
        app.slice.openTour(t, duringTour = true); app.settle()
        assertEquals("the running tour's activity stays", Activity.HIKE, app.planRoute.activity)
        assertEquals(2, app.planRoute.sac)
        assertFalse(app.log.any { it.contains("pref activity") })
        assertTrue(app.log.contains("main: onTheWay true"))
        assertFalse("no event NewPlan during a tour", app.log.any { it.contains("event") })
        assertEquals("…and the app is in Navigate after the plan changed", AppMode.NAVIGATE, app.mode)
        assertTrue(app.log.indexOf("main: enter NAVIGATE") > app.log.indexOfFirst { it.startsWith("main: change") })
        // a route of the running tour's own activity: its saved line is NOT read as a stand-in during a tour (the plan is followed from ①)
        app.log.clear()
        app.slice.openTour(planned("Same", "HIKE", 2, via = arrayOf(c, a)), duringTour = true); app.settle()
        assertFalse(app.log.any { it.contains("standIn") || it.contains("event") })
    }

    @Test fun duringATourFlagWithoutARecordingIsNotOnTheWayAndStaysInPlan() {
        val app = App()   // not touring
        app.slice.openTour(planned("Alm"), duringTour = true); app.settle()
        assertTrue(app.log.contains("main: onTheWay false"))
        assertEquals(AppMode.PLAN, app.mode)
        assertFalse(app.log.any { it.contains("enter") })
    }

    @Test fun aStandInWithNoStoredRouteIsAnUnsavedPlan() {
        val app = App()
        val ghost = planned("Ghost").copy(id = -5L)
        app.slice.openTour(ghost, lineOf = listOf(seg(a, b, c))); app.settle()
        assertTrue(app.log.contains("main: saved null -1"))
        assertFalse("its line came from the caller, not the store", app.log.any { it.contains("store.loadSegments") })
        assertNull(app.plan.savedName)
    }

    @Test fun anUnsavedPlanIsNotLostWhenAnotherRouteOpens() {
        val app = App()
        app.draw(c, a)
        val t = planned("Alm")
        app.slice.openTour(t); app.settle()
        val d = app.drafts().single()
        assertEquals(listOf(c, a), d.waypoints.map { GeoPoint(it.lat, it.lon) })
    }

    /**
     * Owner 2026-11-20 (final, app + planner): a plan opened from Zuletzt, changed and left again UPDATES its entry –
     * no new draft, the old one not in the recycle bin. Through the real path: the opened draft links the plan
     * ([PlanDocument.savedId]), [SaveSlice.keepAsDraft] hands that to [RoutesState.keepDraft].
     */
    @Test fun aPlanOpenedFromZuletztAndChangedUpdatesItsEntryWhenItLeaves() {
        val app = App()
        app.draw(c, a)
        app.save.keepAsDraft("cleared"); app.plan.edit { clear() }; app.settle()
        val d = app.drafts().single()
        app.slice.openTour(d); app.settle()
        assertEquals("the plan belongs to the entry it was opened from", d.id, app.plan.savedId)
        app.plan.edit { add(b) }
        app.save.keepAsDraft("cleared"); app.settle()
        val now = app.drafts().single()
        assertEquals("the same entry", d.id, now.id)
        assertEquals("with the changed points", listOf(c, a, b), now.waypoints.map { GeoPoint(it.lat, it.lon) })
        assertEquals("its name stays", d.name, now.name)
        assertTrue("nothing went to the recycle bin", db.listDeleted().isEmpty())
        assertEquals(d.id, app.routes.recentId)
        // Undo of the clear: the plan is its entry as it is – not "changed"
        app.plan.edit { clear(); addAll(listOf(c, a, b)) }
        assertFalse("the entry holds exactly these points", app.plan.unsaved); assertEquals(d.id, app.plan.savedId)
        // Undo further, back to the version it was opened as, then cleared: THAT version is what the entry must hold –
        // never dropped because the plan's link still called it "saved as it is" (review 2026-11-20)
        app.plan.edit { clear(); addAll(listOf(c, a)) }
        app.save.keepAsDraft("cleared"); app.settle()
        assertEquals("the plan that left the map last is under Zuletzt", listOf(c, a), app.drafts().single().waypoints.map { GeoPoint(it.lat, it.lon) })
        assertEquals(d.id, app.drafts().single().id)
        assertTrue(db.listDeleted().isEmpty())
    }

    // ---------------------------------------------------------------- open a done tour / track

    @Test fun aDoneTourIsShownOnTheMapAndTheActivityFollowsIt() {
        val app = App()
        val t = recorded("Ride", activity = "BIKE_TREKKING")
        app.routes.open = true
        app.mode = AppMode.NAVIGATE
        app.slice.openTour(t); app.settle()
        val sh = app.slice.shown!!
        assertEquals("Ride", sh.name); assertEquals(t.id, sh.tourId)
        assertEquals(listOf(a, b, c), sh.points.map { GeoPoint(it.lat, it.lon) })
        assertEquals(Activity.BIKE_TREKKING, app.planRoute.activity)
        assertNotNull("a change of activity is said in the track panel", sh.note)
        assertFalse(app.routes.open)
        assertEquals("a track shown while navigating goes to Plan", AppMode.PLAN, app.mode)
        assertFalse("taken over from the tour, not the user's own change: Cancel would not undo it",
            app.planRoute.runCauses.next is com.onpath.app.domain.PlanStatus.Cause.ActivityChange)
        assertTrue("the activity is selected before the track is shown (it would clear the panel's line)",
            app.log.indexOf("main: recalc BIKE_TREKKING T2") < app.log.indexOf("main: fit 3"))
        assertTrue(app.log.indexOf("main: enter PLAN") < app.log.indexOf("main: fit 3"))
        assertTrue("the plan is not touched", app.plan.points.isEmpty())
    }

    @Test fun aHikeTourBringsItsLevelAndRemembersIt() {
        val app = App()
        val t = recorded("Ride", activity = "HIKE", sac = 4)
        app.planRoute.selectActivity(Activity.HIKE, userChange = false); app.log.clear()
        app.slice.openTour(t); app.settle()
        assertEquals("a T4 tour is outside the band of T2: its level is taken", 4, app.planRoute.sac)
        assertTrue(app.log.contains("main: pref sac 4"))
    }

    @Test fun aDoneTourTappedDuringATourKeepsTheActivityAndShowsNoNote() {
        val app = App().also { it.touring = true; it.recordingId = 99 }
        app.slice.openTour(recorded("Ride", activity = "BIKE_TREKKING")); app.settle()
        assertEquals(Activity.HIKE, app.planRoute.activity)
        assertNull(app.slice.shown!!.note)
    }

    @Test fun whatAfterTheTrackIsShownFollowsThePlanFromTheTrackOrNothing() {
        val app = App()
        app.slice.openTour(recorded("A"), trackThen = TrackThen.SHOW); app.settle()
        assertFalse(app.log.any { it.contains("followShownNow") })
        app.slice.openTour(recorded("B"), trackThen = TrackThen.FOLLOW); app.settle()
        assertTrue("followed with the track already shown", app.log.contains("main: followShownNow shown=B"))
    }

    @Test fun aDoneTourWithNoPlanBecomesAPlanThroughTheRouterOnTheBackgroundThread() {
        val app = App()
        app.slice.openTour(recorded("Ride"), trackThen = TrackThen.PLAN)
        app.mainEx.runAll(); app.ioEx.runAll(); app.mainEx.runAll()
        assertTrue("the track is shown first", app.slice.shown != null || app.slice.planFromTrackBusy)
        assertTrue("the routing data was asked for before", app.log.any { it.contains("ensure [part-HIKE]") })
        assertTrue(app.slice.planFromTrackBusy)
        assertEquals("the routing runs on the background thread, held", 1, app.bgEx.size)
        assertTrue("nothing changed yet", app.plan.points.isEmpty())
        app.settle()
        assertFalse(app.slice.planFromTrackBusy)
        assertTrue(app.plan.points.size >= 2)
        assertNull("the shown track goes when the plan takes over", app.slice.shown)
        val calls = app.log.filter { it.endsWith("router call") }
        assertTrue("the router was asked", calls.isNotEmpty())
        assertTrue("…only on the background routing thread, never main or io: $calls", calls.all { it.startsWith("bg:") })
    }

    // ---------------------------------------------------------------- track → plan

    private fun shownTrack(app: App, waypoints: List<GpxWaypoint> = emptyList()) {
        app.slice.showTrack(ShownTrack(null, "GPX", listOf(listOf(a, b, c)), waypoints, 3_000.0, 0.0, 0.0, 0.0))
        app.log.clear()
    }

    @Test fun aPlanGpxComesBackWithItsPointsPinsAndNamesNoFitting() {
        val app = App()
        app.draw(c, b)
        val stops = listOf(GpxWaypoint(a.lat, a.lon, "Start", type = null), GpxWaypoint(b.lat, b.lon, "Mid", type = com.onpath.app.gpx.PlanGpx.PINNED), GpxWaypoint(c.lat, c.lon, "Ziel"))
        shownTrack(app, stops)
        app.slice.planFromShown()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals("pinned route points come back pinned", setOf(b), app.plan.pinned)
        assertNull("closed", app.slice.shown)
        assertEquals(listOf(
            "test: clearHighlight",
            "test: change 3 points; shown=null; drafts=1; activity=HIKE/T2; pins=1; routesOpen=false",
            "test: place name Start", "test: place name Mid", "test: place name Ziel"), app.log)
        assertEquals("the plan that was on the map is kept", 1, app.drafts().size)
        assertTrue(app.log.none { it.contains("ensure") })
        assertFalse(app.slice.planFromTrackBusy)
    }

    @Test fun noShownTrackOrAOnePointTrackDoesNothing() {
        val app = App()
        app.slice.planFromShown()
        assertTrue(app.log.isEmpty())
        app.slice.showTrack(ShownTrack(null, "dot", listOf(listOf(a)), emptyList(), 0.0, 0.0, 0.0, 0.0)); app.log.clear()
        app.slice.planFromShown()
        assertTrue(app.log.isEmpty())
        assertTrue(app.plan.points.isEmpty())
    }

    @Test fun routingDataMissingAlongTheTrackSaysSoAndChangesNothing() {
        val app = App()
        app.draw(c, b)
        shownTrack(app)
        app.missingParts = listOf("E10_N45")
        app.slice.planFromShown()
        assertFalse("never busy", app.slice.planFromTrackBusy)
        assertEquals(1, app.notes.size)
        assertEquals(NoticeKind.ERROR, app.notes.single().second); assertEquals(NoticePlace.MAP, app.notes.single().third)
        assertEquals(Txt.s(Res.string.plan_from_track_wait), app.notes.single().first)
        assertEquals(listOf(c, b), app.plan.points.toList())
        assertNotNull("the track stays shown", app.slice.shown)
        assertEquals(0, app.bgEx.size)
    }

    @Test fun aCheckOfTheRoutingDataThatFailsStillPlansFromTheTrackAndIsReported() {
        val app = App()
        shownTrack(app)
        val failing = object : RoutesHost by app.host {
            override fun ensureRoutingData(parts: List<String>): List<String> = throw IllegalStateException("no store")
        }
        val s = RoutesSlice(app.plan, app.routes, app.save, app.tour, app.planRoute, app.store, app.camera, failing, app.scope, app.io, app.main) { 0L }
        s.showTrack(ShownTrack(null, "GPX", listOf(listOf(a, b, c)), emptyList(), 3_000.0, 0.0, 0.0, 0.0))
        s.planFromShown()
        assertTrue("busy: the run goes on", s.planFromTrackBusy)
        assertTrue(problems.said("routing").any { it.contains("Routing data along the track not checked") })
        app.settle()
    }

    @Test fun aSecondTrackToPlanWhileOneRunsIsRefusedAndCancelChangesNothing() {
        val app = App()
        app.draw(c, b)
        shownTrack(app)
        app.slice.planFromShown()
        assertTrue(app.slice.planFromTrackBusy)
        var second: Boolean? = null
        app.slice.planFromLine(listOf(a, b), emptyList(), Activity.HIKE, 2) { second = it }
        assertEquals("refused while busy", false, second)
        app.slice.cancelPlanFromTrack()
        assertFalse(app.slice.planFromTrackBusy)
        app.settle()
        assertEquals("the plan is as it was", listOf(c, b), app.plan.points.toList())
        assertNotNull("the track is still shown", app.slice.shown)
        assertEquals("not kept as a draft either", 0, app.drafts().size)
        assertTrue(app.notes.isEmpty())
        assertTrue(problems.traced("plan").any { it.contains("cancelled by the user") })
    }

    @Test fun aCancelAfterARunFinishedTouchesNothingOfThatRun() {
        val app = App()
        app.draw(c, b)
        app.slice.planFromLine(listOf(a, b, c), listOf("p"), Activity.HIKE, 2) {}
        app.settle()
        val first = app.router.cancels.first()
        assertFalse(app.slice.planFromTrackBusy)
        app.slice.cancelPlanFromTrack()          // the box is gone; a late Cancel has nothing to cancel
        assertFalse("the finished run's handle was let go", first.isCancelled)
        assertFalse(app.slice.planFromTrackBusy)
    }

    @Test fun theTrackBecomesThePlanOnlyAfterDoneAndTheOldPlanIsKeptFirst() {
        val app = App()
        app.draw(c, b)
        shownTrack(app)
        var result: Boolean? = null
        app.slice.planFromLine(listOf(a, b, c), listOf("p"), Activity.HIKE, 2) { result = it; app.log += "${app.where}: done $it" }
        assertEquals("nothing before the router has run", null, result)
        app.settle()
        assertEquals(true, result)
        val n = app.log.indexOfFirst { it.startsWith("main: change") }
        assertTrue("done(true) is told just before the plan changes", n > app.log.indexOf("main: done true"))
        assertTrue("the old plan was kept first: drafts=1 at the change", app.log[n].contains("drafts=1"))
    }

    @Test fun aRouterThatFindsNothingSaysItFailedAndChangesNothing() {
        val app = App()
        app.draw(c, b)
        app.router.straight = true     // no routing data on the phone: only the straight-line stand-in
        var result: Boolean? = null
        app.slice.planFromLine(listOf(a, b, c), listOf("p"), Activity.HIKE, 2) { result = it }
        app.settle()
        assertEquals(false, result)
        assertFalse(app.slice.planFromTrackBusy)
        assertEquals(listOf(c, b), app.plan.points.toList())
        assertEquals(NoticePlace.MAP, app.notes.single().third)
        assertEquals(Txt.s(Res.string.plan_from_track_failed), app.notes.single().first)
        assertEquals(0, app.drafts().size)
    }

    // ---------------------------------------------------------------- delete

    @Test fun deleteMovesTheTourToTheBinThenBackupAndSyncAreToldThenTheListIsRefreshed() {
        val app = App()
        val t = planned("Alm"); val keep = planned("Other")
        app.routes.show(app.routes.load())
        app.slice.deleteTour(t)
        assertEquals("nothing happens before io runs", 0, db.listDeleted().size)
        app.ioEx.runNext()
        assertEquals(listOf(t.id), db.listDeleted().map { it.id })
        assertEquals(listOf(
            "io: backup ${t.id}; bin=[${t.id}]",
            "io: forget mark ${t.uuid}",
            "io: sync moved to the recycle bin; bin=[${t.id}]",
        ), app.log)
        assertEquals("the list still shows it until main has run", 2, app.routes.tours.size)
        app.settle()
        assertEquals(listOf(keep.id), app.routes.tours.map { it.id })
        assertTrue(app.log.contains("main: kickThumbs"))
        assertNull(app.save.problem)
    }

    @Test fun deletingTheTourBeingRecordedIsRefusedAndNothingIsWritten() {
        val app = App().also { it.recordingId = 7 }
        val t = recorded("Now")
        app.recordingId = t.id
        app.slice.deleteTour(t)
        app.settle()
        assertEquals(0, db.listDeleted().size)
        assertTrue(app.log.isEmpty())
        assertEquals(NoticePlace.ROUTES, app.notes.single().third)
        assertEquals(NoticeKind.ERROR, app.notes.single().second)
        assertEquals(Txt.s(Res.string.msg_track_recording_now), app.notes.single().first)
    }

    @Test fun aFailedMoveToTheBinSaysSoAndTellsNobody() {
        val app = App()
        val t = planned("Alm")
        app.slice.openTour(t); app.settle(); app.log.clear()    // the route is the plan, linked
        val broke = object : RoutesStore by app.routesStore {
            override fun setDeleted(id: Long, deletedMs: Long?): Boolean = throw java.sql.SQLException("disk full")
        }
        val routes = RoutesState(broke, clock = { 1L })
        val s = RoutesSlice(app.plan, routes, app.save, app.tour, app.planRoute, app.store, app.camera, app.host, app.scope, app.io, app.main) { 0L }
        s.deleteTour(t); app.settle()
        assertEquals(0, db.listDeleted().size)
        assertTrue("no backup, no sync", app.log.none { it.contains("backup") || it.contains("sync") || it.contains("forget") })
        assertEquals("a dialog that stays until OK", Txt.s(Res.string.msg_tour_delete_failed, t.name), app.save.problem)
        assertEquals("the plan is still linked to its route", t.id, app.plan.savedId)
        assertTrue(problems.said("db").any { it.contains("recycle bin failed") })
    }

    @Test fun deletingTheShownTourClosesItAndDeletingThePlansRouteUnlinksThePlan() {
        val app = App()
        val t = planned("Alm")
        app.slice.openTour(t); app.settle()
        assertEquals(t.id, app.plan.savedId)
        val other = recorded("Ride")
        app.slice.openTour(other); app.settle()
        assertEquals(other.id, app.slice.shown?.tourId)
        // another tour deleted: the shown one stays, the plan stays linked
        val third = planned("Third", via = arrayOf(c, a))
        app.slice.deleteTour(third); app.settle()
        assertNotNull(app.slice.shown); assertEquals(t.id, app.plan.savedId)
        app.log.clear()
        app.slice.deleteTour(other); app.settle()
        assertNull("the shown track of the deleted tour goes", app.slice.shown)
        app.slice.deleteTour(t); app.settle()
        assertEquals("the plan is no longer saved anywhere", -1L, app.plan.savedId)
        assertTrue(app.log.contains("main: saved null -1"))
    }

    @Test fun deletingTheTourJustSavedClosesItsBoxAndOnlyThatBox() {
        val app = App()
        val t = planned("Alm"); val u = planned("Other", via = arrayOf(c, a))
        app.tour.showFinished(t, null)
        app.slice.deleteTour(u); app.settle()
        assertEquals("another tour's deletion leaves the box", t.id, app.tour.finishedTour?.id)
        app.slice.deleteTour(t); app.settle()
        assertNull(app.tour.finishedTour)
    }

    @Test fun aDeletedTourComesBackFromTheBin() {
        val app = App()
        val t = planned("Alm")
        app.slice.deleteTour(t); app.settle()
        assertEquals(1, db.listDeleted().size)
        app.recent.restore(t); app.settle()
        assertEquals(0, db.listDeleted().size)
        assertEquals(listOf(t.id), db.listTours().map { it.id })
    }

    @Test fun aRouterThatCannotStartIsReportedAndSaysTheSame() {
        val app = App()
        app.draw(c, b)
        app.router.createFails = true
        var result: Boolean? = null
        app.slice.planFromLine(listOf(a, b, c), listOf("p"), Activity.HIKE, 2) { result = it }
        app.settle()
        assertEquals(false, result)
        assertFalse(app.slice.planFromTrackBusy)
        assertEquals(listOf(c, b), app.plan.points.toList())
        assertTrue(problems.said("routing", com.onpath.app.debug.Problems.Level.ERROR).any { it.contains("could not be turned into a plan") })
        assertEquals(NoticePlace.MAP, app.notes.single().third)
        assertEquals(Txt.s(Res.string.plan_from_track_failed), app.notes.single().first)
    }

    // ================================================================ Routes II (piece 14): go from My routes, the followed plan,
    // the review, plan links in and out – the real RoutesGoSlice on top of the real RoutesSlice.
    //
    // The WAIT of "Los geht's" (WAIT → START, → DROP, a second go cancelling the first, the 60 s timeout) is RoutesGoWaitTest: it needs the
    // global Compose snapshot, which this class's OwnComposeSnapshot rule forbids. Here only the decisions at the first look
    // (START / DROP) are tested. The tour's own start behind it is the view model's (pieces 16–19).

    private fun route3() = PlannedRoute(listOf(PlannedLeg(listOf(a, b, c), false)), 4_000.0, 0.0, 0.0, 3_000.0, null)

    private fun planCopy(name: String? = "Followed", activity: String = "HIKE", sac: Int = 2, via: List<GeoPoint> = listOf(a, b, c),
                         source: String? = null, line: List<TrackPoint> = emptyList(), pinned: Set<Int> = emptySet()) =
        com.onpath.app.data.TourPlan(name, activity, sac, via.mapIndexed { i, p -> GpxWaypoint(p.lat, p.lon, if (i == 0) "Start" else null) },
            pinned = pinned, line = line, distanceM = 4_000.0, sourceUuid = source)

    /** A done recorded tour with its followed plan [copy] stored (hasPlan as the real query reads it). */
    private fun recordedWithPlan(app: App, copy: com.onpath.app.data.TourPlan?, name: String = "Ride"): Tour {
        val t = recorded(name)
        if (copy != null) app.copies[t.id] = copy
        return t.copy(hasPlan = copy != null)
    }

    // ---- navigating, the ⋮ menu, My routes opened

    @Test fun navigatingIsTheNavigateModeOrARecordingAndTheMenuFollowsRouteSavedToursAndNavigating() {
        val app = App()
        assertFalse(app.go.navigating(false)); assertTrue(app.go.navigating(true))
        app.mode = AppMode.NAVIGATE
        assertTrue("Navigate mode alone", app.go.navigating(false))
        app.mode = AppMode.PLAN
        // nothing saved, no route, no points: only "start recording" is open
        assertEquals(com.onpath.app.domain.RouteMenuState(load = false, save = false, startRecording = true), app.go.menuState(false))
        planned("Alm"); app.slice.refreshTours(); app.settle()
        app.routeNow = route3()
        assertEquals("saved tours: load; a route: save; a route: no start recording", com.onpath.app.domain.RouteMenuState(load = true, save = true, startRecording = false), app.go.menuState(false))
        assertFalse("while recording nothing loads", app.go.menuState(true).load)
        app.mode = AppMode.NAVIGATE
        assertFalse("in Navigate nothing loads", app.go.menuState(false).load)
        app.mode = AppMode.PLAN; app.routeNow = null; app.draw(a)
        assertTrue("one point: still may record", app.go.menuState(false).startRecording)
        app.draw(a, b)
        assertFalse("two points are a plan: no recording from the menu", app.go.menuState(false).startRecording)
    }

    @Test fun openRoutesOpensMyRoutesAtOnceAndReadsTheListAgain() {
        val app = App()
        planned("One")
        app.go.openRoutes()
        assertTrue("open at once", app.routes.open)
        assertTrue("the list is read on io, not yet shown", app.routes.tours.isEmpty())
        app.settle()
        assertEquals(listOf("One"), app.routes.tours.map { it.name })
        assertTrue(app.routes.open)
    }

    // ---- a tap in My routes

    @Test fun aTapWithoutATourOpensThePlannedRouteAndLeavesNoNeuerPlanNote() {
        val app = App()
        app.draw(c, b)
        val t = planned("Alm")
        app.go.requestOpen(t, "a test tap"); app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertNull("no 'Neuer Plan' line without a tour", app.tour.newPlanNote)
        assertEquals("the plan that was on the map goes under Zuletzt", 1, app.drafts().size)
        assertTrue(app.log.contains("main: onTheWay false"))
        assertTrue("the first route may raise the sheet", app.log.contains("main: event NewPlan"))
        assertTrue(problems.traced("routes").any { it == "Open tour ${t.id} \"Alm\" (PLANNED, 4000 m): a test tap" })
        assertTrue("no tour code reached", app.log.none { it.contains("followShownNow") || it.contains("continueNavigation") })
    }

    @Test fun aPlanTappedDuringATourIsFollowedAtOnceWithTheNoteSetBeforeTheOpenStartsAndUndoOffered() {
        val app = App().also { it.touring = true; it.recordingId = 99; it.mode = AppMode.NAVIGATE }
        app.draw(c, b)
        val t = planned("Alm")
        app.go.requestOpen(t); app.settle()
        assertEquals("the note (with Rückgängig) was already set when the open started", listOf<Boolean?>(true), app.noteAtOpen)
        assertEquals("the plan is replaced", listOf(a, b, c), app.plan.points.toList())
        assertEquals("a plan was on the map: Rückgängig is offered", true, app.tour.newPlanNote?.undo)
        assertEquals("the tour's activity stays", Activity.HIKE, app.planRoute.activity)
        assertTrue("the load is 'on the way'", app.log.contains("main: onTheWay true"))
        assertTrue("no new-plan event: the tour's route stays the tour's", app.log.none { it == "main: event NewPlan" })
        assertTrue("the plan before went under Zuletzt first", app.log.any { it.startsWith("main: change 3 points; shown=null; drafts=1;") })
        assertEquals("Navigate is entered (the recording goes on)", "main: enter NAVIGATE", app.log.last { it.contains("enter") })
        assertTrue(problems.traced("tour").any { it.contains("Plan \"Alm\" tapped during the tour: followed at once") })
        assertTrue(problems.traced("plan").any { it.contains("Load \"Alm\" during the tour: the plan on the map goes under Zuletzt if not saved") })
    }

    @Test fun aPlanTappedDuringATourWithAnEmptyPlanOffersNoUndo() {
        val app = App().also { it.touring = true; it.recordingId = 99 }
        app.go.requestOpen(planned("Alm")); app.settle()
        assertEquals("nothing to come back to", false, app.tour.newPlanNote?.undo)
        assertEquals(3, app.plan.points.size)
    }

    @Test fun aDoneTourTappedDuringATourIsFollowedAtOnceThroughTheHost() {
        val app = App().also { it.touring = true; it.recordingId = 99 }
        app.draw(c, b)
        app.go.requestOpen(recorded("Ride")); app.settle()
        assertEquals(true, app.tour.newPlanNote?.undo)
        assertTrue("followed with the track already shown (tour code: the host)", app.log.contains("main: followShownNow shown=Ride"))
        assertEquals("the plan on the map stays for the host to clear", listOf(c, b), app.plan.points.toList())
        assertTrue(problems.traced("tour").any { it.contains("Track \"Ride\" tapped during the tour: followed at once") })
        assertTrue(problems.traced("plan").any { it.contains("Follow \"Ride\" during the tour: the plan on the map goes under Zuletzt if not saved") })
    }

    @Test fun aDoneTourTappedWithoutATourIsOnlyShown() {
        val app = App()
        app.go.requestOpen(recorded("Ride")); app.settle()
        assertNull(app.tour.newPlanNote)
        assertEquals("Ride", app.slice.shown?.name)
        assertTrue(app.log.none { it.contains("followShownNow") })
    }

    @Test fun navigateModeAloneCountsAsDuringATourForTheQuestionButTheLoadIsNotOnTheWay() {
        val app = App().also { it.mode = AppMode.NAVIGATE }       // no recording: touring = false
        app.draw(c, b)
        app.go.requestOpen(planned("Alm")); app.settle()
        assertEquals("followed at once like during a tour", true, app.tour.newPlanNote?.undo)
        assertTrue("…but not 'on the way': no tour records", app.log.contains("main: onTheWay false"))
    }

    // ---- Rückgängig on "Neuer Plan"

    private fun newPlanNote(app: App, undo: Boolean) { app.tour.noteNewPlan(undo = undo); app.log.clear() }

    @Test fun undoWithoutANoteDoesNothing() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        app.go.undoNewPlan()
        assertTrue(app.log.isEmpty())
    }

    @Test fun undoOfANoteWithoutAPlanBeforeOnlyDropsTheNote() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        newPlanNote(app, undo = false)
        app.go.undoNewPlan()
        assertNull(app.tour.newPlanNote)
        assertTrue("no Undo step", app.log.isEmpty())
    }

    @Test fun undoBringsThePlanFromBeforeBackInOneStepAfterTheNoteWentAndTheTourKeepsItsFollowedRoute() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        app.draw(c, b)
        app.plan.edit { clear(); addAll(listOf(a, b, c)) }
        newPlanNote(app, undo = true)
        app.go.undoNewPlan()
        assertEquals("the note is gone when the step runs", listOf("test: undoStep; note=null; plan=3"), app.log)
        assertEquals(listOf(c, b), app.plan.points.toList())
        assertTrue(problems.traced("plan").any { it.contains("Rückgängig on \"Neuer Plan\"") })
    }

    @Test fun undoOfAFollowedTrackMakesTheTourFollowThePlanAgainAfterTheStep() {
        val app = App().also { it.touring = true; it.recordingId = 1; it.followIsTrack = true }
        app.draw(c, b); app.plan.edit { clear(); addAll(listOf(a, b, c)) }
        newPlanNote(app, undo = true)
        app.go.undoNewPlan()
        assertEquals(listOf("test: undoStep; note=null; plan=3", "test: continueNavigation"), app.log)
    }

    @Test fun undoOfAFollowedTrackWithoutARecordingDoesNotTouchTheTour() {
        val app = App().also { it.followIsTrack = true }           // a track followed, but no tour records
        app.draw(c, b)
        newPlanNote(app, undo = true)
        app.go.undoNewPlan()
        assertEquals(1, app.log.size); assertTrue(app.log.single().contains("undoStep"))
    }

    // ---- "Los geht's" in My routes

    @Test fun losGehtsDuringATourIsFolgenAndStartsNothing() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        app.draw(c, b)
        var started = 0
        app.go.goFromRoutes(planned("Alm")) { started++ }; app.settle()
        assertEquals(0, started)
        assertEquals(true, app.tour.newPlanNote?.undo)
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertTrue(problems.traced("routes").any { it.contains("Open tour") && it.endsWith("Los geht's in My routes during a tour") })
    }

    @Test fun losGehtsLoadsThePlanLikePlanenAndStartsOnceItsLineIsThereAfterTheLoadFinished() {
        val app = App()
        app.draw(c, b)
        app.routeNow = route3()                          // the line is there when the wait looks
        val t = planned("Alm")
        app.go.goFromRoutes(t) { app.log += "${app.where}: START points=${app.plan.points.size}; saved=${app.plan.savedName}" }
        assertTrue("nothing before the coroutine runs", app.log.none { it.contains("START") })
        app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals("the plan that was on the map was kept", 1, app.drafts().size)
        val start = app.log.indexOf("main: START points=3; saved=Alm")
        assertTrue(app.log.toString(), start > app.log.indexOf("main: syncRoutingCheck ${t.id}"))
        assertEquals("started exactly once", 1, app.log.count { it.contains("START") })
        assertTrue(problems.traced("routes").any { it.contains("Los geht's on \"Alm\" in My routes: loaded as the plan") })
        assertTrue(problems.traced("routes").any { it.contains("the line of \"Alm\" is there – the normal start") })
    }

    @Test fun losGehtsWithoutALineStartsNothingAndSaysSo() {
        val app = App()                                  // no route
        var started = 0
        app.go.goFromRoutes(planned("Alm")) { started++ }; app.settle()
        assertEquals(0, started)
        assertEquals("the plan is loaded all the same", 3, app.plan.points.size)
        assertTrue(problems.traced("routes").any { it.contains("no tour started for \"Alm\"") })
    }

    @Test fun losGehtsStartsNothingWhenThePlanWasChangedMeanwhile() {
        val app = App()
        app.routeNow = route3()
        var started = 0
        app.go.goFromRoutes(planned("Alm")) { started++ }
        app.untilTheWaitIsQueued()
        app.plan.edit { add(c) }                         // the user edits between the load and the wait
        app.settle()
        assertEquals(0, started)
        assertTrue(problems.traced("routes").any { it.contains("no tour started for \"Alm\"") })
    }

    @Test fun losGehtsStartsNothingWhenATourRecordsMeanwhile() {
        val app = App()
        app.routeNow = route3()
        var started = 0
        app.go.goFromRoutes(planned("Alm")) { started++ }
        app.untilTheWaitIsQueued()
        app.touring = true                               // a recording began between the load and the wait
        app.settle()
        assertEquals(0, started)
    }

    // ---- a recorded tour's followed plan

    @Test fun planenOnAFollowedPlanDuringATourLoadsNothing() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        val t = recordedWithPlan(app, planCopy())
        app.go.planFollowedPlan(t); app.settle()
        assertTrue(app.plan.points.isEmpty())
        assertTrue("the store was not even asked", app.log.none { it.contains("getTourPlan") })
        assertTrue(problems.traced("routes").any { it == "Planen on tour \"Ride\" during a tour: nothing loaded" })
    }

    @Test fun planenOnATourWithoutACopySaysTheFollowedPlanIsMissing() {
        val app = App()
        val t = recordedWithPlan(app, null)
        app.go.planFollowedPlan(t)
        assertNull("nothing before the read", app.save.problem)
        app.settle()
        assertEquals(Txt.s(Res.string.routes_followed_plan_missing), app.save.problem)
        assertEquals(listOf("io: store.getTourPlan ${t.id}"), app.log)
        assertTrue(app.plan.points.isEmpty())
    }

    @Test fun planenOnACopyThatCannotBeReadIsReportedAndSaysTheSame() {
        val app = App().also { it.failTourPlan = true }
        val t = recordedWithPlan(app, planCopy())
        app.go.planFollowedPlan(t); app.settle()
        assertEquals(Txt.s(Res.string.routes_followed_plan_missing), app.save.problem)
        assertTrue(problems.said("db", com.onpath.app.debug.Problems.Level.ERROR).any { it.contains("The plan tour ${t.id} followed could not be read") })
    }

    @Test fun planenOnACopyThatIsStillTheSavedRouteLoadsThatRouteItselfWithItsStoredLine() {
        val app = App()
        app.draw(c, b)
        val saved = planned("Alm")
        val t = recordedWithPlan(app, planCopy(name = "Alm", source = saved.uuid))
        app.go.planFollowedPlan(t); app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals("loaded as itself: no second route", saved.id, app.plan.savedId)
        assertEquals("Alm", app.plan.savedName)
        assertTrue("its line comes from the store on io", app.log.contains("io: store.loadSegments ${saved.id}"))
        assertEquals("the plan that was on the map is kept", 1, app.drafts().size)
        assertTrue(problems.traced("routes").any { it.contains("Planen on tour \"Ride\": its followed plan is the saved route \"Alm\" (unchanged), 3 points") })
        // the copy was read before the list of routes (on io, in this order)
        val g = app.log.indexOf("io: store.getTourPlan ${t.id}"); val l = app.log.indexOf("io: store.listTours")
        assertTrue(app.log.toString(), g in 0 until l)
    }

    @Test fun planenOnACopyWhoseSavedRouteChangedLoadsTheCopyAsANewUnsavedPlanWithItsLine() {
        val app = App()
        val saved = planned("Alm")
        // the saved route has other points now than the copy: not the same plan any more
        val copy = planCopy(name = "Alm", source = saved.uuid, via = listOf(a, c), line = seg(a, b, c), pinned = setOf(1))
        val t = recordedWithPlan(app, copy)
        app.go.planFollowedPlan(t); app.settle()
        assertEquals(listOf(a, c), app.plan.points.toList())
        assertEquals("a stand-in is no saved route", -1L, app.plan.savedId)
        assertNull(app.plan.savedName)
        assertEquals(setOf(c), app.plan.pinned)
        assertTrue("its own line, no store read of segments", app.log.none { it.contains("store.loadSegments") })
        assertTrue("fitted to the copy's 3-point line", app.log.contains("main: fit 3"))
        assertTrue("the copy's first stop name comes back", app.log.contains("main: place name Start"))
        assertTrue(problems.traced("routes").any { it.contains("from the tour's copy") && it.endsWith("2 points") })
    }

    @Test fun aCopyOfAnotherActivityOrLevelIsNotTheSavedRoute() {
        val app = App()
        val saved = planned("Alm", sac = 2)
        val t = recordedWithPlan(app, planCopy(name = "Alm", source = saved.uuid, sac = 3, line = seg(a, b, c)))
        app.go.planFollowedPlan(t); app.settle()
        assertEquals(-1L, app.plan.savedId)
        assertEquals(3, app.planRoute.sac)
    }

    @Test fun aCopyOfAnotherActivityIsNotTheSavedRouteEither() {
        val app = App()
        val saved = planned("Alm", activity = "HIKE")
        val t = recordedWithPlan(app, planCopy(name = "Alm", source = saved.uuid, activity = "BIKE_TREKKING", line = seg(a, b, c)))
        app.go.planFollowedPlan(t); app.settle()
        assertEquals("a stand-in, not the saved hike", -1L, app.plan.savedId)
        assertEquals(Activity.BIKE_TREKKING, app.planRoute.activity)
    }

    @Test fun aCopyWithoutANameTakesTheToursNameAndWithoutALineItsPointsAreTheLine() {
        val app = App()
        app.draw(c, b)
        val t = recordedWithPlan(app, planCopy(name = null, via = listOf(a, c), line = emptyList()), name = "Ride")
        app.go.planFollowedPlan(t); app.settle()
        assertEquals(listOf(a, c), app.plan.points.toList())
        assertTrue("fitted to the two points (the copy's line is its points)", app.log.contains("main: fit 2"))
        assertTrue("named after the tour: ${problems.traced("plan")}", problems.traced("plan").any { it.contains("replaced by \"Ride\"") })
    }

    @Test fun aDraftUnderZuletztIsNotTheSourceEither() {
        val app = App()
        val saved = planned("Alm")
        db.setDraft(saved.id, true)
        val t = recordedWithPlan(app, planCopy(name = "Alm", source = saved.uuid, line = seg(a, b, c)))
        app.go.planFollowedPlan(t); app.settle()
        assertEquals("loaded from the copy", -1L, app.plan.savedId)
    }

    @Test fun losGehtsOnAFollowedPlanLoadsItAndStartsThroughTheCallersLambdaOnceTheLineIsThere() {
        val app = App()
        app.routeNow = route3()
        val saved = planned("Alm")
        val t = recordedWithPlan(app, planCopy(name = "Alm", source = saved.uuid))
        var started = 0
        app.go.goFollowedPlan(t) { started++ }; app.settle()
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals(1, started)
        assertTrue(problems.traced("routes").any { it.contains("Los geht's on tour \"Ride\": its followed plan is the saved route") })
    }

    @Test fun losGehtsOnAFollowedPlanDuringATourLoadsNothingAndStartsNothing() {
        val app = App().also { it.touring = true; it.recordingId = 1 }
        var started = 0
        app.go.goFollowedPlan(recordedWithPlan(app, planCopy())) { started++ }; app.settle()
        assertEquals(0, started); assertTrue(app.plan.points.isEmpty())
    }

    @Test fun theFollowedPlansLineIsOnlyReadForARecordedTourThatHasOne() {
        val app = App()
        val line = seg(a, b, c)
        val rec = recordedWithPlan(app, planCopy(line = line))
        val result = app.runOn { app.go.followedPlanLine(rec) }
        assertEquals(listOf(a, b, c), result)
        assertEquals("on io", listOf("io: store.getTourPlan ${rec.id}"), app.log)
        app.log.clear()
        assertEquals(emptyList<GeoPoint>(), app.runOn { app.go.followedPlanLine(rec.copy(hasPlan = false)) })
        assertEquals(emptyList<GeoPoint>(), app.runOn { app.go.followedPlanLine(planned("P").copy(hasPlan = true)) })
        assertTrue("no store read for those", app.log.isEmpty())
    }

    @Test fun aFollowedPlansLineThatCannotBeReadIsEmptyAndReported() {
        val app = App().also { it.failTourPlan = true }
        val rec = recordedWithPlan(app, planCopy())
        assertEquals(emptyList<GeoPoint>(), app.runOn { app.go.followedPlanLine(rec) })
        assertTrue(problems.said("db").any { it.contains("The followed plan's line of tour ${rec.id} not read") })
    }

    // ---- the look back at a done tour

    private fun reviewTour(name: String, activity: String = "HIKE", sac: Int = 2, km: Double): Tour {
        val id = db.insertTour(TourKind.RECORDED, name, activity, sac, listOf(seg(a, b, c)), distance = km * 1000)
        return db.getTour(id)!!
    }

    @Test fun theReviewRanksTheTourAmongTheDoneToursOfTheSameActivityAndLevelOnly() {
        val app = App()
        val mine = reviewTour("Mine", km = 10.0)
        reviewTour("Short", km = 4.0); reviewTour("Long", km = 20.0)
        reviewTour("Bike", activity = "BIKE_TREKKING", km = 30.0)     // another activity: not counted
        reviewTour("OtherLevel", sac = 4, km = 50.0)                  // a hike of another T level: not counted
        planned("Plan")                                               // not recorded: not counted
        app.slice.refreshTours(); app.settle()
        app.log.clear()
        val review = app.runOn { app.go.tourReview(mine) }!!
        assertEquals("2nd longest of 3", com.onpath.app.time.TourReview.Rank(2, 3), review.rank)
        assertEquals("the points are read on io", listOf("io: store.loadSegments ${mine.id}"), app.log)
    }

    @Test fun theReviewOfATourNotInTheListCountsItAsWell() {
        val app = App()
        val mine = reviewTour("Mine", km = 10.0)
        db.updateStats(mine.id, 10_000.0, 0.0, 0.0, 0.0, "active")        // not "done": not in the list
        reviewTour("Short", km = 4.0); reviewTour("Long", km = 20.0)
        app.slice.refreshTours(); app.settle()
        val review = app.runOn { app.go.tourReview(db.getTour(mine.id)!!) }!!
        assertEquals("counted once, with its own length", com.onpath.app.time.TourReview.Rank(2, 3), review.rank)
    }

    @Test fun aPlannedRouteHasNoReviewAndNothingIsRead() {
        val app = App()
        assertNull(app.runOn { app.go.tourReview(planned("P")) })
        assertTrue(app.log.isEmpty())
    }

    @Test fun aReviewWhoseTourPointsCannotBeReadIsNullAndReported() {
        val app = App()
        val mine = reviewTour("Mine", km = 10.0)
        app.failSegments = true
        assertNull(app.runOn { app.go.tourReview(mine) })
        assertTrue(problems.said("time").any { it.contains("Review of tour ${mine.id} not computed") })
    }

    // ---- Plan exportieren

    @Test fun theFollowedPlanIsExportedAsOnePlanGpxNamedAfterTheTourThroughTheHostsShare() {
        val app = App()
        val t = recordedWithPlan(app, planCopy(line = seg(a, b, c), pinned = setOf(1)), name = "Hilden")
        app.go.exportFollowedPlan(t)
        assertTrue("nothing before the read", app.shared.isEmpty())
        app.settle()
        assertEquals(listOf("io: store.getTourPlan ${t.id}", "main: share Hilden"), app.log)
        val file = app.shared.single()
        assertEquals("Hilden - plan.gpx", file.fileName)
        assertTrue("the named route points and the pin", file.text.contains("Start") && file.text.contains(com.onpath.app.gpx.PlanGpx.PINNED))
        assertTrue(app.notes.isEmpty())
    }

    @Test fun aTourWithoutAUsablePlanCopySaysSoAndExportsNothing() {
        val app = App()
        val one = recordedWithPlan(app, planCopy(via = listOf(a)))
        app.go.exportFollowedPlan(one); app.settle()
        assertTrue(app.shared.isEmpty())
        assertEquals(Triple(Txt.s(Res.string.routes_followed_plan_missing), NoticeKind.ERROR, NoticePlace.ROUTES), app.notes.single())
        assertTrue(problems.said("gpx", com.onpath.app.debug.Problems.Level.WARN).any { it.contains("Plan export of tour ${one.id}: no usable followed plan") })
    }

    @Test fun aCopyThatCannotBeReadForTheExportIsReportedAndSaysTheSame() {
        val app = App().also { it.failTourPlan = true }
        val t = recordedWithPlan(app, planCopy())
        app.go.exportFollowedPlan(t); app.settle()
        assertTrue(app.shared.isEmpty())
        assertEquals(NoticePlace.ROUTES, app.notes.single().third)
        assertTrue(problems.said("db").any { it.contains("The followed plan of tour ${t.id} not read for the export") })
    }

    @Test fun aPlannedRouteOrATourWithoutACopyIsNotExportedAndNothingRuns() {
        val app = App()
        app.go.exportFollowedPlan(planned("P").copy(hasPlan = true))
        app.go.exportFollowedPlan(recordedWithPlan(app, null))
        app.settle()
        assertTrue(app.log.isEmpty()); assertTrue(app.notes.isEmpty())
    }

    // ---- Als Plan übernehmen

    @Test fun aDoneTourBecomesThePlanThroughTheTrackToPlanPathAndTheOldPlanIsKept() {
        val app = App()
        app.draw(c, b)
        app.go.planFromTour(recorded("Ride")); app.settle()
        assertTrue(app.plan.points.size >= 2)
        assertNotEquals(listOf(c, b), app.plan.points.toList())
        assertEquals("the plan that was on the map is kept", 1, app.drafts().size)
        assertNull("the shown track goes when the plan takes over", app.slice.shown)
        assertTrue(problems.traced("plan").any { it == "\"Ride\" as the plan: the plan on the map goes under Zuletzt if not saved" })
    }

    // ---- plan links

    private fun sharedPlan(title: String? = "Link plan", activity: Activity = Activity.BIKE_TREKKING, sac: Int = 9) =
        com.onpath.app.routing.SharedPlan(listOf(a, b, c), activity, sac, setOf(1), mapOf(0 to "Start", 2 to "Ziel"), title)

    @Test fun aPlanFromALinkBecomesTheNewUnsavedPlanInTheOrderTheMapNeeds() {
        val app = App().also { it.mode = AppMode.NAVIGATE }
        app.draw(c, b)
        app.go.openSharedPlan(sharedPlan(), "a test")
        assertEquals(listOf(a, b, c), app.plan.points.toList())
        assertEquals(setOf(b), app.plan.pinned)
        assertEquals(Activity.BIKE_TREKKING, app.planRoute.activity); assertEquals("T level clamped to 1..5", 5, app.planRoute.sac)
        assertNull("an unsaved plan: Save asks for a name", app.plan.savedName)
        assertEquals("the link's name is known for the Save dialog", "Link plan", app.linkFlow.title(listOf(a, b, c)))
        assertEquals(1, app.drafts().size)
        assertEquals(listOf(
            "test: enter PLAN",
            "test: pref activity BIKE_TREKKING",
            "test: pref sac 5",
            "test: change 3 points; shown=null; drafts=1; activity=BIKE_TREKKING/T5; pins=1; routesOpen=false",
            "test: place name Start", "test: place name Ziel",
            "test: saved null -1",
            "test: holdCamera",
            "test: followMe false",
            "test: freeLookAt null",
            "test: event FitNext",
            "test: fit 3",
        ), app.log)
        assertTrue(problems.said("plan").any { it == "Plan link opened (a test): 3 points, BIKE_TREKKING, 1 pinned, 2 names" })
    }

    @Test fun aHikePlanLinkSaysItsLevelInTheLogAndAShownTrackGoes() {
        val app = App()
        app.slice.showTrack(ShownTrack(null, "GPX", listOf(listOf(a, b)), emptyList(), 1.0, 0.0, 0.0, 0.0)); app.log.clear()
        app.go.openSharedPlan(sharedPlan(activity = Activity.HIKE, sac = 3))
        assertNull(app.slice.shown)
        assertTrue("the shown track was gone when the plan changed: ${app.log}", app.log.any { it.startsWith("test: change 3 points; shown=null;") })
        assertTrue("already in Plan: no mode switch", app.log.none { it.contains("enter") })
        assertTrue(problems.said("plan").any { it == "Plan link opened (after asking): 3 points, HIKE T3, 1 pinned, 2 names" })
    }

    @Test fun aLinkWithoutPinsPinsNothing() {
        val app = App()
        app.go.openSharedPlan(com.onpath.app.routing.SharedPlan(listOf(a, b), Activity.HIKE, 2, emptySet(), emptyMap(), null))
        assertTrue(app.plan.pinned.isEmpty())
        assertNull(app.linkFlow.title(listOf(a, b)))
    }

    @Test fun thePlanOnTheMapIsSharedAsALinkWithItsNamePinsAndActivity() {
        val app = App()
        app.go.sharePlan()
        assertTrue("fewer than two points: no link", app.links.isEmpty())
        app.draw(a, b, c); app.plan.pin(b)
        app.stopNames[a] = "Start"
        app.planRoute.activity = Activity.BIKE_TREKKING
        app.plan.setSaved("Saved name", 7, listOf(a, b, c))
        app.go.sharePlan()
        val (link, title) = app.links.single()
        assertEquals("Saved name", title)
        val back = (com.onpath.app.routing.PlanLinks.fromText(link) as com.onpath.app.routing.PlanLinks.Found.Plan).plan
        assertEquals(listOf(a, b, c), back.points); assertEquals(Activity.BIKE_TREKKING, back.activity); assertEquals(setOf(1), back.pinned)
        assertEquals(mapOf(0 to "Start"), back.names); assertEquals("Saved name", back.title)
    }

    @Test fun anUnsavedPlanFromALinkIsSharedWithTheNameTheLinkBrought() {
        val app = App()
        app.go.openSharedPlan(sharedPlan(title = "From link")); app.log.clear()
        app.go.sharePlan()
        assertEquals("From link", app.links.single().second)
    }

    @Test fun aSavedPlannedRouteIsSharedAsALinkWithItsStopNamesPinsAndAnUnknownActivityIsHike() {
        val app = App()
        app.go.shareRoute(recorded("Track")); app.go.shareRoute(planned("One", via = arrayOf(a)))
        assertTrue("only planned routes with two points", app.links.isEmpty())
        val t = planned("Alm", activity = null, sac = 3, pinned = setOf(2), stopNames = mapOf(0 to "Start", 1 to "Mitte"))
        app.go.shareRoute(t)
        val (link, title) = app.links.single()
        assertEquals("Alm", title)
        val back = (com.onpath.app.routing.PlanLinks.fromText(link) as com.onpath.app.routing.PlanLinks.Found.Plan).plan
        assertEquals(Activity.HIKE, back.activity); assertEquals(3, back.sac); assertEquals(setOf(2), back.pinned)
        assertEquals(mapOf(0 to "Start", 1 to "Mitte"), back.names); assertEquals(listOf(a, b, c), back.points)
    }

    /** The link of a saved route of an activity this app does not know is a hike's: detour points lying on another profile are not in it (review finding 3). */
    @Test fun aSharedRouteOfAnUnknownActivityCarriesNoDetourPoints() {
        val app = App()
        val own = mapOf(1 to com.onpath.app.routing.Anchors(before = listOf(GeoPoint(46.690, 11.130)))) // exact to 1e-6, as a link keeps them
        app.go.shareRoute(planned("Alm", activity = "SKI_TOURING").copy(anchors = own))
        app.go.shareRoute(planned("Wald", activity = "HIKE").copy(anchors = own))
        fun anchorsOf(i: Int) = (com.onpath.app.routing.PlanLinks.fromText(app.links[i].first) as com.onpath.app.routing.PlanLinks.Found.Plan).plan.anchors
        assertEquals(emptyMap<Int, com.onpath.app.routing.Anchors>(), anchorsOf(0))
        assertEquals("a known hike keeps them", own, anchorsOf(1))
    }

    // ---- test clean-up G04 (2026-11-19): SavePathsTest.planFromATrackIsOneUndoStep as a flow

    /** Making a plan of a track replaces the plan in ONE edit of the view model (`host.change` = one Undo step), also through the router path. */
    @Test fun aTrackBecomesThePlanInOneEditThatIsOneUndoStep() {
        val app = App()
        app.draw(c, b)
        app.log.clear()
        app.go.planFromTour(recorded("Ride")); app.settle()
        assertEquals("one change of the plan = one Undo step: ${app.log}", 1, app.log.count { it.contains(": change ") })
    }
}
