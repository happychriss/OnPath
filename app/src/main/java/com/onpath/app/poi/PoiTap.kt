package com.onpath.app.poi

import android.content.res.Resources
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.io.File

/**
 * Returns the POI under [screenPoint] (pixels, as from projection.toScreenLocation), searching a
 * ~24 dp box on all style layers whose id starts with "poi-". Nearest hit wins. Never throws.
 */
fun findPoiAt(map: MapLibreMap, screenPoint: PointF, boxDp: Float = 24f): Poi? = try {
    val style = map.style
    val ids = style?.layers?.map { it.id }?.filter { it.startsWith(POI_LAYER_PREFIX) }.orEmpty()
    if (style == null || ids.isEmpty()) {
        null
    } else {
        val half = boxDp * Resources.getSystem().displayMetrics.density / 2f
        val box = RectF(screenPoint.x - half, screenPoint.y - half, screenPoint.x + half, screenPoint.y + half)
        val features = map.queryRenderedFeatures(box, *ids.toTypedArray())
        features.mapNotNull { Poi.fromFeature(it) }
            .distinctBy { it.id }
            .minByOrNull { p ->
                val s = map.projection.toScreenLocation(LatLng(p.lat, p.lon))
                val dx = s.x - screenPoint.x
                val dy = s.y - screenPoint.y
                dx * dx + dy * dy
            }
    }
} catch (e: Exception) {
    Log.w("OnPath", "POI query failed: $e")
    null
}

/**
 * pois.geojson is optional. If it is missing in [dataDir], removes the "pois" source and every
 * "poi-" layer from the style JSON so MapLibre never tries to load a missing file. Returns the
 * input unchanged on any error.
 */
fun stripPoisIfMissing(styleJson: String, dataDir: File): String {
    if (File(dataDir, POI_DATA_FILE).isFile) return styleJson
    return try {
        val root = JSONObject(styleJson)
        root.optJSONObject("sources")?.remove(POI_SOURCE_ID)
        val layers = root.optJSONArray("layers") ?: return styleJson
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val l = layers.optJSONObject(i) ?: continue
            if (l.optString("source") == POI_SOURCE_ID || l.optString("id").startsWith(POI_LAYER_PREFIX)) continue
            kept.put(l)
        }
        root.put("layers", kept)
        root.toString()
    } catch (e: Exception) {
        Log.w("OnPath", "could not strip POI layers: $e")
        styleJson
    }
}
