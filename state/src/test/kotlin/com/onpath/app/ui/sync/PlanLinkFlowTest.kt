package com.onpath.app.ui.sync

import com.onpath.app.debug.Problems
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlanLinks
import com.onpath.app.routing.SharedPlan
import com.onpath.app.testkit.ProblemsRule
import com.onpath.app.ui.NoticePlace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Plan links in and out ([PlanLinkFlow], cleanup piece 4; docs/core-features/C15-plan-links.md): what the view model's source text
 * was pinned for – a broken link says so and changes nothing – and the other ways a link ends, as behaviour. The rule
 * when a link asks / waits is CorePlanLinkTest's (RouteRules.linkOpen); the round trip on the phone CorePlanLinkE2ETest.
 */
class PlanLinkFlowTest {
    @get:Rule val problems = ProblemsRule()

    private val a = GeoPoint(51.17, 6.93)
    private val b = GeoPoint(51.20, 6.77)
    private val c = GeoPoint(51.25, 6.80)
    private val plan = SharedPlan(listOf(a, b), Activity.HIKE, sac = 3, title = "Algund → Meran")

    private class FakeHost : PlanLinkHost {
        val log = ArrayList<String>()
        var navigatingNow = false
        var points = listOf<GeoPoint>()
        var saved: List<GeoPoint>? = null
        var startKnown = CompletableDeferred(Unit)
        val opened = ArrayList<SharedPlan>()
        val shared = ArrayList<Pair<String, String?>>()
        override fun backToMap() { log += "back to the map" }
        override fun sayError(text: String, place: NoticePlace) { log += "$place: $text" }
        override fun receivePairing(secret: ByteArray, via: String) { log += "pairing ${secret.size} bytes ($via)" }
        override fun openSyncSettings() { log += "browser sync page" }
        override suspend fun startModeKnown() { startKnown.await() }
        override fun navigating() = navigatingNow
        override fun planPoints() = points
        override fun savedPoints() = saved
        override fun open(plan: SharedPlan, via: String) { opened += plan; log += "opened ($via)" }
        override fun share(link: String, title: String?) { shared += link to title }
        override var storedLinkPoints: List<GeoPoint>? = null
    }

    private val host = FakeHost()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    @After fun end() = scope.cancel()
    private val links = PlanLinkFlow(host, scope)

    // ---------------- a link comes in ----------------

    /** One red line on the map, a line in the problem log, the plan untouched. */
    @Test fun aBrokenLinkSaysSoAndChangesNothing() {
        host.points = listOf(a, c)
        links.receive(PlanLinks.Found.Broken, "tapped")
        assertEquals(listOf("back to the map", "MAP: This plan link can't be read. Share or copy the whole link again."), host.log)
        assertTrue(host.opened.isEmpty())
        assertNull("what the last link brought stays as it was", host.storedLinkPoints)
        assertEquals(listOf("Plan link (tapped) could not be read: cut off, damaged or a newer format"), problems.said("plan", Problems.Level.WARN))
    }

    @Test fun aPlanLinkOpensThePlan() {
        links.receive(PlanLinks.Found.Plan(plan), "camera")
        assertEquals(listOf("back to the map", "opened (camera)"), host.log)
        assertEquals(listOf(plan), host.opened)
    }

    /** No question any more (owner 2026-11-06): an unsaved plan on the map goes under Zuletzt in the view model's open. */
    @Test fun aLinkOverAnUnsavedPlanOpensWithoutAQuestion() {
        host.points = listOf(a, c)
        links.receive(PlanLinks.Found.Plan(plan), "tapped")
        assertEquals(listOf(plan), host.opened)
    }

    /** During a tour the followed route stays: nothing is loaded, a line says when it will open. */
    @Test fun duringATourTheLinkWaits() {
        host.navigatingNow = true
        links.receive(PlanLinks.Found.Plan(plan), "tapped")
        assertTrue(host.opened.isEmpty())
        assertEquals("MAP: Plan links open after the tour – end the tour, then open the link again.", host.log.last())
        assertTrue("no warning: it is the rule, said to the user", problems.none())
    }

