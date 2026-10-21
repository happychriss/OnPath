package com.onpath.app.map

import android.content.Context
import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject

/**
 * The overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md §9b), so a place without
 * downloaded data is never a blank screen at low zoom:
 * - `world.pmtiles`: Natural Earth world z0–5 (coast, water, borders, countries, cities) with a hole at z5 where the
 *   Alps file has data – MapLibre over-zooms its z5 tiles up to z9 outside the Alps;
 * - `base.pmtiles`: the Alps z5–8 (OpenMapTiles from OSM), cut to exactly that hole;
 * - `terrain-world.pmtiles`: hill shade of the world z0–4, drawn below zoom 5 only ([Part.maxZoom]: hill shade
 *   cannot get a hole like the vector file, and over-zoomed it would shade the Alps twice);
 * - `terrain.pmtiles`: hill shade of the Alps z5–9.
 *
 * MapLibre cannot draw a layer "only where another source has no data", so the split is by zoom:
 * - below [SPLIT_ZOOM] every base-map / hill-shade layer is drawn from the overview (copies of the layer, same look,
 *   source `overview-world` / `overview` / `overview-terrain-world` / `overview-terrain`, id `<id>~world` /
 *   `<id>~overview`);
 * - from [SPLIT_ZOOM] the original layers draw the detailed data (cells only carry z ≥ 10 anyway).
 * The two never draw on top of each other (no doubled hill shade, lines or labels), and the world and Alps copies
 * cover different areas (the hole). Prefix-based code (LayerSettings "hillshade", "poi-", …) still finds the copies
 * because the id keeps its start.
 *
 * The overview is optional: a build without it (Gradle property `onpath.overviewAssets` not set) leaves the
 * style unchanged.
 */
object Overview {
    const val ASSET_DIR = "overview"
    const val SPLIT_ZOOM = 10.0
    const val SUFFIX = "~overview"
    const val WORLD_SUFFIX = "~world"

    /**
     * One overview file: the source it becomes, its file in [ASSET_DIR], the suffix of the layer copies and the zoom
     * where its copies end (at most [SPLIT_ZOOM]).
     */
    data class Part(val source: String, val file: String, val suffix: String, val maxZoom: Double = SPLIT_ZOOM) {
        val url: String get() = "pmtiles://asset://$ASSET_DIR/$file"
    }

    /** Detailed source → its overview parts, drawn in this order (world below the Alps; they do not overlap). */
    val SOURCES = mapOf(
        "base" to listOf(Part("overview-world", "world.pmtiles", WORLD_SUFFIX), Part("overview", "base.pmtiles", SUFFIX)),
        "terrain" to listOf(Part("overview-terrain-world", "terrain-world.pmtiles", WORLD_SUFFIX, maxZoom = 5.0),
            Part("overview-terrain", "terrain.pmtiles", SUFFIX)),
    )

    /** Overview files present in the APK: "world.pmtiles", "base.pmtiles", "terrain*.pmtiles" (each optional). */
    fun bundled(context: Context): Set<String> = try {
        context.assets.list(ASSET_DIR)?.toSet().orEmpty()
    } catch (e: Exception) {
        Problems.warn("map", "Overview map in the app not readable, low zooms only from downloaded data", e)
        emptySet()
    }

    /**
     * Adds the overview sources and low-zoom layer copies to [styleJson]. [files] = overview files present
     * (see [bundled]); a detailed source without any overview file is left as it is. Never throws.
     */
    fun merge(styleJson: String, files: Set<String>): String {
        if (files.isEmpty()) return styleJson
        return try {
            val root = JSONObject(styleJson)
            val sources = root.optJSONObject("sources") ?: return styleJson
            val active = SOURCES.mapValues { (_, parts) -> parts.filter { it.file in files } }
                .filter { (detailed, parts) -> sources.has(detailed) && parts.isNotEmpty() }
            if (active.isEmpty()) return styleJson
            for ((detailed, parts) in active) for (part in parts) {
                val src = JSONObject(sources.getJSONObject(detailed).toString())
                src.put("url", part.url)
                // zoom range from the file's own header (the world file ends at z5 and is over-zoomed from there)
                src.remove("minzoom"); src.remove("maxzoom")
                sources.put(part.source, src)
            }
            val layers = root.optJSONArray("layers") ?: return styleJson
            val out = JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                val parts = active[l.optString("source")]
                val min = l.optDouble("minzoom", 0.0)
                if (parts == null || min >= SPLIT_ZOOM) { out.put(l); continue }
                for (part in parts) {
                    val max = minOf(l.optDouble("maxzoom", 24.0), part.maxZoom)
                    if (max <= min) continue
                    val copy = JSONObject(l.toString())
                    copy.put("id", l.getString("id") + part.suffix)
                    copy.put("source", part.source)
                    copy.put("maxzoom", max)
                    out.put(copy)
                }
                // a detailed layer that ends below the split is drawn only by its copies
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
