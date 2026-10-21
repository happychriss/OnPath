package com.onpath.app.map

import android.content.Context
import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject

/**
 * The overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md §9b): one world overview –
 * base map (OpenMapTiles schema from Natural Earth: coast, water, borders, country and city names) and hill shade –
 * so a place without downloaded data is never a blank screen at low zoom. MapLibre over-zooms its last zoom level
 * (from the files' headers) up to [SPLIT_ZOOM].
 *
 * MapLibre cannot draw a layer "only where another source has no data", so the split is by zoom:
 * - below [SPLIT_ZOOM] every base-map / hill-shade layer is drawn from the overview (a copy of the layer, same look,
 *   source `overview` / `overview-terrain`, id `<id>~overview`);
 * - from [SPLIT_ZOOM] the original layers draw the detailed data (cells only carry z ≥ 10 anyway).
 * The two never draw on top of each other (no doubled hill shade, lines or labels). Prefix-based code
 * (LayerSettings "hillshade", "poi-", …) still finds the copies because the id keeps its start.
 *
 * The overview is optional: a build without it (Gradle property `onpath.overviewAssets` not set) leaves the
 * style unchanged.
 */
object Overview {
    const val ASSET_DIR = "overview"
    const val SPLIT_ZOOM = 10.0
    const val SUFFIX = "~overview"

    /** Detailed source → overview source and its URL. */
    val SOURCES = mapOf(
        "base" to ("overview" to "pmtiles://asset://$ASSET_DIR/base.pmtiles"),
        "terrain" to ("overview-terrain" to "pmtiles://asset://$ASSET_DIR/terrain.pmtiles"),
    )

    /** Overview files present in the APK: "base.pmtiles", "terrain.pmtiles" (each optional). */
    fun bundled(context: Context): Set<String> = try {
        context.assets.list(ASSET_DIR)?.toSet().orEmpty()
    } catch (e: Exception) {
        Problems.warn("map", "Overview map in the app not readable, low zooms only from downloaded data", e)
        emptySet()
    }

    /**
     * Adds the overview sources and low-zoom layer copies to [styleJson]. [files] = overview files present
     * (see [bundled]); a detailed source without its overview file is left as it is. Never throws.
     */
    fun merge(styleJson: String, files: Set<String>): String {
        if (files.isEmpty()) return styleJson
        return try {
            val root = JSONObject(styleJson)
            val sources = root.optJSONObject("sources") ?: return styleJson
            val active = SOURCES.filter { (detailed, ov) ->
                sources.has(detailed) && ov.second.substringAfterLast('/') in files
            }
            if (active.isEmpty()) return styleJson
            for ((detailed, ov) in active) {
                val src = JSONObject(sources.getJSONObject(detailed).toString())
                src.put("url", ov.second)
                src.remove("minzoom"); src.remove("maxzoom")
                sources.put(ov.first, src)
            }
            val layers = root.optJSONArray("layers") ?: return styleJson
            val out = JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                val ov = active[l.optString("source")]
                val min = l.optDouble("minzoom", 0.0)
                if (ov == null || min >= SPLIT_ZOOM) { out.put(l); continue }
                val copy = JSONObject(l.toString())
                copy.put("id", l.getString("id") + SUFFIX)
                copy.put("source", ov.first)
                copy.put("maxzoom", minOf(l.optDouble("maxzoom", 24.0), SPLIT_ZOOM))
                out.put(copy)
                // a detailed layer that ends below the split is drawn only by its copy
                if (l.optDouble("maxzoom", 24.0) > SPLIT_ZOOM) { l.put("minzoom", SPLIT_ZOOM); out.put(l) }
            }
            root.put("layers", out)
            root.toString()
        } catch (e: Exception) {
            Problems.warn("map", "Overview map not added to the style, low zooms only from downloaded data", e)
            styleJson
        }
    }
}
