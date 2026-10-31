package com.onpath.app.ui

import com.onpath.app.data.TourKind
import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-10-31 ("routes can't be loaded"): during a tour a planned route in My routes asks "Replace the route of
 * the running tour?". Replace = it becomes the plan and the tour follows it; the recording goes on unchanged (same
 * tour, track, timer – core feature C4); Undo brings the previous plan back. Cancel = nothing changes.
 *
 * The steps are AppViewModel's (RouteRules.openAsk + PlanHistory + the follow on the next route result), checked here
 * as a model like [RouteBackTest], plus the code paths that must never touch the recording. The real app on an
 * emulator: `CoreFieldE2ETest.loadingASavedRouteDuringATourReplacesTheFollowedRouteOnly`.
 */
class TourRouteReplaceTest {
    private val a1 = GeoPoint(46.670, 11.160)
    private val a2 = GeoPoint(46.700, 11.200)
    private val b1 = GeoPoint(46.640, 11.130)
    private val b2 = GeoPoint(46.620, 11.100)
    private val b3 = GeoPoint(46.610, 11.090)

    /** What the recording service holds – nothing in the replace path may change it. */
    private data class Recording(val tourId: Long, val track: List<GeoPoint>, val elapsedS: Long, val paused: Boolean)

    /** A saved planned route in My routes. */
    private class Saved(val name: String, val points: List<GeoPoint>, val activity: String)

    /** A running tour following plan [plan], as AppViewModel holds it. */
    private inner class Vm(var plan: List<GeoPoint>) {
        val recording = Recording(42, listOf(a1, GeoPoint(46.675, 11.165)), 1_800, paused = false)
        var rec: Recording? = recording
        val history = PlanHistory()
        var saved: List<GeoPoint>? = null
        var savedName: String? = null
        var activity = "HIKE"
        var followed: List<GeoPoint>? = plan
        var followName: String? = "Route 31.10."
        var tourAsk: Saved? = null
        var replaceAsk: Saved? = null
        var followNext = false
        var tourActivity = "HIKE"

        /** [AppViewModel.requestOpen]. */
        fun tap(s: Saved) = when (RouteRules.openAsk(TourKind.PLANNED, s.points.size, rec != null, plan, saved)) {
            OpenAsk.REPLACE_TOUR_ROUTE -> tourAsk = s
            OpenAsk.REPLACE_PLAN -> replaceAsk = s
            OpenAsk.NONE -> open(s)
        }
        fun cancel() { tourAsk = null }
        fun replace() { val s = tourAsk ?: return; tourAsk = null; open(s) }

        /** [AppViewModel.openTour], planned route: one Undo step, its activity, linked to it, followed during a tour. */
        private fun open(s: Saved) {
            activity = s.activity
            history.record(plan); plan = s.points
            saved = s.points; savedName = s.name
            if (rec != null) followNext = true
        }

        /** The route of the plan is computed ([AppViewModel.recalc] → followPlanned, the tour takes the activity). */
        fun routeReady() {
            if (!followNext) return
            followNext = false
            followed = plan; followName = savedName.takeIf { RouteRules.linkedToSaved(plan, saved) } ?: "Route 31.10."
            tourActivity = activity
        }

        fun undo() { history.undo(plan)?.let { plan = it; if (rec != null && followed != null) followed = plan } }
    }

    private val meran = Saved("Meran loop", listOf(b1, b2, b3), "BIKE_TOURING")

