package com.onpath.app.map

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Builds the style JSON: the map agent's asset `style/onpath.json` with {DATA_DIR} replaced,
 * or a minimal inline style if that asset is missing or unreadable.
 */
object StyleLoader {

    const val STYLE_ASSET = "style/onpath.json"

    fun load(context: Context, dataDir: File): String {
        val dir = dataDir.absolutePath
        return try {
            val json = context.assets.open(STYLE_ASSET).bufferedReader().use { it.readText() }
            if (!json.trimStart().startsWith("{")) error("style is not JSON")
            json.replace("{DATA_DIR}", dir)
        } catch (e: Exception) {
            Log.w("OnPath", "Style asset missing, using fallback: $e")
            fallback(dataDir)
        }
    }

    /** Small but usable style: base map basics + route overlays, if the data files exist. */
    fun fallback(dataDir: File): String {
        val dir = dataDir.absolutePath
        val hasBase = File(dataDir, "base.pmtiles").isFile
        val hasRoutes = File(dataDir, "routes.geojson").isFile
        val sources = mutableListOf<String>()
        val layers = mutableListOf("""{"id":"background","type":"background","paint":{"background-color":"#f3f1e8"}}""")
        if (hasBase) {
            sources += """"base":{"type":"vector","url":"pmtiles://file://$dir/base.pmtiles","attribution":"© OpenStreetMap contributors"}"""
            layers += """{"id":"wood","type":"fill","source":"base","source-layer":"landcover","filter":["==",["get","class"],"wood"],"paint":{"fill-color":"#d6e8c4"}}"""
            layers += """{"id":"grass","type":"fill","source":"base","source-layer":"landcover","filter":["==",["get","class"],"grass"],"paint":{"fill-color":"#e6efd6"}}"""
            layers += """{"id":"water","type":"fill","source":"base","source-layer":"water","paint":{"fill-color":"#a9d3ef"}}"""
            layers += """{"id":"waterway","type":"line","source":"base","source-layer":"waterway","paint":{"line-color":"#a9d3ef","line-width":1.2}}"""
            layers += """{"id":"roads","type":"line","source":"base","source-layer":"transportation","filter":["!",["in",["get","class"],["literal",["path","track"]]]],"paint":{"line-color":"#ffffff","line-width":["interpolate",["linear"],["zoom"],10,1,16,6]}}"""
            layers += """{"id":"tracks","type":"line","source":"base","source-layer":"transportation","filter":["==",["get","class"],"track"],"paint":{"line-color":"#8a6d3b","line-width":1.5,"line-dasharray":[3,1]}}"""
            layers += """{"id":"paths","type":"line","source":"base","source-layer":"transportation","filter":["==",["get","class"],"path"],"paint":{"line-color":"#7a3b2e","line-width":1.4,"line-dasharray":[2,1.5]}}"""
            layers += """{"id":"buildings","type":"fill","source":"base","source-layer":"building","minzoom":14,"paint":{"fill-color":"#ddd6cc"}}"""
        }
        if (hasRoutes) {
            sources += """"routes":{"type":"geojson","data":"file://$dir/routes.geojson"}"""
            for (kind in listOf("hike", "bike", "mtb")) {
                layers += """{"id":"routes-$kind","type":"line","source":"routes","filter":["==",["get","kind"],"$kind"],"layout":{"visibility":"none","line-join":"round"},"paint":{"line-color":"#3fa34d","line-opacity":0.35,"line-width":8}}"""
            }
        }
        return """{"version":8,"name":"OnPath fallback","sources":{${sources.joinToString(",")}},"layers":[${layers.joinToString(",")}]}"""
    }
}
