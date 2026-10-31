package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

class SearchRankingTest {

    private fun place(name: String?, kind: String, lat: Double, lon: Double, alt: List<String> = emptyList(), sub: String? = null) =
        Place("$kind:$name:$lat", name, kind, lat, lon, sub = sub, alt = alt)

    private val centre = 46.67 to 11.16
    private fun q(text: String, cat: SearchKinds.Category? = null, route: List<GeoPoint>? = null) =
        SearchQuery(text, cat, centre.first, centre.second, route)

    private val index = OfflineIndex(listOf(
        place("Meraner Hütte", "hut", 46.732, 11.091),
        place("Meran", "place", 46.670, 11.160, alt = listOf("Meran - Merano", "Merano"), sub = "town"),
        place("Meraner Höhenweg", "trail", 46.75, 11.05),
        place("Gasthaus Merana", "restaurant", 46.60, 11.20),
        place("Leiter Alm", "restaurant", 46.70, 11.10),
        place("Hochganghaus", "hut", 46.71, 11.06),
        place(null, "water", 46.671, 11.161),
        place(null, "water", 46.60, 11.40),
        place("Bahnhof Algund", "train", 46.679, 11.131),
        place("Hohe Weiße", "peak", 46.75, 11.10, alt = listOf("Cima Bianca")),
    ).map { OfflineIndex.Entry(it) })

    private fun names(hits: List<SearchHit>) = hits.map { it.place.name }

    @Test fun exactNameBeatsPrefix() {
        val hits = index.search(q("meran"))
        assertEquals("Meran", hits.first().place.name)
        assertEquals(3, hits.first().tier)
        assertTrue(names(hits).containsAll(listOf("Meraner Hütte", "Meraner Höhenweg")))
    }

    @Test fun italianNameAndTypo() {
        assertEquals("Meran", index.search(q("merano")).first().place.name)
        assertEquals("Hohe Weiße", index.search(q("cima bianca")).first().place.name)
        assertEquals("Hochganghaus", index.search(q("hochgnaghaus")).first().place.name) // swapped letters
        assertEquals("Hohe Weiße", index.search(q("hohe weise")).first().place.name)    // ß typed as s
    }

    @Test fun multiWordAllWordsMustMatch() {
        val hits = index.search(q("meraner hutte"))
        assertEquals(listOf("Meraner Hütte"), names(hits))
    }

    @Test fun kindWordFindsUnnamedPlacesNearestFirst() {
        val hits = index.search(q("trinkwasser"))
        assertEquals(2, hits.size)
        assertTrue(hits[0].distanceM < hits[1].distanceM)
    }

    @Test fun categoryWithoutTextListsKindByDistance() {
        val hits = index.search(q("", SearchKinds.Category.HUTS))
        assertEquals(setOf("Hochganghaus", "Meraner Hütte"), names(hits).toSet())
        assertTrue(hits[0].distanceM <= hits[1].distanceM)
        assertTrue(index.search(q("")).isEmpty())
        assertEquals(listOf("Bahnhof Algund"), names(index.search(q("", SearchKinds.Category.STATIONS))))
    }

    @Test fun nearRouteHelpsButExactNameStaysFirst() {
        // owner: one list by relevance – "Meran" (exact) stays first; among the prefix matches the hut on
        // the route beats the trail that is nearer to the map centre but off the route.
        val route = listOf(GeoPoint(46.72, 11.08), GeoPoint(46.732, 11.0915), GeoPoint(46.74, 11.10))
        val hits = index.search(q("meran", route = route))
        assertEquals("Meran", hits.first().place.name)
        assertFalse(hits.first().nearRoute)
        val hut = hits.first { it.place.name == "Meraner Hütte" }
        assertTrue(hut.nearRoute)
        assertTrue(hits.indexOf(hut) < hits.indexOfFirst { it.place.name == "Gasthaus Merana" })
    }

    @Test fun onlineResultsMixIntoOneList() {
        val q = q("meran")
        val offline = index.search(q)
        val online = SearchRanking.onlineHits(listOf(
            Place("o:1", "Meraner Straße 12", "address", 46.50, 11.35, address = "39100 Bozen", source = Place.Source.ONLINE, town = "Bozen"),
            Place("o:2", "Meran 2000", "lift", 46.68, 11.22, source = Place.Source.ONLINE),
            Place("o:3", "Hotel Aurora", "other", 46.67, 11.16, source = Place.Source.ONLINE), // Photon matched a hidden field
        ), q)
        val mixed = SearchRanking.mix(offline, online)
        assertEquals(offline.size + 3, mixed.size)
        assertEquals("Meran", mixed.first().place.name)          // exact offline match first
        // the nearby online lift ("Meran 2000", prefix) ranks among the offline prefix matches, before the far trail
        val lift = mixed.indexOfFirst { it.place.name == "Meran 2000" }
        assertTrue(lift < mixed.indexOfFirst { it.place.name == "Gasthaus Merana" })
        // an online hit that does not match the words we see goes to the end (tier 1)
        assertEquals("Hotel Aurora", mixed.last().place.name)
        assertEquals(1, mixed.last().tier)
        // tiers never go up along the list
        assertTrue(mixed.zipWithNext().all { (a, b) -> a.tier >= b.tier })
    }

