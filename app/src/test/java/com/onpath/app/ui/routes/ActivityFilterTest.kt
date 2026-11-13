package com.onpath.app.ui.routes

import com.onpath.app.data.Tour
import com.onpath.app.data.TourKind
import com.onpath.app.routing.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** My routes' activity filter (owner 2026-11-13): families, "Alle", unknown activities; the preset is the planning activity. */
class ActivityFilterTest {
    private fun tour(id: Long, a: String?, kind: TourKind = TourKind.PLANNED, fav: Boolean = false) =
        Tour(id, kind, "T$id", id, a, 2, 1.0, 0.0, 0.0, 0.0, "done", emptyList(), "u$id", favourite = fav)

    private val all = listOf(tour(1, "HIKE"), tour(2, "BIKE_TOURING"), tour(3, "BIKE_TREKKING"), tour(4, "EBIKE"),
        tour(5, "MTB"), tour(6, "EMTB"), tour(7, null), tour(8, "SOMETHING_NEW"))

    private fun shown(a: Activity?) = all.filter { ActivityFilter.keep(it, a) }.map { it.id }

    @Test fun eachChoiceShowsItsFamily() {
        assertEquals(listOf(1L), shown(Activity.HIKE))
        for (bike in listOf(Activity.BIKE_TOURING, Activity.BIKE_TREKKING, Activity.EBIKE)) assertEquals(listOf(2L, 3L, 4L), shown(bike))
        for (mtb in listOf(Activity.MTB, Activity.EMTB)) assertEquals(listOf(5L, 6L), shown(mtb))
    }

    @Test fun alleShowsEverythingEvenUnknownActivities() {
        assertEquals(all.map { it.id }, shown(null))
        assertFalse("an unknown activity only under Alle", ActivityFilter.keep(tour(7, null), Activity.HIKE))
    }

    @Test fun theMenuHasEverySixOnceAndEveryFamilyCoversIt() {
        assertEquals(Activity.entries.toSet(), ActivityFilter.MENU.toSet())
        assertEquals(6, ActivityFilter.MENU.size)
        for (a in Activity.entries) assertTrue(a in ActivityFilter.family(a))
    }

    @Test fun itCombinesWithFavourites() {
        val list = listOf(tour(1, "HIKE", fav = true), tour(2, "HIKE"), tour(3, "MTB", fav = true))
        assertEquals(listOf(1L), Favourites.shown(list, true).filter { ActivityFilter.keep(it, Activity.HIKE) }.map { it.id })
    }

    /** The app opens My routes with the planning activity as the preset (owner 2026-11-13). */
    @Test fun thePresetIsThePlanningActivity() {
        val main = File("src/main/java/com/onpath/app/MainActivity.kt").readText()
        assertTrue(main.substringAfter("RoutesScreen(").contains("initialActivity = vm.activity"))
    }
}
