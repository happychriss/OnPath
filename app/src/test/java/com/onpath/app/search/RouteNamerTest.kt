package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNamerTest {

    private fun place(name: String, kind: String, lat: Double, lon: Double, sub: String? = null) =
        Place("$kind:$name", name, kind, lat, lon, sub = sub)

    // Roughly the real layout around Meran (1° lat ≈ 111 km, 1° lon ≈ 76 km here).
    private val algund = place("Algund", "place", 46.681, 11.126, "village")
    private val meran = place("Meran", "place", 46.670, 11.160, "town")
    private val vellau = place("Vellau", "place", 46.695, 11.110, "hamlet")
    private val leiterAlm = place("Leiter Alm", "restaurant", 46.7000, 11.1000)
    private val hochmuth = place("Hochmuth", "lift", 46.700, 11.130)
    private val mutspitze = place("Mutspitze", "peak", 46.7300, 11.1300)
    private val pizzeria = place("Pizzeria Sole", "restaurant", 46.6812, 11.1262)
    private val places = listOf(algund, meran, vellau, leiterAlm, hochmuth, mutspitze, pizzeria)

    private fun p(lat: Double, lon: Double) = GeoPoint(lat, lon)
    private val inAlgund = p(46.682, 11.127)
    private val inMeran = p(46.671, 11.161)

    @Test fun aToB() {
        assertEquals("Algund → Meran", RouteNamer.name(listOf(inAlgund, p(46.676, 11.14), inMeran), places))
    }

    @Test fun loopIsRoundTrip() {
        val line = listOf(inAlgund, p(46.70, 11.10), p(46.730, 11.13), p(46.6825, 11.1275))
        assertEquals("Round trip from Algund", RouteNamer.name(line, places))
    }

    @Test fun poiNearEndBeatsVillage() {
        // ends 30 m from the Alm, Vellau (hamlet) ~1 km, Algund ~2.5 km: the Alm wins (no village within 1 km)
        val line = listOf(inAlgund, p(46.6998, 11.1003))
        assertEquals("Algund → Leiter Alm", RouteNamer.name(line, places))
    }

    @Test fun peakNearEnd() {
        assertEquals("Algund → Mutspitze", RouteNamer.name(listOf(inAlgund, p(46.7301, 11.1301)), places))
    }

    @Test fun innInVillageDoesNotBeatVillage() {
        // start right at the pizzeria in Algund: the village name is the better label
        assertEquals("Algund → Meran", RouteNamer.name(listOf(p(46.6812, 11.1262), inMeran), places))
    }

    @Test fun pinnedStopNamesWinAndViaWhenShort() {
        val line = listOf(inAlgund, p(46.70, 11.10), p(46.700, 11.130))
        assertEquals("Algund → Hochmuth (via Leiter Alm)",
            RouteNamer.name(line, places, endName = "Hochmuth", viaNames = listOf("Leiter Alm")))
        val long = RouteNamer.name(line, places, endName = "Hochmuth", viaNames = listOf("Gasthaus zur schönen Aussicht am Berg"))
        assertEquals("Algund → Hochmuth", long)
    }

    @Test fun sameStartAndEndPlaceNotALoop() {
        // starts and ends in Algund but ~1 km apart, Mutspitze at the far end
        val line = listOf(inAlgund, p(46.7300, 11.1300), p(46.674, 11.120))
        assertEquals("Algund – Mutspitze – Algund", RouteNamer.name(line, places))
        val noTop = listOf(inAlgund, p(46.676, 11.112), p(46.674, 11.120))
        assertEquals("Round trip from Algund", RouteNamer.name(noTop, places))
    }

    @Test fun nothingCloseUsesNear() {
        // 5 km north-west of Algund in the forest, ending in Meran
        val name = RouteNamer.name(listOf(p(46.72, 11.07), inMeran), places)
        assertEquals("near Algund → Meran", name)
    }

    @Test fun nothingNearbyGivesNull() {
        assertNull(RouteNamer.name(listOf(p(47.5, 12.5), p(47.6, 12.6)), places))
    }

    @Test fun missingIndexUsesStopNamesOnly() {
        assertNull(RouteNamer.name(listOf(inAlgund, inMeran), emptyList()))
        assertEquals("To Leiter Alm", RouteNamer.name(listOf(inAlgund, inMeran), emptyList(), endName = "Leiter Alm"))
        assertNull(RouteNamer.name(listOf(inAlgund), places))
    }

    @Test fun namesStayShort() {
        val longA = place("Sankt Martin im Kofel bei Latsch im Vinschgau", "place", 46.681, 11.126, "village")
        val longB = place("Unsere Liebe Frau im Walde-Sankt Felix", "place", 46.670, 11.160, "town")
        val n = RouteNamer.name(listOf(inAlgund, inMeran), listOf(longA, longB))!!
        assertTrue(n, n.length <= RouteNamer.MAX_LEN + 15)
        assertTrue(n.endsWith("…"))
    }
}
