package com.onpath.app.map

import com.onpath.app.routing.GeoPoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overview's map picture: the route goes into the style JSON itself (the snapshotter rejects builder sources). */
class RouteSnapshotTest {
    private val style = """{"version":8,"sources":{"base":{"type":"vector","url":"pmtiles://x"}},"layers":[{"id":"bg","type":"background"}]}"""

    @Test fun theLineAndItsEndsGoOnTopOfTheStyle() {
        val seg = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.61, 11.12))
        val o = JSONObject(RouteSnapshot.withLine(style, listOf(seg), seg, 0xFF2B4C6F.toInt()))
        val sources = o.getJSONObject("sources")
        assertTrue("the style's own sources stay", sources.has("base"))
        val line = sources.getJSONObject("overview-line").getJSONObject("data").getJSONObject("geometry")
        assertEquals("MultiLineString", line.getString("type"))
        assertEquals(11.1, line.getJSONArray("coordinates").getJSONArray(0).getJSONArray(0).getDouble(0), 1e-9)
        val layers = o.getJSONArray("layers")
        assertEquals("bg first, the route on top", "bg", layers.getJSONObject(0).getString("id"))
        assertEquals("overview-ends", layers.getJSONObject(layers.length() - 1).getString("id"))
        assertEquals("#2B4C6F", layers.getJSONObject(layers.length() - 2).getJSONObject("paint").getString("line-color"))
    }

    @Test fun aOnePointSegmentIsLeftOut() {
        val o = JSONObject(RouteSnapshot.withLine(style, listOf(listOf(GeoPoint(46.6, 11.1)), listOf(GeoPoint(46.6, 11.1), GeoPoint(46.7, 11.2))),
            listOf(GeoPoint(46.6, 11.1), GeoPoint(46.7, 11.2)), 0))
        assertEquals(1, o.getJSONObject("sources").getJSONObject("overview-line").getJSONObject("data").getJSONObject("geometry")
            .getJSONArray("coordinates").length())
    }
}
