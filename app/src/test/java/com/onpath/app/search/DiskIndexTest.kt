package com.onpath.app.search

import com.onpath.app.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * The index on disk ([DiskIndex]) answers exactly like the in-memory one ([OfflineIndex], the former app index):
 * the same hits in the same order with the same scores and the same place details – for thousands of queries made
 * from the names in the data (prefixes, typos, several words, kind words, categories, near a route, with addresses).
 */
class DiskIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun queriesFrom(places: List<Place>, r: Random, n: Int): List<SearchQuery> {
        val named = places.filter { it.name != null }
        val out = ArrayList<SearchQuery>()
        val cats = listOf<SearchKinds.Category?>(null, null, null, null) + SearchKinds.Category.entries
        val route = listOf(GeoPoint(46.60, 11.00), GeoPoint(46.65, 11.10), GeoPoint(46.70, 11.15))
        repeat(n) {
            val p = named[r.nextInt(named.size)]
            val words = SearchText.tokens(p.name)
            val text = when (r.nextInt(7)) {
                0 -> p.name!!.take(1 + r.nextInt(p.name!!.length))
                1 -> words.first().take(1 + r.nextInt(words.first().length))
                2 -> words.joinToString(" ").let { w -> w.take(1 + r.nextInt(w.length)) }
                3 -> words.last().let { w -> if (w.length < 3) w else w.substring(1) } // inside a word
                4 -> words.first().let { w -> if (w.length < 5) w else w.substring(0, 2) + w[3] + w[2] + w.substring(4) } // typo
                5 -> words.shuffled(r).joinToString(" ")
                else -> listOf("hütte", "rifugio", "alm", "weg", "bahnhof", "brunnen", "joch", "a", "s", "12")[r.nextInt(10)]
            }
            val cat = if (r.nextInt(5) == 0) cats[r.nextInt(cats.size)] else null
            out += SearchQuery(if (cat != null && r.nextBoolean()) "" else text, cat, 46.55 + r.nextDouble() * 0.3, 10.95 + r.nextDouble() * 0.4,
                if (r.nextInt(4) == 0) route else null, limit = if (r.nextInt(6) == 0) 5 else 60)
        }
        return out
    }

    private fun addresses(r: Random): List<AddressHit> = List(r.nextInt(3)) { i ->
        AddressHit(Place("a:$i", "Hauptstraße $i", "address", 46.6 + r.nextDouble() * 0.1, 11.1 + r.nextDouble() * 0.1,
            town = "Meran", cc = "IT"), 0.5 + r.nextDouble() * 0.8, 1 + r.nextInt(3))
    }

    private fun assertSame(ref: PlaceIndex, disk: PlaceIndex, queries: List<SearchQuery>, r: Random) {
        for (q in queries) {
            val addr = addresses(r)
            val a = ref.search(q, addr); val b = disk.search(q, addr)
            assertEquals("'${q.text}' ${q.category}", a, b)
        }
    }

    @Test fun answersLikeTheInMemoryIndex() {
        val data = tmp.newFolder("data")
        SearchFixture.write(data)
        val ref = OfflineIndex.load(data, tmp.newFolder("cache"))
        val areas = SearchAreas(tmp.newFolder("areas"), opener = JdbcSearchDb.opener)
        val disk = areas.build(AreaPlan.of(data))
        assertTrue(disk is DiskIndex)
        assertEquals(ref.size, disk.size)
        assertEquals(ref.entries.map { it.place }, disk.places())
        val r = Random(11)
        assertSame(ref, disk, queriesFrom(ref.entries.map { it.place }, r, 3000), r)
        assertSame(ref, disk, SearchFixture.queries.map { (t, c, route) ->
            SearchQuery(t, c, 46.67, 11.16, if (route) SearchFixture.route else null) }, r)

        // places by id (POIs, base-map places, merged trails) and by box
        for (p in ref.entries.map { it.place }.shuffled(r).take(300)) assertEquals(ref.place(p.id), disk.place(p.id))
        assertNull(disk.place("nothing")); assertNull(disk.place("t:99999"))
        repeat(200) {
            val lat = 46.5 + r.nextDouble() * 0.4; val lon = 10.9 + r.nextDouble() * 0.5; val d = r.nextDouble() * 0.1
            val kinds = if (r.nextBoolean()) RouteNamer.KINDS else setOf("hut", "restaurant", "trail", "place")
            assertEquals(ref.placesIn(lat - d, lon - d, lat + d, lon + d, kinds), disk.placesIn(lat - d, lon - d, lat + d, lon + d, kinds))
        }
        // route names from the box query = from all places
        repeat(100) {
            val a = GeoPoint(46.56 + r.nextDouble() * 0.28, 10.96 + r.nextDouble() * 0.38)
            val b = GeoPoint(a.lat + r.nextDouble() * 0.05, a.lon + r.nextDouble() * 0.05)
            val all = ref.entries.map { it.place }
            assertEquals(RouteNamer.label(a, all), RouteNamer.label(a, RouteNamer.placesNear(disk, listOf(a))))
            assertEquals(RouteNamer.name(listOf(a, b), all), RouteNamer.name(listOf(a, b), RouteNamer.placesNear(disk, listOf(a, b))))
        }
    }

    /** Region package + installed cells: the region's entries inside a cell stay out, as in the in-memory union. */
    @Test fun cellsAndRegionLikeTheInMemoryUnion() {
        val data = tmp.newFolder("data")
        com.onpath.app.cells.CellFixtures.region(data)
        val store = com.onpath.app.cells.CellStore(data)
        val a = com.onpath.app.cells.CellId(543, 361)
        val dir = File(File(store.downloadDir, a.id), "v1")
        store.apply(add = listOf(com.onpath.app.cells.CellStore.Download(com.onpath.app.cells.CellFixtures.cell(dir, a, "v1", pois = 40), dir)))
        val disk = SearchAreas(tmp.newFolder("areas"), opener = JdbcSearchDb.opener).build(AreaPlan.of(data))
        val all = disk.places()
        assertEquals(disk.size, all.size)
        assertEquals(3, all.count { it.name?.startsWith("region") == true }) // 4 region POIs, one inside a
        val r = Random(3)
        for (p in all) assertEquals(p, disk.place(p.id))
        val ref = OfflineIndex(all.map { OfflineIndex.Entry(it) })
        assertSame(ref, disk, queriesFrom(all, r, 300).map { it.copy(refLat = all[0].lat, refLon = all[0].lon) }, r)
    }

    /**
     * Optional, local only: real map cells (not in git) – the same answers as the in-memory index, and the time per
     * keystroke. Set ONPATH_SEARCH_CELLS to folders with pois.geojson / base.pmtiles / overlays.pmtiles (`:`-separated).
     */
    @Test fun realCellsWhenAvailable() {
        val dirs = System.getenv("ONPATH_SEARCH_CELLS")?.split(':')?.map { File(it) }?.filter { File(it, "pois.geojson").isFile }.orEmpty()
        if (dirs.isEmpty()) return
        for (d in dirs) {
            val ref = OfflineIndex.load(d, tmp.newFolder())
            val disk = SearchAreas(tmp.newFolder(), opener = JdbcSearchDb.opener).build(AreaPlan.of(d)) as DiskIndex
            val t0 = System.currentTimeMillis(); disk.warmUp(); val warm = System.currentTimeMillis() - t0
            val r = Random(5)
            val places = ref.entries.map { it.place }
            val qs = queriesFrom(places, r, 400).map { it.copy(refLat = places[0].lat, refLon = places[0].lon, route = null) }
            var refMs = 0L; var diskMs = 0L
            for (q in qs) {
                val t1 = System.nanoTime(); val a = ref.search(q); val t2 = System.nanoTime(); val b = disk.search(q); val t3 = System.nanoTime()
                refMs += (t2 - t1) / 1_000_000; diskMs += (t3 - t2) / 1_000_000
                assertEquals("'${q.text}'", a, b)
            }
            println("${d.name}: ${ref.size} entries, word index read in $warm ms; ${qs.size} queries: in memory $refMs ms, disk $diskMs ms")
        }
    }
}
