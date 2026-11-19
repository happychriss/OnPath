package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.onpath.app.search.Place
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.place_favourite_add
import com.onpath.strings.place_favourite_on
import com.onpath.strings.place_home_set
import com.onpath.strings.search_favourites
import com.onpath.strings.search_home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * „Orte merken“ (owner 2026-11-15, F-PLAN-14): a picked place's menu keeps it as a favourite with one tap (the menu shows
 * the new state), and the empty search then lists the place under „Favourites“ – kept on the phone (its own prefs, read
 * again by a new search). Zuhause is off for now (owner 2026-11-19, HOME_ROW_SHOWN = false): the menu offers no
 * „Set as home“ and the search shows no „Home“ row, while a stored home stays in the state.
 */
@RunWith(AndroidJUnit4::class)
class KeptPlacesE2ETest : E2EBase() {

    @Test fun merkenShowsInTheSearchZuhauseHidden() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        launch(beforeLaunch = { ctx.getSharedPreferences("onpath-places", 0).edit().clear().commit() })
        val cafe = Place("kept-1", "Café Kept", "cafe", MERAN_LAT, MERAN_LON)
        scenario.onActivity { vm(it).searchFlow.pickSearchResult(cafe) }
        waitUntil("the picked place's menu", 20_000) { read { it.placeMenus.poiMenu } != null }

        clickText(Txt.s(Res.string.place_favourite_add))
        assertNotNull("a tap keeps it: „Gemerkt“", findFresh(By.text(Txt.s(Res.string.place_favourite_on)), 5_000))
        assertTrue("no „Set as home“ while Zuhause is off", goneFresh(By.text(Txt.s(Res.string.place_home_set)), 2_000))
        shot("kept-menu")
        // a home stored before (or synced from the browser) stays – it is only not shown
        scenario.onActivity { vm(it).let { vm -> vm.placeMenus.poiMenu?.let(vm.keptPlaces::toggleHomePlace) } }

        scenario.onActivity { vm(it).searchFlow.openSearch() }
        assertNotNull("the favourites' head", findFresh(By.text(Txt.s(Res.string.search_favourites).uppercase()), 10_000))
        assertTrue("no ⌂ Home row while Zuhause is off", goneFresh(By.text(Txt.s(Res.string.search_home)), 2_000))
        assertEquals("the place once under the favourites", listOf("kept-1"), read { vm -> vm.search.ui.favourites.map { it.place.id } })
        assertEquals("the stored home stays", "kept-1", read { vm -> vm.search.ui.home?.place?.id })
        shot("kept-search")
    }

    private fun <T> read(f: (com.onpath.app.ui.AppViewModel) -> T): T {
        var v: T? = null
        scenario.onActivity { v = f(vm(it)) }
        @Suppress("UNCHECKED_CAST") return v as T
    }
}
