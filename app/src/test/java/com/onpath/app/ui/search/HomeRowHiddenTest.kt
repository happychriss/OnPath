package com.onpath.app.ui.search

import com.onpath.app.domain.PinMenuItems
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * „⌂ Zuhause“ off for now (owner 2026-11-19, via the captain: „Können wir das erstmal abschalten, bis wir eine saubere
 * Lösung haben“): while [HOME_ROW_SHOWN] is false the empty search has no Zuhause row although a home is stored, and no
 * place menu offers „Als Zuhause festlegen“ – „★ Merken“ stays. Turning the switch back on makes this test fail on
 * purpose: then pin the rows again.
 */
class HomeRowHiddenTest {
    private val home = SearchHit(Place("a:1", "Mittelstraße 12 · Hilden", "address", 51.17, 6.93, source = Place.Source.MAP), 0.0, 0, 4_200.0, false)
    private val fav = SearchHit(Place("n1", "Waldcafé Lindenhof", "cafe", 51.2, 6.9), 0.0, 0, 12_000.0, false)

    @Test fun theSwitchIsOff() = assertFalse(HOME_ROW_SHOWN)

    @Test fun noHomeRowInTheEmptySearchWhileOff() {
        val ui = SearchUi(home = home, favourites = listOf(fav))
        assertEquals("the stored home stays in the state", home, ui.home)
        assertNull("but the empty search shows no Zuhause row", shownHome(ui))
    }

    @Test fun noSetHomeInThePlaceMenuWhileOff() {
        for (emptyPlan in listOf(true, false)) for (pin in listOf(true, false)) {
            val all = PinMenuItems.of(emptyPlan = emptyPlan, startHere = true, menu = null, pin = pin, keep = true)
            assertTrue("PinMenuItems itself still knows the row", PinMenuItems.Row.HOME in all)
            val shown = shownMenuRows(all)
            assertFalse("no „Als Zuhause festlegen“", PinMenuItems.Row.HOME in shown)
            assertEquals("everything else stays, „★ Merken“ too", all - PinMenuItems.Row.HOME, shown)
            assertTrue(PinMenuItems.Row.FAVOURITE in shown)
        }
    }
}
