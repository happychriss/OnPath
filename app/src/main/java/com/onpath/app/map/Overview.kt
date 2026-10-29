package com.onpath.app.map

import android.content.Context
import com.onpath.app.debug.Problems
import org.json.JSONArray
import org.json.JSONObject

/**
 * The overview map shipped in the APK (docs/design/map-cells.md §7, docs/map-pipeline.md §9b): one world overview –
 * base map (OpenMapTiles schema from Natural Earth: coast, water, borders, country and city names) and relief – so a
 * place without downloaded data is never a blank screen. MapLibre over-zooms its last zoom level (from the files'
 * headers) to any zoom.
 *
 * MapLibre cannot draw a layer "only where another source has no data", so:
 * - below [SPLIT_ZOOM] every base-map / relief layer is drawn from the overview (a copy of the layer, same look,
 *   source `overview` / `overview-terrain`, id `<id>~overview`); cells only carry z ≥ 10, so nothing doubles;
 * - from [SPLIT_ZOOM] the original layers draw the detailed data. Underneath them (right above the background) the
 *   overview goes on – fills, lines and relief, no labels (`<id>~under`; owner field test 0.8.7: "everything outside
 *   the downloaded areas is empty beige at z10"). Over those lies [COVER]: an opaque fill in the background colour over
 *   the areas that have the detailed map (installed cells, the region package) – there the overview is hidden
 *   completely (no offset Natural Earth lakes, no doubled relief); outside it shows through.
 * Labels of the overview stop at [SPLIT_ZOOM]: hidden under the cover they would still take label space.
 * Natural Earth urban areas (`landuse`) are left out of the overview: magnified they are grey smudges (owner 0.8.7).
 * Prefix-based code (LayerSettings "hillshade", "poi-", …) still finds the copies because the id keeps its start.
 *
 * Relief: a pre-rendered hill-shade raster ([HILLSHADE_FILE], one `raster` layer – magnifies smoothly, no tile seams)
 * is used when bundled; otherwise the Terrarium DEM (`terrain.pmtiles`) with copies of the style's hillshade layers.
 *
 * Region packages (map-cells.md §20; [merge]'s `packages` = their boxes): they bring the detailed map for z7–9, so with
 * packages installed the detailed base / relief layers start at [PACKAGE_ZOOM] instead of [SPLIT_ZOOM] (outside the
 * packages they find no tiles and draw nothing). Inside a package the overview must not show: its fills and lines go
 * to the bottom (right above its relief) and the package's own relief ([REGION_SHADE_SOURCE], opaque tiles over the
 * whole package) is drawn over them – the cover for z7–9, and the relief there at the same time. Overview labels from
 * z7 get a `!within` filter (packages' boxes), so a town is not named twice.
 *
 * The overview is optional: a build without it (Gradle property `onpath.overviewAssets` not set) leaves the
 * style unchanged.
 */
object Overview {
    const val ASSET_DIR = "overview"
    const val SPLIT_ZOOM = 10.0
    const val SUFFIX = "~overview"
    const val UNDER = "~under"
    const val COVER = "detail-cover"
    const val HILLSHADE_FILE = "hillshade.pmtiles"
    const val HILLSHADE_SOURCE = "overview-hillshade"
    /** Region packages bring the map from this zoom (map-cells.md §20). */
    const val PACKAGE_ZOOM = 7.0
    /** The region packages' pre-rendered relief (style source, tools/make_style.py; file hillshade.pmtiles). */
    const val REGION_SHADE_SOURCE = "region-shade"
    /** Overview label copies from [PACKAGE_ZOOM] on, kept out of the packages' boxes. */
    const val OUTSIDE = "~outside"
    /**
     * Paint of the pre-rendered hill shade (its look is baked into the images; tools/build_hillshade.py); hill shading
     * balanced (c), owner 2026-10-22: a little more contrast, highlights not pure white.
     */
    private const val HILLSHADE_PAINT =
        """{"raster-resampling":"linear","raster-fade-duration":0,"raster-contrast":0.25,"raster-brightness-max":0.9}"""
    /** Full up to the split, then calmer: outside the downloaded areas it is only a magnified stand-in. */
    private const val RELIEF_OPACITY = """["interpolate",["linear"],["zoom"],10,1,12,0.6,14,0.45]"""
    /** Overview land cover (Natural Earth ice …) over the opaque relief tiles. */
    private const val LANDCOVER_OPACITY = 0.6
    private const val DEFAULT_BACKGROUND = "#f6f4ec"

