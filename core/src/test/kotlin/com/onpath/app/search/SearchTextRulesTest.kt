package com.onpath.app.search

import com.onpath.app.poi.PoiKinds
import com.onpath.app.search.SearchText.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure search rules as tables (test audit G15, replaces SearchTextTest and SearchCategoryTest's table checks): text
 * normalisation, word match, one-edit distance, kind words, and the six chips (owner 2026-10-31).
 */
class SearchTextRulesTest {

    @Test fun normaliseStripsAccentsAndPunctuation() {
        val table = listOf(
            "Meraner Hütte" to "meraner hutte",
            "Weißkugel" to "weisskugel",
            "Pippo’s Mountain Lodge" to "pippos mountain lodge",
            "Rifugio Città di Merano" to "rifugio citta di merano",
            "Hohe Weiße - Cima Bianca" to "hohe weisse cima bianca",
            null to "",
        )
        for ((raw, want) in table) assertEquals("normalize($raw)", want, SearchText.normalize(raw))
        assertEquals(listOf("st", "leonhard"), SearchText.tokens("St. Leonhard"))
    }

    @Test fun wordMatchTable() {
        // query, word, match (exact / prefix / inside the word / one typo for long words / none)
        val table = listOf(
            Triple("meran", "meran", Match.EXACT),
            Triple("mer", "meraner", Match.PREFIX),
            Triple("joch", "hochjoch", Match.INFIX),
            Triple("jo", "hochjoch", Match.NONE),            // too short for inside-word
            Triple("merna", "meran", Match.TYPO),            // swap
            Triple("rifugo", "rifugio", Match.TYPO),         // missing letter
            Triple("huette", "hutte", Match.TYPO),           // "ue" spelling
            Triple("hochganh", "hochganghaus", Match.TYPO),  // typo in a prefix
            Triple("mera", "mira", Match.NONE),              // 4 letters: no typo tolerance
            Triple("merxxn", "meran", Match.NONE),           // two edits
        )
        for ((q, w, want) in table) assertEquals("matchWord($q, $w)", want, SearchText.matchWord(q, w))
    }

    @Test fun osaDistanceAtMostOne() {
        val table = listOf(
            Triple("abcde", "abdce", true), Triple("abcde", "abcd", true), Triple("abcd", "xabcd", true),
            Triple("abcde", "badce", false), Triple("abc", "abcde", false),
        )
        for ((a, b, want) in table) assertEquals("osaAtMostOne($a, $b)", want, SearchText.osaAtMostOne(a, b))
    }

    @Test fun kindWords() {
        for ((word, kind) in listOf("hutte" to "hut", "rifugio" to "hut", "bahnh" to "train" /* prefix of bahnhof */, "station" to "lift"))
            assertTrue("$word → $kind", kind in SearchText.kindsFor(word))
        assertTrue(SearchText.kindsFor("ba").isEmpty())
    }

    /** Every kind a search result can have: the POI kinds plus the search-only ones. */
    private val searchable = PoiKinds.all.map { it.key }.toSet() +
        setOf("peak", "saddle", "place", "trail", "address", "street", "store", "other")

    @Test fun theOwnersChips() {
        // every kind in at most one chip – huts excepted (Essen + Übernachten, owner 2026-11-26); every chip names only
        // kinds that exist
        for (k in searchable) {
            val chips = SearchKinds.Category.entries.filter { k in it.kinds }
            assertTrue("$k is in ${chips.size} chips: $chips", chips.size <= if (k == "hut") 2 else 1)
            assertEquals("Category.of($k)", chips.firstOrNull(), SearchKinds.Category.of(k))
        }
        for (c in SearchKinds.Category.entries) for (k in c.kinds) assertTrue("$c: unknown kind $k", k in searchable)
        assertEquals(listOf("PLACES", "FOOD", "SLEEP", "SIGHTS", "WATER", "TRANSIT", "PARKING"), SearchKinds.Category.entries.map { it.name })
        assertEquals(listOf("Places", "Food", "Lodging", "Sights", "Water", "Transit", "Parking"), SearchKinds.Category.entries.map { it.label })
        assertTrue(SearchKinds.Category.entries.all { ' ' !in it.label })
        // what the owner listed per chip
        val table = listOf(
            "street" to SearchKinds.Category.PLACES, "address" to SearchKinds.Category.PLACES,
            "hut" to SearchKinds.Category.FOOD, "bar" to SearchKinds.Category.FOOD /* beer gardens */,
            "peak" to SearchKinds.Category.SIGHTS, "waterfall" to SearchKinds.Category.SIGHTS,
            "spring" to SearchKinds.Category.WATER, "lift" to SearchKinds.Category.TRANSIT,
            "parking" to SearchKinds.Category.PARKING, "toilets" to null,
            "hotel" to SearchKinds.Category.SLEEP, "guest_house" to SearchKinds.Category.SLEEP,
            "hostel" to SearchKinds.Category.SLEEP, "camping" to SearchKinds.Category.SLEEP,
            "bivouac" to SearchKinds.Category.SLEEP, "shelter" to null,
        )
        for ((kind, chip) in table) assertEquals("chip of $kind", chip, SearchKinds.Category.of(kind))
        // „Übernachten“ is the one shared set (PoiKinds.LODGING): a hut is in it as well as in Essen
        assertEquals(PoiKinds.LODGING, SearchKinds.Category.SLEEP.kinds)
        assertTrue("hut" in SearchKinds.Category.SLEEP.kinds && "hut" in SearchKinds.Category.FOOD.kinds)
    }

    @Test fun lodging() {
        for (k in listOf("hut", "bivouac", "camping", "hotel", "guest_house", "hostel")) assertTrue(k, PoiKinds.isLodging(k))
        for (k in listOf("shelter", "restaurant", null, "unknown")) assertTrue("$k", !PoiKinds.isLodging(k))
        for (k in listOf("hotel", "guest_house", "hostel")) assertTrue("$k hidden by default", k in PoiKinds.defaultHidden)
        // a user who never chose (old default stored) gets today's default with the beds hidden
        assertEquals(PoiKinds.defaultHidden,
            PoiKinds.upgradeHidden(setOf("shop", "parking", "toilets", "picnic", "info", "bike_shop", "charging")))
        for ((word, kind) in listOf("pension" to "guest_house", "jugendherberge" to "hostel", "hotel" to "hotel"))
            assertTrue("$word → $kind", kind in SearchText.kindsFor(word))
    }
}
