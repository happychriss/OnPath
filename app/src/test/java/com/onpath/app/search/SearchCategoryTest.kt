package com.onpath.app.search

import com.onpath.app.poi.PoiKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The six search chips (owner 2026-10-31): one table, every kind in at most one chip, one short word each. */
class SearchCategoryTest {
    /** Every kind a search result can have: the POI kinds plus the search-only ones. */
    private val searchable = PoiKinds.all.map { it.key }.toSet() +
        setOf("peak", "saddle", "place", "trail", "address", "street", "store", "other")

    @Test fun everyKindInAtMostOneChip() {
        for (k in searchable) {
            val chips = SearchKinds.Category.entries.filter { k in it.kinds }
            assertTrue("$k is in ${chips.size} chips: $chips", chips.size <= 1)
            assertEquals(chips.firstOrNull(), SearchKinds.Category.of(k))
        }
    }

    @Test fun chipsNameOnlyKindsThatExist() {
        for (c in SearchKinds.Category.entries) for (k in c.kinds) assertTrue("$c: unknown kind $k", k in searchable)
    }

    @Test fun theOwnersSixChips() {
        assertEquals(listOf("PLACES", "FOOD", "SIGHTS", "WATER", "TRANSIT", "PARKING"), SearchKinds.Category.entries.map { it.name })
        assertEquals(listOf("Places", "Food", "Sights", "Water", "Transit", "Parking"), SearchKinds.Category.entries.map { it.label })
        assertTrue(SearchKinds.Category.entries.all { ' ' !in it.label })
        // what the owner listed per chip
        assertEquals(SearchKinds.Category.PLACES, SearchKinds.Category.of("street"))
        assertEquals(SearchKinds.Category.PLACES, SearchKinds.Category.of("address"))
        assertEquals(SearchKinds.Category.FOOD, SearchKinds.Category.of("hut"))
        assertEquals(SearchKinds.Category.FOOD, SearchKinds.Category.of("bar")) // beer gardens
        assertEquals(SearchKinds.Category.SIGHTS, SearchKinds.Category.of("peak"))
        assertEquals(SearchKinds.Category.SIGHTS, SearchKinds.Category.of("waterfall"))
        assertEquals(SearchKinds.Category.WATER, SearchKinds.Category.of("spring"))
        assertEquals(SearchKinds.Category.TRANSIT, SearchKinds.Category.of("lift"))
        assertEquals(SearchKinds.Category.PARKING, SearchKinds.Category.of("parking"))
        assertEquals(null, SearchKinds.Category.of("toilets"))
    }

    @Test fun placesChipFindsStreetsAndAddresses() {
        val street = Place("a:x:1", "Mittelstraße · Hilden", "street", 51.166, 6.94, source = Place.Source.MAP)
        val addr = AddressHit(street, 1.0, 2)
        val idx = OfflineIndex(listOf(OfflineIndex.Entry(Place("n1", "Mittelalm", "hut", 51.17, 6.95))))
        fun q(c: SearchKinds.Category?) = SearchQuery("mittel", c, 51.17, 6.94)
        assertEquals(listOf("Mittelstraße · Hilden"), idx.search(q(SearchKinds.Category.PLACES), listOf(addr)).map { it.place.name })
        assertEquals(listOf("Mittelalm"), idx.search(q(SearchKinds.Category.FOOD), listOf(addr)).map { it.place.name })
        assertEquals(2, idx.search(q(null), listOf(addr)).size)
    }
}