    /** Cold start by a link: whether a tour is running is known only after the database check – decided then, not before. */
    @Test fun onAColdStartTheLinkWaitsForTheStartMode() {
        host.startKnown = CompletableDeferred()
        links.receive(PlanLinks.Found.Plan(plan), "tapped")
        assertTrue(host.opened.isEmpty())
        host.navigatingNow = true // the check found an unfinished tour that goes on
        host.startKnown.complete(Unit)
        assertTrue(host.opened.isEmpty())
        assertTrue(host.log.last().startsWith("MAP: Plan links open after the tour"))
    }

    @Test fun aPairingLinkGoesToPairingAndABrokenOneToBrowserSync() {
        links.receive(PlanLinks.Found.Pairing(ByteArray(32)), "camera")
        assertEquals(listOf("back to the map", "pairing 32 bytes (camera)"), host.log)
        host.log.clear()
        links.receive(PlanLinks.Found.BrokenPairing, "camera")
        assertEquals(listOf("back to the map", "browser sync page",
            "SETTINGS: This connection code can't be read. Show the code in the browser again and scan it once more."), host.log)
        assertTrue(host.opened.isEmpty())
        assertEquals(1, problems.said("sync", Problems.Level.WARN).size)
    }

    @Test fun pastedTextIsALinkOrASearch() {
        var cleared = 0
        assertFalse(links.pasted("Schloss Benrath") { cleared++ })
        assertEquals(0, cleared); assertTrue(host.log.isEmpty())
        assertTrue(links.pasted("schau mal: " + PlanLinks.link(plan)) { cleared++ })
        assertEquals("the search box empties", 1, cleared)
        assertEquals(listOf(plan), host.opened)
        assertEquals("opened (pasted)", host.log.last())
    }

    // ---------------- what the last link brought ----------------

    /** The link's name is proposed while the plan still has its points – also after the router moved them onto their ways. */
    @Test fun theLinksNameHoldsWhileThePlanHasItsPoints() {
        assertNull(links.title(listOf(a, b)))
        links.opened("Algund → Meran", listOf(a, b))
        assertEquals("Algund → Meran", links.title(listOf(a, b)))
        assertNull("a point added: the name is not this plan's any more", links.title(listOf(a, b, c)))
        val snapped = listOf(a, GeoPoint(51.2001, 6.7702))
        links.snapped(routed = listOf(a, b), snapped = snapped)
        assertEquals("Algund → Meran", links.title(snapped))
        assertEquals(snapped, host.storedLinkPoints)
        links.opened(null, listOf(a, c)) // a link without a name replaces it
        assertNull(links.title(listOf(a, c)))
    }

    /** The same link again over its own unchanged plan – also after an app restart (the points are a pref) – just opens. */
    @Test fun theLinksPointsSurviveARestart() {
        links.opened(plan.title, plan.points)
        assertEquals(plan.points, host.storedLinkPoints)
        host.points = plan.points
        val again = PlanLinkFlow(host, scope) // the app started again
        again.receive(PlanLinks.Found.Plan(plan), "tapped")
        assertEquals(listOf(plan), host.opened)
        // a snap of another plan's points does not move what the link brought
        again.snapped(routed = listOf(a, c), snapped = listOf(a, GeoPoint(51.26, 6.81)))
        assertEquals(plan.points, host.storedLinkPoints)
    }

    // ---------------- a plan goes out ----------------

    @Test fun aSharedPlanIsALinkThatReadsBack() {
        links.share(plan, plan.points.size, plan.title)
        val (link, title) = host.shared.single()
        assertEquals("Algund → Meran", title)
        assertEquals(PlanLinks.Found.Plan(plan), PlanLinks.fromText(link))
        assertEquals(listOf("Plan shared as a link: 2 points, ${link.length} characters"), problems.traced("plan"))
    }
}
