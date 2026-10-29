package com.onpath.app.e2e

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanLinks
import com.onpath.app.ui.UiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CORE FEATURE C15 (A-005): a plan link from the web planner (the phone's camera reads the QR code and opens the link)
 * reaches the running app as a VIEW intent and becomes the plan; a broken one is said and changes nothing; unsaved
 * changes ask first; Share plan writes a link that opens the same plan again.
 */
@RunWith(AndroidJUnit4::class)
class CorePlanLinkE2ETest : E2EBase() {
    /** The frozen version-1 code (PlanCodeTest.formatVersion1IsFrozen): Hike T3, 2 points, point 2 "Vellau", pinned. */
    private val frozenLink = "https://plan.onpath.earth/#p=AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU"

    @Before fun setUp() = launch()

    /** What the camera does: VIEW with the link, to OnPath (singleTask → onNewIntent of the running app). */
    private fun open(link: String) {
        scenario.onActivity { a ->
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(a.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Thread.sleep(500)
    }

    private fun points(): List<GeoPoint> { var p = emptyList<GeoPoint>(); scenario.onActivity { p = vm(it).waypoints.toList() }; return p }

    @Test fun linkBecomesThePlan() {
        open(frozenLink)
        waitUntil("plan from the link – ${state()}") { waypointCount() == 2 }
        scenario.onActivity { a ->
            val vm = vm(a)
            assertEquals(Activity.HIKE, vm.activity)
            assertEquals(3, vm.sac)
            assertEquals(setOf(1), vm.pinnedIndices())
            // the pinned, named point keeps its place exactly (never snapped)
            assertEquals(GeoPoint(46.6879, 11.1111), vm.waypoints[1])
            assertEquals("Vellau", vm.stopName(vm.waypoints[1]))
            assertNull("a link is not a saved route", vm.savedName)
            assertNull(vm.errorCard)
        }
    }

    @Test fun brokenLinkIsSaidAndChangesNothing() {
        startPlanWithTwoPoints()
        val before = points()
        open(frozenLink.dropLast(9))
        waitUntil("error card") { var m: String? = null; scenario.onActivity { m = vm(it).errorCard }; m != null }
        scenario.onActivity { a ->
            // no place for it (the link came from outside): the light error card
            assertTrue(vm(a).errorCard!!, vm(a).errorCard!!.startsWith("This plan link can't be read"))
            assertNull(vm(a).replaceAsk)
        }
        assertEquals(before, points())
    }

    @Test fun unsavedPlanAsksFirst() {
        startPlanWithTwoPoints()
        val before = points()
        open(frozenLink)
        assertNotNull("Replace current route? – ${state()}", findFresh(By.text("Replace current route?"), 10_000))
        assertEquals("nothing replaced before the answer", before, points())
        clickText("Replace")
        waitUntil("replaced by the link's plan") { points().getOrNull(1) == GeoPoint(46.6879, 11.1111) }
        scenario.onActivity { assertEquals(Activity.HIKE, vm(it).activity) }
    }

    /** The next QR code replaces an unchanged link plan silently; a changed one asks (owner). */
    @Test fun secondLinkReplacesAnUnchangedLinkPlanWithoutAsking() {
        val second = PlanLinks.link(com.onpath.app.routing.SharedPlan(
            listOf(GeoPoint(46.68012, 11.1264), GeoPoint(46.6879, 11.1111), GeoPoint(46.6923, 11.0986)), Activity.MTB,
            pinned = setOf(0, 1, 2)))
        open(frozenLink)
        waitUntil("first link's plan") { waypointCount() == 2 }
        Thread.sleep(1_500) // routed and snapped – still "unchanged"
        open(second)
        waitUntil("second link's plan, no question – ${state()}") { waypointCount() == 3 }
        scenario.onActivity { assertNull(vm(it).replaceAsk); assertEquals(Activity.MTB, vm(it).activity) }
        // changed on the phone → the next link asks
        Thread.sleep(1_500)
        scenario.onActivity { vm(it).deletePointFromList(2) }
        waitUntil("point deleted") { waypointCount() == 2 }
        open(frozenLink)
        assertNotNull("Replace current route? – ${state()}", findFresh(By.text("Replace current route?"), 10_000))
    }

    @Test fun sharedLinkOpensTheSamePlan() {
        open(frozenLink)
        waitUntil("plan from the link") { waypointCount() == 2 }
        var link: String? = null
        scenario.onActivity { a ->
            val vm = vm(a)
            vm.sharePlan()
            val chooser = vm.events.filterIsInstance<UiEvent.Share>().last().intent
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            link = send.getStringExtra(Intent.EXTRA_TEXT)
            vm.events.removeAll { it is UiEvent.Share } // the share sheet is not opened in the test
        }
        val plan = (PlanLinks.fromLink(link) as PlanLinks.Found.Plan).plan
        assertEquals(points().map { GeoPoint(it.lat, it.lon) }.size, plan.points.size)
        assertEquals(Activity.HIKE, plan.activity)
        assertEquals(3, plan.sac)
        assertEquals(setOf(1), plan.pinned)
        assertEquals(mapOf(1 to "Vellau"), plan.names)
        assertEquals("the link's name comes along", "Waalweg", plan.title)
    }
}
