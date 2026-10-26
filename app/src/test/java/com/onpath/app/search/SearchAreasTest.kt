package com.onpath.app.search

import com.onpath.app.cells.CellFixtures
import com.onpath.app.cells.CellId
import com.onpath.app.cells.CellStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Per-area offline search index (owner 2026-10-23, field: a full rebuild took 59 s after every map store generation):
 * an incremental change gives the same index as a full build, old installs migrate, a one-area change is fast.
 */
class SearchAreasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = CellId(543, 361)
    private val b = CellId(544, 361)

    private fun keys(idx: OfflineIndex) = idx.entries.map { e ->
        val p = e.place; "${p.id}|${p.name}|${p.kind}|${p.sub}|${p.lat}|${p.lon}|${p.ele}|${p.alt}|${p.poi}"
    }.sorted()

    private fun download(store: CellStore, c: CellId, version: String): CellStore.Download {
        val dir = File(File(store.downloadDir, c.id), version)
        return CellStore.Download(CellFixtures.cell(dir, c, version), dir)
    }

    /** Region package + cells, changed step by step: incremental = full build = the old read of the joined store. */
    @Test fun incrementalEqualsFullBuild() {
        val data = tmp.newFolder("data")
        CellFixtures.region(data)
        val store = CellStore(data)
        val live = SearchAreas(tmp.newFolder("areas"))

        fun check(expectAdded: Int, expectRemoved: Int) {
            val plan = AreaPlan.of(data)
            val inc = live.build(plan)
            val s = live.lastStats!!
            assertEquals("added", expectAdded, s.added); assertEquals("removed", expectRemoved, s.removed)
            val full = SearchAreas(tmp.newFolder()).build(plan)
            assertEquals(keys(full), keys(inc))
            // the same POIs as a full read of the joined map store (the index before 2026-10-23)
            val gen = store.mapDir()!!
            assertEquals(OfflineIndex.load(gen, tmp.newFolder()).entries.map { it.place.id }.sorted(), inc.entries.map { it.place.id }.sorted())
        }

        store.apply(add = listOf(download(store, a, "v1")))
        check(expectAdded = 2, expectRemoved = 0) // cell a + region (region POI in a left out)
        store.apply(add = listOf(download(store, b, "v1")))
        check(expectAdded = 1, expectRemoved = 0) // only b is read; a and the region are reused
        store.apply(add = listOf(download(store, a, "v2")))
        check(expectAdded = 1, expectRemoved = 1)
        store.remove(setOf(b))
        check(expectAdded = 0, expectRemoved = 1)
        val now = live.build(AreaPlan.of(data))
        assertEquals(3, now.entries.count { it.place.name?.startsWith("$a@v2") == true })
        assertEquals(0, now.entries.count { it.place.name?.startsWith("$b@") == true })
        assertEquals(3, now.entries.count { it.place.name?.startsWith("region") == true }) // 4 region POIs, one inside a
    }

    /** Without cells: the data folder is one area, and the index equals the former full build (POIs, trails, places). */
    @Test fun dataFolderAreaEqualsFormerIndex() {
        val dir = tmp.newFolder("data")
        File(dir, OfflineIndex.POIS).writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.0915,46.7321]},"properties":{"id":"n1","kind":"hut","name":"Meraner Hütte","ele":"1960","opening_hours":"Jun-Oct","phone":"+39 1"}},
            {"type":"Feature","properties":{"id":"n3","kind":"cafe","name":"Props first"},"geometry":{"type":"Point","coordinates":[11.2,46.6]}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.10,46.70]},"properties":{"id":"n2","kind":"water"}}]}""")
        File(dir, OfflineIndex.TRAILS).writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[11.10,46.68],[11.11,46.68],[11.12,46.68]]},"properties":{"t":"n","name":"Algunder Waalweg","len":1500}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[11.12,46.68],[11.13,46.68]]},"properties":{"t":"n","name":"Algunder Waalweg","len":700}}]}""")
        javaClass.classLoader!!.getResourceAsStream("search/mini.pmtiles")!!.use { File(dir, OfflineIndex.BASE).outputStream().use(it::copyTo) }
        val files = tmp.newFolder("files")
        val areas = SearchAreas(File(files, "search-areas"))
        val idx = areas.build(AreaPlan.of(dir))
        val former = OfflineIndex.load(dir, tmp.newFolder())
        assertEquals(keys(former), keys(idx))
        // ranking unchanged: the same answers in the same order
        for (q in listOf("meran", "algunder waal", "merano", "hütte")) {
            val sq = SearchQuery(q, null, 46.68, 11.11)
            assertEquals(q, former.search(sq).map { it.place.id }, idx.search(sq).map { it.place.id })
        }
        // after an app start: read from the area file, nothing extracted, same content
        val again = SearchAreas(File(files, "search-areas"))
        val idx2 = again.build(AreaPlan.of(dir))
        assertEquals(1, again.lastStats!!.fromFiles); assertEquals(0, again.lastStats!!.extracted)
        assertEquals(keys(idx), keys(idx2))
        // a changed file = a new area version: extracted again, the old file goes
        File(dir, OfflineIndex.POIS).writeText("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[11.0915,46.7321]},"properties":{"id":"n9","kind":"hut","name":"Zoggler Hütte"}}]}""")
        File(dir, OfflineIndex.POIS).setLastModified(System.currentTimeMillis() + 5000)
        val idx3 = again.build(AreaPlan.of(dir))
        assertEquals(1, again.lastStats!!.extracted); assertEquals(1, again.lastStats!!.removed)
        assertTrue(idx3.entries.any { it.place.name == "Zoggler Hütte" })
        assertEquals(1, File(files, "search-areas").listFiles()!!.size)
    }

    /** Old installs: the single place cache goes, per-area files are written; a damaged area file is extracted again. */
    @Test fun migrationAndDamagedFile() {
        val data = tmp.newFolder("data")
        CellFixtures.region(data)
        val store = CellStore(data)
        store.apply(add = listOf(download(store, a, "v1"), download(store, b, "v1")))
        val files = tmp.newFolder("files")
        val legacy = File(files, "search-places-v1.tsv").apply { writeText("1:2\n") }
        val areasDir = File(files, "search-areas")
        val first = SearchAreas(areasDir, legacy).build(AreaPlan.of(data))
        assertFalse(legacy.exists())
        assertEquals(3, areasDir.listFiles()!!.size) // a, b, region
        // damaged (cut short) file of a
        val fa = areasDir.listFiles()!!.first { it.name.startsWith("cell-${a.id}@") }
        fa.writeText(fa.readLines().dropLast(2).joinToString("\n"))
        val restarted = SearchAreas(areasDir, legacy)
        val second = restarted.build(AreaPlan.of(data))
        assertEquals(2, restarted.lastStats!!.fromFiles); assertEquals(1, restarted.lastStats!!.extracted)
        assertEquals(keys(first), keys(second))
        assertNotNull(SearchAreas.read(fa, AreaPlan.of(data).areas.first { it.cell == a }))
    }

    // ------------------------------------------------------------------------------------------- 130k entries

    private fun writeGeneration(data: File, number: Int, versions: Map<CellId, String>, poisPerCell: Int) {
        val dir = File(data, "cells/store/g%06d".format(number)).apply { mkdirs() }
        val cells = JSONArray()
        File(dir, "pois.geojson").bufferedWriter().use { w ->
            w.write("""{"type":"FeatureCollection","features":[""")
            var first = true
            for ((c, v) in versions) {
                cells.put(JSONObject().put("id", c.id).put("version", v).put("built", "").put("format", 2)
                    .put("bytes", JSONObject()).put("addressBytes", 0).put("installedAt", 0))
                val bb = c.bbox
                for (i in 0 until poisPerCell) {
                    val lat = bb[1] + (bb[3] - bb[1]) * ((i % 57) + 0.5) / 57.0
                    val lon = bb[0] + (bb[2] - bb[0]) * ((i / 57) + 0.5) / (poisPerCell / 57 + 1.0)
                    val kind = KINDS[i % KINDS.size]
                    if (!first) w.write(",")
                    first = false
                    w.write("""{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"id":"n${c.id}-$v-$i",""" +
                        """"kind":"$kind","name":"${WORDS[i % WORDS.size]} ${WORDS[(i / 7) % WORDS.size]}-$v $i","opening_hours":"Mo-Su 08:00-18:00"}}""")
                }
            }
            w.write("]}")
        }
        File(dir, CellStore.STORE_JSON).writeText(JSONObject().put("generation", number).put("format", 2).put("createdAt", 0)
            .put("region", JSONObject()).put("files", JSONObject()).put("cells", cells).toString())
    }

    /** 130 000 entries in 40 areas: changing one area is much faster than the full build, with the same result. */
    @Test fun oneAreaChangeIsMuchFasterThanFullBuild() {
        val data = tmp.newFolder("data")
        val cells = (0 until 40).map { CellId(540 + it % 8, 358 + it / 8) }
        writeGeneration(data, 1, cells.associateWith { "v1" }, 3250)
        val live = SearchAreas(tmp.newFolder("areas"))
        live.build(AreaPlan.of(data)) // warm-up (JIT), not measured
        val measured = SearchAreas(tmp.newFolder("areas2"))
        val full = measured.build(AreaPlan.of(data))
        val fullMs = measured.lastStats!!.ms
        assertEquals(130_000, full.entries.size)

        writeGeneration(data, 2, cells.associateWith { if (it == cells[7]) "v2" else "v1" }, 3250)
        val inc = measured.build(AreaPlan.of(data))
        val incMs = measured.lastStats!!.ms
        assertEquals(1, measured.lastStats!!.added); assertEquals(1, measured.lastStats!!.removed)
        assertEquals(130_000, inc.entries.size)
        assertEquals(3250, inc.entries.count { it.place.name?.contains("-v2 ") == true })

        val restart = SearchAreas(File(measured.dir.path))
        restart.build(AreaPlan.of(data))
        val fromFilesMs = restart.lastStats!!.ms
        println("Search index, 130k entries / 40 areas: full build $fullMs ms, one area changed $incMs ms, " +
            "after an app start (from files) $fromFilesMs ms")
        assertTrue("one area changed ($incMs ms) must be much faster than the full build ($fullMs ms)", incMs * 2 < fullMs)
        assertEquals(keys(SearchAreas(tmp.newFolder()).build(AreaPlan.of(data))), keys(inc))
    }

    private companion object {
        val KINDS = listOf("hut", "restaurant", "water", "parking", "viewpoint", "spring", "cafe", "bus")
        val WORDS = listOf("Zoggler", "Ultner", "Meraner", "Alm", "Hütte", "Brunnen", "Gasthof", "Kofel", "Wiese", "Spitz", "Sankt", "Pankraz", "Zons")
    }
}
