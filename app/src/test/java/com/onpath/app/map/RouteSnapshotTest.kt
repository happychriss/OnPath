package com.onpath.app.map

import com.onpath.app.routing.GeoPoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overview's map picture: the route goes into the style JSON itself (the snapshotter rejects builder sources). */
class RouteSnapshotTest {
    private val style = """{"version":8,"sources":{"base":{"type":"vector","url":"pmtiles://x"}},"layers":[{"id":"bg","type":"background"}]}"""

    @Test fun theLineGoesOnTopOfTheStyleAsWideAsTheMapsRoute() {
        val seg = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.61, 11.12))
        val o = JSONObject(RouteSnapshot.withLine(style, listOf(seg), 0xFF335A85.toInt()))
        val sources = o.getJSONObject("sources")
        assertTrue("the style's own sources stay", sources.has("base"))
        val line = sources.getJSONObject("overview-line").getJSONObject("data").getJSONObject("geometry")
        assertEquals("MultiLineString", line.getString("type"))
        assertEquals(11.1, line.getJSONArray("coordinates").getJSONArray(0).getJSONArray(0).getDouble(0), 1e-9)
        val layers = o.getJSONArray("layers")
        assertEquals("bg first, the route on top", "bg", layers.getJSONObject(0).getString("id"))
        val top = layers.getJSONObject(layers.length() - 1).getJSONObject("paint")
        val casing = layers.getJSONObject(layers.length() - 2).getJSONObject("paint")
        assertEquals("#335A85", top.getString("line-color"))
        // in dp like the map's planned route – the snapshot's pixel ratio makes them pixels
        assertEquals(RouteLook.LINE_DP.toDouble(), top.getDouble("line-width"), 1e-9)
        assertEquals("#FFFFFF", casing.getString("line-color"))
        assertEquals(RouteLook.CASING_DP.toDouble(), casing.getDouble("line-width"), 1e-9)
    }

    /** A recorded tour's followed plan: thin, plan blue, faint, beneath the track's casing and line (owner 2026-11-07). */
    @Test fun theFollowedPlanGoesUnderTheTrack() {
        val seg = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.61, 11.12))
        val plan = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.605, 11.13), GeoPoint(46.61, 11.12))
        val o = JSONObject(RouteSnapshot.withLine(style, listOf(seg), 0xFF245F46.toInt(), plan, 0xFF335A85.toInt()))
        assertEquals(3, o.getJSONObject("sources").getJSONObject("overview-plan").getJSONObject("data").getJSONObject("geometry")
            .getJSONArray("coordinates").length())
        val layers = o.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertEquals(listOf("bg", "overview-plan", "overview-line-case", "overview-line"), ids)
        val paint = layers.getJSONObject(1).getJSONObject("paint")
        assertEquals("#335A85", paint.getString("line-color"))
        assertEquals(RouteLook.PLAN_UNDER_DP.toDouble(), paint.getDouble("line-width"), 1e-9)
        assertTrue(paint.getDouble("line-width") < RouteLook.LINE_DP)
        assertEquals(RouteLook.PLAN_UNDER_OPACITY.toDouble(), paint.getDouble("line-opacity"), 1e-6)
        // without a plan nothing is added
        assertTrue(!JSONObject(RouteSnapshot.withLine(style, listOf(seg), 0)).getJSONObject("sources").has("overview-plan"))
    }

    /** My routes' small map pictures (owner 2026-11-13, L1): the same line, thinner ([ThumbRules.LINE_SCALE]). */
    @Test fun aSmallPictureDrawsTheLineThinner() {
        val seg = listOf(GeoPoint(46.6, 11.1), GeoPoint(46.61, 11.12))
        val layers = JSONObject(RouteSnapshot.withLine(style, listOf(seg), 0, lineScale = ThumbRules.LINE_SCALE)).getJSONArray("layers")
        assertEquals(RouteLook.LINE_DP * ThumbRules.LINE_SCALE.toDouble(), layers.getJSONObject(layers.length() - 1).getJSONObject("paint").getDouble("line-width"), 1e-6)
        assertEquals(RouteLook.CASING_DP * ThumbRules.LINE_SCALE.toDouble(), layers.getJSONObject(layers.length() - 2).getJSONObject("paint").getDouble("line-width"), 1e-6)
    }

    @Test fun aOnePointSegmentIsLeftOut() {
        val o = JSONObject(RouteSnapshot.withLine(style, listOf(listOf(GeoPoint(46.6, 11.1)), listOf(GeoPoint(46.6, 11.1), GeoPoint(46.7, 11.2))), 0))
        assertEquals(1, o.getJSONObject("sources").getJSONObject("overview-line").getJSONObject("data").getJSONObject("geometry")
            .getJSONArray("coordinates").length())
    }

    /** Field 2026-11-07: the picture was asked in pixels at ratio 1 – a 5 px line on a 1080 px wide picture. */
    @Test fun thePictureIsAskedInDpWithThePhonesDensity() {
        val mdpi = RouteSnapshot.Scale.of(1000, 1500, 48, 1f)!!
        assertEquals(RouteSnapshot.Scale(1000, 1500, 48, 1f), mdpi)
        assertEquals(6f, mdpi.linePx, 1e-4f)

        val pixel = RouteSnapshot.Scale.of(1008, 1575, 126, 2.625f)!! // a Pixel 9: 384 × 600 dp
        assertEquals(384, pixel.width); assertEquals(600, pixel.height); assertEquals(48, pixel.padding)
        assertEquals(2.625f, pixel.ratio, 0f)
        assertEquals("the line on the bitmap: 6 dp", 15.75f, pixel.linePx, 1e-4f)
        assertEquals(26.25f, pixel.casingPx, 1e-4f)

        val xxxhdpi = RouteSnapshot.Scale.of(1400, 2100, 168, 3.5f)!!
        assertEquals(400, xxxhdpi.width); assertEquals(600, xxxhdpi.height); assertEquals(48, xxxhdpi.padding)
        assertEquals(21f, xxxhdpi.linePx, 1e-4f)
    }

    @Test fun noPictureForNoSpaceAndNoNonsenseDensity() {
        assertNull(RouteSnapshot.Scale.of(0, 500, 10, 2f))
        assertNull(RouteSnapshot.Scale.of(1, 500, 10, 3f)) // under one dp wide
        assertEquals("a broken density counts as 1", 1f, RouteSnapshot.Scale.of(300, 300, 10, Float.NaN)!!.ratio, 0f)
        assertEquals("padding never eats the picture", 100, RouteSnapshot.Scale.of(300, 300, 900, 1f)!!.padding)
    }
}
