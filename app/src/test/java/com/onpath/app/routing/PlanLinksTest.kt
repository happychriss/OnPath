package com.onpath.app.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CORE FEATURE C15 (A-005, web planner phase 1): a plan link (`https://planer.onpath.earth/#p=<code>`) from the phone's
 * camera (QR code), a tapped link, shared text or the search box opens as the plan; a broken link is said, never
 * loaded; Share plan writes the link that opens again. The intent arrives here as its plain strings
 * (MainActivity.planLinkOf → [PlanLinks.fromIntent]).
 */
class PlanLinksTest {
    private val view = PlanLinks.Via.OPENED
    private val send = PlanLinks.Via.SHARED
    /** The frozen version-1 code (PlanCodeTest.formatVersion1IsFrozen, also checked by the web planner's browser test). */
    private val frozen = "AQUDAsTcuQTwjYgBqBnjPAIBB1dhYWx3ZWcCBwFWZWxsYXU"
    private val frozenLink = "https://planer.onpath.earth/#p=$frozen"

    private val meran = GeoPoint(46.67170, 11.15000)
    private val algund = GeoPoint(46.68012, 11.12640)
    private val vellau = GeoPoint(46.68790, 11.11110)

    private fun planOf(f: PlanLinks.Found?): SharedPlan = (f as? PlanLinks.Found.Plan)?.plan ?: error("no plan: $f")

    @Test fun cameraLinkOpensThePlan() {
        // what the camera / Google Lens hands over: VIEW with the whole link (the part after # included)
        val plan = planOf(PlanLinks.fromIntent(view, frozenLink, null, null, hasStream = false))
        assertEquals(listOf(GeoPoint(46.6717, 11.15), GeoPoint(46.6879, 11.1111)), plan.points)
        assertEquals(Activity.HIKE, plan.activity)
        assertEquals(3, plan.sac)
        assertEquals(setOf(1), plan.pinned)
        assertEquals(mapOf(1 to "Vellau"), plan.names)
        assertEquals("Waalweg", plan.title)
    }

    @Test fun loadPutsNamesAndPinsOnThePoints() {
        val load = PlanLinks.load(planOf(PlanLinks.fromLink(frozenLink)))
        val vellauFrozen = GeoPoint(46.6879, 11.1111)
        assertEquals(listOf(GeoPoint(46.6717, 11.15), vellauFrozen), load.points)
        assertEquals(setOf(vellauFrozen), load.pinned)
        assertEquals(mapOf(vellauFrozen to "Vellau"), load.names)
    }

    @Test fun everyActivityAndEveryHikingLevelComesThrough() {
        for (a in Activity.entries) {
            val levels = if (a == Activity.HIKE) 1..5 else 2..2
            for (t in levels) {
                val link = PlanLinks.link(SharedPlan(listOf(meran, algund), a, t))
                val plan = planOf(PlanLinks.fromIntent(view, link, null, null, false))
                assertEquals(a, plan.activity)
                assertEquals("$a T$t", t, plan.sac)
            }
        }
    }

    @Test fun roundTripStaysARoundTrip() {
        val link = PlanLinks.link(SharedPlan(listOf(meran, algund, vellau, meran), Activity.MTB))
        val load = PlanLinks.load(planOf(PlanLinks.fromLink(link)))
        assertEquals(4, load.points.size)
        assertTrue(PlanEdits.isRoundTrip(load.points))
    }

    @Test fun sharedPlanOpensAgainUnchanged() {
        // Share plan (sheet ⋯): the plan's points, activity, level, pins by index, names by point, saved name
        val pts = listOf(meran, algund, vellau)
        val names = mapOf(meran to "Meran", vellau to "Gasthaus Vellau")
        val shared = PlanLinks.share(pts, Activity.HIKE, 4, setOf(2, 7), { names[it] }, "Waalweg")
        val link = PlanLinks.link(shared)
        assertTrue(link, link.startsWith("https://planer.onpath.earth/#p="))
        val back = planOf(PlanLinks.fromLink(link))
        assertEquals(pts, back.points) // 1e-5° steps: these points are on the grid
        assertEquals(Activity.HIKE, back.activity)
        assertEquals(4, back.sac)
        assertEquals(setOf(2), back.pinned) // an index beyond the plan is dropped
        assertEquals(mapOf(0 to "Meran", 2 to "Gasthaus Vellau"), back.names)
        assertEquals("Waalweg", back.title)
        // the link opens the same way shared as text, pasted, or as the frozen web code
        assertEquals(back, planOf(PlanLinks.fromIntent(send, null, "text/plain", "Mein Plan: $link", false)))
        assertEquals(back, planOf(PlanLinks.fromText("  $link\n")))
        assertEquals(PlanCode.decode(frozen), planOf(PlanLinks.fromText(frozenLink)))
    }

