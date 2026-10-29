package com.onpath.app.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionalSourcesTest {
    private val style = """
        {"version":8,"sources":{
          "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"},
          "contours":{"type":"vector","url":"pmtiles://file:///d/contours.pmtiles"},
          "trailnames":{"type":"geojson","data":"file:///d/trailnames.geojson"},
          "pois":{"type":"geojson","data":"file:///d/pois.geojson"}},
         "layers":[{"id":"bg","type":"background"},
          {"id":"roads","type":"line","source":"base"},
          {"id":"contour-minor","type":"line","source":"contours"},
          {"id":"trail-name","type":"symbol","source":"trailnames"},
          {"id":"poi-tier1","type":"symbol","source":"pois"}]}
    """.trimIndent()

    private fun ids(json: String) = JSONObject(json).getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    @Test fun missingOptionalFilesAreStripped() {
        val out = OptionalSources.strip(style) { it == "/d/base.pmtiles" || it == "/d/pois.geojson" }
        assertEquals(listOf("bg", "roads", "poi-tier1"), ids(out))
        val src = JSONObject(out).getJSONObject("sources")
        assertFalse(src.has("contours")); assertFalse(src.has("trailnames")); assertTrue(src.has("pois"))
    }

    @Test fun essentialSourcesAreKeptEvenIfMissing() {
        val out = OptionalSources.strip(style) { false }
        assertEquals(listOf("bg", "roads"), ids(out))
    }

    @Test fun allPresentReturnsInputUnchanged() = assertEquals(style, OptionalSources.strip(style) { true })

    private val withFallback = """
        {"version":8,"sources":{
          "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"},
          "paths":{"type":"geojson","data":"file:///d/paths.geojson"}},
         "layers":[{"id":"path-base","type":"line","source":"base","metadata":{"onpath:fallback-for":"paths"}},
          {"id":"paths-own","type":"line","source":"paths"},
          {"id":"roads","type":"line","source":"base"}]}
    """.trimIndent()

    @Test fun fallbackLayerOnlyWhenItsSourceIsMissing() {
        assertEquals(listOf("paths-own", "roads"), ids(OptionalSources.strip(withFallback) { true }))
        assertEquals(listOf("path-base", "roads"), ids(OptionalSources.strip(withFallback) { it == "/d/base.pmtiles" }))
    }

    @Test fun brokenJsonIsReturnedAsIs() = assertEquals("{nope", OptionalSources.strip("{nope") { false })

    /** Format 2: POIs as tiles, the GeoJSON as stand-in (data folder / store without POI tiles). */
    private val poiTiles = """
        {"version":8,"metadata":{"onpath:fallback-sources":{"pois":{"type":"geojson","data":"file:///d/pois.geojson","maxzoom":14}}},
         "sources":{
          "base":{"type":"vector","url":"pmtiles://file:///d/base.pmtiles"},
          "pois":{"type":"vector","url":"pmtiles://file:///d/pois.pmtiles"}},
         "layers":[{"id":"roads","type":"line","source":"base","source-layer":"transportation"},
          {"id":"poi-tier1","type":"symbol","source":"pois","source-layer":"pois"}]}
    """.trimIndent()

    @Test fun poiTilesUsedWhenPresent() = assertEquals(poiTiles, OptionalSources.strip(poiTiles) { true })

    @Test fun poiGeoJsonStandsInForMissingTiles() {
        val out = JSONObject(OptionalSources.strip(poiTiles) { it != "/d/pois.pmtiles" })
        val src = out.getJSONObject("sources").getJSONObject("pois")
        assertEquals("geojson", src.getString("type")); assertEquals("file:///d/pois.geojson", src.getString("data"))
        assertEquals(listOf("roads", "poi-tier1"), ids(out.toString()))
        val layers = out.getJSONArray("layers")
        assertFalse(layers.getJSONObject(1).has("source-layer"))
        assertEquals("transportation", layers.getJSONObject(0).getString("source-layer"))
    }

    @Test fun noPoisAtAllStripsThePoiLayers() {
        val out = OptionalSources.strip(poiTiles) { it == "/d/base.pmtiles" }
        assertEquals(listOf("roads"), ids(out))
        assertFalse(JSONObject(out).getJSONObject("sources").has("pois"))
    }
}
