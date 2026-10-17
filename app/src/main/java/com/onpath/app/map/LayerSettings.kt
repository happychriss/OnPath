package com.onpath.app.map

import android.util.Log
import com.onpath.app.data.AppSettings
import com.onpath.app.poi.POI_LAYER_PREFIX
import com.onpath.app.poi.PoiFilter
import com.onpath.app.poi.PoiKinds
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Applies the Settings page to the base style: POI kind filters (keeping each poi- layer's
 * original filter, see docs/architecture-contract.md) and contour / hillshade visibility.
 * Never throws – a broken layer just keeps its look.
 */
class LayerSettings(private val style: Style) {

    /** Original filter JSON per poi- layer (null = layer had no filter). */
    private val originalPoiFilters: Map<String, String?> = readPoiFilters()
    private var lastHidden: Set<String>? = null

    fun apply(s: AppSettings) {
        if (s.hiddenPoiKinds != lastHidden) { applyPoi(s.hiddenPoiKinds); lastHidden = s.hiddenPoiKinds }
        setVisible("contour-", s.contours)
        setVisible("hillshade", s.hillshade)
    }

    private fun applyPoi(hidden: Set<String>) {
        val known = PoiKinds.all.map { it.key }
        for ((id, orig) in originalPoiFilters) {
            try {
                val json = PoiFilter.layerFilter(orig, known, hidden)
                val expr = if (json == null) Expression.literal(true) else Expression.raw(json)
                setFilter(style.getLayer(id) ?: continue, expr)
            } catch (e: Exception) {
                Log.w("OnPath", "POI filter on $id failed: $e")
            }
        }
    }

    private fun setVisible(prefix: String, visible: Boolean) {
        style.layers.filter { it.id.startsWith(prefix) }.forEach {
            runCatching { it.setProperties(PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)) }
        }
    }

    private fun setFilter(layer: Layer, e: Expression) {
        when (layer) {
            is SymbolLayer -> layer.setFilter(e)
            is CircleLayer -> layer.setFilter(e)
            is LineLayer -> layer.setFilter(e)
            is FillLayer -> layer.setFilter(e)
        }
    }

    private fun readPoiFilters(): Map<String, String?> = try {
        val layers = JSONObject(style.json).optJSONArray("layers")
        buildMap {
            for (i in 0 until (layers?.length() ?: 0)) {
                val l = layers!!.optJSONObject(i) ?: continue
                val id = l.optString("id")
                if (id.startsWith(POI_LAYER_PREFIX)) put(id, l.opt("filter")?.toString())
            }
        }
    } catch (e: Exception) {
        Log.w("OnPath", "could not read POI filters: $e")
        emptyMap()
    }
}
