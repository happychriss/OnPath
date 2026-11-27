package com.onpath.app.map

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.PropertyValue

/**
 * How big the plan's numbered points are drawn by zoom (owner 2026-11-27, variant 2 of the sketch): always about 20 %
 * bigger than the map's POI symbols – they grow with the same curve – but never under [MIN_DP], so the number stays
 * readable; out in the region view a point is a third smaller than the fixed 30 dp before (the start of a round trip
 * no longer stacks "15 14 1 10"). Only the picture: tapping and holding a point keep their radius in dp.
 */
object PointMarkSize {
    /** The plain point's bitmap (MapController.marker); a point on a POI scales by the same factor. */
    const val MARKER_DP = 30f
    const val MIN_DP = 20f
    /** The POI symbol's disc at icon-size 1 (the sprite's 24 units, its white edge at 10.5). */
    const val POI_DISC_DP = 21f
    const val OVER_POI = 1.2f
    /** The map style's POI icon-size by zoom (style/onpath.json, layers poi-tier1 … poi-bus). */
    val POI_SIZE = listOf(12f to 0.45f, 12.9f to 0.5f, 13f to 0.78f, 15f to 0.92f, 17f to 1.05f)

    /** The point's scale at a stop of the POI curve. */
    fun scale(poiSize: Float): Float = maxOf(MIN_DP, OVER_POI * POI_DISC_DP * poiSize) / MARKER_DP

    /** The stops (zoom, icon-size) of the points. */
    fun stops(): List<Pair<Float, Float>> = POI_SIZE.map { (z, s) -> z to scale(s) }

    /** The layer's properties: the point's own picture, always drawn, its size by zoom. */
    fun layerProps(): Array<PropertyValue<*>> = arrayOf(
        PF.iconImage(Expression.get("icon")), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        PF.iconSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
            *stops().map { (z, s) -> Expression.stop(z, s) }.toTypedArray())))
}
