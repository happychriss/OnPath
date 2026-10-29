package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "New York" searched from South Tyrol: Photon's biased answer holds only nearby shops; the places
 * request brings the city and it must come first in the list.
 */
class FarCitySearchTest {
    private fun shop(id: String, name: String, lat: Double, lon: Double, kind: String = "restaurant") =
        Place("o:N$id", name, kind, lat, lon, source = Place.Source.ONLINE)
    private val bistro = shop("1", "New York Bistro", 47.27, 11.39)          // Innsbruck
    private val yorker = shop("2", "New Yorker", 46.50, 11.35, "clothes")    // Bozen
    private val city = Place("o:R175905", "New York", "place", 40.71, -74.01, sub = "city", source = Place.Source.ONLINE)
    private val mills = Place("o:N3", "New York Mills", "place", 46.52, -95.37, sub = "village", source = Place.Source.ONLINE)

    @Test fun placesRequestAsksOnlyForPlaceLayers() {
        val u = PhotonProvider("x").placesUrl("New York", 46.67, 11.16, 5)
        assertTrue(u, u.contains("&layer=city") && u.contains("&layer=country"))
        assertTrue(u.contains("q=New+York"))
    }

    @Test fun namedPlacesGoFirstAndTwiceIsOnce() {
        val m = PhotonProvider.mergePlaces("New York", listOf(city, mills, shop("9", "Paris", 48.8, 2.3, "place")),
            listOf(bistro, city.copy(id = "o:N99"), yorker))
        assertEquals(listOf("New York", "New York Mills", "New York Bistro", "New Yorker"), m.map { it.name })
    }

    @Test fun theCityIsFirstAfterRanking() {
        val merged = PhotonProvider.mergePlaces("New York", listOf(city), listOf(bistro, yorker))
        val ranked = OnlineRanking.rank(merged, 46.67, 11.16, "IT", emptySet(), "New York")
        assertEquals("New York", ranked.first().name)
        val q = SearchQuery("New York", null, 46.67, 11.16, null)
        val hits = SearchRanking.mix(emptyList(), SearchRanking.onlineHits(ranked, q))
        assertEquals(city, hits.first().place)
    }

    @Test fun nearbyShopStillWinsWithoutAnExactPlace() {
        val ranked = OnlineRanking.rank(listOf(mills, bistro), 46.67, 11.16, "IT", emptySet(), "New York")
        assertEquals("only an exact name gets the bonus", "New York Bistro", ranked.first().name)
    }
}
