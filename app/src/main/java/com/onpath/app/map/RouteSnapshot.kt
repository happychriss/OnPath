package com.onpath.app.map

import android.content.Context
import android.graphics.Bitmap
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.ui.OverviewRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * The large route overview's map picture (My routes, owner 2026-11-07): MapLibre's snapshotter draws the app's own style
 * (offline cells; the overview map where there is no detail) with the route fitted, once – a still picture instead of a
 * second live map in a dialog (no gestures needed, far lighter on memory, nothing to tear down). Main thread, as MapLibre
 * wants; a failure or [TIMEOUT_MS] gives null (reported) and the overview keeps the drawn shape.
 */
object RouteSnapshot {
    const val TIMEOUT_MS = 15_000L

    suspend fun make(context: Context, styleJson: String, segments: List<List<GeoPoint>>, color: Int,
                     widthPx: Int, heightPx: Int, paddingPx: Int): Bitmap? {
        val all = segments.flatten()
        val b = OverviewRegion.of(all) ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val json = try { withLine(styleJson, segments, all, color) } catch (e: Exception) {
            Problems.warn("routes", "Overview: the route could not be added to the map style – the shape is shown alone", e)
            return null
        }
        return withContext(Dispatchers.Main) {
            var failed = false
            val bmp = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<Bitmap?> { cont ->
                    try {
                        val bounds = LatLngBounds.Builder().include(LatLng(b[0], b[1])).include(LatLng(b[2], b[3])).build()
                        // the line is part of the style JSON: sources added through Style.Builder fail in the snapshotter
                        // ("invalid native peer", MapLibre 13.7.1, seen on the emulator 2026-11-07)
                        val opts = MapSnapshotter.Options(widthPx, heightPx).withStyleJson(json).withRegion(bounds)
                            .withPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                        opts.showLogo = false
                        val snap = MapSnapshotter(context, opts)
                        cont.invokeOnCancellation { runCatching { snap.cancel() } }
                        snap.start({ shot -> if (cont.isActive) cont.resume(shot.bitmap) }) { err ->
                            Problems.warn("routes", "Overview map picture failed: $err – the shape is shown alone")
                            failed = true
                            if (cont.isActive) cont.resume(null)
                        }
                    } catch (e: Exception) {
                        Problems.warn("routes", "Overview map picture could not be started – the shape is shown alone", e)
                        failed = true
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (bmp == null && !failed) Problems.warn("routes", "Overview map picture took longer than ${TIMEOUT_MS / 1000} s – the shape is shown alone")
            bmp
        }
    }

    /** [styleJson] with the route (white casing, the line's colour) and its start / goal dots on top. Pure JSON work. */
    internal fun withLine(styleJson: String, segments: List<List<GeoPoint>>, all: List<GeoPoint>, color: Int): String {
        fun coords(s: List<GeoPoint>) = JSONArray().apply { s.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
        fun point(p: GeoPoint, kind: String) = JSONObject().put("type", "Feature")
            .put("properties", JSONObject().put("k", kind))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
        val lines = JSONArray().apply { segments.filter { it.size >= 2 }.forEach { put(coords(it)) } }
        val o = JSONObject(styleJson)
        val sources = o.optJSONObject("sources") ?: JSONObject().also { o.put("sources", it) }
        sources.put(SRC_LINE, JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", lines))))
        sources.put(SRC_ENDS, JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "FeatureCollection")
            .put("features", JSONArray().put(point(all.first(), "start")).put(point(all.last(), "goal")))))
        val hex = String.format("#%06X", 0xFFFFFF and color)
        val goal = String.format("#%06X", 0xFFFFFF and GOAL)
        fun round() = JSONObject().put("line-cap", "round").put("line-join", "round")
        val layers = o.optJSONArray("layers") ?: JSONArray().also { o.put("layers", it) }
        layers.put(JSONObject().put("id", LAYER_CASE).put("type", "line").put("source", SRC_LINE).put("layout", round())
            .put("paint", JSONObject().put("line-color", "#FFFFFF").put("line-width", 8)))
        layers.put(JSONObject().put("id", LAYER_LINE).put("type", "line").put("source", SRC_LINE).put("layout", round())
            .put("paint", JSONObject().put("line-color", hex).put("line-width", 5)))
        layers.put(JSONObject().put("id", LAYER_ENDS).put("type", "circle").put("source", SRC_ENDS)
            .put("paint", JSONObject().put("circle-radius", 7).put("circle-stroke-width", 3).put("circle-stroke-color", "#FFFFFF")
                .put("circle-color", JSONArray().put("match").put(JSONArray().put("get").put("k")).put("goal").put(goal).put(hex))))
        return o.toString()
    }

    private const val SRC_LINE = "overview-line"
    private const val SRC_ENDS = "overview-ends"
    private const val LAYER_CASE = "overview-line-case"
    private const val LAYER_LINE = "overview-line"
    private const val LAYER_ENDS = "overview-ends"
    /** The map's goal colour (OnPathColors.PointGoal). */
    private const val GOAL = 0xFFA32020.toInt()
}
