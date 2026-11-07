package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Optional measurement (map-cells.md §19.9) – skipped unless `ONPATH_STORE_BENCH=1`: how long one store generation
 * takes to write as the number of installed cells grows. Synthetic cells of real size (~10 MB in the store each, like
 * the field store on the Galaxy S10: 495 MB for 46 cells): base 5.2 MB, overlays 3.0 MB, terrain 1.0 MB (WebP),
 * contours 50 KB, ~2 500 POIs (0.8 MB GeoJSON). Writes several GB to the temp folder; prints a table.
 *   ONPATH_STORE_BENCH=1 tools/dev/gradle_slot.sh -- ./gradlew :app:testDebugUnitTest --tests '*StoreWriteCostTest' --rerun -i
 */
class StoreWriteCostTest {
    @get:Rule val tmp = TemporaryFolder()

    private val layers = mapOf( // name → (bytes per cell, tile type, tile compression)
        "base.pmtiles" to Triple(5_200_000, 1, 2), "overlays.pmtiles" to Triple(3_000_000, 1, 2),
        "terrain.pmtiles" to Triple(1_000_000, 4, 1), "contours.pmtiles" to Triple(50_000, 1, 2),
    )

    private fun writeCell(dir: File, c: CellId, rnd: Random): CellStore.Download {
        dir.mkdirs()
        val files = JSONArray()
        val tiles = CellFixtures.cellTiles(c, 14) // 341 tiles z10–14
        for ((name, spec) in layers) {
            val per = spec.first / tiles.size
            val f = File(dir, name)
            PmTiles.write(tiles.associateWith { rnd.nextBytes(per / 2 + rnd.nextInt(per)) }, f, spec.second, spec.third,
                """{"vector_layers":[{"id":"${name.substringBefore('.')}"}]}""")
            files.put(JSONObject().put("name", name).put("size", f.length()).put("sha256", "0".repeat(64)).put("url", "u"))
        }
        val b = c.bbox
        val pois = JSONArray()
        repeat(2_500) { i ->
            pois.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point").put("coordinates",
                    JSONArray(listOf(b[0] + (b[2] - b[0]) * rnd.nextDouble(), b[1] + (b[3] - b[1]) * rnd.nextDouble()))))
                .put("properties", JSONObject().put("id", "n${c.id}-$i").put("kind", listOf("restaurant", "peak", "bench", "shelter")[i % 4])
                    .put("name", "Place ${c.id} $i").put("ele", 1000 + i).put("opening_hours", "Mo-Su 09:00-18:00")
                    .put("website", "https://example.org/${c.id}/$i").put("note", "x".repeat(120))))
        }
        File(dir, CellStore.POIS).writeText(JSONObject().put("type", "FeatureCollection").put("features", pois).toString())
        files.put(JSONObject().put("name", CellStore.POIS).put("size", File(dir, CellStore.POIS).length()).put("sha256", "0".repeat(64)).put("url", "u"))
        val m = JSONObject().put("cell", c.id).put("format", 2).put("osm_date", "2026-11-01").put("built", "2026-11-01T03:00:00Z")
            .put("min_app", "0.1.0").put("files", files).put("total_size", 0)
        File(dir, CellStore.MANIFEST).writeText(m.toString())
        return CellStore.Download(CellManifest.parse(m.toString()), dir)
    }

    @Test fun generationWriteTimeByCellCount() {
        assumeTrue("ONPATH_STORE_BENCH not set", System.getenv("ONPATH_STORE_BENCH") == "1")
        val data = tmp.newFolder("data")
        val src = tmp.newFolder("src")
        val store = CellStore(data)
        val rnd = Random(7)
        val ids = (0 until 8).flatMap { dx -> (0 until 6).map { dy -> CellId(530 + dx, 334 + dy) } } // 48 cells
        val cells = ids.map { writeCell(File(src, it.id), it, rnd) }
        // the store moves nothing out of a download except addresses (none here): the same folders are joined again
        val rows = ArrayList<String>()
        var have = 0
        fun timed(label: String, add: List<CellStore.Download>, remove: Set<CellId> = emptySet()) {
            val t0 = System.nanoTime()
            val g = store.apply(add = add, remove = remove)
            val ms = (System.nanoTime() - t0) / 1_000_000
            store.confirmLoaded(g.number)
            val mb = g.files.values.sum() / 1_048_576.0
            rows += "%-28s %3d cells  %6.0f MB  %6d ms  %5.0f MB/s".format(label, g.cells.size, mb, ms, mb * 1000 / ms.coerceAtLeast(1))
        }
        for (target in listOf(1, 2, 4, 8, 16, 32, 44)) {
            if (target - 1 > have) { store.apply(add = cells.subList(have, target - 1)).also { store.confirmLoaded(it.number) }; have = target - 1 }
            timed("+1 cell", listOf(cells[have])); have++
        }
        // the same 4 cells: one generation vs four
        timed("+4 cells in one generation", cells.subList(44, 48)); have = 48
        val last4 = ids.subList(44, 48).toSet()
        timed("−4 cells", emptyList(), last4); have = 44
        var four = 0L
        for (i in 44 until 48) { val t0 = System.nanoTime(); store.apply(add = listOf(cells[i])).also { store.confirmLoaded(it.number) }; four += (System.nanoTime() - t0) / 1_000_000 }
        rows += "+4 cells one by one          48 cells             %6d ms (sum of 4)".format(four)
        timed("rewrite, nothing added", emptyList())
        // where the time goes at 48 cells: each part alone, on the current generation's files
        val g = store.current()!!
        val parts = ArrayList<String>()
        for (n in CellStore.LAYERS + CellStore.POIS + PoiTiles.FILE) {
            val f = File(g.dir, n).takeIf { it.isFile } ?: continue
            val out = File(tmp.root, "part-$n")
            val t0 = System.nanoTime()
            if (n == CellStore.POIS) GeoJsonMerge.merge(listOf(GeoJsonMerge.Input(f) { _, _ -> true }), out)
            else PmTiles.merge(listOf(PmTiles.Input(f) { _, _, _ -> true }), out)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val v0 = System.nanoTime(); if (n != CellStore.POIS) PmTiles.verify(out); val vms = (System.nanoTime() - v0) / 1_000_000
            parts += "  %-18s %6.0f MB  copy %5d ms  verify %4d ms".format(n, f.length() / 1_048_576.0, ms, vms)
            out.delete()
        }
        val enc0 = System.nanoTime()
        PoiTiles.build(listOf(PoiTiles.Input(File(cells[0].dir, CellStore.POIS)) { _, _ -> true }), File(tmp.root, "enc.pmtiles"), File(tmp.root, "enc-work"))
        parts += "  POI tiles of one added cell: encode %d ms".format((System.nanoTime() - enc0) / 1_000_000)
        println("STORE-BENCH generation write time (laptop JVM, ${Runtime.getRuntime().availableProcessors()} cores)\n" +
            rows.joinToString("\n") + "\nparts at 48 cells:\n" + parts.joinToString("\n"))
    }
}
