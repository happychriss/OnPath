package com.onpath.app.map

import android.util.Log
import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Removes style sources whose local data file is missing, together with every layer that uses them.
 *
 * Only `base` and `terrain` are essential; everything else (contours, pois, trail names, …) is optional
 * data that a region may not have. MapLibre would otherwise log errors or show nothing for those layers,
 * and new optional files can be added to the style without any app change.
 *
 * A layer with `"metadata": {"onpath:fallback-for": "<source>"}` is the opposite: it is drawn only when that
 * source is missing (e.g. the base map's paths when an older map package has no paths.geojson).
 */
object OptionalSources {

    /** Sources that are never stripped (the map cannot work without them anyway). */
    private val ESSENTIAL = setOf("base", "terrain")

    /** Layer metadata key: the layer stands in for the named source and is drawn only when that source is missing. */
    const val FALLBACK_KEY = "onpath:fallback-for"

    /**
     * Style metadata key: source id → a stand-in source used when the source's own file is missing and the stand-in's
     * exists (format 2: `pois` vector tiles → `pois.geojson`, for a data folder or store without POI tiles).
     */
    const val FALLBACK_SOURCES_KEY = "onpath:fallback-sources"

    /** The local file a source points to ("file://…" or "pmtiles://file://…"), or null if remote/inline. */
    fun localFile(source: JSONObject): String? {
        val url = source.optString("data", "").ifEmpty { source.optString("url", "") }
        val path = url.removePrefix("pmtiles://")
        return if (path.startsWith("file://")) path.removePrefix("file://") else null
    }

    /** Logging must never break style loading (and is not available in JVM tests). */
    private fun log(msg: String) { try { Log.i("OnPath", msg) } catch (_: Throwable) { } }

    /**
     * [standIn]: essential sources that may be stripped like optional ones when their file is missing (the app passes
     * true for all: fresh install without map files – only the overview from the APK draws, MapLibre never tries to
     * open a missing file). Default: essential sources are always kept.
     */
    fun strip(styleJson: String, standIn: (String) -> Boolean = { false }, exists: (String) -> Boolean = { File(it).isFile }): String = try {
        val root = JSONObject(styleJson)
        val sources = root.optJSONObject("sources") ?: return styleJson
        val missing = mutableSetOf<String>()
        sources.keys().asSequence().toList().forEach { id ->
            if (id in ESSENTIAL && !standIn(id)) return@forEach
            val file = sources.optJSONObject(id)?.let { localFile(it) } ?: return@forEach
            if (!exists(file)) missing += id
        }
        val layers = root.optJSONArray("layers") ?: JSONArray()
        // a missing source with a stand-in source whose file exists (POI tiles → the GeoJSON): swapped in, its layers
        // lose "source-layer" (a GeoJSON source has one unnamed layer)
        val swapped = mutableSetOf<String>()
        val standIns = root.optJSONObject("metadata")?.optJSONObject(FALLBACK_SOURCES_KEY)
        for (id in missing.toList()) {
            val alt = standIns?.optJSONObject(id) ?: continue
            val file = localFile(alt)
            if (file != null && !exists(file)) continue
            sources.put(id, JSONObject(alt.toString()))
            missing -= id; swapped += id
        }
        if (swapped.isNotEmpty()) {
            for (i in 0 until layers.length()) layers.optJSONObject(i)?.takeIf { it.optString("source") in swapped }?.remove("source-layer")
            log("Map data stand-in sources used: $swapped")
        }
        fun fallbackFor(l: JSONObject) = l.optJSONObject("metadata")?.optString(FALLBACK_KEY)?.takeIf { it.isNotEmpty() }
        val hasFallbacks = (0 until layers.length()).any { i -> layers.optJSONObject(i)?.let { fallbackFor(it) } != null }
        if (missing.isEmpty() && !hasFallbacks && swapped.isEmpty()) styleJson else {
            missing.forEach { sources.remove(it) }
            val kept = JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                val fb = fallbackFor(l)
                // a stand-in layer needs its own source too (the base map may be gone as well – overview only)
                val keep = (fb == null || fb in missing) && l.optString("source") !in missing
                if (keep) kept.put(l)
            }
            root.put("layers", kept)
            if (missing.isNotEmpty()) log("Optional map data missing, layers hidden: $missing")
            root.toString()
        }
    } catch (e: Exception) {
        Problems.warn("map", "Optional map data not checked, style used unchanged", e)
        styleJson
    }
}
