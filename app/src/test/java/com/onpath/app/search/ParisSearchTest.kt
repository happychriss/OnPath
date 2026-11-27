package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner's Pixel 2026-11-27: „Paris“ searched from Hilden showed the hairdresser „Paris“ in Düsseldorf first – the list's
 * scoring had no exact-place bonus (only OnlineRanking had). Photon's real answers (normal + places request) from Hilden.
 */
class ParisSearchTest {
    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream("photon/$n")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun parisFromHildenIsFirst() {
        val main = PhotonParser.parse(res("paris-hilden.json"))
        val places = PhotonParser.parse(res("paris-hilden-places.json"))
        val merged = PhotonProvider.mergePlaces("Paris", places, main)
        val ranked = OnlineRanking.rank(merged, 51.169, 6.93, "DE", emptySet(), "Paris").take(10)
        val q = SearchQuery("Paris", null, 51.169, 6.93, null)
        val hits = SearchRanking.mix(emptyList(), SearchRanking.onlineHits(ranked, q))
        assertEquals("Paris", hits.first().place.name)
        assertEquals("FR", hits.first().place.cc?.uppercase())
    }
}
