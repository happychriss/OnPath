package com.onpath.web

import com.onpath.app.search.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kept places in the browser (owner 2026-11-15, „Orte merken“): the app's SavedPlaces rules over the page's lines, the
 * lines = the app's SearchStore format, „Zuletzt gesucht“ = SearchStore.addRecent.
 */
class WebKeptTest {
    private fun code(id: String, name: String?, lat: Double = 51.0, lon: Double = 7.0, kind: String = "cafe", ele: Int? = null) =
        WebKept.encode(Place(id, name, kind, lat, lon, ele))

    private val cafe = code("n1", "Waldcafé")
    private val ruin = code("n2", "Burgruine", 51.1, 7.1, "ruins")
    private fun favs(json: String) = Regex("\"favourites\":\\[(.*?)]").find(json)!!.groupValues[1]
        .split("\",\"").filter { it.isNotBlank() }.map { it.trim('"').replace("\\t", "\t") }

    @Test fun theLineIsTheAppsSearchStoreLine() {
        assertEquals("n1\tWaldcafé\tcafe\t\t51.000000\t7.000000\t\t\tPOI\t\t\t", cafe)
        assertTrue(code("m", "Minus", -0.0001, -12.5).contains("\t-0.000100\t-12.500000\t"))
        val p = WebKept.decode(code("x", "Hütte", 46.123456789, 11.5, "hut", 2100))!!
        assertEquals(46.123457, p.lat, 1e-9); assertEquals(2100, p.ele)
        assertNull(WebKept.decode("broken"))
    }

    @Test fun keepUnkeepAndTheSamePlaceFromTheMap() {
        val k = WebKept.apply("", "", "keep", cafe)
        assertEquals(listOf(cafe), favs(k))
        // the same café held on the map (another id, a few metres off) is the same favourite
        val fromMap = code("cafe@51.00001,7.0", "Waldcafé", 51.00001)
        assertTrue(WebKept.state("", cafe, fromMap).contains("\"favourite\":true"))
        assertEquals(listOf(cafe), favs(WebKept.apply("", cafe, "keep", fromMap)))
        assertEquals(emptyList<String>(), favs(WebKept.apply("", cafe, "unkeep", fromMap)))
        // the newest first
        assertEquals(listOf(ruin, cafe), favs(WebKept.apply("", cafe, "keep", ruin)))
    }

    @Test fun oneHome() {
        val h = WebKept.apply("", "", "setHome", cafe)
        assertTrue(h, h.startsWith("{\"home\":\"n1\\tWaldcafé"))
        assertTrue(WebKept.state(cafe, "", cafe).contains("\"home\":true"))
        assertTrue(WebKept.apply(cafe, "", "clearHome", "").startsWith("{\"home\":null"))
        // another place: it replaces the old home
        assertTrue(WebKept.apply(cafe, "", "setHome", ruin).startsWith("{\"home\":\"n2\\tBurgruine"))
    }

    /**
     * The app's KeptPlacesSlice toggles; the page's rows say what they showed. A row that no longer fits what is kept now
     * (the sync or another tab changed it meanwhile) changes nothing – never the opposite of what was tapped.
     */
    @Test fun aStaleRowNeverDoesTheOpposite() {
        assertEquals(listOf(cafe), favs(WebKept.apply("", cafe, "keep", cafe))) // a favourite already: stays one
        assertEquals(listOf(cafe), favs(WebKept.apply("", cafe, "unkeep", ruin))) // not a favourite: nothing goes
        assertTrue(WebKept.apply(cafe, "", "setHome", cafe).startsWith("{\"home\":\"n1\\tWaldcafé")) // the home already: stays
        assertEquals("{\"home\":null,\"favourites\":[]}", WebKept.apply("", "", "clearHome", "")) // no home: nothing
        // the kept line is the place as the page had it (its address, kind, height) – not rebuilt from a map POI
        val full = WebKept.encode(Place("n3", "Hütte", "hut", 46.5, 11.2, 2100, address = "Weg 1, Meran"))
        assertEquals(listOf(full), favs(WebKept.apply("", "", "keep", full)))
    }

    @Test fun theEmptySearchListsEveryPlaceOnce() {
        val six = (1..6).map { code("p$it", "Ort $it", 51.0 + it * 0.01) }
        val s = WebKept.start(cafe, six.joinToString("\n"), (listOf(cafe, ruin) + six.take(1)).joinToString("\n"), false, 51.0, 7.0)
        assertTrue(s, s.contains("\"more\":1"))
        assertEquals(5, Regex("\"title\":\"Ort \\d\"").findAll(s.substringAfter("\"favourites\"").substringBefore("\"more\"")).count())
        // Zuletzt gesucht: only the ruin (the café is home, Ort 1 a favourite)
        val recent = s.substringAfter("\"recent\":")
        assertTrue(recent, recent.contains("Burgruine") && !recent.contains("Waldcafé") && !recent.contains("Ort 1"))
        // Zuhause's grey part: the place's name · distance
        assertTrue(s, s.substringBefore("\"favourites\"").contains("\"subtitle\":\"Waldcafé · 0 m\""))
        assertTrue(WebKept.start("", six.joinToString("\n"), "", true, 51.0, 7.0).contains("\"more\":0"))
    }

    @Test fun recentFirstNoRepeatsAtMostTwenty() {
        var r = ""
        for (i in 1..25) r = WebKept.addRecent(r, code("p$i", "Ort $i", 51.0 + i * 0.01))
        r = WebKept.addRecent(r, code("p25b", "Ort 25", 51.25)) // the same place again, another id
        val lines = r.split('\n')
        assertEquals(20, lines.size)
        assertTrue(lines[0].startsWith("p25b\t"))
        assertEquals(1, lines.count { it.contains("\tOrt 25\t") })
    }
}
