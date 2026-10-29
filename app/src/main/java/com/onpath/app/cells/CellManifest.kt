package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject

/** manifest.json of a cell version (docs/design/map-cells.md §6). */
data class CellManifest(
    val cell: CellId,
    val format: Int,
    /** Version folder on the server ("2026-10-20-s2"; schema 1 cells: the OSM date "2026-10-19"). */
    val version: String,
    val built: String?,
    val minApp: String?,
    val files: List<File>,
    val totalSize: Long,
    val json: String,
) {
    data class File(val name: String, val size: Long, val sha256: String, val url: String)

    companion object {
        /** Names a cell file may have: plain names or "addresses/<cell>.sqlite"; nothing that leaves the folder. */
        private val SAFE_NAME = Regex("""^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?$""")

        /** Parses and checks a manifest; throws IllegalArgumentException with a plain reason when unusable. */
        fun parse(json: String): CellManifest {
            val o = JSONObject(json)
            val cell = CellId.parse(o.optString("cell")) ?: throw IllegalArgumentException("manifest without a valid cell id")
            // schema 1 manifests have no "version", only the OSM date
            val version = o.optString("version").ifBlank { o.optString("osm_date") }
                .ifBlank { throw IllegalArgumentException("manifest without version / osm_date") }
            if (!Regex("""^[0-9A-Za-z._-]+$""").matches(version)) throw IllegalArgumentException("bad version $version")
            val arr = o.optJSONArray("files") ?: JSONArray()
            val files = (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                val name = f.optString("name")
                if (!SAFE_NAME.matches(name) || name.split('/').any { it == ".." || it == "." })
                    throw IllegalArgumentException("unsafe file name in manifest: $name")
                val sha = f.optString("sha256").lowercase()
                if (!Regex("^[0-9a-f]{64}$").matches(sha)) throw IllegalArgumentException("no sha256 for $name")
                val size = f.optLong("size", -1)
                if (size < 0) throw IllegalArgumentException("no size for $name")
                File(name, size, sha, f.optString("url").ifBlank { throw IllegalArgumentException("no url for $name") })
            }
            if (files.isEmpty()) throw IllegalArgumentException("manifest lists no files")
            return CellManifest(cell, o.optInt("format", 1), version, o.optString("built").ifBlank { null },
                o.optString("min_app").ifBlank { null }, files, o.optLong("total_size", files.sumOf { it.size }), json)
        }
    }
}
