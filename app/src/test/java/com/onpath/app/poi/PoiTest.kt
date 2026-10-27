package com.onpath.app.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiTest {

    // The old single "name lat,lon" search query is gone: Google searched that text without any bias to the
    // coordinates and often landed elsewhere (see PoiLinks.googleMaps KDoc).

    @Test
    fun googleMapsNamedSearchesNearTheCoordinate() {
        val l = PoiLinks.googleMaps("Schloss Tirol", 46.723, 11.12345)
        assertEquals("geo:46.723,11.12345?z=16&q=Schloss%20Tirol", l.appUri)
        assertEquals("https://www.google.com/maps/search/Schloss%20Tirol/@46.723,11.12345,16z", l.webUrl)
    }

    @Test
    fun googleMapsUnnamedIsAnExactPin() {
        val l = PoiLinks.googleMaps(null, 46.5, 11.25)
        assertEquals("geo:0,0?q=46.5,11.25", l.appUri)
        assertEquals("https://www.google.com/maps/search/?api=1&query=46.5%2C11.25", l.webUrl)
        assertEquals(l, PoiLinks.googleMaps("  ", 46.5, 11.25))
        assertEquals("geo:0,0?q=46.5,11.25(Aussicht%20Nord)", PoiLinks.googleMaps(null, 46.5, 11.25, " Aussicht (Nord) ").appUri)
    }

    @Test
    fun googleMapsEncodesUmlautsAndAmpersand() {
        val l = PoiLinks.googleMaps("Gasthof  Rößl & Café", 46.1, 11.2)
        assertEquals("geo:46.1,11.2?z=16&q=Gasthof%20R%C3%B6%C3%9Fl%20%26%20Caf%C3%A9", l.appUri)
    }

    @Test
    fun googleMapsPoiUsesKindLabelOnlyWhenUnnamed() {
        val named = Poi("n1", "restaurant", "Gasthaus Brunner", 46.68, 11.13)
        assertEquals("geo:46.68,11.13?z=16&q=Gasthaus%20Brunner", PoiLinks.googleMaps(named).appUri)
        val unnamed = Poi("n2", "viewpoint", null, 46.6801234567, 11.1)
        assertTrue(PoiLinks.googleMaps(unnamed).appUri.startsWith("geo:0,0?q=46.680123,11.1("))
    }

    @Test
    fun wikipedia() {
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Tirol", PoiLinks.wikipediaUrl("de:Schloss Tirol"))
        assertEquals("https://it.wikipedia.org/wiki/Castel_Tirolo", PoiLinks.wikipediaUrl("it:Castel_Tirolo"))
        assertEquals("https://de.wikipedia.org/wiki/K%C3%BCchelberg", PoiLinks.wikipediaUrl("de:Küchelberg"))
        assertEquals("https://en.wikipedia.org/wiki/Merano", PoiLinks.wikipediaUrl("Merano"))
        assertEquals("https://x.org/a", PoiLinks.wikipediaUrl("https://x.org/a"))
        assertNull(PoiLinks.wikipediaUrl(" "))
        assertEquals("Schloss Tirol (de)", PoiLinks.wikipediaLabel("de:Schloss Tirol"))
    }

    @Test
    fun websiteAndPhone() {
        assertEquals("https://www.huette.it", PoiLinks.websiteUrl("www.huette.it"))
        assertEquals("http://a.it/x", PoiLinks.websiteUrl("http://a.it/x;https://b.it"))
        assertEquals("huette.it", PoiLinks.websiteLabel("https://www.huette.it/"))
        assertEquals("tel:+390473123456", PoiLinks.phoneUri("+39 0473 123 456; +39 333 1"))
        assertNull(PoiLinks.phoneUri("n/a"))
    }

    @Test
    fun fromProperties() {
        val p = Poi.fromProperties(
            mapOf("id" to "n42", "kind" to "hut", "name" to "Hochganghaus", "ele" to 1839,
                "opening_hours" to "May-Oct 08:00-18:00; Nov off", "phone" to "+39 0473 443310",
                "cuisine" to "regional;pizza"),
            46.71, 11.08,
        )!!
        assertEquals("n42", p.id)
        assertEquals("hut", p.kind)
        assertEquals(1839, p.ele)
        assertEquals(listOf("May-Oct 08:00-18:00", "Nov off"), p.openingHoursLines)
        assertEquals("Regional, Pizza", p.cuisineLabel)
        assertEquals("Hochganghaus", p.title(german = true))
        assertEquals("https://www.openstreetmap.org/node/42", p.osmUrl)
        assertNull(p.website)
    }

    @Test
    fun fromPropertiesUnnamedAndStringEle() {
        val p = Poi.fromProperties(mapOf("id" to "w7", "kind" to "water", "ele" to "1200.6"), 1.0, 2.0)!!
        assertEquals(1201, p.ele)
        assertEquals("Trinkwasser", p.title(german = true))
        assertEquals("Drinking water", p.title(german = false))
        assertNull(Poi.fromProperties(mapOf("name" to "x"), 1.0, 2.0))
    }

    @Test
    fun fromGeoJsonFeature() {
        val f = org.maplibre.geojson.Feature.fromJson(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[11.1,46.7]},""" +
                """"properties":{"id":"n1","kind":"viewpoint","name":"Aussicht","ele":812,"wikipedia":"de:X"}}""")
        val p = Poi.fromFeature(f)!!
        assertEquals(46.7, p.lat, 1e-9)
        assertEquals(11.1, p.lon, 1e-9)
        assertEquals(812, p.ele)
        assertEquals("Aussicht", p.name)
        assertEquals("https://de.wikipedia.org/wiki/X", PoiLinks.wikipediaUrl(p.wikipedia))
    }

    @Test
    fun unknownKindFallsBack() {
        val k = PoiKinds.of("ski_rental")
        assertEquals(PoiGroup.OTHER, k.group)
        assertEquals("Ski rental", k.en)
    }
}
