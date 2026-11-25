package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.domain.RoutesTab
import com.onpath.app.domain.routes.ListTop
import com.onpath.app.domain.routes.OverviewPages
import com.onpath.app.domain.routes.RoundTrip
import com.onpath.app.domain.routes.RouteSort
import com.onpath.app.domain.routes.RouteSort.Choice
import com.onpath.app.domain.routes.RouteSort.Key
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.plansync.RouteItems
import com.onpath.app.plansync.SyncMarks
import com.onpath.app.recording.DemoLocation
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.RouteTag
import com.onpath.app.ui.TagRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * My routes' list as the screen builds it (converted from ActivityFilterTest, RoutesOpeningTest, RouteTagTest – test-audit
 * G13): activity filter, every opening starts the same way, New / Changed tags. The Compose-file wiring pins that no JVM
 * test can run stay at the end. The pure rules (sort, list top, overview pages) are in core's RoutesListRulesTest, the
 * tabs / search in RoutesTabTest, the round-trip label in RoundTripTest.
 */
class RoutesListFlowTest {
    private fun tour(id: Long, kind: TourKind = TourKind.PLANNED, name: String = "T$id", created: Long = id, act: String? = "HIKE",
                     km: Double = 1.0, h: Double = 0.0, up: Double = 0.0, uuid: String = "u$id", fav: Boolean = false,
                     w: List<GpxWaypoint> = emptyList(), roundTrip: Boolean? = null) =
        Tour(id, kind, name, created, act, 2, km * 1000, up, up, h * 3600, "done", w, uuid, favourite = fav, roundTrip = roundTrip)
    private fun ids(l: List<Tour>) = l.map { it.id }

    // ---- activity filter ----
    // My routes' activity filter (owner 2026-11-13): families, "Alle", unknown activities; the preset is the planning activity.

    private val acts = listOf(tour(1, act = "HIKE"), tour(2, act = "BIKE_TOURING"), tour(3, act = "BIKE_TREKKING"), tour(4, act = "EBIKE"),
        tour(5, act = "MTB"), tour(6, act = "EMTB"), tour(7, act = null), tour(8, act = "SOMETHING_NEW"))
    private fun shown(act: Activity?) = acts.filter { ActivityFilter.keep(it, act) }.map { it.id }

    /**
     * Owner 2026-11-13 on the Pixel: „wenn ich E-Bike anklicke, will ich nur E-Bike sehen; Bike nur Bike“.
     * "Alle" shows everything, even unknown activities.
     */
    @Test fun eachChoiceShowsExactlyItsOwnActivityAndAlleEverything() {
        assertEquals(listOf(1L), shown(Activity.HIKE)); assertEquals(listOf(2L), shown(Activity.BIKE_TOURING))
        assertEquals(listOf(3L), shown(Activity.BIKE_TREKKING)); assertEquals(listOf(4L), shown(Activity.EBIKE))
        assertEquals(listOf(5L), shown(Activity.MTB)); assertEquals(listOf(6L), shown(Activity.EMTB))
        assertEquals(acts.map { it.id }, shown(null))
        assertFalse("an unknown activity only under Alle", ActivityFilter.keep(tour(7, act = null), Activity.HIKE))
        assertEquals(Activity.entries.toSet(), ActivityFilter.MENU.toSet())
        assertEquals(6, ActivityFilter.MENU.size)
        val favs = listOf(tour(1, fav = true), tour(2), tour(3, act = "MTB", fav = true))
        assertEquals(listOf(1L), Favourites.shown(favs, true).filter { ActivityFilter.keep(it, Activity.HIKE) }.map { it.id })
    }

    /**
     * Field bug 2026-11-13 (owner's Pixel): choosing Fahrrad / Wandern did not always filter the list – the screen kept
     * the list computed for the previous choice (the activity was no key of it). The list is one function of every input
     * now, and the screen recomputes it with each of them as a key (the pin at the end).
     */
    @Test fun everyChoiceGivesItsOwnList() {
        val sort = RouteSort.DEFAULT
        assertEquals(listOf(1L), ids(ActivityFilter.saved(acts, sort, false, Activity.HIKE)))
        assertEquals(listOf(2L), ids(ActivityFilter.saved(acts, sort, false, Activity.BIKE_TOURING)))
        assertEquals(acts.size, ActivityFilter.saved(acts, sort, false, null).size)
        assertTrue("drafts only under Zuletzt", ActivityFilter.saved(acts.map { it.copy(draft = true) }, sort, false, null).isEmpty())
    }

    // ---- every opening starts the same way ----
    // Owner 2026-11-13 („Das ist wichtig“): every time My routes opens it starts the same way – "Neueste zuerst" (a sort
    // chosen holds only while it is open, nothing across openings or app starts), the chip row at its left end, the activity
    // chip on the planning activity – and the Zuletzt row (the plan worked on) shows whatever the filters.

    private val store = object : RoutesStore {
        override fun listTours(): List<Tour> = emptyList()
        override fun listDeleted(): List<Tour> = emptyList()
        override fun setDraft(id: Long, draft: Boolean) = true
        override fun setDeleted(id: Long, deletedMs: Long?) = true
        override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
        override fun deleteForGood(id: Long) {}
        override fun rename(id: Long, name: String) {}
    }

    /** Pixel review 2026-11-06: back from a route's page, My routes forgot its search (the tab was kept). */
    @Test fun theSearchTextIsKeptLikeTheTabWhileTheAppRuns() {
        val r = RoutesState(store)
        r.open = true
        r.tab = RoutesTab.DONE
        r.query = "Zugspitze"
        r.open = false
        r.open = true
        assertEquals(RoutesTab.DONE, r.tab)
        assertEquals("Zugspitze", r.query)
    }

