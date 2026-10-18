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

    @Test fun brokenJsonIsReturnedAsIs() = assertEquals("{nope", OptionalSources.strip("{nope") { false })
}