    @Test fun replaceMakesTheSavedRouteTheFollowedPlanAndTheRecordingStays() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(meran)
        assertEquals("one question, not \"Replace current route?\"", meran, vm.tourAsk)
        assertNull(vm.replaceAsk)
        vm.replace()
        vm.routeReady()
        assertEquals(listOf(b1, b2, b3), vm.plan)
        assertEquals("the tour follows the new route", listOf(b1, b2, b3), vm.followed)
        assertEquals("named after the loaded route", "Meran loop", vm.followName)
        assertEquals("the route keeps its activity, the tour takes it", "BIKE_TOURING", vm.tourActivity)
        assertEquals("same tour, track and timer", vm.recording, vm.rec)
    }

    @Test fun cancelChangesNothing() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(meran)
        vm.cancel()
        vm.routeReady()
        assertNull(vm.tourAsk)
        assertEquals(listOf(a1, a2), vm.plan)
        assertEquals(listOf(a1, a2), vm.followed)
        assertEquals("HIKE", vm.activity)
        assertFalse(vm.history.canUndo)
        assertEquals(vm.recording, vm.rec)
    }

    @Test fun undoBringsThePreviousPlanBack() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(meran); vm.replace(); vm.routeReady()
        vm.undo()
        assertEquals(listOf(a1, a2), vm.plan)
        assertEquals(listOf(a1, a2), vm.followed)
        assertEquals(vm.recording, vm.rec)
    }

    @Test fun anUnsavedPlanDuringATourStillAsksTheOneQuestion() {
        val vm = Vm(listOf(a1, a2)) // never saved
        vm.tap(meran)
        assertEquals(meran, vm.tourAsk)
        assertNull(vm.replaceAsk)
    }

    // ---- the app's code: the replace path never ends, splits or restarts the recording

    private val vmSrc = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vmSrc.substringAfter(start).substringBefore("\n    }\n")

    @Test fun theReplacePathNeverTouchesTheRecording() {
        for (f in listOf("fun tourReplaceConfirmed(", "fun tourReplaceCancel(", "fun openTour(", "fun followPlanned(",
                "private fun setFollow(", "private fun saveTourActivity(")) {
            val b = body(f)
            assertTrue("$f found", b.length > 20)
            for (bad in listOf("ACTION_STOP", "ACTION_START", "stopRecording", "startRecording", "ACTION_PAUSE", "endTour"))
                assertFalse("$f must not $bad", b.contains(bad))
        }
        // the only word to the service: the followed line changed (the service reloads nav-session.json)
        assertTrue(body("private fun setFollow(").contains("ACTION_FOLLOW_CHANGED"))
    }

    @Test fun duringATourTheLoadedRouteIsFollowedWhenItsLineIsThere() {
        val open = body("fun openTour(")
        assertTrue(open, open.contains("if (recState.value != null) { followNextRoute = true; storeTourActivity = true }"))
        val confirm = body("fun tourReplaceConfirmed(")
        assertTrue(confirm, confirm.contains("tourReplaceAsk = null") && confirm.contains("openTour(t)"))
        val cancel = body("fun tourReplaceCancel(")
        assertTrue(cancel, cancel.contains("tourReplaceAsk = null") && !cancel.contains("openTour"))
        val recalc = vmSrc.substringAfter("if (followNextRoute) {").substringBefore("} else if (planEditPending")
        assertTrue(recalc, recalc.contains("followPlanned(checkNow = true)") && recalc.contains("if (storeActivity) saveTourActivity()"))
        // the followed route carries the saved route's name
        assertTrue(body("fun followPlanned(").contains("savedName ?: defaultName("))
    }

    @Test fun theFollowChangedCommandKeepsTheTour() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        val follow = svc.substringAfter("ACTION_FOLLOW_CHANGED -> {").substringBefore("\n            }\n")
        assertTrue(follow, follow.contains("loadFollow()"))
        for (bad in listOf("finish(", "insertTour", "tourId =", "track.clear", "elapsedS ="))
            assertFalse("FOLLOW_CHANGED must not $bad", follow.contains(bad))
        val load = svc.substringAfter("private fun loadFollow() {").substringBefore("\n    }\n")
        for (bad in listOf("finish(", "insertTour", "tourId =", "track.clear", "track =", "elapsedS =", "distance ="))
            assertFalse("loadFollow must not $bad", load.contains(bad))
    }
}
