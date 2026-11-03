package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-02 ("zuletzt habe ich es ja gesucht"): a place shared into OnPath (Google Maps → Share) goes to
 * "Recent" too, with a share sign instead of the clock; it survives the store's round trip.
 */
class SharedRecentTest {
    private val p = Place("shared:51.78,7.54", "Restaurant Schloss", "other", 51.78, 7.54, address = "Schloßstraße 1, Nordkirchen")

    @Test fun theSharedMarkSurvivesTheStore() {
        val back = SearchStore.decode(SearchStore.encode(p.copy(shared = true)))!!
        assertTrue(back.shared)
        assertEquals("Restaurant Schloss", back.name)
        assertFalse(SearchStore.decode(SearchStore.encode(p))!!.shared)
        // an entry written before (11 fields) reads as not shared
        val old = SearchStore.encode(p).split('\t').take(11).joinToString("\t")
        assertFalse(SearchStore.decode(old)!!.shared)
    }

    @Test fun aSharedPlaceIsRememberedAndShownWithTheShareSign() {
        val flow = File("src/main/java/com/onpath/app/ui/SearchFlow.kt").readText()
        val shared = flow.substringAfter("fun showSharedPlace(").substringBefore("\n    }\n")
        assertTrue(shared, shared.contains("search.remember(p.copy(shared = true))"))
        val screen = File("src/main/java/com/onpath/app/ui/search/SearchScreen.kt").readText()
        assertTrue(screen.contains("if (h.place.shared) Icon(Icons.Outlined.Share"))
    }
}
