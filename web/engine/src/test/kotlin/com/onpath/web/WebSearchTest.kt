package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The web's search keeps its list like the app's SearchSession (field bug 0.13.2, backlog "Search list never jumps"):
 * for the same text the rows on screen keep their places and online rows go below (SearchRanking.keepShown); online
 * rows of the text before that still match stay while typing on (stillMatching); the six chips filter by kind
 * (SearchKinds.Category) and work without a text.
 */
class WebSearchTest {
    private companion object { var indexed = false } // WebSearch is one object: its test area is indexed once
    private val photon = """{"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"osm_type":"R","osm_id":47284,"osm_key":"place","osm_value":"village","type":"city","name":"Algund","county":"Bozen","state":"Trentino-Südtirol","country":"Italien","postcode":"39022","countrycode":"IT"},"geometry":{"type":"Point","coordinates":[11.1230691,46.6825194]}},
      {"type":"Feature","properties":{"osm_type":"N","osm_id":7416709365,"osm_key":"railway","osm_value":"halt","type":"house","name":"Algund","street":"Bahnhofsplatz","city":"Algund","county":"Bozen","country":"Italien","postcode":"39022","countrycode":"IT"},"geometry":{"type":"Point","coordinates":[11.1318251,46.6788576]}}]}"""

    private fun area() {
        WebSearch.reset()
        if (indexed) return
        indexed = true
        val dir = Files.createTempDirectory("onpath-websearch").toFile()
        File(dir, "pois.geojson").writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.11,46.69]},"properties":{"id":"n1","kind":"hut","name":"Algunder Alm"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.12,46.68]},"properties":{"id":"n2","kind":"water","name":"Algunder Brunnen"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.16,46.67]},"properties":{"id":"n3","kind":"parking","name":"Parkplatz Algund Süd"}}]}""")
        WebSearch.addArea("websearch-test", dir.path)
    }

    private fun titles(json: String) = Regex("\"title\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
    private fun ids(json: String) = Regex("\"id\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()

    @Test fun theSameTextKeepsItsRowsAndOnlineGoesBelow() {
        area()
        val offline = ids(WebSearch.search("algund", 46.68, 11.12, "", ""))
        assertTrue("offline rows found: $offline", offline.size >= 2)
        val mixed = ids(WebSearch.online("algund", photon, ""))
        assertEquals("the rows on screen keep their places", offline, mixed.take(offline.size))
        assertTrue("online rows below them: $mixed", mixed.size > offline.size)
        assertEquals("the same text again: nothing moves", mixed, ids(WebSearch.search("algund", 46.68, 11.12, "", "")))
    }

    @Test fun onlineRowsThatStillMatchStayWhileTyping() {
        area()
        WebSearch.search("algun", 46.68, 11.12, "", "")
        val online = ids(WebSearch.online("algun", photon, "")).filter { it.startsWith("ph") || !it.startsWith("n") }
        val next = ids(WebSearch.search("algund", 46.68, 11.12, "", ""))
        assertTrue("an online row of \"algun\" stays for \"algund\" ($online in $next)", online.any { it in next })
    }

    @Test fun chipsFilterByKindAlsoWithoutText() {
        area()
        assertEquals(listOf("Parkplatz Algund Süd"), titles(WebSearch.search("", 46.68, 11.12, "", "PARKING")))
        assertEquals(listOf("Algunder Alm"), titles(WebSearch.search("algund", 46.68, 11.12, "", "FOOD")))
        val chips = WebSearch.categories()
        assertTrue(chips, chips.startsWith("[[\"PLACES\",\"Places\",[\"place\",\"street\",\"address\"]],[\"FOOD\",\"Food\",[") &&
            chips.contains("[\"PARKING\",\"Parking\",[\"parking\"]]"))
    }

    /**
     * Owner's bug (planner 2026-11-19): „München“, then the chip „Orte“ – the list still showed everything. Photon's
     * answer for the text WITHOUT the chip (asked before the click, back after it) came into the engine after the
     * chip's search and mixed its rows – the station, the restaurant – into the chip's list. The app cancels that
     * request (SearchSession.run: onlineJob.cancel()); here a late answer for another text or chip changes nothing.
     */
    /** Photon's real answer for „München“ near Munich (2026-11-19: the city, stations, pubs, hospitals, streets). */
    @Test fun muenchenWithPlacesShowsOnlyPlacesStreetsAddresses() {
        area()
        val muc = File("../app/test/fixtures/photon-muenchen.json").readText()
        fun kinds(json: String) = Regex("\"kind\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        WebSearch.search("München", 48.14, 11.57, "", "PLACES")
        val places = kinds(WebSearch.online("München", muc, "PLACES"))
        assertTrue("the chip's list has rows ($places)", places.isNotEmpty())
        assertTrue("„Orte“: only places, streets, addresses ($places)", places.all { it in setOf("place", "street", "address") })
        WebSearch.reset()
        WebSearch.search("München", 48.14, 11.57, "", "")
        val all = kinds(WebSearch.online("München", muc, ""))
        WebSearch.search("München", 48.14, 11.57, "", "PLACES") // the chip clicked after the list was there
        val after = kinds(WebSearch.online("München", muc, "PLACES"))
        assertTrue("„Orte“ after the full list: only places, streets, addresses ($after; before $all)", after.all { it in setOf("place", "street", "address") })
    }

    /**
     * Owner 2026-11-27 („warum findet der Planer keine Städte wie Berlin mehr?“, at Hilden with „Orte“): Photon's biased
     * answer near Hilden has only „Berliner Straße“s; the places-only request (the app's PhotonProvider.search) brings the
     * city, and it goes on top. Real answers of 2026-11-27.
     */
    @Test fun berlinFromHildenComesFromThePlacesRequest() {
        area()
        val main = File("../app/test/fixtures/photon-berlin-hilden.json").readText()
        val places = File("../app/test/fixtures/photon-berlin-hilden-places.json").readText()
        WebSearch.search("berlin", 51.17, 6.94, "", "PLACES")
        val without = titles(WebSearch.online("berlin", main, "PLACES"))
        assertTrue("the biased answer alone has no city Berlin ($without)", "Berlin" !in without)
        WebSearch.reset()
        WebSearch.search("berlin", 51.17, 6.94, "", "PLACES")
        val with = titles(WebSearch.online("berlin", main, "PLACES", places))
        assertEquals("the city Berlin on top ($with)", "Berlin", with.firstOrNull())
    }

    @Test fun aLateOnlineAnswerWithoutTheChipDoesNotFillTheChipsList() {
        area()
        val muc = """{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"osm_type":"R","osm_id":62428,"osm_key":"place","osm_value":"city","type":"city","name":"München","state":"Bayern","country":"Deutschland","countrycode":"DE"},"geometry":{"type":"Point","coordinates":[11.5753822,48.1371079]}},
          {"type":"Feature","properties":{"osm_type":"N","osm_id":21608575,"osm_key":"railway","osm_value":"station","type":"house","name":"München Hauptbahnhof","city":"München","country":"Deutschland","countrycode":"DE"},"geometry":{"type":"Point","coordinates":[11.5596,48.1402]}},
          {"type":"Feature","properties":{"osm_type":"N","osm_id":1,"osm_key":"amenity","osm_value":"restaurant","type":"house","name":"München Stubn","city":"München","country":"Deutschland","countrycode":"DE"},"geometry":{"type":"Point","coordinates":[11.57,48.14]}}]}"""
        fun kinds(json: String) = Regex("\"kind\":\"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()
        WebSearch.search("München", 48.14, 11.57, "", "")
        val all = kinds(WebSearch.online("München", muc, ""))
        assertTrue("without a chip: the station and the restaurant too ($all)", "train" in all && "restaurant" in all)
        WebSearch.reset()
        WebSearch.search("München", 48.14, 11.57, "", "")         // typed; Photon asked …
        WebSearch.search("München", 48.14, 11.57, "", "PLACES")   // … the chip „Orte“ clicked …
        WebSearch.online("München", muc, "")                       // … the answer for the text without the chip arrives
        val places = kinds(WebSearch.online("München", muc, "PLACES"))
        assertTrue("the chip's list has rows ($places)", places.isNotEmpty())
        assertTrue("„Orte“: only places, streets, addresses ($places)", places.all { it in setOf("place", "street", "address") })
        val again = kinds(WebSearch.search("München", 48.14, 11.57, "", ""))
        assertTrue("the chip unpicked: no online row is lost for good ($again)", again.isNotEmpty())
    }
}
