package com.onpath.app.map

import com.onpath.app.debug.Problems
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer

/**
 * The numbered plan points once more, above the position dot, shown only in Plan (owner 2026-11-26, „wichtig ist, dass
 * man den Startpunkt sieht“ – option 1): planning you look at the points, navigating at yourself
 * ([PositionDot.pointsOverDot]). Added after the position layers exist, so a plain addLayer is the top of the map.
 * Failing, the dot covers a point as before – reported, never thrown.
 */
object PointsOverDot {
    const val LAYER = "waypoints-icons-over-dot"

    fun add(style: Style, source: String) {
        runCatching {
            style.addLayer(SymbolLayer(LAYER, source).withProperties( // LAYER_WPT_OVER_DOT: the one layer above the dot
                PF.iconImage(Expression.get("icon")), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
            ))
        }.onFailure { Problems.warn("map", "Plan points over the position dot not added", it) }
    }

    fun apply(style: Style, navigate: Boolean, overlaysVisible: Boolean, live: () -> Boolean) {
        val show = PositionDot.pointsOverDot(navigate = navigate, overlaysVisible = overlaysVisible)
        runCatching { style.getLayer(LAYER)?.setProperties(PF.visibility(if (show) Property.VISIBLE else Property.NONE)) }
            .onFailure { if (live()) Problems.warn("map", "Plan points over the position dot not shown / hidden", it) }
    }
}
