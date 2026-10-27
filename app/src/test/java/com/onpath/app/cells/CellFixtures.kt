package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small synthetic format-2 cells and region packages for the store / download tests (built on the fly, ~KB). */
object CellFixtures {

    val LAYERS = mapOf(
        "base.pmtiles" to (1 to 2), "overlays.pmtiles" to (1 to 2), "contours.pmtiles" to (1 to 2),
        "terrain.pmtiles" to (4 to 1), // WebP, not compressed – like real format-2 terrain
    )

    /** Tile content that says where it came from: "<tag>:<layer>:z/x/y". */
    fun content(tag: String, layer: String, z: Int, x: Int, y: Int) = "$tag:$layer:$z/$x/$y".toByteArray()

    /** Tiles z10–[maxZ] of [cell]. */
    fun cellTiles(cell: CellId, maxZ: Int = 12): List<Triple<Int, Int, Int>> = (10..maxZ).flatMap { z ->
        val s = 1 shl (z - 10)
        (0 until s).flatMap { dx -> (0 until s).map { dy -> Triple(z, cell.x * s + dx, cell.y * s + dy) } }
    }

    /** Writes the files of one cell version (as downloaded and verified) into [dir]; returns its manifest. */
    fun cell(dir: File, cell: CellId, version: String, tag: String = "$cell@$version", pois: Int = 3, maxZ: Int = 12): CellManifest {
        dir.mkdirs()
        val files = JSONArray()
        for ((name, tt) in LAYERS) {
            val f = File(dir, name)
            PmTiles.write(cellTiles(cell, maxZ).associateWith { (z, x, y) -> content(tag, name, z, x, y) }, f, tt.first, tt.second,
                """{"vector_layers":[{"id":"${name.substringBefore('.')}"}]}""")
            files.put(fileJson(name, f, cell, version))
        }
        val b = cell.bbox
        val pts = (0 until pois).map { i -> (b[1] + (b[3] - b[1]) * (i + 1) / (pois + 1)) to (b[0] + (b[2] - b[0]) * (i + 1) / (pois + 1)) }
        File(dir, "pois.geojson").writeText(geojson(pts, tag))
        files.put(fileJson("pois.geojson", File(dir, "pois.geojson"), cell, version))
        File(dir, "addresses").mkdirs()
        val addr = File(dir, "addresses/${cell.id}.sqlite").apply { writeText("addresses of $tag") }
        files.put(fileJson("addresses/${cell.id}.sqlite", addr, cell, version))
        val m = JSONObject().put("cell", cell.id).put("format", 2).put("osm_date", version).put("built", "${version}T03:00:00Z")
            .put("min_app", "0.1.0").put("files", files).put("total_size", (0 until files.length()).sumOf { files.getJSONObject(it).getLong("size") })
        File(dir, CellStore.MANIFEST).writeText(m.toString())
        return CellManifest.parse(m.toString())
    }

    private fun fileJson(name: String, f: File, cell: CellId, version: String) = JSONObject().put("name", name).put("size", f.length())
        .put("sha256", CellApi.sha256(f)).put("url", "cells/f2/${cell.path}/$version/$name")

    fun geojson(points: List<Pair<Double, Double>>, tag: String) = JSONObject().put("type", "FeatureCollection").put("features",
        JSONArray(points.mapIndexed { i, (lat, lon) ->
            JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lon, lat))))
                .put("properties", JSONObject().put("id", "$tag-$i").put("kind", "restaurant").put("name", "$tag $i"))
        })).toString()

    /**
     * A format-2 region package in [dataDir]: tiles z8–11 over the 2 × 2 cells 543–544 / 361–362, POIs in each cell.
     */
    fun region(dataDir: File, tag: String = "region") {
        val cells = listOf(CellId(543, 361), CellId(544, 361), CellId(543, 362), CellId(544, 362))
        for ((name, tt) in LAYERS) {
            val tiles = HashMap<Triple<Int, Int, Int>, ByteArray>()
            for (c in cells) for (t in cellTiles(c, 11)) tiles[t] = content(tag, name, t.first, t.second, t.third)
            for (z in 8..9) { val s = 10 - z; for (c in cells) { val t = Triple(z, c.x shr s, c.y shr s); tiles[t] = content(tag, name, t.first, t.second, t.third) } }
            PmTiles.write(tiles, File(dataDir, name), tt.first, tt.second, """{"vector_layers":[{"id":"r-${name.substringBefore('.')}"}]}""")
        }
        File(dataDir, "pois.geojson").writeText(geojson(cells.map { it.center.lat to it.center.lon }, tag))
    }
}
