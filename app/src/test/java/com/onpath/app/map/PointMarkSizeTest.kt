package com.onpath.app.map

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-27 (Pixel, round trip at Hilden: "15 14 1 10" stacked at the start, the points never got smaller):
 * the plan points are about 20 % bigger than the map's POI symbols at every zoom, never under 20 dp (variant 2).
 */
class PointMarkSizeTest {
    @Test fun twentyPercentOverThePoiSymbolsNeverUnderTwentyDp() {
        for ((z, s) in PointMarkSize.stops()) {
            val dp = s * PointMarkSize.MARKER_DP
            val poiDp = PointMarkSize.POI_SIZE.first { it.first == z }.second * PointMarkSize.POI_DISC_DP
            assertTrue("zoom $z: $dp dp", dp >= PointMarkSize.MIN_DP - 0.01f)
            assertTrue("zoom $z: $dp dp vs POI $poiDp dp", dp >= 1.2f * poiDp - 0.01f)
        }
        // out in the region view a third smaller than the old fixed 30 dp, close up nearly as before
        assertEquals(20f, PointMarkSize.stops().first().second * PointMarkSize.MARKER_DP, 0.01f)
        assertEquals(26.46f, PointMarkSize.stops().last().second * PointMarkSize.MARKER_DP, 0.05f)
    }

    /** The curve is the style's: a change of the POI sizes in onpath.json must move the points too. */
    @Test fun thePoiCurveIsTheStyles() {
        val style = JSONObject(File("src/main/assets/style/onpath.json").readText())
        val layers = style.getJSONArray("layers")
        val tier1 = (0 until layers.length()).map { layers.getJSONObject(it) }.first { it.getString("id") == "poi-tier1" }
        val e = tier1.getJSONObject("layout").get("icon-size") as JSONArray
        val stops = (3 until e.length() step 2).map { e.getDouble(it).toFloat() to e.getDouble(it + 1).toFloat() }
        assertEquals(PointMarkSize.POI_SIZE, stops)
    }

    @Test fun bothPointLayersUseIt() {
        val ctl = File("src/main/java/com/onpath/app/map/MapController.kt").readText()
        val over = File("src/main/java/com/onpath/app/map/PointsOverDot.kt").readText()
        assertTrue(ctl.contains("SymbolLayer(LAYER_WPT, SRC_WPT).withProperties(*PointMarkSize.layerProps())"))
        assertTrue(over.contains("SymbolLayer(LAYER, source).withProperties(*PointMarkSize.layerProps())"))
    }
}
