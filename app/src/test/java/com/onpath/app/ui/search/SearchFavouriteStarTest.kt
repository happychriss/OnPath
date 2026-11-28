package com.onpath.app.ui.search

import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import com.onpath.app.sync.PlaceSync
import com.onpath.app.sync.SyncFormat
import com.onpath.app.sync.SyncLedger
import com.onpath.app.sync.SyncPlaces
import com.onpath.app.sync.SyncPlacesTest
import com.onpath.app.ui.plan.KeptPlacesHost
import com.onpath.app.ui.plan.KeptPlacesSlice
import com.onpath.app.ui.plan.PlacesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ★ on a „Favoriten“ row of the search (owner 2026-11-28 15:10 „Ich glaube, der soll in der Liste einen Stern
 * haben“): a tap removes the favourite (stored, and its sync item tombstoned at the next export) – the row stays with an
 * empty star until the search is left; a second tap keeps it again, in the same place; a tap on the row still picks it.
 */
class SearchFavouriteStarTest {
    private val cafe = Place("n1", "Waldcafé Lindenhof", "cafe", 51.2, 6.9)
    private val ruin = Place("n2", "Burgruine Hohenstein", "ruins", 51.1, 7.0, ele = 412)
    private val hut = Place("n3", "Meraner Hütte", "hut", 46.732, 11.091, ele = 1960)

    /** The phone's kept places in memory, the same store the search reads ([SearchPorts.saved]). */
    private class Store(var places: SavedPlaces) : PlacesStore {
        var syncs = 0
        override fun saved() = places
        override fun update(change: (SavedPlaces) -> SavedPlaces) = change(places).also { places = it }
        override fun backupChanged() {}
        override fun syncChanged() { syncs++ }
    }

    private class Search(val store: Store) : SearchState(object : SearchPorts {
        val recent = mutableListOf<Place>()
        override fun saved() = store.saved()
        override fun recent() = recent.toList()
        override fun addRecent(p: Place) { recent.removeAll { it.id == p.id }; recent.add(0, p) }
        override fun clearRecent() = recent.clear()
        override fun indexedPlace(id: String): Place? = null
    }, hasOnline = false, context = { SearchContext(51.2, 6.9, null) }) {
        override fun run() { begin() }
        fun open() { allFavourites = false; ui = startLists(ui.copy(query = "", category = null, unstarred = emptyList()), context()) }
    }

    private val store = Store(SavedPlaces(favourites = listOf(cafe, ruin, hut)))
    private val kept = KeptPlacesSlice(store, object : KeptPlacesHost {
        override fun searchMark(): Place? = null
        override fun isCandidate(poi: com.onpath.app.poi.Poi) = false
    })
    private val search = Search(store).apply { open() }

    /** What the search row's star does (SearchHost): the kept places, then the row's star. */
    private fun tapStar(p: Place) {
        val on = !search.ui.starred(p)
        kept.setFavourite(p, on)
        search.favouriteTapped(p, on)
    }

    private fun shown() = search.ui.favourites.map { it.place.id }

    @Test fun aStarTapRemovesTheFavouriteTheRowStaysASecondTapKeepsItAgain() {
        val phone = SyncFormat.newDevice(ByteArray(8) { 1 })
        val sync = PlaceSync(SyncPlacesTest.Lines::encode, SyncPlacesTest.Lines::decode, withHome = false)
        val ledger = SyncLedger.empty()
        sync.export(ledger, store.places, 1_000, phone)
        val ruinId = SyncPlaces.itemId(SyncPlacesTest.Lines.encode(ruin))
        assertTrue(SyncPlaces.live(ledger.get(ruinId)!!))

        tapStar(ruin)
        assertFalse("stored: no longer a favourite", store.places.isFavourite(ruin))
        assertEquals("the browser is told", 1, store.syncs)
        assertEquals("the row stays, in its place", listOf("n1", "n2", "n3"), shown())
        assertFalse("its star is empty", search.ui.starred(ruin))
        assertTrue(search.ui.starred(cafe))
        // the next sync round: the removal goes out as a tombstone like any other
        assertEquals(1, sync.export(ledger, store.places, 2_000, phone))
        assertTrue("tombstone", ledger.get(ruinId)!!.deleted)

        tapStar(ruin)
        assertTrue("kept again", store.places.isFavourite(ruin))
        assertTrue(search.ui.starred(ruin))
        assertEquals("still in the same place", listOf("n1", "n2", "n3"), shown())
        sync.export(ledger, store.places, 3_000, phone)
        assertTrue("live again", SyncPlaces.live(ledger.get(ruinId)!!))
    }

    @Test fun theRowStaysOnAllFavouritesAndLeavesWhenTheSearchOpensAgain() {
        tapStar(cafe)
        search.showAllFavourites()
        assertEquals(listOf("n1", "n2", "n3"), shown())
        assertFalse(search.ui.starred(cafe))
        assertEquals(0, search.ui.moreFavourites)
        search.open()
        assertEquals("left the search: the row is gone", listOf("n2", "n3"), shown())
        assertTrue(search.ui.unstarred.isEmpty())
    }

    @Test fun aRowTapStillPicksAnUnstarredRow() {
        tapStar(hut)
        // SearchFlow.pickSearchResult: resolve + remember – the row's place, not the star's
        val picked = search.resolve(search.ui.favourites.last().place)
        assertEquals(hut, picked)
        search.remember(picked)
        search.showAllFavourites()
        assertEquals("the row stays a favourites row, not a recent one", listOf("n1", "n2", "n3"), shown())
        assertTrue(search.ui.recent.isEmpty())
    }
}
