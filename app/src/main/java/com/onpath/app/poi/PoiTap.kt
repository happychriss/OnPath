package com.onpath.app.poi

import android.content.res.Resources
import android.graphics.PointF
import android.graphics.RectF
import com.onpath.app.debug.Problems
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Returns the POI under [screenPoint] (pixels, as from projection.toScreenLocation), searching a
 * ~24 dp box on all style layers whose id starts with "poi-" (icon layers and their "-name" layers, so a tap on
 * the icon or on the name finds it). Nearest hit wins. Never throws.
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
    Problems.warn("poi", "POI lookup at the tapped point failed, tap treated as no POI", e)
    null
}

