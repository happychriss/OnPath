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
 * Owner 2026-10-31: no new route during a tour ("too much automation – as a hiker I wouldn't trust it"). A tap on a
 * planned route in My routes during a tour loads nothing; the blue box "End the running tour first, then this route can
 * be loaded." with OK shows at the top of My routes until OK or until My routes is left. Tracks open as before.
 *
 * The steps are AppViewModel's (RouteRules.openAsk + the box state), checked here as a model like [RouteBackTest], plus
 * the app's code for the same path. The real app on an emulator:
 * `CoreFieldE2ETest.aSavedRouteTappedDuringATourKeepsTheTourRouteAndSaysEndTheTourFirst`.
 */
class TourRouteBlockedTest {
    private val a1 = GeoPoint(46.670, 11.160)
    private val a2 = GeoPoint(46.700, 11.200)
    private val b1 = GeoPoint(46.640, 11.130)
    private val b2 = GeoPoint(46.620, 11.100)

    /** A running tour following plan [plan], My routes open, as AppViewModel holds it. */
    private class Vm(var plan: List<GeoPoint>, var touring: Boolean = true) {
        var saved: List<GeoPoint>? = plan
        var followed: List<GeoPoint>? = plan.takeIf { touring }
        var replaceAsk: List<GeoPoint>? = null
        var shownTrack: String? = null
        var routesOpen = true
            set(v) { if (!v) blocked = false; field = v }
        var blocked = false

        /** [AppViewModel.requestOpen]. */
        fun tap(kind: TourKind, name: String, points: List<GeoPoint>) =
            when (RouteRules.openAsk(kind, points.size, touring, plan, saved)) {
                OpenAsk.BLOCKED_DURING_TOUR -> blocked = true
                OpenAsk.REPLACE_PLAN -> replaceAsk = points
                OpenAsk.NONE -> if (RouteRules.replacesPlan(kind, points.size)) { plan = points; saved = points } else shownTrack = name
            }
    }

    @Test fun aPlannedRouteTappedDuringATourLoadsNothingAndSaysEndTheTourFirst() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        assertTrue("the box shows", vm.blocked)
        assertEquals("the plan stays", listOf(a1, a2), vm.plan)
        assertEquals("the followed route stays", listOf(a1, a2), vm.followed)
        assertNull("no \"Replace current plan?\"", vm.replaceAsk)
    }

    @Test fun theBoxStaysUntilOkOrUntilMyRoutesIsLeft() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.blocked = false // OK
        assertFalse(vm.blocked)
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        vm.routesOpen = false // Back
        assertFalse(vm.blocked)
    }

    @Test fun tracksStillOpenDuringATour() {
        val vm = Vm(listOf(a1, a2))
        vm.tap(TourKind.IMPORTED, "Waalweg", listOf(b1, b2))
        assertFalse(vm.blocked)
        assertEquals("Waalweg", vm.shownTrack)
        assertEquals(listOf(a1, a2), vm.plan)
    }

    @Test fun afterTheTourThePlannedRouteLoads() {
        val vm = Vm(listOf(a1, a2), touring = false)
        vm.tap(TourKind.PLANNED, "Meran loop", listOf(b1, b2))
        assertFalse(vm.blocked)
        assertEquals(listOf(b1, b2), vm.plan)
    }

    // ---- the app's code

    private val vmSrc = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
    private fun body(start: String) = vmSrc.substringAfter(start).substringBefore("\n    }\n")

    @Test fun requestOpenDuringATourOnlyShowsTheBox() {
        val open = body("fun requestOpen(")
        assertTrue(open, open.contains("navigating(recState.value != null)"))
        val blocked = open.substringAfter("OpenAsk.BLOCKED_DURING_TOUR ->").substringBefore("OpenAsk.REPLACE_PLAN")
        assertTrue(blocked, blocked.contains("routesTourBlocked = true"))
        for (bad in listOf("openTour", "open(", "replaceAsk", "followNextRoute", "setFollow", "change {"))
            assertFalse("the blocked tap must not $bad", blocked.contains(bad))
        // the box goes when My routes is left
        val routesSetter = vmSrc.substringAfter("var routesOpen: Boolean").substringBefore("\n    /**")
        assertTrue(routesSetter, routesSetter.contains("routesTourBlocked = false"))
    }

    @Test fun theReplaceDuringATourPathIsGone() {
        for (gone in listOf("tourReplaceAsk", "tourReplaceConfirmed", "storeTourActivity", "saveTourActivity"))
            assertFalse("$gone removed", vmSrc.contains(gone))
        // kept: the followed route carries the saved route's name while the plan belongs to one
        assertTrue(body("fun followPlanned(").contains("savedName ?: defaultName("))
    }
}
