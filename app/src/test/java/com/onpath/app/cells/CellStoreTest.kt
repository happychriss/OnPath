package com.onpath.app.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CellStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    private fun download(store: CellStore, c: CellId, version: String, tag: String = "$c@$version"): CellStore.Download {
        val dir = File(File(store.downloadDir, c.id), version)
        return CellStore.Download(CellFixtures.cell(dir, c, version, tag), dir)
    }

    private fun tileOf(dir: File, layer: String, z: Int, x: Int, y: Int): String? =
        PmTiles.Reader(File(dir, layer)).use { r -> r.tile(z, x, y)?.let { String(it) } }

    private fun poiNames(dir: File): List<String> {
        val out = ArrayList<String>()
        GeoJsonMerge.forEachFeature(File(dir, "pois.geojson")) { out += it.asJsonObject["properties"].asJsonObject["name"].asString }
        return out.sorted()
    }

    @Test fun addReplaceDelete() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        assertNull(store.mapDir())

        val g1 = store.apply(add = listOf(download(store, a, "2026-10-19")))
        assertEquals(1, g1.number)
        assertEquals(g1.dir, store.mapDir())
        assertEquals(listOf(a), store.installed().map { it.id })
        assertEquals("$a@2026-10-19:base.pmtiles:12/${a.x * 4 + 3}/${a.y * 4}", tileOf(g1.dir, "base.pmtiles", 12, a.x * 4 + 3, a.y * 4))
        PmTiles.Reader(File(g1.dir, "terrain.pmtiles")).use { assertEquals(4, it.header.tileType); assertEquals(1, it.header.tileCompression) }
        assertEquals(3, poiNames(g1.dir).size)
        assertTrue(File(data, "addresses/${a.id}.sqlite").isFile)
        assertTrue(store.installed().single().bytes.getValue("base.pmtiles") > 0)

        // neighbour: both cells in one generation, the previous one kept until the map confirms
        val g2 = store.apply(add = listOf(download(store, b, "2026-10-19")))
        assertEquals(setOf(a, b), g2.cellIds)
        assertEquals(21L * 2, PmTiles.verify(File(g2.dir, "base.pmtiles")))
        assertNotNull(tileOf(g2.dir, "overlays.pmtiles", 10, a.x, a.y)); assertNotNull(tileOf(g2.dir, "overlays.pmtiles", 10, b.x, b.y))
        assertEquals(6, poiNames(g2.dir).size)
        assertTrue(g1.dir.isDirectory)
        store.confirmLoaded(g2.number)
        assertFalse(g1.dir.exists())

        // replace a with a new version: a's tiles new, b's untouched
        val g3 = store.apply(add = listOf(download(store, a, "2026-11-19")))
        assertEquals("2026-11-19", g3.cell(a)!!.version)
        assertEquals("$a@2026-11-19:base.pmtiles:10/${a.x}/${a.y}", tileOf(g3.dir, "base.pmtiles", 10, a.x, a.y))
        assertEquals("$b@2026-10-19:base.pmtiles:10/${b.x}/${b.y}", tileOf(g3.dir, "base.pmtiles", 10, b.x, b.y))
        assertEquals(6, poiNames(g3.dir).size)
        assertEquals(3, poiNames(g3.dir).count { it.startsWith("$a@2026-11-19") })
        assertEquals("2026-10-19", g3.cell(b)!!.version)
        assertTrue(g3.cell(b)!!.bytes.getValue("base.pmtiles") > 0) // sizes carried over

        // delete a
        val g4 = store.remove(setOf(a))
        assertEquals(setOf(b), g4.cellIds)
        assertNull(tileOf(g4.dir, "base.pmtiles", 10, a.x, a.y))
        assertFalse(File(data, "addresses/${a.id}.sqlite").exists())
        assertTrue(File(data, "addresses/${b.id}.sqlite").isFile)

        // delete the last cell: no store any more → the data folder is used
        store.remove(setOf(b))
        assertNull(store.mapDir())
        assertTrue(store.installed().isEmpty())
    }

    @Test fun brokenInputKeepsThePreviousGeneration() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val g1 = store.apply(add = listOf(download(store, a, "v1")))
        val d = download(store, b, "v1")
        val base = File(d.dir, "base.pmtiles")
        base.writeBytes(base.readBytes().copyOf(200)) // damaged after verification (disk error)
        try { store.apply(add = listOf(d)); fail("expected StoreException") } catch (e: CellStore.StoreException) {
            assertTrue(e.message!!, e.message!!.contains("previous map is kept"))
        }
        assertEquals(g1.number, store.current()!!.number)
        assertEquals(setOf(a), store.current()!!.cellIds)
        assertTrue(store.storeDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** A crash while writing leaves a .tmp generation: it is never used and cleaned up. */
    @Test fun crashLeftoversAreIgnored() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val g1 = store.apply(add = listOf(download(store, a, "v1")))
        val half = File(store.storeDir, "g000002.tmp").apply { mkdirs() }
        File(half, "base.pmtiles").writeText("half written")
        assertEquals(g1.number, store.current()!!.number) // tmp is not a generation
        // a renamed generation without (or with broken) store.json is skipped too
        File(store.storeDir, "g000003").mkdirs()
        assertEquals(g1.number, store.current()!!.number)
        store.cleanTemp()
        assertFalse(half.exists())
        // the next change gets a fresh number and works
        val g = store.apply(add = listOf(download(store, b, "v1")))
        assertEquals(setOf(a, b), g.cellIds)
    }

    /**
     * Half-written (.tmp) and set-aside (.bad) folders are not generations (a reader must not raise a false "unreadable"
     * ERROR while another install writes one) – but they still count for numbering.
     */
    @Test fun halfWrittenGenerationIsNotReadAndRaisesNoError() {
        com.onpath.app.debug.Problems.clear()
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val g1 = store.apply(add = listOf(download(store, a, "v1")))
        File(store.storeDir, "g000005.tmp").apply { mkdirs(); File(this, "store.json").writeText("{half") }
        File(store.storeDir, "g000006.bad").mkdirs()
        assertEquals(g1.number, store.current()!!.number)
        assertTrue(com.onpath.app.debug.Problems.snapshot().none { it.area == "cells" && it.message.contains("unreadable") })
        // numbering stays above the leftovers
        val g = store.apply(add = listOf(download(store, b, "v1")))
        assertTrue("new generation ${g.number} above the leftovers", g.number > 6)
    }

    @Test fun lowStorageChangesNothing() {
        val data = tmp.newFolder("data")
        var free = Long.MAX_VALUE
        val store = CellStore(data) { free }
        val g1 = store.apply(add = listOf(download(store, a, "v1")))
        free = 10L * 1024 * 1024
        try { store.apply(add = listOf(download(store, b, "v1"))); fail() } catch (e: CellStore.StoreException) {
            assertTrue(e.message!!, e.message!!.startsWith("Not enough space"))
        }
        assertEquals(g1.number, store.current()!!.number)
        assertTrue(store.storeDir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** Cells override a format-2 region package where both exist; the package fills the rest and z < 10. */
    @Test fun regionPackageAndCellsJoin() {
        val data = tmp.newFolder("data")
        CellFixtures.region(data)
        val store = CellStore(data)
        val g = store.apply(add = listOf(download(store, a, "v1")))
        val c = CellId(543, 362) // region-only cell
        assertEquals("$a@v1:base.pmtiles:11/${a.x * 2}/${a.y * 2}", tileOf(g.dir, "base.pmtiles", 11, a.x * 2, a.y * 2))
        assertEquals("region:base.pmtiles:11/${c.x * 2}/${c.y * 2}", tileOf(g.dir, "base.pmtiles", 11, c.x * 2, c.y * 2))
        assertEquals("region:base.pmtiles:9/${a.x / 2}/${a.y / 2}", tileOf(g.dir, "base.pmtiles", 9, a.x / 2, a.y / 2))
        // the cell's z12 tiles exist, region has none there
        assertNotNull(tileOf(g.dir, "base.pmtiles", 12, a.x * 4, a.y * 4))
        // POIs: the region's POI inside cell a is replaced by the cell's three
        val names = poiNames(g.dir)
        assertEquals(3 + 3, names.size)
        assertEquals(3, names.count { it.startsWith("region") })
        // vector_layers of region and cell united
        PmTiles.Reader(File(g.dir, "base.pmtiles")).use { r ->
            val ids = org.json.JSONObject(r.metadata()).getJSONArray("vector_layers").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") } }
            assertEquals(setOf("r-base", "base"), ids.toSet())
        }
        assertFalse(store.regionChanged())
        // a new region package import → regionChanged → re-joined
        Thread.sleep(1100)
        CellFixtures.region(data, tag = "region2")
        assertTrue(store.regionChanged())
        val g2 = store.apply()
        assertEquals("region2:base.pmtiles:11/${c.x * 2}/${c.y * 2}", tileOf(g2.dir, "base.pmtiles", 11, c.x * 2, c.y * 2))
        assertEquals("$a@v1:base.pmtiles:11/${a.x * 2}/${a.y * 2}", tileOf(g2.dir, "base.pmtiles", 11, a.x * 2, a.y * 2))
    }

    /** A format-1 package (no overlays.pmtiles) is not joined: once cells exist, the map shows cells only. */
    @Test fun formatOnePackageIsNotJoined() {
        val data = tmp.newFolder("data")
        CellFixtures.region(data)
        File(data, "overlays.pmtiles").delete()
        File(data, "routes.geojson").writeText("{}")
        val store = CellStore(data)
        val g = store.apply(add = listOf(download(store, a, "v1")))
        assertTrue(g.region.isEmpty())
        assertNull(tileOf(g.dir, "base.pmtiles", 9, a.x / 2, a.y / 2))
    }

    @Test fun rollbackGoesToThePreviousGeneration() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val g1 = store.apply(add = listOf(download(store, a, "v1")))
        val g2 = store.apply(add = listOf(download(store, b, "v1")))
        assertTrue(store.rollback(g2.number))
        assertEquals(g1.number, store.current()!!.number)
        assertFalse(store.rollback(g1.number)) // nothing older to go back to
    }

    /** The map style, POI tap and search read the generation folder; its number is known from the path. */
    @Test fun generationFolderIsTheMapFolder() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        val g = store.apply(add = listOf(download(store, a, "v1")))
        assertEquals(g.number, CellStore.generationOf(g.dir))
        assertNull(CellStore.generationOf(data))
        assertNull(CellStore.generationOf(File(store.storeDir, "g000009.tmp")))
        assertEquals(2, com.onpath.app.data.DataFiles.formatOf { File(g.dir, it).isFile })
        val index = com.onpath.app.search.OfflineIndex.load(g.dir, tmp.newFolder("cache"))
        assertEquals(3, index.entries.count { it.place.name?.startsWith("$a@v1") == true })
    }

    @Test fun pendingDownloadsAreFound() {
        val data = tmp.newFolder("data")
        val store = CellStore(data)
        download(store, a, "v1")
        File(File(store.downloadDir, b.id), "v1.part").mkdirs() // unfinished: not a pending download
        assertEquals(listOf(a), store.pendingDownloads().map { it.manifest.cell })
    }

    private fun pkgDownload(store: CellStore, p: CellId, version: String): CellStore.Download {
        val dir = File(File(store.downloadDir, p.id), version)
        return CellStore.Download(CellFixtures.pkg(dir, p, version), dir)
    }

    /**
     * Region packages (map-cells.md §20) join the store like cells: a package owns z7-9 under its tile (base, overlays,
     * relief), cells own z10+; with a package installed the region package's z<10 tiles go (the map draws z7-9 from the
     * detailed sources then). A store with only packages is not a map folder; packages leave like cells.
     */
    @Test fun regionPackagesJoinWithCells() {
        val data = tmp.newFolder("data")
        CellFixtures.region(data)
        val store = CellStore(data)
        val p = CellId.packageOf(a) // 7-67-45
        val g1 = store.apply(add = listOf(pkgDownload(store, p, "p1")))
        assertNull("only packages: not a map folder", store.mapDir())
        val g2 = store.apply(add = listOf(download(store, a, "v1")))
        assertEquals(g2.dir, store.mapDir())
        assertEquals(setOf(a, p), g2.cellIds)
        // z7-9 from the package, z10+ from the cell, z10+ elsewhere from the region package
        assertEquals("$p@p1:base.pmtiles:9/${a.x shr 1}/${a.y shr 1}", tileOf(g2.dir, "base.pmtiles", 9, a.x shr 1, a.y shr 1))
        assertEquals("$p@p1:base.pmtiles:7/${p.x}/${p.y}", tileOf(g2.dir, "base.pmtiles", 7, p.x, p.y))
        assertEquals("$a@v1:base.pmtiles:10/${a.x}/${a.y}", tileOf(g2.dir, "base.pmtiles", 10, a.x, a.y))
        assertEquals("region:base.pmtiles:10/${b.x}/${b.y}", tileOf(g2.dir, "base.pmtiles", 10, b.x, b.y))
        // the region package's own z8-9 tiles are left out while a package is installed (also outside the package)
        assertFalse(tileOf(g2.dir, "base.pmtiles", 9, b.x shr 1, b.y shr 1).orEmpty().startsWith("region"))
        PmTiles.Reader(File(g2.dir, "base.pmtiles")).use { assertEquals(7, it.header.minZoom) }
        PmTiles.Reader(File(g2.dir, "hillshade.pmtiles")).use { r ->
            assertEquals(4, r.header.tileType); assertEquals(7, r.header.minZoom); assertEquals(9, r.header.maxZoom)
        }
        assertTrue(g2.cell(p)!!.bytes.getValue("hillshade.pmtiles") > 0)
        assertEquals(3, poiNames(g2.dir).count { it.startsWith("$a@v1") }) // packages carry no POIs
        assertTrue(g1.number < g2.number)

        // a new package version replaces only its tiles; the cell stays
        val g3 = store.apply(add = listOf(pkgDownload(store, p, "p2")))
        assertEquals("$p@p2:overlays.pmtiles:8/${a.x shr 2}/${a.y shr 2}", tileOf(g3.dir, "overlays.pmtiles", 8, a.x shr 2, a.y shr 2))
        assertEquals("$a@v1:base.pmtiles:10/${a.x}/${a.y}", tileOf(g3.dir, "base.pmtiles", 10, a.x, a.y))

        // the package goes: its tiles go, the region package's z<10 tiles come back
        val g4 = store.remove(setOf(p))
        assertEquals(setOf(a), g4.cellIds)
        assertFalse(File(g4.dir, "hillshade.pmtiles").exists())
        assertEquals("region:base.pmtiles:9/${a.x shr 1}/${a.y shr 1}", tileOf(g4.dir, "base.pmtiles", 9, a.x shr 1, a.y shr 1))
    }
}
