package com.onpath.app.map

import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import com.onpath.app.debug.Problems
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import java.io.File

/**
 * Keeps MapLibre's native memory in check (Plan mode with many map areas):
 *
 * - **Style reload** (new map store generation, theme, developer reload): the GeoJSON sources the style loads from
 *   files (the POIs, format-1 overlays) are emptied first and one or two frames are drawn ([releaseFileGeoJson],
 *   [afterFrames]); only then the new style is set. Without this MapLibre holds the old data – for the POIs a geojson-vt
 *   index of ~0.8 GB with 145 k points – until the new file is parsed and indexed, so both sit in memory at once, and
 *   the old index can stay held by the POI tiles of the reused render source.
 * - **Trim**: when Android says the app is hidden or memory runs low ([freesCaches]), the map's tile caches are freed
 *   (`MapView.onLowMemory`); the visible tiles stay.
 */
object MapMemory {
    /** Frames drawn after emptying the old data before the new style is set (the first may have started before). */
    const val RELEASE_FRAMES = 2

    /** Longest wait for those frames (the map draws nothing while the app is in the background). */
    const val RELEASE_TIMEOUT_MS = 400L

    /** The local file of a source URI ("file:///…/pois.geojson"), or null (inline data, asset, web). Pure. */
    fun fileOf(uri: String?): File? {
        if (uri == null || !uri.startsWith("file://")) return null
        val path = uri.removePrefix("file://")
        return if (path.startsWith("/")) File(path) else null
    }

    /** Whether Android's trim [level] should free the map's tile caches: app hidden, in the background, or low memory. Pure. */
    @Suppress("DEPRECATION") // RUNNING_LOW / RUNNING_CRITICAL are still sent below Android 14
    fun freesCaches(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    /** The style's GeoJSON sources that load a local file. Never throws (an old style being replaced → empty). */
    private fun fileGeoJson(style: Style): List<Pair<GeoJsonSource, File>> = try {
        style.sources.filterIsInstance<GeoJsonSource>().mapNotNull { s -> fileOf(s.uri)?.let { s to it } }
    } catch (e: Exception) {
        Problems.info("map", "Map sources not readable (style being replaced)", e); emptyList()
    }

    /**
     * Empties [style]'s file GeoJSON sources before the style is replaced, so MapLibre frees their index with the next
     * frame instead of holding it beside the new one. Returns how many were emptied. Never throws.
     */
    fun releaseFileGeoJson(style: Style): Int {
        var n = 0
        for ((s, _) in fileGeoJson(style)) try {
            s.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            n++
        } catch (e: Exception) {
            Problems.warn("map", "Old map data (${s.id}) not freed before the reload – it stays in memory until the new style loads", e)
        }
        return n
    }

    /**
     * Runs [action] on the main thread once [frames] frames were drawn, or after [timeoutMs] at the latest (nothing is
     * drawn while the app is in the background). Exactly once.
     */
    fun afterFrames(mapView: MapView, map: MapLibreMap, frames: Int = RELEASE_FRAMES,
                    timeoutMs: Long = RELEASE_TIMEOUT_MS, action: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var left = frames
        var done = false
        lateinit var listener: MapView.OnDidFinishRenderingFrameListener
        lateinit var timeout: Runnable
        fun finish() {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            mapView.removeOnDidFinishRenderingFrameListener(listener)
            action()
        }
        timeout = Runnable { finish() }
        // posted: the listener list must not change while MapLibre walks it
        listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> if (--left == 0) main.post { finish() } }
        mapView.addOnDidFinishRenderingFrameListener(listener)
        main.postDelayed(timeout, timeoutMs)
        try { map.triggerRepaint() } catch (e: Exception) {
            Problems.info("map", "Map repaint not asked before the reload (the timeout loads it)", e)
        }
    }
}
