package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What needs :app of the search rules (test audit G15, replaces SearchCategoryTest's Places-chip search, SearchNotesTest,
 * SharedRecentTest): the Places chip over the offline index, the two owner decisions in search notes, and "a place shared
 * into OnPath (Google Maps → Share) goes to Recent" (owner 2026-11-02, "zuletzt habe ich es ja gesucht").
 */
class SearchRulesTest {

    // The pure rules (text, POI filter, chip table) are in :core: SearchTextRulesTest, PoiFilterTest.

    @Test fun placesChipFindsStreetsAndAddresses() {
        val street = Place("a:x:1", "Mittelstraße · Hilden", "street", 51.166, 6.94, source = Place.Source.MAP)
        val addr = AddressHit(street, 1.0, 2)
        val idx = OfflineIndex(listOf(OfflineIndex.Entry(Place("n1", "Mittelalm", "hut", 51.17, 6.95))))
        fun q(c: SearchKinds.Category?) = SearchQuery("mittel", c, 51.17, 6.94)
        assertEquals(listOf("Mittelstraße · Hilden"), idx.search(q(SearchKinds.Category.PLACES), listOf(addr)).map { it.place.name })
        assertEquals(listOf("Mittelalm"), idx.search(q(SearchKinds.Category.FOOD), listOf(addr)).map { it.place.name })
        assertEquals(2, idx.search(q(null), listOf(addr)).size)
    }

    // ---- owner decisions in search notes, and Recent ----

    @Test fun sharedPositionNoteOnlyWhenPositionedFromTheAddress() {
        assertEquals(SharedPosition.LINK, SharedPosition.of(point = true))
        assertNull(SharedPosition.of(point = true).note)
        // no coordinate at all: found by searching the address
        assertEquals("Position from the address", SharedPosition.of(point = false).note)
    }

    @Test fun recentsKeepVillageAndReadOldLines() {
        val p = Place("a:x:1:5", "Hauptstraße 5 · Völlan", "address", 46.61, 11.12, source = Place.Source.MAP,
            town = "Lana", village = "Völlan")
        val back = SearchStore.decode(SearchStore.encode(p))!!
        assertEquals("Völlan", back.village)
        assertEquals("Lana", back.municipality)
        // an older line (9 fields)
        val old = SearchStore.decode("n1\tMeraner Hütte\thut\t\t46.732000\t11.091000\t1960\t\tPOI")!!
        assertEquals("Meraner Hütte", old.name)
        assertNull(old.village); assertNull(old.municipality)
    }

    private val sharedPlace = Place("shared:51.78,7.54", "Restaurant Schloss", "other", 51.78, 7.54, address = "Schloßstraße 1, Nordkirchen")

    /** Owner 2026-11-02 ("zuletzt habe ich es ja gesucht"): a shared place (Google Maps → Share) goes to Recent too, with a share sign instead of the clock. */
    @Test fun theSharedMarkSurvivesTheStore() {
        val back = SearchStore.decode(SearchStore.encode(sharedPlace.copy(shared = true)))!!
        assertTrue(back.shared)
        assertEquals("Restaurant Schloss", back.name)
        assertFalse(SearchStore.decode(SearchStore.encode(sharedPlace))!!.shared)
        // an entry written before (11 fields) reads as not shared
        val old = SearchStore.encode(sharedPlace).split('\t').take(11).joinToString("\t")
        assertFalse(SearchStore.decode(old)!!.shared)
    }

    /** Owner 2026-11-02, same rule: source-text wiring check kept from SharedRecentTest (no behaviour test drives showSharedPlace; test-audit G15). */
    @Test fun aSharedPlaceIsRememberedAndShownWithTheShareSign() {
        val flow = File("src/main/java/com/onpath/app/ui/SearchFlow.kt").readText()
        val block = flow.substringAfter("fun showSharedPlace(").substringBefore("\n    }\n")
        assertTrue(block, block.contains("search.remember(p.copy(shared = true))"))
        val screen = File("src/main/java/com/onpath/app/ui/search/SearchScreen.kt").readText()
        assertTrue(screen.contains("if (h.place.shared) Box(") && screen.contains("Icon(Icons.Outlined.Share, contentDescription = str(Res.string.search_recent_shared)"))
    }
}
