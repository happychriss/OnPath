package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewTest {
    private val style = """{"version":8,"sources":{
        "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles","attribution":"© OSM"},
        "terrain":{"type":"raster-dem","url":"pmtiles://file:///d/terrain.pmtiles","encoding":"terrarium","tileSize":256},
        "routes":{"type":"geojson","data":"file:///d/routes.geojson"}},
      "layers":[{"id":"background","type":"background"},
        {"id":"hillshade-nw","type":"hillshade","source":"terrain","maxzoom":17},
        {"id":"water","type":"fill","source":"base","source-layer":"water"},
        {"id":"rail","type":"line","source":"base","source-layer":"transportation","minzoom":8},
        {"id":"buildings","type":"fill","source":"base","source-layer":"building","minzoom":14},
        {"id":"lowonly","type":"line","source":"base","source-layer":"boundary","maxzoom":6},
        {"id":"routes-hike","type":"line","source":"routes"}]}"""

    private fun layers(json: String) = JSONObject(json).getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun ids(json: String) = layers(json).map { it.getString("id") }
    private fun layer(json: String, id: String) = layers(json).first { it.getString("id") == id }

    @Test fun noOverviewLeavesStyleUnchanged() = assertEquals(style, Overview.merge(style, emptySet()))

    @Test fun lowZoomLayersComeFromOverview() {
        val out = Overview.merge(style, setOf("base.pmtiles", "terrain.pmtiles"))
        assertEquals(listOf("background", "hillshade-nw~overview", "hillshade-nw", "water~overview", "water",
            "rail~overview", "rail", "buildings", "lowonly~overview", "routes-hike"), ids(out))
        val src = JSONObject(out).getJSONObject("sources")
        assertEquals("pmtiles://asset://overview/base.pmtiles", src.getJSONObject("overview").getString("url"))
        assertEquals("terrarium", src.getJSONObject("overview-terrain").getString("encoding"))
        assertEquals("overview", layer(out, "water~overview").getString("source"))
        assertEquals("overview-terrain", layer(out, "hillshade-nw~overview").getString("source"))
        assertEquals(10.0, layer(out, "water~overview").getDouble("maxzoom"), 0.0)
        assertEquals(10.0, layer(out, "water").getDouble("minzoom"), 0.0)
        assertEquals(8.0, layer(out, "rail~overview").getDouble("minzoom"), 0.0)
        assertEquals(6.0, layer(out, "lowonly~overview").getDouble("maxzoom"), 0.0)
        assertFalse(layer(out, "buildings").has("maxzoom"))
        assertFalse(layer(out, "routes-hike").has("minzoom"))
    }

    @Test fun onlyBundledFilesAreUsed() {
        val out = Overview.merge(style, setOf("terrain.pmtiles"))
        assertTrue("hillshade-nw~overview" in ids(out))
        assertFalse("water~overview" in ids(out))
        assertFalse(JSONObject(out).getJSONObject("sources").has("overview"))
    }

    @Test fun brokenJsonIsReturnedUnchanged() = assertEquals("{nope", Overview.merge("{nope", setOf("base.pmtiles")))
}
