package com.onpath.app.cells

import com.onpath.app.search.BaseMapPlaces
import com.onpath.app.search.OfflineIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Optional tests on real data – skipped unless the environment says where it is:
 * - `ONPATH_CELLS_DIR=…/onpath-build/cells-data/cells/f2` – two neighbouring format-2 cells built locally
 *   (10/543/361 Meran and 10/544/361 Bozen side), joined in the store;
 * - `ONPATH_REGION_DIR=…/onpath-build/out-f2` (optional, with the cells) – a format-2 region package joined too;
 * - `ONPATH_LIVE=1` – downloads cell 10-543-361 from the test doorman (key `onpath.mapKey.test` from
 *   ~/.gradle/gradle.properties, never printed) and joins it.
 */
class RealCellsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    private fun cellDir(root: File, c: CellId): File {
        val v = org.json.JSONObject(File(root, "${c.path}/current.json").readText()).getString("version")
        return File(root, "${c.path}/$v")
    }

    /** Copy (the store moves the address file away). */
    private fun asDownload(store: CellStore, src: File): CellStore.Download {
        val m = CellManifest.parse(File(src, CellStore.MANIFEST).readText())
        val dir = store.completeDir(m)
        src.copyRecursively(dir, overwrite = true)
        return CellStore.Download(m, dir)
    }

    private fun tiles(f: File): Map<Long, PmTiles.Entry> = PmTiles.Reader(f).use { r -> r.entries().associateBy { it.tileId } }

    @Test fun twoRealNeighboursJoin() {
        val root = System.getenv("ONPATH_CELLS_DIR")?.let(::File)
        assumeTrue("ONPATH_CELLS_DIR not set", root != null && File(root, "10").isDirectory)
        val data = tmp.newFolder("data")
        val regionDir = System.getenv("ONPATH_REGION_DIR")?.let(::File)?.takeIf { File(it, "overlays.pmtiles").isFile }
        regionDir?.listFiles()?.filter { it.name.endsWith(".pmtiles") || it.name == "pois.geojson" }?.forEach { it.copyTo(File(data, it.name)) }
        val store = CellStore(data)
        val da = asDownload(store, cellDir(root!!, a)); val db = asDownload(store, cellDir(root, b))
        val t0 = System.currentTimeMillis()
        store.apply(add = listOf(da))
        val g = store.apply(add = listOf(db))
        println("joined 2 real cells${if (regionDir != null) " + region" else ""} in ${System.currentTimeMillis() - t0} ms: " +
            g.files.entries.joinToString { "${it.key} ${it.value / 1024} KB" })
        for (layer in CellStore.LAYERS) {
            val ta = tiles(File(cellDir(root, a), layer)); val tb = tiles(File(cellDir(root, b), layer))
            assertTrue("$layer: cells share no tile", ta.keys.intersect(tb.keys).isEmpty())
            val joined = tiles(File(g.dir, layer))
            PmTiles.verify(File(g.dir, layer))
            if (regionDir == null) assertEquals(layer, ta.size + tb.size, joined.size)
            // every cell tile is in the store with the same bytes
            PmTiles.Reader(File(g.dir, layer)).use { out ->
                for ((src, entries) in listOf(File(cellDir(root, a), layer) to ta, File(cellDir(root, b), layer) to tb)) PmTiles.Reader(src).use { r ->
                    for ((id, e) in entries.entries.take(60)) assertTrue("$layer tile $id", out.tileBytes(joined.getValue(id)).contentEquals(r.tileBytes(e)))
                }
            }
        }
        // search reads places and trail names from both sides of the border (lon 11.25)
        val places = BaseMapPlaces.read(File(g.dir, "base.pmtiles"))
        assertTrue(places.any { it.lon < 11.25 } && places.any { it.lon > 11.25 })
        assertTrue(places.any { it.name.startsWith("Meran") })
        val trails = OfflineIndex.readTrailsFromTiles(File(g.dir, "overlays.pmtiles"))
        assertTrue(trails.any { it.lon < 11.25 } && trails.any { it.lon > 11.25 })
        val pois = OfflineIndex.readPois(File(g.dir, "pois.geojson"))
        if (regionDir == null) assertEquals(OfflineIndex.readPois(File(cellDir(root, a), "pois.geojson")).size +
            OfflineIndex.readPois(File(cellDir(root, b), "pois.geojson")).size, pois.size)
        assertTrue(File(data, "addresses/${a.id}.sqlite").isFile && File(data, "addresses/${b.id}.sqlite").isFile)
    }

    @Test fun liveTestServer() {
        assumeTrue("ONPATH_LIVE not set", System.getenv("ONPATH_LIVE") == "1")
        val key = File(System.getProperty("user.home"), ".gradle/gradle.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("onpath.mapKey.test=") }?.substringAfter('=')?.trim()
        assumeTrue("no onpath.mapKey.test in ~/.gradle/gradle.properties", !key.isNullOrBlank())
        val url = (System.getenv("ONPATH_LIVE_URL") ?: "https://maps-test.free-your-pictures.org") + "/v1"
        val api = CellApi(url, key!!, "0.8.0")
        val st = api.status(listOf(a.id)).cells.single()
        assumeTrue("cell ${a.id} not ready on the test server (${st.state})", st.state == "ready")
        val m = api.manifest(a)
        assertEquals(a, m.cell); assertEquals(2, m.format)
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val part = store.partDir(m)
        for (f in m.files) {
            val dest = File(part, f.name)
            api.download(f, File(part, f.name + ".part"))
            assertTrue(File(part, f.name + ".part").renameTo(dest.also { it.parentFile?.mkdirs() }))
        }
        File(part, CellStore.MANIFEST).writeText(m.json)
        val done = store.completeDir(m)
        assertTrue(part.renameTo(done))
        val g = store.apply(add = listOf(CellStore.Download(m, done)))
        println("live: ${m.cell} ${m.version} ${m.totalSize / 1024} KB → generation ${g.number}: ${g.files}")
        assertTrue(BaseMapPlaces.read(File(g.dir, "base.pmtiles")).any { it.name.startsWith("Meran") })
    }
}
