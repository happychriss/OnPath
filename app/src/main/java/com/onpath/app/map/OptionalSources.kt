package com.onpath.app.map

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Removes style sources whose local data file is missing, together with every layer that uses them.
 *
 * Only `base` and `terrain` are essential; everything else (contours, pois, trail names, …) is optional
 * data that a region may not have. MapLibre would otherwise log errors or show nothing for those layers,
 * and new optional files can be added to the style without any app change.
 */
object OptionalSources {

    /** Sources that are never stripped (the map cannot work without them anyway). */
    private val ESSENTIAL = setOf("base", "terrain")

    /** The local file a source points to ("file://…" or "pmtiles://file://…"), or null if remote/inline. */
    fun localFile(source: JSONObject): String? {
        val url = source.optString("data", "").ifEmpty { source.optString("url", "") }
        val path = url.removePrefix("pmtiles://")
        return if (path.startsWith("file://")) path.removePrefix("file://") else null
    }

    /** Logging must never break style loading (and is not available in JVM tests). */
    private fun log(msg: String) { try { Log.i("OnPath", msg) } catch (_: Throwable) { } }

    fun strip(styleJson: String, exists: (String) -> Boolean = { File(it).isFile }): String = try {
        val root = JSONObject(styleJson)
        val sources = root.optJSONObject("sources") ?: return styleJson
        val missing = mutableSetOf<String>()
        sources.keys().asSequence().toList().forEach { id ->
            if (id in ESSENTIAL) return@forEach
            val file = sources.optJSONObject(id)?.let { localFile(it) } ?: return@forEach
            if (!exists(file)) missing += id
        }
        if (missing.isEmpty()) styleJson else {
            missing.forEach { sources.remove(it) }
            val layers = root.optJSONArray("layers") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until layers.length()) {
                val l = layers.optJSONObject(i) ?: continue
                if (l.optString("source") !in missing) kept.put(l)
            }
            root.put("layers", kept)
            log("Optional map data missing, layers hidden: $missing")
            root.toString()
        }
    } catch (e: Exception) {
        log("could not check optional sources: $e")
        styleJson
    }
}
