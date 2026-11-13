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

    /** Owner 2026-11-13 on the Pixel: „wenn ich E-Bike anklicke, will ich nur E-Bike sehen; Bike nur Bike“. */
    @Test fun eachChoiceShowsExactlyItsOwnActivity() {
        assertEquals(listOf(1L), shown(Activity.HIKE))
        assertEquals(listOf(2L), shown(Activity.BIKE_TOURING))
        assertEquals(listOf(3L), shown(Activity.BIKE_TREKKING))
        assertEquals(listOf(4L), shown(Activity.EBIKE))
        assertEquals(listOf(5L), shown(Activity.MTB))
        assertEquals(listOf(6L), shown(Activity.EMTB))
    }

    /**
     * Field bug 2026-11-13 (owner's Pixel): choosing Fahrrad / Wandern did not always filter the list – the screen kept
     * the list computed for the previous choice (the activity was no key of it). The list is one function of every input
     * now, and the screen recomputes it with each of them as a key.
     */
    @Test fun everyChoiceGivesItsOwnListAndTheScreenRecomputesIt() {
        val sort = RouteSort.DEFAULT
        val hike = ActivityFilter.saved(all, sort, false, Activity.HIKE)
        val bike = ActivityFilter.saved(all, sort, false, Activity.BIKE_TOURING)
        assertEquals(listOf(1L), hike.map { it.id }); assertEquals(listOf(2L), bike.map { it.id })
        assertEquals(all.size, ActivityFilter.saved(all, sort, false, null).size)
        assertTrue("drafts only under Zuletzt", ActivityFilter.saved(all.map { it.copy(draft = true) }, sort, false, null).isEmpty())
        val screens = File("src/main/java/com/onpath/app/ui/Screens.kt").readText()
        val line = screens.lines().single { "ActivityFilter.saved(" in it }
        assertTrue("the activity is a key of the list: $line", Regex("remember\\(shown, sort, favOnly, act\\)").containsMatchIn(line))
    }

    @Test fun alleShowsEverythingEvenUnknownActivities() {
        assertEquals(all.map { it.id }, shown(null))
        assertFalse("an unknown activity only under Alle", ActivityFilter.keep(tour(7, null), Activity.HIKE))
    }

    @Test fun theMenuHasEverySixOnce() {
        assertEquals(Activity.entries.toSet(), ActivityFilter.MENU.toSet())
        assertEquals(6, ActivityFilter.MENU.size)
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