    @Test fun sharedTextAroundTheLinkIsIgnored() {
        val plan = planOf(PlanLinks.fromIntent(send, null, "text/plain",
            "Schau mal (Waalweg): $frozenLink.\nBis morgen", hasStream = false))
        assertEquals(2, plan.points.size)
        // any host with #p= (a copy of the planner, a test page)
        assertEquals(2, planOf(PlanLinks.fromLink("https://test.example.org/planner/#p=$frozen")).points.size)
        // http, capital letters in the host
        assertEquals(2, planOf(PlanLinks.fromLink("http://Planer.OnPath.earth/#p=$frozen")).points.size)
    }

    @Test fun brokenLinksAreSaidNeverLoaded() {
        val broken = listOf(
            "https://planer.onpath.earth/#p=" + frozen.dropLast(9), // cut off (half a QR code, copied short)
            "https://planer.onpath.earth/#p=" + frozen.replace('Q', '*'), // damaged
            "https://planer.onpath.earth/#p=" + "Ag" + frozen.drop(2), // a newer format version
            "https://planer.onpath.earth/#p=",
            "https://planer.onpath.earth/#p=AQUDAA", // no points
            "https://planer.onpath.earth/", // the planner itself, no plan in it
            "https://planer.onpath.earth/#x=1",
        )
        for (l in broken) assertEquals(l, PlanLinks.Found.Broken, PlanLinks.fromIntent(view, l, null, null, false))
        assertEquals(PlanLinks.Found.Broken, PlanLinks.fromText("look: https://example.org/#p=%%%"))
        assertEquals(PlanLinks.Found.Broken, PlanLinks.fromIntent(send, null, "text/plain", "x #p=zz", false))
    }

    @Test fun everythingElseIsNotAPlanLink() {
        // GPX files, geo: links, places from Google Maps, other web pages: the other handlers decide
        assertNull(PlanLinks.fromIntent(view, "content://com.android.providers/document/12", "application/gpx+xml", null, false))
        assertNull(PlanLinks.fromIntent(view, "geo:46.67,11.15?q=Meran", null, null, false))
        assertNull(PlanLinks.fromIntent(view, "https://maps.app.goo.gl/abc?p=1", null, null, false))
        assertNull(PlanLinks.fromIntent(view, "https://planer.onpath.earth.evil.com/#q", null, null, false))
        assertNull(PlanLinks.fromIntent(view, null, null, null, false))
        assertNull(PlanLinks.fromIntent(send, null, "text/plain", "Hotel Meran https://maps.app.goo.gl/xyz", false))
        assertNull(PlanLinks.fromIntent(send, null, "text/plain", null, false))
        // a GPX shared as a file with a text beside it stays a file
        assertNull(PlanLinks.fromIntent(send, null, "text/plain", frozenLink, hasStream = true))
        assertNull(PlanLinks.fromIntent(send, null, "application/gpx+xml", frozenLink, false))
        assertNull(PlanLinks.fromIntent(null, frozenLink, null, null, false))
        // the search box: a plain search stays a search
        assertNull(PlanLinks.fromText("Gasthaus Vellau"))
        assertNull(PlanLinks.fromText(""))
    }

    @Test fun manifestOpensPlannerLinksAsAppLinks() {
        // the camera hands the link to the app only with this filter; autoVerify for App Links (assetlinks.json on the host)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val filter = Regex("""<intent-filter android:autoVerify="true">(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1) ?: error("no autoVerify intent filter")
        for (part in listOf("android.intent.action.VIEW", "android.intent.category.BROWSABLE", "android.intent.category.DEFAULT",
            """android:scheme="https"""", """android:host="${PlanLinks.HOST}"""", """android:path="/""""))
            assertTrue(part, part in filter)
        assertTrue(PlanLinks.BASE == "https://${PlanLinks.HOST}/")
    }
}
