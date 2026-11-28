package com.onpath.app.ui.search

import com.onpath.app.domain.PinMenuItems
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app has no Zuhause (hidden since 2026-11-19, removed 2026-11-28 – function verdict): no place menu offers „Als
 * Zuhause festlegen“ (PinMenuItems still knows the row – the planner shows it), „★ Merken“ stays; and a home kept before
 * is never lost – it becomes the first favourite (the format and the planner's home stay as they are).
 */
class HomeRowHiddenTest {
    private val home = Place("a:1", "Mittelstraße 12 · Hilden", "address", 51.17, 6.93, source = Place.Source.MAP)
    private val fav = Place("n1", "Waldcafé Lindenhof", "cafe", 51.2, 6.9)

    @Test fun noSetHomeInThePlaceMenu() {
        for (emptyPlan in listOf(true, false)) for (pin in listOf(true, false)) {
            val all = PinMenuItems.of(emptyPlan = emptyPlan, startHere = true, menu = null, pin = pin, keep = true)
            assertTrue("PinMenuItems itself still knows the row", PinMenuItems.Row.HOME in all)
            val shown = shownMenuRows(all)
            assertFalse("no „Als Zuhause festlegen“", PinMenuItems.Row.HOME in shown)
            assertEquals("everything else stays, „★ Merken“ too", all - PinMenuItems.Row.HOME, shown)
            assertTrue(PinMenuItems.Row.FAVOURITE in shown)
        }
    }

    @Test fun aKeptHomeBecomesTheFirstFavouriteNeverLost() {
        val s = com.onpath.app.search.homeAsFavourite(SavedPlaces(home = home, favourites = listOf(fav)))
        assertNull(s.home)
        assertEquals(listOf(home, fav), s.favourites)
        // already a favourite: once, not twice
        assertEquals(listOf(fav), com.onpath.app.search.homeAsFavourite(SavedPlaces(home = fav, favourites = listOf(fav))).favourites)
        // nothing kept as home: unchanged
        val none = SavedPlaces(favourites = listOf(fav))
        assertEquals(none, com.onpath.app.search.homeAsFavourite(none))
    }
}
