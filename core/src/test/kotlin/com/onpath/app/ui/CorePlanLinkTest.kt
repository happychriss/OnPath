package com.onpath.app.ui

import com.onpath.app.domain.LinkOpen
import com.onpath.app.domain.RouteRules
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
     * A second link while the plan is still the unchanged plan of the first link replaces it without a
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

    /**
     * A broken link says so and changes nothing: behaviour now (ui/sync/PlanLinkFlowTest.aBrokenLinkSaysSoAndChangesNothing –
     * the red line on the map, the problem log, nothing opened). Here the wiring only the source shows: the view model
     * hands every link to that flow, and MainActivity looks for a plan link first.
     */
    @Test fun brokenLinkSaysSoAndChangesNothing() {
        // MainActivity hands every link to the flow (piece 21: the view model's `receivePlanLink` forwarder is gone)
        val main = File("../app/src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue("vm.links.receive(found, if (intent.action == Intent.ACTION_VIEW) \"opened\" else \"shared\")" in main)
        // MainActivity checks for a plan link before the place / GPX handling (a planner link is no Google place)
        val act = main.substringAfter("private fun handleIntent(")
        assertTrue(act.indexOf("planLinkOf(intent)") in 0 until act.indexOf("sharedPlaceOf(intent)"))
    }
}
