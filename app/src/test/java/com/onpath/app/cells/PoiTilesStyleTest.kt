package com.onpath.app.cells

import com.onpath.app.poi.POI_LAYER_PREFIX
import com.onpath.app.poi.PoiKinds
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The POI tiles the app writes ([PoiTiles]) must hold every POI from the zoom the format-2 style starts drawing its kind
 * (tools/make_style.py POI_TIERS) – otherwise a kind silently disappears at those zooms.
 */
class PoiTilesStyleTest {
    private val f2 = JSONObject(File("src/main/assets/style/f2/onpath.json").readText())
    private val kinds = PoiKinds.all.map { it.key }.toSet()

    private fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is JSONArray -> (0 until v.length()).flatMap { strings(v.get(it)) }
        else -> emptyList()
    }

    @Test fun everyPoiLayerReadsTheTilesFromItsKindsMinZoom() {
        val layers = f2.getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("id").startsWith(POI_LAYER_PREFIX) }
        assertTrue(layers.size >= 10)
        for (l in layers) {
            assertEquals(l.getString("id"), "pois", l.getString("source"))
            assertEquals(l.getString("id"), "pois", l.getString("source-layer"))
            val minZoom = l.optDouble("minzoom", 0.0)
            val drawn = strings(l.opt("filter")).filter { it in kinds }
            assertTrue("${l.getString("id")}: filter names no kind – it would draw every kind", drawn.isNotEmpty())
            for (k in drawn) assertTrue("${l.getString("id")} draws $k from z$minZoom, tiles have it from z${PoiTiles.minZoom(k)}",
                PoiTiles.minZoom(k) <= minZoom)
        }
    }

    @Test fun styleUsesThePoiTilesWithTheGeoJsonAsStandIn() {
        val src = f2.getJSONObject("sources").getJSONObject("pois")
        assertEquals("vector", src.getString("type"))
        assertEquals("pmtiles://file://{DATA_DIR}/${PoiTiles.FILE}", src.getString("url"))
        val alt = f2.getJSONObject("metadata").getJSONObject("onpath:fallback-sources").getJSONObject("pois")
        assertEquals("geojson", alt.getString("type"))
        assertEquals("file://{DATA_DIR}/${CellStore.POIS}", alt.getString("data"))
    }
}