    @Test fun lateOnlineResultsNeverMoveShownRows() {
        // owner rule: rows must not move under the finger. Online results arriving after the offline rows were
        // shown go below them (ranked among themselves); with nothing shown yet, or after typing, all is re-ranked.
        val q = q("meran")
        val offline = index.search(q)
        val online = SearchRanking.onlineHits(listOf(
            Place("o:far", "Meraner Straße 12", "address", 46.50, 11.35, source = Place.Source.ONLINE),
            Place("o:2", "Meran 2000", "lift", 46.68, 11.22, source = Place.Source.ONLINE),
        ), q)
        val kept = SearchRanking.mix(offline, online, keepShown = true)
        assertEquals(offline, kept.take(offline.size))
        assertEquals(listOf("Meran 2000", "Meraner Straße 12"), names(kept.drop(offline.size)))
        // the lift would rank among the offline rows when nothing was shown yet / after the next keystroke
        val mixed = SearchRanking.mix(offline, online, keepShown = false)
        assertTrue(mixed.indexOfFirst { it.place.name == "Meran 2000" } < offline.size)
        // no offline rows shown: online only, best first
        assertEquals(listOf("Meran 2000", "Meraner Straße 12"), names(SearchRanking.mix(emptyList(), online, keepShown = true)))
        // the session keeps the shown list for the same text: rows on screen stay, new ones go below, nothing twice
        val shown = SearchRanking.keepShown(offline, mixed)
        assertEquals(offline, shown.take(offline.size))
        assertEquals(listOf("Meran 2000", "Meraner Straße 12"), names(shown.drop(offline.size)))
        assertEquals(shown, SearchRanking.keepShown(shown, mixed))
        // SearchUi shows the kept list when there is one
        val ui = com.onpath.app.ui.search.SearchUi(query = "meran", hits = offline, online = online, shown = kept)
        assertEquals(kept, ui.results)
    }

    @Test fun onlineRowsStillMatchingTheLongerText() {
        val olive = Place("o:1", "Restaurant Olive", "restaurant", 51.1655, 6.9395, address = "Mittelstraße 1, 40721 Hilden",
            source = Place.Source.ONLINE, town = "Hilden")
        val hill = Place("o:2", "Olive Hill", "place", 38.3, -83.2, source = Place.Source.ONLINE)
        assertEquals(listOf(olive, hill), SearchRanking.stillMatching(listOf(olive, hill), "olive hil"))
        assertEquals(listOf(olive), SearchRanking.stillMatching(listOf(olive, hill), "olive hilde"))
        assertEquals(emptyList<Place>(), SearchRanking.stillMatching(listOf(olive, hill), " "))
    }

    @Test fun onlineTieWithOfflineKeepsOfflineFirst() {
        val p = place("Leiter Alm", "restaurant", 46.70, 11.10)
        val off = index.search(q("leiter alm"))
        val on = SearchRanking.onlineHits(listOf(p.copy(id = "o:9", source = Place.Source.ONLINE)), q("leiter alm"))
        assertEquals(Place.Source.POI, SearchRanking.mix(off, on).first().place.source)
    }

    @Test fun townNamedInQueryLiftsOnlineResult() {
        val q = q("via roma 3 bolzano")
        val a = Place("o:a", "Via Roma 3", "address", 46.67, 11.16, source = Place.Source.ONLINE, town = "Meran")
        val b = Place("o:b", "Via Roma 3", "address", 46.49, 11.35, source = Place.Source.ONLINE, town = "Bozen")
        val hits = SearchRanking.onlineHits(listOf(a, b), q, setOf("bozen", "bolzano"), 46.498, 11.354)
        assertEquals("o:b", SearchRanking.mix(emptyList(), hits).first().place.id)
    }

    @Test fun nearerWinsAmongEqualMatches() {
        val hits = index.search(q("leiter"))
        assertEquals("Leiter Alm", hits.single().place.name)
        val a = index.search(SearchQuery("hochganghaus", null, 46.71, 11.06)).first()
        val b = index.search(SearchQuery("hochganghaus", null, 47.5, 12.0)).first()
        assertTrue(a.score > b.score)
    }

    @Test fun loadsRealRegionFastWhenAvailable() {
        // Optional: a built region in /home/user/data/out (not in git).
        val dir = File("/home/user/data/out")
        if (!File(dir, "pois.geojson").isFile) return
        val cache = File(System.getProperty("java.io.tmpdir"), "onpath-search-test").apply { mkdirs() }
        val idx = OfflineIndex.load(dir, cache)
        assertTrue(idx.entries.size > 10_000)
        idx.search(q("meraner hutte")) // warm up
        for (text in listOf("meran", "hutte", "spitze", "algunder waalweg", "ifinegr hutte", "st leonhard", "meraner huttte")) {
            var hits: List<SearchHit> = emptyList()
            val ms = measureTimeMillis { hits = idx.search(q(text)) }
            println("search '$text': ${hits.size} hits in $ms ms, first: ${hits.take(3).map { it.place.name + " (" + it.place.kind + ")" }}")
            assertTrue("'$text' took $ms ms", ms < 300) // JVM on a shared build machine; phone target < 100 ms
            assertTrue("no hits for '$text'", hits.isNotEmpty())
        }
    }
}
