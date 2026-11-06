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
        assertTrue(chips, chips.startsWith("[[\"PLACES\",\"Places\"],[\"FOOD\",\"Food\"]") && chips.contains("[\"PARKING\",\"Parking\"]"))
    }
}
