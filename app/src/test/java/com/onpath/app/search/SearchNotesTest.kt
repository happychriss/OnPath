package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner decisions 2026-10-22: the shared-place note; recents keep the village / municipality. */
class SearchNotesTest {

    @Test fun noteOnlyWhenPositionedFromTheAddress() {
        assertEquals(SharedPosition.LINK, SharedPosition.of(point = true, fromPage = false, addressWon = false))
        assertNull(SharedPosition.of(point = true, fromPage = false, addressWon = false).note)
        assertNull(SharedPosition.of(point = true, fromPage = true, addressWon = false).note)
        // the address overruled the map page's point
        assertEquals("Position from the address", SharedPosition.of(point = true, fromPage = true, addressWon = true).note)
        // no coordinate at all: found by searching the address
        assertEquals("Position from the address", SharedPosition.of(point = false, fromPage = false, addressWon = false).note)
    }

    @Test fun recentsKeepVillageAndReadOldLines() {
        val p = Place("a:x:1:5", "Hauptstraße 5 · Völlan", "address", 46.61, 11.12, source = Place.Source.MAP,
            town = "Lana", village = "Völlan")
        val back = SearchStore.decode(SearchStore.encode(p))!!
        assertEquals("Völlan", back.village)
        assertEquals("Lana", back.municipality)
        // a line written before 2026-10-22 (9 fields)
        val old = SearchStore.decode("n1\tMeraner Hütte\thut\t\t46.732000\t11.091000\t1960\t\tPOI")!!
        assertEquals("Meraner Hütte", old.name)
        assertNull(old.village); assertNull(old.municipality)
    }
}
