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
 * Owner 2026-11-03 (was 2026-10-31's dead end "End the running tour first"): a planned route opened from My routes while
 * a tour runs asks "Route „‹name›“ laden? Du bist gerade unterwegs." – **Mit dieser Route weiter** (the recording goes on,
 * the tour follows the loaded route, back to Navigate) · **Tour beenden und laden** (the usual end question, the route
 * loads only after the tour has ended) · **Abbrechen** (nothing changes). Tracks open as before.
 *
 * The steps as a model of AppViewModel (RouteRules.openAsk + TourLoad), plus the app's code for the same path. On the
 * emulator: `ReleaseRundgang.flow06_savedRouteDuringATourAsksAndContinuesWithTheRoute`,
 * `CoreFieldE2ETest.aSavedRouteTappedDuringATourAsksAndCancelKeepsTheTourRoute`.
 */
class TourLoadTest {
    private val a1 = GeoPoint(46.670, 11.160)
    private val a2 = GeoPoint(46.700, 11.200)
    private val b1 = GeoPoint(46.640, 11.130)
    private val b2 = GeoPoint(46.620, 11.100)

    /** A running tour following plan [plan], My routes open, as AppViewModel holds it. */
    private class Vm(var plan: List<GeoPoint>, var touring: Boolean = true) {
        var saved: List<GeoPoint>? = plan
        var followed: List<GeoPoint>? = plan.takeIf { touring }
        var ask: List<GeoPoint>? = null
        var replaceAsk: List<GeoPoint>? = null
        var shownTrack: String? = null
        var endQuestion = false
        var loadAfter: List<GeoPoint>? = null
        var navigate = false
        var routesOpen = true
            set(v) { if (!v) ask = null; field = v }

        /** [AppViewModel.requestOpen]. */
        fun tap(kind: TourKind, name: String, points: List<GeoPoint>) =
            when (RouteRules.openAsk(kind, points.size, touring, plan, saved)) {
                OpenAsk.BLOCKED_DURING_TOUR -> ask = points
                OpenAsk.REPLACE_PLAN -> replaceAsk = points
                OpenAsk.NONE -> if (RouteRules.replacesPlan(kind, points.size)) { plan = points; saved = points } else shownTrack = name
            }

        /** [AppViewModel.answerTourLoad]. */
        fun answer(a: TourLoad.Answer) {
            val t = ask ?: return
            ask = null
            when (TourLoad.step(a)) {
                TourLoad.Step.FOLLOW_NOW -> { plan = t; saved = t; followed = t; routesOpen = false; navigate = true }
                TourLoad.Step.END_THEN_LOAD -> { loadAfter = t; routesOpen = false; endQuestion = true }
                TourLoad.Step.NOTHING -> Unit
            }
        }

        /** The end question: Save / Delete end the tour, Cancel keeps it ([AppViewModel.dismissEndAsk]). */
        fun end(saveOrDelete: Boolean) {
            endQuestion = false
            if (!saveOrDelete) { loadAfter = null; return }
            touring = false; followed = null; plan = emptyList()
            loadAfter?.let { plan = it; saved = it; loadAfter = null }
        }
    }

    @Test fun threeAnswers() {
        assertEquals(TourLoad.Step.FOLLOW_NOW, TourLoad.step(TourLoad.Answer.CONTINUE))
        assertEquals(TourLoad.Step.END_THEN_LOAD, TourLoad.step(TourLoad.Answer.END_AND_LOAD))
        assertEquals(TourLoad.Step.NOTHING, TourLoad.step(TourLoad.Answer.CANCEL))
    }

    @Test fun aPlannedRouteTappedDuringATourAsks() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        assertEquals("the question", listOf(b1, b2), vm.ask)
        assertEquals("nothing loaded yet", listOf(a1, a2), vm.plan)
        assertNull("no \"Replace current plan?\"", vm.replaceAsk)
    }

    @Test fun continueFollowsTheLoadedRouteAndTheTourGoesOn() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.answer(TourLoad.Answer.CONTINUE)
        assertEquals(listOf(b1, b2), vm.plan)
        assertEquals("the tour follows it", listOf(b1, b2), vm.followed)
        assertTrue("still recording", vm.touring)
        assertTrue("back to Navigate", vm.navigate)
    }

    @Test fun endAndLoadLoadsOnlyAfterTheTourHasEnded() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.answer(TourLoad.Answer.END_AND_LOAD)
        assertTrue("the end question", vm.endQuestion)
        assertEquals("not loaded before the tour ends", listOf(a1, a2), vm.plan)
        vm.end(saveOrDelete = true)
        assertFalse(vm.touring)
        assertEquals(listOf(b1, b2), vm.plan)
    }

    @Test fun cancellingTheEndQuestionLoadsNothing() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.answer(TourLoad.Answer.END_AND_LOAD)
        vm.end(saveOrDelete = false)
        assertTrue(vm.touring)
        assertEquals(listOf(a1, a2), vm.plan)
        assertNull(vm.loadAfter)
    }

    @Test fun cancelChangesNothingAndLeavingMyRoutesDropsTheQuestion() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.answer(TourLoad.Answer.CANCEL)
        assertNull(vm.ask)
        assertEquals(listOf(a1, a2), vm.plan)
        assertEquals(listOf(a1, a2), vm.followed)
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.routesOpen = false // Back
        assertNull(vm.ask)
    }

    @Test fun tracksStillOpenDuringATour() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.IMPORTED, "Waalweg", listOf(b1, b2))
        assertNull(vm.ask)
        assertEquals("Waalweg", vm.shownTrack)
    }

    // ---- the app's code

    private val vmSrc = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vmSrc.substringAfter(start).substringBefore("\n    }\n")

    @Test fun theAppAsksAndActsOnTheAnswer() {
        val open = body("fun requestOpen(")
        val blocked = open.substringAfter("OpenAsk.BLOCKED_DURING_TOUR ->").substringBefore("OpenAsk.REPLACE_PLAN")
        assertTrue(blocked, blocked.contains("tourLoadAsk = t"))
        for (bad in listOf("openTour", "replaceAsk")) assertFalse("asking must not $bad", blocked.contains(bad))
        val answer = body("fun answerTourLoad(")
        assertTrue(answer, answer.contains("TourLoad.Step.FOLLOW_NOW -> openTour(t, duringTour = true)"))
        assertTrue(answer, answer.contains("TourLoad.Step.END_THEN_LOAD -> { loadAfterTour = t; routesOpen = false; askEndTour() }"))
        // the tour keeps its activity, Navigate after it
        val openTour = body("fun openTour(")
        assertTrue(openTour, openTour.contains("if (!duringTour) {"))
        assertTrue(openTour, openTour.contains("if (duringTour && recState.value != null) enter(AppMode.NAVIGATE)"))
        // the route waiting for the end loads after Save / Delete, not after Cancel
        assertTrue(vmSrc.contains("loadAfterTour?.let { t -> loadAfterTour = null; openTour(t) }"))
        assertTrue(body("fun dismissEndAsk(").contains("loadAfterTour = null"))
        val map = File("src/main/java/com/onpath/app/ui/MapScreen.kt").readText()
        assertFalse("every end-question dismiss goes through dismissEndAsk", map.contains("vm.endAsk = null"))
        // the question goes when My routes is left
        val routesSetter = vmSrc.substringAfter("var routesOpen: Boolean").substringBefore("\n    /**")
        assertTrue(routesSetter, routesSetter.contains("tourLoadAsk = null"))
        assertFalse("the dead end is gone", vmSrc.contains("routesTourBlocked"))
    }

    @Test fun theReplaceDuringATourPathIsGone() {
        for (gone in listOf("tourReplaceAsk", "tourReplaceConfirmed", "storeTourActivity", "saveTourActivity"))
            assertFalse("$gone removed", vmSrc.contains(gone))
        assertTrue(body("fun followPlanned(").contains("savedName ?: defaultName("))
    }
}
