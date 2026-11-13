package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.gpx.TrackPoint
import com.onpath.app.ui.RoutesTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-13 („Das ist wichtig“): every time My routes opens it starts the same way – "Neueste zuerst" (a sort
 * chosen holds only while it is open, nothing across openings or app starts), the chip row at its left end, the activity
 * chip on the planning activity – and the Zuletzt row (the plan worked on) shows whatever the filters.
 */
class RoutesOpeningTest {
    private val store = object : RoutesStore {
        override fun listTours(): List<Tour> = emptyList()
        override fun listDeleted(): List<Tour> = emptyList()
        override fun setDraft(id: Long, draft: Boolean) = true
        override fun setDeleted(id: Long, deletedMs: Long?) = true
        override fun loadSegments(id: Long): List<List<TrackPoint>> = emptyList()
        override fun deleteForGood(id: Long) {}
        override fun rename(id: Long, name: String) {}
    }

    @Test fun everyOpeningStartsWithNewestFirst() {
        val r = RoutesState(store)
        r.open = true
        val byLength = RouteSort.Choice(RouteSort.Key.LENGTH)
        r.sort = byLength
        r.tab = RoutesTab.DONE
        r.open = true // still open (a recomposition, a second ☰ tap): the choice holds
        assertEquals(byLength, r.sort)
        r.open = false
        r.open = true
        assertEquals("a new opening: Neueste zuerst", RouteSort.DEFAULT, r.sort)
        assertEquals(RouteSort.Key.NEWEST, RouteSort.DEFAULT.key)
    }

    private val src get() = File("src/main/java/com/onpath/app")

    @Test fun nothingKeepsTheSortAcrossOpeningsOrStarts() {
        val vm = File(src, "ui/AppViewModel.kt").readText()
        assertFalse("the sort is not read from the prefs any more", vm.contains("RouteSort.decode(prefs.routesSort)"))
        assertFalse("nor written", vm.contains("prefs.routesSort ="))
        val main = File(src, "MainActivity.kt").readText().substringAfter("RoutesScreen(")
        assertTrue("the activity chip: the planning activity", main.contains("initialActivity = vm.activity"))
        // the chip row opens at the chip chosen – with "Neueste zuerst" (the first sort key) that is its left end
        assertEquals(0, RouteSort.Key.entries.indexOf(RouteSort.DEFAULT.key))
    }

    /** Owner 2026-11-13 on the Pixel: „keine Zuletzt-Route mehr, obwohl ich gerade geplant habe“ – no filter hides it. */
    @Test fun zuletztShowsWhateverTheFilters() {
        val screens = File(src, "ui/Screens.kt").readText()
        val line = screens.lines().single { "val recent = " in it }
        assertTrue(line, line.contains("if (tab == RoutesTab.PLANNED) LivePlanRows"))
        assertFalse("no filter in it: $line", line.contains("favOnly") || Regex("\\bact\\b").containsMatchIn(line) || line.contains("filtered"))
    }
}