    /** Detailed source → overview source and its URL. */
    val SOURCES = mapOf(
        "base" to ("overview" to "pmtiles://asset://$ASSET_DIR/base.pmtiles"),
        "terrain" to ("overview-terrain" to "pmtiles://asset://$ASSET_DIR/terrain.pmtiles"),
    )

    /** Overview source-layers never drawn (Natural Earth urban areas: smudges when magnified). */
    private val SKIPPED_SOURCE_LAYERS = setOf("landuse")

    /** Overview files present in the APK: "base.pmtiles", "terrain.pmtiles", "hillshade.pmtiles" (each optional). */
    fun bundled(context: Context): Set<String> = try {
        context.assets.list(ASSET_DIR)?.toSet().orEmpty()
    } catch (e: Exception) {
        Problems.warn("map", "Overview map in the app not readable, low zooms only from downloaded data", e)
        emptySet()
    }

    /**
     * Adds the overview sources, the low-zoom layer copies, the under-copies and the cover to [styleJson]. [files] =
     * overview files present (see [bundled]); a detailed source without its overview file is left as it is.
     * [cover]: areas with the detailed map as [west, south, east, north] boxes (installed cells, region package).
     * [packages]: boxes of the installed region packages (z7–9, see the class comment). Never throws.
     */
    fun merge(styleJson: String, files: Set<String>, cover: List<DoubleArray> = emptyList(),
              packages: List<DoubleArray> = emptyList()): String {
        if (files.isEmpty()) return styleJson
        return try {
            val root = JSONObject(styleJson)
            val sources = root.optJSONObject("sources") ?: return styleJson
            val raster = HILLSHADE_FILE in files && sources.has("terrain")
            val active = SOURCES.filter { (detailed, ov) ->
                sources.has(detailed) && ov.second.substringAfterLast('/') in files && !(raster && detailed == "terrain")
            }
            if (active.isEmpty() && !raster) return styleJson
            for ((detailed, ov) in active) {
                val src = JSONObject(sources.getJSONObject(detailed).toString())
                src.put("url", ov.second)
                src.remove("minzoom"); src.remove("maxzoom")
                sources.put(ov.first, src)
            }
            if (raster) {
                val src = JSONObject().put("type", "raster").put("url", "pmtiles://asset://$ASSET_DIR/$HILLSHADE_FILE")
                    .put("tileSize", 256)
                sources.getJSONObject("terrain").optString("attribution").takeIf { it.isNotEmpty() }?.let { src.put("attribution", it) }
                sources.put(HILLSHADE_SOURCE, src)
            }
            val layers = root.optJSONArray("layers") ?: return styleJson
            val withPackages = packages.isNotEmpty()
            // detailed base / relief layers start here (packages: z7, their tiles; else the cells' z10)
            val detailFrom = if (withPackages) PACKAGE_ZOOM else SPLIT_ZOOM
            val out = JSONArray()
            val under = JSONArray()
            /** With packages: the overview's fills / lines below z10 (bottom, under the packages' opaque relief). */
            val lowCopies = JSONArray()
            /** With packages: their relief layers, right above [lowCopies]. */
            val regionShade = JSONArray()
            val outsidePackages = if (withPackages) JSONArray().put("!").put(JSONArray().put("within").put(multiPolygon(packages))) else null
            /** The pre-rendered relief: opaque tiles (shade composited onto the background), so right above it. */
            var relief: JSONObject? = null
            var background = DEFAULT_BACKGROUND
            var underAt = 0
            var rasterDone = false
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                if (l.optString("type") == "background" && out.length() == underAt) {
                    l.optJSONObject("paint")?.optString("background-color")?.takeIf { it.startsWith("#") }?.let { background = it }
                    out.put(l); underAt = out.length(); continue
                }
                val source = l.optString("source")
                if (withPackages && source == REGION_SHADE_SOURCE) { regionShade.put(l); continue }
                val min = l.optDouble("minzoom", 0.0)
                val max = l.optDouble("maxzoom", 24.0)
                val isRelief = raster && source == "terrain" && l.optString("type") == "hillshade"
                val ov = active[source]
                if ((ov == null && !isRelief) || min >= SPLIT_ZOOM) { out.put(l); continue }
                val copy: JSONObject? = when {
                    l.optString("source-layer") in SKIPPED_SOURCE_LAYERS -> null
                    isRelief -> {
                        // one layer for every zoom: full below the split, calmer the deeper one zooms outside the
                        // downloaded areas (there it is only a stand-in, magnified); the cover hides it inside them
                        if (!rasterDone) relief = JSONObject().put("id", "hillshade$SUFFIX").put("type", "raster")
                            .put("source", HILLSHADE_SOURCE).put("paint", JSONObject(HILLSHADE_PAINT).put("raster-opacity", JSONArray(RELIEF_OPACITY)))
                            .also { c -> l.optJSONObject("layout")?.let { c.put("layout", JSONObject(it.toString())) } }
                        rasterDone = true
                        null
                    }
                    else -> JSONObject(l.toString()).put("id", l.getString("id") + SUFFIX).put("source", ov!!.first).also { c ->
                        // the relief lies under the fills: Natural Earth ice / land cover lets it through
                        if (raster && c.optString("type") == "fill" && c.optString("source-layer") == "landcover") {
                            val paint = c.optJSONObject("paint") ?: JSONObject().also { c.put("paint", it) }
                            if (!paint.has("fill-opacity")) paint.put("fill-opacity", LANDCOVER_OPACITY)
                        }
                    }
                }
                if (copy != null) {
                    if (min > 0) copy.put("minzoom", min)
                    copy.put("maxzoom", minOf(max, SPLIT_ZOOM))
                    val label = copy.optString("type") == "symbol"
                    when {
                        !withPackages -> out.put(copy)
                        !label -> lowCopies.put(copy)
                        minOf(max, SPLIT_ZOOM) <= PACKAGE_ZOOM -> out.put(copy)
                        else -> {
                            // below z7 as before; from z7 only outside the packages (their own labels draw there)
                            if (min < PACKAGE_ZOOM) out.put(JSONObject(copy.toString()).put("maxzoom", PACKAGE_ZOOM))
                            val o = JSONObject(copy.toString()).put("id", copy.getString("id") + OUTSIDE)
                                .put("minzoom", maxOf(min, PACKAGE_ZOOM))
                            o.put("filter", copy.optJSONArray("filter")?.let { f -> JSONArray().put("all").put(f).put(outsidePackages) }
                                ?: outsidePackages)
                            out.put(o)
                        }
                    }
                    // from the split on, below the detailed layers and the cover: the overview goes on (no labels)
                    if (max > SPLIT_ZOOM && copy.optString("type") != "symbol") {
                        val u = JSONObject(copy.toString())
                        u.put("id", copy.getString("id").removeSuffix(SUFFIX) + UNDER)
                        u.put("minzoom", SPLIT_ZOOM)
                        if (max >= 24.0) u.remove("maxzoom") else u.put("maxzoom", max)
                        under.put(u)
                    }
                }
                // a detailed layer that ends below the split is drawn only by its copy (with packages: below z7)
                if (max > detailFrom) { l.put("minzoom", maxOf(min, detailFrom)); out.put(l) }
            }
            val coverLayer = if ((under.length() > 0 || relief != null) && cover.isNotEmpty()) {
                sources.put(COVER, JSONObject().put("type", "geojson").put("data", coverGeoJson(cover)))
                JSONObject().put("id", COVER).put("type", "fill").put("source", COVER).put("minzoom", SPLIT_ZOOM)
                    .put("paint", JSONObject().put("fill-color", background).put("fill-antialias", false))
            } else null
            val all = JSONArray()
            fun putUnder() {
                relief?.let { all.put(it) }
                for (j in 0 until lowCopies.length()) all.put(lowCopies.get(j))
                for (j in 0 until regionShade.length()) all.put(regionShade.get(j))
                for (j in 0 until under.length()) all.put(under.get(j))
                coverLayer?.let { all.put(it) }
            }
            for (i in 0 until out.length()) {
                if (i == underAt) putUnder()
                all.put(out.get(i))
            }
            if (underAt >= out.length()) putUnder()
            root.put("layers", all)
            root.toString()
        } catch (e: Exception) {
            Problems.warn("map", "Overview map not added to the style, low zooms only from downloaded data", e)
            styleJson
        }
    }

    /** [west, south, east, north] boxes as one GeoJSON MultiPolygon (the `within` filter's geometry). */
    internal fun multiPolygon(boxes: List<DoubleArray>): JSONObject {
        val polys = JSONArray()
        for (b in boxes) {
            if (b.size < 4) continue
            val ring = JSONArray()
            for ((x, y) in listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3], b[0] to b[1])) ring.put(JSONArray().put(x).put(y))
            polys.put(JSONArray().put(ring))
        }
        return JSONObject().put("type", "MultiPolygon").put("coordinates", polys)
    }

    /** [west, south, east, north] boxes as a GeoJSON FeatureCollection of rectangles. */
    internal fun coverGeoJson(boxes: List<DoubleArray>): JSONObject {
        val features = JSONArray()
        for (b in boxes) {
            if (b.size < 4) continue
            val ring = JSONArray()
            for ((x, y) in listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3], b[0] to b[1])) ring.put(JSONArray().put(x).put(y))
            features.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features)
    }
}
