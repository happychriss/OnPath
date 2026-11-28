package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner's Pixel 8 2026-11-28 11:03 (Hilden, „olive“, filter Essen): „Olive“ in 11.6 km and two cafés „Olive“ 487 / 496 km
 * away (online) came before „Restaurant Olive“ in 470 m – a name that IS the query had its own tier over every other hit,
 * however far. For a shop / POI the exact name is no reason to jump ahead of a whole word in the name: distance decides
 * (captain 2026-11-28). A town / village / peak keeps the exact-name lead (the „Paris“ case).
 */
class OliveSearchTest {
    private val lat = 51.169; private val lon = 6.93
    private fun north(km: Double) = lat + km / 111.2

    private fun ranked(query: String, offline: List<Place>, online: List<Place> = emptyList(), cat: SearchKinds.Category? = null): List<String> {
        val q = SearchQuery(query, cat, lat, lon, null)
        val hits = SearchRanking.mix(SearchRanking.rank(offline.map { OfflineIndex.Entry(it) }, q), SearchRanking.onlineHits(online, q))
        return hits.map { "${it.place.name} ${it.place.kind} ${"%.1f".format(java.util.Locale.ROOT, it.distanceM / 1000)}" }
    }

    @Test fun nearRestaurantOliveBeforeFarOlive() {
        val offline = listOf(
            Place("n1", "Olive", "restaurant", north(11.6), lon),
            Place("n2", "Restaurant Olive", "restaurant", north(0.470), lon),
            Place("n3", "Restaurant Olive", "bar", north(0.476), lon),
        )
        val online = listOf(Place("p1", "Olive", "cafe", north(487.0), lon, source = Place.Source.ONLINE),
            Place("p2", "Olive", "cafe", north(496.0), lon, source = Place.Source.ONLINE))
        assertEquals(listOf("Restaurant Olive restaurant 0.5", "Restaurant Olive bar 0.5", "Olive restaurant 11.6", "Olive cafe 487.0", "Olive cafe 496.0"),
            ranked("olive", offline, online, SearchKinds.Category.FOOD))
    }

    @Test fun aTownNamedLikeTheQueryStaysFirst() {
        val offline = listOf(Place("c1", "Café Paris", "cafe", north(1.0), lon), Place("t1", "Paris", "place", north(400.0), lon, sub = "city"))
        assertEquals("Paris place 400.0", ranked("paris", offline).first())
    }
}
