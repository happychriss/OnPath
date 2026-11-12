package com.onpath.app.poi

import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A route point pinned on a POI keeps the POI's kind (owner 2026-11-10, variant A: symbol + blue ring + badge). */
class PinnedPoiKindsTest {
    private val start = GeoPoint(46.6700, 11.1500)
    private val inn = GeoPoint(46.6812, 11.1601)   // Gasthof Hochmuth-like stop
    private val goal = GeoPoint(46.6900, 11.1700)

    private fun place(kind: String, lat: Double, lon: Double) = Place(id = "$kind@$lat", name = null, kind = kind, lat = lat, lon = lon)

    @Test
    fun aPoiPinnedByATapKeepsItsKind() {
        val k = PinnedPoiKinds.byIndex(listOf(start, inn, goal), setOf(1), known = mapOf(inn to "restaurant"))
        assertEquals(mapOf(1 to "restaurant"), k)
    }

    @Test
    fun aPointThatIsNotPinnedNeverGetsAKind() {
        val k = PinnedPoiKinds.byIndex(listOf(start, inn, goal), emptySet(), known = mapOf(inn to "restaurant"))
        assertEquals(emptyMap<Int, String>(), k)
    }

    @Test
    fun aLoadedPlanFindsTheKindInTheIndex() {
        // the vector tile rounds the POI's position by about a metre
        val places = listOf(place("cafe", inn.lat + 0.00001, inn.lon), place("hut", inn.lat + 0.001, inn.lon))
        assertEquals("cafe", PinnedPoiKinds.lookup(inn, places))
        val k = PinnedPoiKinds.byIndex(listOf(start, inn, goal), setOf(1, 2), known = emptyMap(),
            found = mapOf(inn to "cafe"))
        assertEquals(mapOf(1 to "cafe"), k)
    }

    @Test
    fun theNearestPoiWinsAndFarOnesDoNot() {
        val places = listOf(place("bar", inn.lat + 0.00003, inn.lon), place("restaurant", inn.lat, inn.lon + 0.00001))
        assertEquals("restaurant", PinnedPoiKinds.lookup(inn, places))
        assertNull(PinnedPoiKinds.lookup(inn, listOf(place("hut", inn.lat + 0.0001, inn.lon)))) // ~11 m
    }

    @Test
    fun searchOnlyKindsAndUnknownKindsStayPlainPoints() {
        // a peak or a village from search is pinned too, but the map has no POI symbol for it
        assertNull(PinnedPoiKinds.lookup(inn, listOf(place("peak", inn.lat, inn.lon), place("place", inn.lat, inn.lon))))
        assertEquals(emptyMap<Int, String>(),
            PinnedPoiKinds.byIndex(listOf(inn), setOf(0), known = mapOf(inn to "peak")))
    }
}