    /** A sort chosen holds only while My routes is open; a new opening is "Neueste zuerst". */
    @Test fun everyOpeningStartsWithNewestFirst() {
        val r = RoutesState(store)
        r.open = true
        val byLength = Choice(Key.LENGTH)
        r.sort = byLength
        r.tab = RoutesTab.DONE
        r.open = true // still open (a recomposition, a second ☰ tap): the choice holds
        assertEquals(byLength, r.sort)
        r.open = false
        r.open = true
        assertEquals("a new opening: Neueste zuerst", RouteSort.DEFAULT, r.sort)
        assertEquals(Key.NEWEST, RouteSort.DEFAULT.key)
        // the chip row opens at the chip chosen – with "Neueste zuerst" (the first sort key) that is its left end
        assertEquals(0, Key.entries.indexOf(RouteSort.DEFAULT.key))
    }

    // ---- tags ----
    // My routes: the tag after a route's name (owner 2026-10-31, ui-style.md "Tags") – "New" while a route from the browser
    // was not opened, "Changed" when the browser changed it since it was last opened, none once opened. Same marks as
    // before (plansync/SyncMarks), planned routes and imported tracks alike.

    private val marks = SyncMarks({ null }, {})
    private fun tag(t: Tour) = RouteTag.of(t, marks.all())

    /** "New" while a route from the browser was not opened, "Changed" when changed since last opened, none once opened; imported tracks alike; no uuid, no tag; unpair clears. */
    @Test fun newAndChangedTagsFollowTheSyncMarks() {
        val t = tour(1, uuid = "u-1")
        assertNull(tag(t))
        marks.arrived("u-1"); assertEquals(RouteTag.NEW, tag(t))
        marks.changed("u-1"); assertEquals("changed again before it was opened: still new", RouteTag.NEW, tag(t))
        marks.remove("u-1"); assertNull("opened", tag(t))
        val u = tour(2, uuid = "u-2")
        marks.changed("u-2"); assertEquals(RouteTag.CHANGED, tag(u))
        marks.remove("u-2"); assertNull(tag(u))
        marks.changed("u-2"); assertEquals("changed again in the browser", RouteTag.CHANGED, tag(u))
        marks.arrived("u-3"); assertEquals(RouteTag.NEW, tag(tour(3, TourKind.IMPORTED, uuid = "u-3")))
        marks.arrived(""); assertNull(tag(tour(4, uuid = "")))
        marks.arrived("u-1"); marks.changed("u-2")
        marks.clear()
        assertNull(tag(t)); assertNull(tag(u))
    }

    /** The tag is never cut: beside the name while the name keeps a fair share of the row, else under it. */
    @Test fun tagGoesBesideTheNameOrUnderIt() {
        assertTrue("short name: always beside", TagRow.sameRow(rowWidth = 600, titleWidth = 200, tagWidth = 150, gap = 20))
        assertTrue("long name, normal font: beside, the name ellipsizes (it keeps 430 of 600)", TagRow.sameRow(rowWidth = 600, titleWidth = 2000, tagWidth = 150, gap = 20))
        assertFalse("long name, largest font: the tag would leave the name < 55 % → under it", TagRow.sameRow(rowWidth = 600, titleWidth = 2000, tagWidth = 340, gap = 20))
        assertTrue("big tag but the whole name still fits beside it", TagRow.sameRow(rowWidth = 600, titleWidth = 200, tagWidth = 340, gap = 20))
    }

    // ---- source pins (Compose wiring that no JVM test can run) ----

    private val src get() = File("src/main/java/com/onpath/app")

    /** The app opens My routes with the planning activity as the preset (owner 2026-11-13). */
    @Test fun pinNothingKeepsTheSortAcrossOpeningsOrStartsAndTheActivityChipIsThePlanningActivity() {
        val vm = File(src, "ui/AppViewModel.kt").readText()
        assertFalse("the sort is not read from the prefs any more", vm.contains("RouteSort.decode(prefs.routesSort)"))
        assertFalse("nor written", vm.contains("prefs.routesSort ="))
        val main = File(src, "MainActivity.kt").readText().substringAfter("RoutesScreen(")
        assertTrue("the activity chip / preset: the planning activity (owner 2026-11-13)", main.contains("initialActivity = vm.planRoute.activity"))
    }

    /** Field bug 2026-11-13 (owner's Pixel): the screen kept the list computed for the previous activity choice – every input is a key of it now. */
    @Test fun pinTheScreenRecomputesTheListWithEveryInputAsKey() {
        val line = File(src, "ui/Screens.kt").readText().lines().single { "ActivityFilter.saved(" in it }
        assertTrue("the activity is a key of the list: $line", Regex("remember\\(shown, sort, favOnly, act\\)").containsMatchIn(line))
    }

    /** Owner 2026-11-13 on the Pixel: „keine Zuletzt-Route mehr, obwohl ich gerade geplant habe“ – no filter hides it. */
    @Test fun pinZuletztShowsWhateverTheFilters() {
        val line = File(src, "ui/Screens.kt").readText().lines().single { "val recent = " in it }
        assertTrue(line, line.contains("if (tab == RoutesTab.PLANNED) LivePlanRows"))
        assertFalse("no filter in it: $line", line.contains("favOnly") || Regex("\\bact\\b").containsMatchIn(line) || line.contains("filtered"))
    }
}
