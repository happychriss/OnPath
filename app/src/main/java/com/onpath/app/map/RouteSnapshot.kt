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

    /**
     * The picture and where the route's ends lie on it (bitmap pixels): the overview draws the start dot and the goal flag
     * over it – the same signs as over the drawn shape, no second marker drawing here.
     */
    class Shot(val bitmap: Bitmap, val startX: Float, val startY: Float, val goalX: Float, val goalY: Float)

    /**
     * The snapshot is asked in dp with the phone's density as its pixel ratio, so the map's labels and the route line come
     * out as big as on the map itself. It was asked in pixels at ratio 1 – a 5 px line on a 1080 px picture (owner
     * 2026-11-07: "die Linie ist sehr, sehr dünn"). Pure.
     */
    data class Scale(val width: Int, val height: Int, val padding: Int, val ratio: Float) {
        /** The route line's width on the bitmap, in pixels. */
        val linePx: Float get() = RouteLook.LINE_DP * ratio
        val casingPx: Float get() = RouteLook.CASING_DP * ratio

        companion object {
            fun of(widthPx: Int, heightPx: Int, paddingPx: Int, density: Float): Scale? {
                val d = if (density.isFinite() && density > 0f) density else 1f
                val w = (widthPx / d).toInt(); val h = (heightPx / d).toInt()
                if (w <= 0 || h <= 0) return null
                return Scale(w, h, (paddingPx / d).toInt().coerceIn(0, minOf(w, h) / 3), d)
            }
        }
    }

    /** [under] + [underColor]: a recorded tour's followed plan, thin under the track (owner 2026-11-07); empty = none. */
    suspend fun make(context: Context, styleJson: String, segments: List<List<GeoPoint>>, color: Int,
                     widthPx: Int, heightPx: Int, paddingPx: Int, density: Float,
                     under: List<GeoPoint> = emptyList(), underColor: Int = 0): Shot? {
        val all = segments.flatten()
        if (all.isEmpty()) return null
        val b = OverviewRegion.of(all + under) ?: return null
        val scale = Scale.of(widthPx, heightPx, paddingPx, density) ?: return null
        val json = try { withLine(styleJson, segments, color, under, underColor) } catch (e: Exception) {
            Problems.warn("routes", "Overview: the route could not be added to the map style – the shape is shown alone", e)
            return null
        }
        return withContext(Dispatchers.Main) {
            var failed = false
            val shot = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<Shot?> { cont ->
                    try {
                        val bounds = LatLngBounds.Builder().include(LatLng(b[0], b[1])).include(LatLng(b[2], b[3])).build()
                        // the line is part of the style JSON: sources added through Style.Builder fail in the snapshotter
                        // ("invalid native peer", MapLibre 13.7.1, seen on the emulator 2026-11-07)
                        val p = scale.padding
                        val opts = MapSnapshotter.Options(scale.width, scale.height).withPixelRatio(scale.ratio)
                            .withStyleJson(json).withRegion(bounds).withPadding(p, p, p, p)
                        opts.showLogo = false
                        val snap = MapSnapshotter(context, opts)
                        cont.invokeOnCancellation { runCatching { snap.cancel() } }
                        snap.start({ shot ->
                            // pixelForLatLng answers in the bitmap's pixels already (pixel ratio applied; emulator 2026-11-07)
                            val s = shot.pixelForLatLng(LatLng(all.first().lat, all.first().lon))
                            val g = shot.pixelForLatLng(LatLng(all.last().lat, all.last().lon))
                            if (cont.isActive) cont.resume(Shot(shot.bitmap, s.x, s.y, g.x, g.y))
                        }) { err ->
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
            if (shot == null && !failed) Problems.warn("routes", "Overview map picture took longer than ${TIMEOUT_MS / 1000} s – the shape is shown alone")
            shot
        }
    }

    /**
     * [styleJson] with the route on top: a white casing and the line's colour, as wide as the map's planned route
     * ([RouteLook], in dp – the snapshot's pixel ratio turns them into pixels). Pure JSON work.
     */
    internal fun withLine(styleJson: String, segments: List<List<GeoPoint>>, color: Int,
                          under: List<GeoPoint> = emptyList(), underColor: Int = 0): String {
        fun coords(s: List<GeoPoint>) = JSONArray().apply { s.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
        val lines = JSONArray().apply { segments.filter { it.size >= 2 }.forEach { put(coords(it)) } }
        val o = JSONObject(styleJson)
        val sources = o.optJSONObject("sources") ?: JSONObject().also { o.put("sources", it) }
        val layers = o.optJSONArray("layers") ?: JSONArray().also { o.put("layers", it) }
        if (under.size >= 2) {
            // the followed plan first: thin, plan blue, faint – the track is drawn over it
            sources.put(SRC_UNDER, JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords(under)))))
            layers.put(JSONObject().put("id", LAYER_UNDER).put("type", "line").put("source", SRC_UNDER)
                .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
                .put("paint", JSONObject().put("line-color", String.format("#%06X", 0xFFFFFF and underColor))
                    .put("line-width", RouteLook.PLAN_UNDER_DP.toDouble()).put("line-opacity", RouteLook.PLAN_UNDER_OPACITY.toDouble())))
        }
        sources.put(SRC_LINE, JSONObject().put("type", "geojson").put("data", JSONObject().put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "MultiLineString").put("coordinates", lines))))
        val hex = String.format("#%06X", 0xFFFFFF and color)
        fun round() = JSONObject().put("line-cap", "round").put("line-join", "round")
        layers.put(JSONObject().put("id", LAYER_CASE).put("type", "line").put("source", SRC_LINE).put("layout", round())
            .put("paint", JSONObject().put("line-color", "#FFFFFF").put("line-width", RouteLook.CASING_DP.toDouble())))
        layers.put(JSONObject().put("id", LAYER_LINE).put("type", "line").put("source", SRC_LINE).put("layout", round())
            .put("paint", JSONObject().put("line-color", hex).put("line-width", RouteLook.LINE_DP.toDouble())))
        return o.toString()
    }

    private const val SRC_LINE = "overview-line"
    private const val SRC_UNDER = "overview-plan"
    private const val LAYER_UNDER = "overview-plan"
    private const val LAYER_CASE = "overview-line-case"
    private const val LAYER_LINE = "overview-line"
}
