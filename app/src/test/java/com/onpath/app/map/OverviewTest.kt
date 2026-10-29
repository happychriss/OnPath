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
        assertEquals(listOf("background", "hillshade-nw~under", "water~under", "rail~under",
            "hillshade-nw~overview", "hillshade-nw", "water~overview", "water",
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

    /** Field test 0.8.7: from z10 the overview goes on under the detailed layers; the cover hides it where cells are. */
    @Test fun overviewGoesOnUnderTheDetailedMapAndIsCoveredWhereCellsAre() {
        val cell = doubleArrayOf(10.8984375, 46.5588603, 11.25, 46.8000594)
        val out = Overview.merge(style, setOf("base.pmtiles", "terrain.pmtiles"), listOf(cell))
        assertEquals(listOf("background", "hillshade-nw~under", "water~under", "rail~under", "detail-cover"), ids(out).take(5))
        val u = layer(out, "water~under")
        assertEquals("overview", u.getString("source"))
        assertEquals(10.0, u.getDouble("minzoom"), 0.0)
        assertFalse(u.has("maxzoom"))
        assertEquals(17.0, layer(out, "hillshade-nw~under").getDouble("maxzoom"), 0.0)
        val cover = layer(out, "detail-cover")
        assertEquals(10.0, cover.getDouble("minzoom"), 0.0)
        assertEquals("#f6f4ec", cover.getJSONObject("paint").getString("fill-color"))
        val ring = JSONObject(out).getJSONObject("sources").getJSONObject("detail-cover").getJSONObject("data")
            .getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals(5, ring.length())
        assertEquals(10.8984375, ring.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(46.8000594, ring.getJSONArray(2).getDouble(1), 1e-9)
    }

    @Test fun overviewLabelsAndUrbanAreasStopAtTheSplit() {
        val s = """{"version":8,"sources":{"base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"}},
          "layers":[{"id":"background","type":"background","paint":{"background-color":"#ffeedd"}},
            {"id":"landuse-residential","type":"fill","source":"base","source-layer":"landuse"},
            {"id":"place-city","type":"symbol","source":"base","source-layer":"place","minzoom":6,"maxzoom":15}]}"""
        val out = Overview.merge(s, setOf("base.pmtiles"), listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)))
        assertEquals(listOf("background", "landuse-residential", "place-city~overview", "place-city"), ids(out))
        assertEquals(10.0, layer(out, "landuse-residential").getDouble("minzoom"), 0.0)
        assertFalse(JSONObject(out).getJSONObject("sources").has("detail-cover"))
    }

    @Test fun preRenderedHillShadeReplacesTheDemCopies() {
        val out = Overview.merge(style, setOf("base.pmtiles", "terrain.pmtiles", "hillshade.pmtiles"), listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)))
        val src = JSONObject(out).getJSONObject("sources")
        assertFalse(src.has("overview-terrain"))
        assertEquals("raster", src.getJSONObject("overview-hillshade").getString("type"))
        assertEquals("pmtiles://asset://overview/hillshade.pmtiles", src.getJSONObject("overview-hillshade").getString("url"))
        // opaque tiles: one raster layer right above the background, every zoom, calmer from z10, under the cover
        val r = layer(out, "hillshade~overview")
        assertEquals("raster", r.getString("type"))
        assertEquals("overview-hillshade", r.getString("source"))
        assertFalse(r.has("maxzoom"))
        assertEquals("interpolate", r.getJSONObject("paint").getJSONArray("raster-opacity").getString(0))
        // hill shading balanced (c), owner 2026-10-22
        assertEquals(0.25, r.getJSONObject("paint").getDouble("raster-contrast"), 1e-9)
        assertEquals(0.9, r.getJSONObject("paint").getDouble("raster-brightness-max"), 1e-9)
        assertEquals(listOf("background", "hillshade~overview", "water~under", "rail~under", "detail-cover"), ids(out).take(5))
        assertFalse("hillshade~under" in ids(out))
        assertEquals("terrain", layer(out, "hillshade-nw").getString("source"))
        assertEquals(10.0, layer(out, "hillshade-nw").getDouble("minzoom"), 0.0)
    }

    @Test fun onlyBundledFilesAreUsed() {
        val out = Overview.merge(style, setOf("terrain.pmtiles"))
        assertTrue("hillshade-nw~overview" in ids(out))
        assertFalse("water~overview" in ids(out))
        assertFalse(JSONObject(out).getJSONObject("sources").has("overview"))
    }

    @Test fun brokenJsonIsReturnedUnchanged() = assertEquals("{nope", Overview.merge("{nope", setOf("base.pmtiles")))

    /** Region packages (map-cells.md §20): the detailed map from z7 inside them, the overview hidden there. */
    private val regionStyle = """{"version":8,"sources":{
        "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"},
        "terrain":{"type":"raster-dem","url":"pmtiles://file:///d/terrain.pmtiles","encoding":"terrarium","tileSize":256},
        "region-shade":{"type":"raster","url":"pmtiles://file:///d/hillshade.pmtiles","tileSize":256}},
      "layers":[{"id":"background","type":"background","paint":{"background-color":"#f6f4ec"}},
        {"id":"region-relief","type":"raster","source":"region-shade","minzoom":7,"maxzoom":10},
        {"id":"water","type":"fill","source":"base","source-layer":"water"},
        {"id":"hillshade-nw","type":"hillshade","source":"terrain","maxzoom":17},
        {"id":"rail","type":"line","source":"base","source-layer":"transportation","minzoom":8},
        {"id":"place-city","type":"symbol","source":"base","source-layer":"place","minzoom":6,"maxzoom":15,
         "filter":["==",["get","class"],"city"]},
        {"id":"place-town","type":"symbol","source":"base","source-layer":"place","minzoom":8,"filter":["==",["get","class"],"town"]}]}"""
    private val files = setOf("base.pmtiles", "terrain.pmtiles", "hillshade.pmtiles")
    private val cell = doubleArrayOf(6.6796875, 51.0828, 7.03125, 51.3066)
    private val pkg = doubleArrayOf(5.625, 50.7365, 8.4375, 52.4828)

    @Test fun regionPackagesBringTheDetailedMapFromZoom7() {
        val out = Overview.merge(regionStyle, files, listOf(cell), listOf(pkg))
        // bottom: overview relief, overview fills / lines (z<10), the packages' opaque relief over them, then from z10
        // the overview under the cells and the cells' cover; the detailed layers above all of it
        assertEquals(listOf("background", "hillshade~overview", "water~overview", "rail~overview", "region-relief",
            "water~under", "rail~under", "detail-cover", "water", "hillshade-nw", "rail",
            "place-city~overview", "place-city~overview~outside", "place-city", "place-town~overview~outside", "place-town"), ids(out))
        assertEquals(7.0, layer(out, "water").getDouble("minzoom"), 0.0)
        assertEquals(8.0, layer(out, "rail").getDouble("minzoom"), 0.0)
        assertEquals(7.0, layer(out, "hillshade-nw").getDouble("minzoom"), 0.0)
        assertEquals(7.0, layer(out, "place-city").getDouble("minzoom"), 0.0) // below z7 the overview names the cities
        // overview labels: as before below z7, from z7 only outside the packages
        assertEquals(7.0, layer(out, "place-city~overview").getDouble("maxzoom"), 0.0)
        val o = layer(out, "place-city~overview~outside")
        assertEquals(7.0, o.getDouble("minzoom"), 0.0); assertEquals(10.0, o.getDouble("maxzoom"), 0.0)
        assertEquals("overview", o.getString("source"))
        val f = o.getJSONArray("filter")
        assertEquals("all", f.getString(0)); assertEquals("==", f.getJSONArray(1).getString(0))
        val within = f.getJSONArray(2)
        assertEquals("!", within.getString(0)); assertEquals("within", within.getJSONArray(1).getString(0))
        val mp = within.getJSONArray(1).getJSONObject(1)
        assertEquals("MultiPolygon", mp.getString("type"))
        assertEquals(5.625, mp.getJSONArray("coordinates").getJSONArray(0).getJSONArray(0).getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(8.0, layer(out, "place-town~overview~outside").getDouble("minzoom"), 0.0)
        assertFalse("place-town~overview" in ids(out)) // starts at z8: nothing to draw below z7
    }

    /** Without packages nothing changes: detailed layers from z10, the packages' relief stays where the style has it. */
    @Test fun withoutPackagesTheSplitStaysAtZoom10() {
        val out = Overview.merge(regionStyle, files, listOf(cell))
        assertEquals(10.0, layer(out, "water").getDouble("minzoom"), 0.0)
        assertEquals(listOf("background", "hillshade~overview", "water~under", "rail~under", "detail-cover", "region-relief"), ids(out).take(6))
        assertTrue(layer(out, "place-city~overview").getDouble("maxzoom") == 10.0)
        assertFalse(ids(out).any { it.endsWith(Overview.OUTSIDE) })
    }
}
