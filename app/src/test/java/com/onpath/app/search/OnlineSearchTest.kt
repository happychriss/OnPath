package com.onpath.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchTest {

    // Real Photon responses (2026-10-18), shortened.
    private val algund = """{"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"osm_type":"R","osm_id":47284,"osm_key":"place","osm_value":"village","type":"city","name":"Algund","county":"Bozen","state":"Trentino-Südtirol","country":"Italien","postcode":"39022","countrycode":"IT","extra":{"admin_level":"8"}},"geometry":{"type":"Point","coordinates":[11.1230691,46.6825194]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":7416709365,"osm_key":"railway","osm_value":"halt","type":"house","name":"Algund","street":"Bahnhofsplatz","district":"Dorf","city":"Algund","county":"Bozen","state":"Trentino-Südtirol","country":"Italien","postcode":"39022","countrycode":"IT"},"geometry":{"type":"Point","coordinates":[11.1318251,46.6788576]}}]}"""

    private val house = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"osm_type":"N","osm_id":2755796708,"osm_key":"place","osm_value":"house","type":"house","housenumber":"10","street":"Laubengasse","locality":"Historischer Ortskern","city":"Meran","county":"Bozen","state":"Trentino-Südtirol","country":"Italien","postcode":"39012","countrycode":"IT"},"geometry":{"type":"Point","coordinates":[11.1643935,46.6717354]}}]}"""

    @Test fun parsesPlacesAndStations() {
        val r = PhotonParser.parse(algund)
        assertEquals(2, r.size)
        assertEquals("place", r[0].kind); assertEquals("village", r[0].sub)
        assertEquals("Bozen, Trentino-Südtirol", r[0].address)
        assertEquals(46.6825194, r[0].lat, 1e-9); assertEquals(11.1230691, r[0].lon, 1e-9)
        assertEquals("train", r[1].kind)
        assertEquals("Bahnhofsplatz, 39022 Algund", r[1].address)
        assertEquals(Place.Source.ONLINE, r[1].source)
        assertEquals("o:R47284", r[0].id)
    }

    @Test fun addressWithoutNameUsesStreet() {
        val p = PhotonParser.parse(house).single()
        assertEquals("Laubengasse 10", p.name)
        assertEquals("address", p.kind)
        assertEquals("39012 Meran", p.address)
    }

    @Test fun brokenJsonGivesNothing() {
        assertTrue(PhotonParser.parse("<html>busy</html>").isEmpty())
        assertTrue(PhotonParser.parse("""{"features":[{"geometry":null}]}""").isEmpty())
    }

    @Test fun kindMapping() {
        assertEquals("hut" to null, PhotonParser.kindOf("tourism", "alpine_hut"))
        assertEquals("bivouac" to null, PhotonParser.kindOf("tourism", "wilderness_hut"))
        assertEquals("bus" to null, PhotonParser.kindOf("highway", "bus_stop"))
        assertEquals("street" to null, PhotonParser.kindOf("highway", "residential"))
        assertEquals("place" to "town", PhotonParser.kindOf("place", "town"))
        assertEquals("peak" to null, PhotonParser.kindOf("natural", "peak"))
        assertEquals("lift" to null, PhotonParser.kindOf("aerialway", "station"))
        assertEquals("train" to null, PhotonParser.kindOf("railway", "station"))
        assertEquals("water" to null, PhotonParser.kindOf("amenity", "drinking_water"))
        assertEquals("store" to "clothes", PhotonParser.kindOf("shop", "clothes"))
        assertEquals("Clothes", SearchKinds.of("other", "clothes").label(false))
        assertEquals("Village", SearchKinds.of("place", "village").label(false))
        assertEquals("Dorf", SearchKinds.of("place", "village").label(true))
    }

    @Test fun urlAndUserAgent() {
        val u = PhotonProvider("x").url("Meraner Hütte", 46.67, 11.16, 10)
        assertEquals("https://photon.komoot.io/api/?q=Meraner+H%C3%BCtte&lat=46.67000&lon=11.16000&zoom=12&location_bias_scale=0.3&limit=10&lang=de", u)
        assertEquals("OnPath-Android/0.6.0 (github.com/happychriss/OnPath)", PhotonProvider.userAgent("0.6.0"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Spar%20Algund", GoogleSearchLink.url(" Spar Algund "))
    }

    @Test fun deduplicatesOnlineAgainstOffline() {
        val offline = listOf(Place("n1", "Bahnhof Algund", "train", 46.6788, 11.1318, alt = listOf("Algund")))
        val online = PhotonParser.parse(algund)
        val extra = SearchMerge.onlineExtras(offline, online)
        assertEquals(listOf("place"), extra.map { it.kind }) // the station (same name, < 100 m) is dropped
        // Same name but 2 km apart: both stay.
        val far = Place("o:x", "Algund", "place", 46.70, 11.12)
        assertTrue(SearchMerge.onlineExtras(listOf(online[0]), listOf(far)).isNotEmpty())
        // Different name, same spot: both stay.
        assertTrue(SearchMerge.onlineExtras(offline, listOf(online[1].copy(name = "Bar Stazione"))).isNotEmpty())
        // Within the online list itself
        assertEquals(1, PhotonParser.dedupe(listOf(online[1], online[1].copy(id = "o:other", lat = online[1].lat + 0.0003))).size)
    }

    @Test fun addressLineNeverRepeatsName() {
        assertEquals("Bozen", PhotonParser.addressLine("Meran", "place", null, null, "Meran", null, "Bozen", null, "Italien"))
        assertNull(PhotonParser.addressLine("X", "other", null, null, null, null, null, null, null))
    }

    /** Owner bug: "Bozen" landed on the province boundary's centroid in the forest above Meran. */
    @Test fun skipsAdministrativeBoundariesAndSigns() {
        val json = """{"features":[
          {"geometry":{"coordinates":[11.2301833,46.6559296]},"properties":{"name":"Bozen","osm_key":"boundary","osm_value":"administrative","osm_type":"R","osm_id":1}},
          {"geometry":{"coordinates":[11.3547399,46.4984781]},"properties":{"name":"Bozen","osm_key":"place","osm_value":"city","osm_type":"N","osm_id":2}},
          {"geometry":{"coordinates":[11.3199164,46.4787258]},"properties":{"name":"Bozen","osm_key":"highway","osm_value":"traffic_sign","osm_type":"N","osm_id":3}}]}"""
        val r = PhotonParser.parse(json)
        org.junit.Assert.assertEquals(1, r.size)
        org.junit.Assert.assertEquals("city", r[0].sub)
        org.junit.Assert.assertEquals(46.4985, r[0].lat, 1e-3)
    }

    @Test fun onlineRankingPrefersNamedTownThenCountryThenDistance() {
        fun pl(id: String, name: String, town: String, cc: String, lat: Double, lon: Double, kind: String = "address") =
            Place(id, name, kind, lat, lon, source = Place.Source.ONLINE, cc = cc, town = town)
        val meran = pl("a", "Romstraße 3", "Meran", "IT", 46.6695, 11.152)
        val bozen = pl("b", "Romstraße 3", "Bozen", "IT", 46.4905, 11.342)
        val r = OnlineRanking.rank(listOf(meran, bozen), 46.67, 11.16, "IT", OnlineRanking.queryTowns("via Roma 3 Bozen", emptyList()))
        assertEquals("b", r.first().id)
        val de = pl("d", "Hauptstraße 10", "Peißenberg", "DE", 47.8, 11.07)
        val it = pl("i", "Hauptstraße 10", "Terlan", "IT", 46.53, 11.25)
        assertEquals("i", OnlineRanking.rank(listOf(de, it), 46.67, 11.16, "IT", emptySet()).first().id)
    }

    @Test fun streetPiecesAreOneResult() {
        val json = """{"features":[
          {"properties":{"osm_type":"W","osm_id":1,"osm_key":"highway","osm_value":"primary","name":"Romstraße","city":"Meran","countrycode":"IT"},"geometry":{"coordinates":[11.15,46.67]}},
          {"properties":{"osm_type":"W","osm_id":2,"osm_key":"highway","osm_value":"tertiary","name":"Romstraße","city":"Meran","countrycode":"IT"},"geometry":{"coordinates":[11.16,46.672]}}]}"""
        val r = PhotonParser.parse(json)
        assertEquals(1, r.size)
        assertEquals("IT", r[0].cc); assertEquals("Meran", r[0].town)
    }
}
