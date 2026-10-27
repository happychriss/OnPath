package com.onpath.app.ui

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CORE FEATURE C15 (A-005): a plan link opens like a saved route – "Replace current route?" when the plan has unsaved
 * changes, at once otherwise, never during a tour (the followed route stays). Broken links change nothing.
 * Parsing: routing/PlanLinksTest; emulator: CorePlanLinkE2ETest.
 */
class CorePlanLinkTest {
    private val a = GeoPoint(46.67, 11.16)
    private val b = GeoPoint(46.70, 11.20)
    private val c = GeoPoint(46.72, 11.22)
    private val link = listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111))

    @Test fun unsavedChangesAskFirst() {
        assertEquals(LinkOpen.ASK_FIRST, RouteRules.linkOpen(false, listOf(a, b), saved = null, incoming = link))
        // a loaded route, changed
        assertEquals(LinkOpen.ASK_FIRST, RouteRules.linkOpen(false, listOf(a, b, c), saved = listOf(a, b), incoming = link))
    }

    @Test fun nothingToLoseOpensAtOnce() {
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, emptyList(), null, link))
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, listOf(a), null, link)) // one point is no route yet
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, listOf(a, b), saved = listOf(a, b), incoming = link)) // saved
        // the same link opened twice (camera, then the browser's "open in app"): nothing to ask
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, link, null, link))
    }

    /**
     * Owner 2026-10-27: a second link while the plan is still the unchanged plan of the first link replaces it without a
     * question (the plan is in the web planner / the first link anyway); once it was changed on the phone, it asks.
     */
    @Test fun secondLinkReplacesAnUnchangedLinkPlanWithoutAsking() {
        val first = listOf(a, b)
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, first, saved = null, incoming = link, opened = first))
        // the router moved the free point onto its way (snapToRoute → the opened points follow) – elevation never counts
        val snapped = listOf(a.copy(ele = 812.0), b)
        assertEquals(LinkOpen.NOW, RouteRules.linkOpen(false, snapped, saved = null, incoming = link, opened = first))
        // changed on the phone: a point added / moved → asks (Save first / Replace)
        assertEquals(LinkOpen.ASK_FIRST, RouteRules.linkOpen(false, first + c, saved = null, incoming = link, opened = first))
        assertEquals(LinkOpen.ASK_FIRST, RouteRules.linkOpen(false, listOf(a, c), saved = null, incoming = link, opened = first))
        // during a tour still nothing is loaded
        assertEquals(LinkOpen.AFTER_TOUR, RouteRules.linkOpen(true, first, saved = null, incoming = link, opened = first))
    }

    @Test fun duringATourItWaits() {
        for (plan in listOf(emptyList(), listOf(a, b)))
            assertEquals(LinkOpen.AFTER_TOUR, RouteRules.linkOpen(true, plan, null, link))
    }

    /** The wiring the pure rules cannot see (the view model needs Android): checked in the source. */
    @Test fun brokenLinkSaysSoAndChangesNothing() {
        val vm = File("src/main/java/com/onpath/app/ui/AppViewModel.kt").readText()
        val receive = vm.substringAfter("fun receivePlanLink(").substringBefore("fun pastedPlanLink(")
        val broken = receive.substringAfter("Found.Broken ->").substringBefore("Found.Plan ->")
        assertTrue(broken, "showError(Txt.s(Res.string.msg_plan_link_unreadable))" in broken)
        assertTrue(broken, "Problems.warn(" in broken)
        assertTrue(broken, "return" in broken && "change" !in broken && "waypoints" !in broken)
        // MainActivity checks for a plan link before the place / GPX handling (a planner link is no Google place)
        val act = File("src/main/java/com/onpath/app/MainActivity.kt").readText().substringAfter("private fun handleIntent(")
        assertTrue(act.indexOf("planLinkOf(intent)") in 0 until act.indexOf("sharedPlaceOf(intent)"))
    }
}
