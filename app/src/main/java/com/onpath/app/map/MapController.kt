package com.onpath.app.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.location.Location
import android.util.Log
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.routing.PlannedRoute
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Owns the app's own sources/layers on top of the base style and the position puck. */
class MapController(private val context: Context, val map: MapLibreMap, val style: Style) {

    companion object {
        const val ROUTE_COLOR = "#1E3FAE"     // deep blue, high contrast on green/beige
        const val GPX_COLOR = "#8E24AA"       // purple
        const val REC_COLOR = "#EF6C00"       // orange
        const val SRC_PLANNED = "planned-route"
        const val SRC_GPX = "gpx-track"
        const val SRC_REC = "recorded-track"
        const val SRC_WPT = "waypoints"
        const val SRC_HL = "highlight"
        const val LAYER_WPT = "waypoints-icons"
        const val SRC_DRAG = "drag-preview"
    }

    /** Settings page → base style layers (POI filter, contours, hillshade). */
    val layers = LayerSettings(style)

    private val density = context.resources.displayMetrics.density
    private var locationReady = false
    private var markerImages = 0

    init {
        addLine(SRC_GPX, GPX_COLOR, 5f, casing = true)
        addLine(SRC_REC, REC_COLOR, 4.5f, casing = true)
        addPlannedLayers()
        style.addSource(GeoJsonSource(SRC_HL))
        style.addLayer(CircleLayer("highlight-dot", SRC_HL).withProperties(
            PF.circleRadius(7f), PF.circleColor("#FFFFFF"), PF.circleStrokeColor(ROUTE_COLOR), PF.circleStrokeWidth(4f),
        ))
        // Dashed preview from the neighbours to the finger while a point is dragged.
        style.addSource(GeoJsonSource(SRC_DRAG))
        style.addLayer(LineLayer("drag-preview", SRC_DRAG).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(3f), PF.lineDasharray(arrayOf(2f, 1.5f)), PF.lineOpacity(0.8f),
        ))
        style.addSource(GeoJsonSource(SRC_WPT))
        // Gold ring under markers placed on a POI (passed exactly, see Planner pinned points).
        style.addLayer(CircleLayer("waypoints-pin", SRC_WPT).withFilter(Expression.eq(Expression.get("pinned"), Expression.literal(true)))
            .withProperties(PF.circleRadius(19f), PF.circleColor("#00000000"), PF.circleStrokeColor("#F2B33D"), PF.circleStrokeWidth(3f)))
        style.addLayer(SymbolLayer(LAYER_WPT, SRC_WPT).withProperties(
            PF.iconImage(Expression.get("icon")),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
        activateLocation()
    }

    private fun addLine(id: String, color: String, width: Float, casing: Boolean) {
        style.addSource(GeoJsonSource(id))
        if (casing) style.addLayer(LineLayer("$id-casing", id).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(width + 4f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer(id, id).withProperties(
            PF.lineColor(color), PF.lineWidth(width),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
    }

    /** Routed legs solid, failed legs (straight fallback) dashed. */
    private fun addPlannedLayers() {
        style.addSource(GeoJsonSource(SRC_PLANNED))
        val routed = Expression.eq(Expression.get("straight"), Expression.literal(false))
        val straight = Expression.eq(Expression.get("straight"), Expression.literal(true))
        style.addLayer(LineLayer("planned-route-casing", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(10f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-route", SRC_PLANNED).withFilter(routed).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(6f),
            PF.lineJoin(Property.LINE_JOIN_ROUND), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-straight-casing", SRC_PLANNED).withFilter(straight).withProperties(
            PF.lineColor("#FFFFFF"), PF.lineWidth(7f), PF.lineCap(Property.LINE_CAP_ROUND),
        ))
        style.addLayer(LineLayer("planned-straight", SRC_PLANNED).withFilter(straight).withProperties(
            PF.lineColor(ROUTE_COLOR), PF.lineWidth(4f), PF.lineDasharray(arrayOf(1.5f, 1.5f)),
        ))
    }

    @SuppressLint("MissingPermission")
    private fun activateLocation() {
        try {
            val lc = map.locationComponent
            val options = LocationComponentOptions.builder(context)
                .accuracyAlpha(0.18f)
                .enableStaleState(true)
                .staleStateTimeout(30_000)
                .build()
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .locationComponentOptions(options)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.COMPASS
            lc.cameraMode = CameraMode.NONE
            locationReady = true
        } catch (e: Exception) {
            Log.w("OnPath", "Location component not available: $e")
        }
    }

    fun updateLocation(loc: Location) {
        if (!locationReady) return
        try { map.locationComponent.forceLocationUpdate(loc) } catch (_: Exception) { }
    }

    /** Follow mode: camera tracks the position; MapLibre ends it when the user pans. */
    fun setFollow(follow: Boolean, onDismissed: () -> Unit) {
        if (!locationReady) return
        try {
            val lc = map.locationComponent
            if (follow) {
                lc.setCameraMode(CameraMode.TRACKING, 750L, null, null, null, null)
                lc.addOnCameraTrackingChangedListener(object : org.maplibre.android.location.OnCameraTrackingChangedListener {
                    override fun onCameraTrackingDismissed() { lc.removeOnCameraTrackingChangedListener(this); onDismissed() }
                    override fun onCameraTrackingChanged(currentMode: Int) {}
                })
                if (map.cameraPosition.zoom < 13) map.animateCamera(CameraUpdateFactory.zoomTo(15.0))
            } else if (lc.cameraMode != CameraMode.NONE) {
                lc.cameraMode = CameraMode.NONE
            }
        } catch (_: Exception) { }
    }

    fun setOverlay(activity: Activity, enabled: Boolean) {
        val want = when (activity) {
            Activity.HIKE -> "routes-hike"
            Activity.MTB, Activity.EMTB -> "routes-mtb"
            else -> "routes-bike"
        }
        for (id in listOf("routes-hike", "routes-bike", "routes-mtb")) {
            style.getLayer(id)?.setProperties(
                PF.visibility(if (enabled && id == want) Property.VISIBLE else Property.NONE)
            )
        }
    }

    fun setPlanned(route: PlannedRoute?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_PLANNED) ?: return
        if (route == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val features = route.legs.filter { it.points.size >= 2 }.map { leg ->
            Feature.fromGeometry(LineString.fromLngLats(leg.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addBooleanProperty("straight", leg.straight)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun setTrack(sourceId: String, segments: List<List<GeoPoint>>) {
        val src = style.getSourceAs<GeoJsonSource>(sourceId) ?: return
        val lines = segments.filter { it.size >= 2 }.map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
        if (lines.isEmpty()) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        src.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(lines)))
    }

    fun setWaypoints(points: List<GeoPoint>, pinned: Set<Int> = emptySet()) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_WPT) ?: return
        ensureMarkerImages(points.size)
        val features = points.mapIndexed { i, p ->
            val kind = when { i == 0 -> "s"; i == points.lastIndex && points.size > 1 -> "e"; else -> "v" }
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("icon", "wp-$kind-${i + 1}")
                addNumberProperty("idx", i)
                addBooleanProperty("pinned", i in pinned)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Drag preview: lines prev → p → next (either neighbour may be null); null clears. */
    fun setDragPreview(prev: GeoPoint?, p: GeoPoint?, next: GeoPoint?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_DRAG) ?: return
        if (p == null) { src.setGeoJson(FeatureCollection.fromFeatures(emptyList())); return }
        val lines = listOfNotNull(prev?.let { listOf(it, p) }, next?.let { listOf(p, it) })
            .map { seg -> seg.map { Point.fromLngLat(it.lon, it.lat) } }
        if (lines.isEmpty()) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(MultiLineString.fromLngLats(lines)))
    }

    /** Metres per screen pixel at the camera centre (for "tap near the line" tolerances). */
    fun metersPerPixel(): Double = try {
        map.projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 46.0)
    } catch (_: Exception) { 5.0 }

    fun dpToPx(dp: Float) = dp * density

    fun setHighlight(p: GeoPoint?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_HL) ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

    /** Index of the waypoint marker under a screen point, or -1. */
    fun waypointAt(screen: PointF): Int {
        val r = 24 * density
        val box = android.graphics.RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
        val f = map.queryRenderedFeatures(box, LAYER_WPT).firstOrNull() ?: return -1
        return f.getNumberProperty("idx")?.toInt() ?: -1
    }

    fun fitTo(points: List<GeoPoint>, bottomPaddingPx: Int) {
        if (points.isEmpty()) return
        try {
            if (points.size == 1) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 14.0))
                return
            }
            val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
            val pad = (48 * density).toInt()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, pad, pad * 2, pad, bottomPaddingPx + pad), 800)
        } catch (e: Exception) {
            Log.w("OnPath", "fit failed: $e")
        }
    }

    /** Numbered round markers drawn once per index (no glyphs needed). */
    private fun ensureMarkerImages(count: Int) {
        while (markerImages < count) {
            val n = markerImages + 1
            style.addImage("wp-s-$n", marker(n, "#2E7D32"))
            style.addImage("wp-v-$n", marker(n, ROUTE_COLOR))
            style.addImage("wp-e-$n", marker(n, "#C62828"))
            markerImages++
        }
    }

    private fun marker(n: Int, color: String): Bitmap {
        val sizeDp = 30
        val px = (sizeDp * density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        bmp.density = context.resources.displayMetrics.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(px / 2f, px / 2f, px / 2f, p)
        p.color = android.graphics.Color.parseColor(color)
        c.drawCircle(px / 2f, px / 2f, px / 2f - 3 * density, p)
        p.color = android.graphics.Color.WHITE
        p.textSize = 14 * density
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        val y = px / 2f - (p.descent() + p.ascent()) / 2
        c.drawText(n.toString(), px / 2f, y, p)
        return bmp
    }
}
