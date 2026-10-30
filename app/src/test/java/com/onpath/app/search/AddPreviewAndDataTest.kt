package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.WaypointInsertion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AddPreviewAndDataTest {

    private val a = GeoPoint(46.60, 11.10)
    private val b = GeoPoint(46.60, 11.20)

    /** Owner: with only a start the next point is the destination – the button says so, not "Add to route". */
    @Test fun buttonSaysWhatAddingDoes() {
        val e = AddPreview.of(emptyList(), a, null)
        assertEquals("Set as start", e.label); assertEquals("", e.subtitle)
        val one = AddPreview.of(listOf(a), b, null)
        assertEquals("Set as destination", one.label); assertEquals("", one.subtitle)
        // Beside the middle of A→B: best-fit stop 2 with an estimated detour.
        val mid = GeoPoint(46.605, 11.15)
        val v = AddPreview.of(listOf(a, b), mid, null)
        assertEquals(WaypointInsertion.Kind.VIA_BEST_FIT, v.decision.kind)
        assertEquals("Add as stop 2", v.label)
        assertTrue(v.subtitle, v.subtitle.startsWith("fits best there · about +"))
        // Beyond B: new destination, distance about straight line × 1.3.
        val beyond = GeoPoint(46.60, 11.30)
        val d = AddPreview.of(listOf(a, b), beyond, null, speedMps = 1.0)
        assertEquals(WaypointInsertion.Kind.DESTINATION, d.decision.kind)
        assertEquals("Add as new destination", d.label)
        assertEquals(7640.0 * 1.3, d.extraM!!, 150.0)
        assertTrue(d.subtitle, d.subtitle.startsWith("about +9.9 km, +2 h"))
        // On the line.
        val on = AddPreview.of(listOf(a, b), GeoPoint(46.60, 11.15), listOf(a, b))
        assertEquals("Add as stop 2", on.label)
        assertEquals("on your route", on.subtitle)
        assertNull(on.extraM)
    }

    @Test fun extraTextFormats() {
        assertEquals(" · about +400 m", AddPreview.extraText(410.0, null))
        assertEquals(" · about +50 m", AddPreview.extraText(3.0, null))
        assertEquals(" · about +1.2 km, +20 min", AddPreview.extraText(1200.0, 1.0))
        assertEquals("", AddPreview.extraText(null, 1.0))
        assertEquals("is stop 2", AddPreview.existingRole(1, 3))
        assertEquals("is the destination", AddPreview.existingRole(2, 3))
        assertEquals("is the start", AddPreview.existingRole(0, 3))
    }

    @Test fun pmtilesTileIds() {
        assertEquals(Triple(0, 0, 0), BaseMapPlaces.tileIdToZxy(0))
        assertEquals(Triple(1, 0, 0), BaseMapPlaces.tileIdToZxy(1))
        assertEquals(Triple(1, 0, 1), BaseMapPlaces.tileIdToZxy(2))
        assertEquals(Triple(1, 1, 1), BaseMapPlaces.tileIdToZxy(3))
        assertEquals(Triple(1, 1, 0), BaseMapPlaces.tileIdToZxy(4))
        assertEquals(Triple(2, 0, 0), BaseMapPlaces.tileIdToZxy(5))
        assertEquals(21L, BaseMapPlaces.zoomStart(3))
    }

    @Test fun readsPeaksAndPlacesFromBaseMap() {
        val f = File(javaClass.classLoader!!.getResource("search/mini.pmtiles")!!.toURI())
        val items = BaseMapPlaces.read(f)
        val byName = items.associateBy { it.name }
        assertEquals(setOf("Hohe Weiße", "Eisjöchl", "Meran", "Algund"), byName.keys) // cliff + country skipped, duplicate merged
        val peak = byName.getValue("Hohe Weiße")
        assertEquals("peak", peak.kind); assertEquals(3281, peak.ele)
        assertEquals(46.75, peak.lat, 0.0005); assertEquals(11.1, peak.lon, 0.0005)
        assertTrue("Cima Bianca" in peak.alt)
        assertEquals("saddle", byName.getValue("Eisjöchl").kind)
        assertEquals("town", byName.getValue("Meran").sub)
        assertTrue("Merano" in byName.getValue("Meran").alt)
    }

    @Test fun indexWithMissingFilesIsEmptyNotACrash() {
        val dir = File(System.getProperty("java.io.tmpdir"), "onpath-empty-${System.nanoTime()}").apply { mkdirs() }
        val idx = OfflineIndex.load(dir, dir)
        assertTrue(idx.entries.isEmpty())
        assertTrue(idx.search(SearchQuery("meran", null, 46.0, 11.0)).isEmpty())
        // Broken files are skipped too.
        File(dir, OfflineIndex.POIS).writeText("{\"type\":\"FeatureCollection\",\"features\":[{")
        File(dir, OfflineIndex.BASE).writeText("not a pmtiles file at all, but long enough for a header ................................................................................................")
        assertTrue(OfflineIndex.load(dir, dir).entries.isEmpty())
    }

    @Test fun indexReadsGeoJsonAndCachesBaseMap() {
        val dir = File(System.getProperty("java.io.tmpdir"), "onpath-idx-${System.nanoTime()}").apply { mkdirs() }
        File(dir, OfflineIndex.POIS).writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.0915,46.7321]},"properties":{"id":"n1","kind":"hut","name":"Meraner Hütte","ele":"1960","opening_hours":"Jun-Oct"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.10,46.70]},"properties":{"id":"n2","kind":"water"}}]}""")
        File(dir, OfflineIndex.TRAILS).writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[11.10,46.68],[11.11,46.68],[11.12,46.68]]},"properties":{"t":"n","name":"Algunder Waalweg","tier":1,"len":1500}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[11.12,46.68],[11.13,46.68]]},"properties":{"t":"n","name":"Algunder Waalweg","tier":1,"len":700}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[11.10,46.68],[11.11,46.68]]},"properties":{"t":"r","ref":"24"}}]}""")
        javaClass.classLoader!!.getResourceAsStream("search/mini.pmtiles")!!.use { File(dir, OfflineIndex.BASE).outputStream().use(it::copyTo) }
        val idx = OfflineIndex.load(dir, dir)
        val kinds = idx.entries.groupingBy { it.place.kind }.eachCount()
        assertEquals(1, kinds["hut"]); assertEquals(1, kinds["water"]); assertEquals(1, kinds["trail"]) // parts merged
        assertEquals(1, kinds["peak"]); assertEquals(2, kinds["place"])
        val hut = idx.entries.first { it.place.kind == "hut" }.place
        assertEquals(1960, hut.ele); assertEquals(listOf("Jun-Oct"), hut.openingHoursLines)
        assertNotNull(File(dir, "search-places-v1.tsv").takeIf { it.isFile })
        // Second load comes from the cache, same result.
        assertEquals(idx.entries.size, OfflineIndex.load(dir, dir).entries.size)
        assertEquals("Algunder Waalweg", idx.search(SearchQuery("algunder waal", null, 46.68, 11.11)).first().place.name)
        assertEquals("Meran", idx.search(SearchQuery("merano", null, 46.68, 11.11)).first().place.name)
    }

    @Test fun cacheLineRoundTrip() {
        val i = BaseMapPlaces.Item("Hohe\tWeiße", listOf("Cima | Bianca"), "peak", null, 46.75, 11.1, 3281)
        val back = OfflineIndex.parseCacheLine(OfflineIndex.cacheLine(i))!!
        assertEquals("Hohe Weiße", back.name); assertEquals(listOf("Cima / Bianca"), back.alt); assertEquals(3281, back.ele)
        assertNull(back.sub)
    }
}
