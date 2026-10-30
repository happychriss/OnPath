package com.onpath.app.search

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * Optional, local only: the address search on real map cells (not in git) – time per keystroke and the answers as
 * text (to compare before / after a change). ONPATH_ADDRESS_DIR = a data folder with `addresses/<cell>.sqlite`;
 * ONPATH_ADDRESS_OUT = file the answers are written to; ONPATH_SEARCH_CELL_DIRS (`:`-separated cell folders with
 * pois.geojson) adds the place index, so the answers are the full result list the search view shows.
 */
class AddressSpeedLocalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val typed = listOf("k", "ko", "kol", "koln", "h", "hi", "hil", "hild", "hilden", "d", "du", "due", "duess",
        "duesseldorf", "s", "st", "sch", "schul", "schulstrasse", "haupt", "hauptstrasse 5", "am", "im", "an der", "q", "x",
        "y", "z", "zu", "berg", "bergische", "kirch", "kirchweg", "garten", "wald", "markt", "bahnhof", "linden", "rosen",
        "mozart", "goethe", "goethestr 12", "koelner", "koelner str", "frankfurter", "a", "b", "e", "w", "wi", "we", "mo")

    @Test fun timesAndAnswers() {
        val dir = System.getenv("ONPATH_ADDRESS_DIR")?.let(::File)
        assumeTrue("ONPATH_ADDRESS_DIR not set", dir != null && File(dir, AddressIndex.DIR).isDirectory)
        val idx = AddressIndex.open(dir!!) { JdbcAddressDb(it) }
        val cellDirs = System.getenv("ONPATH_SEARCH_CELL_DIRS")?.split(':')?.map(::File)?.filter { File(it, "pois.geojson").isFile }.orEmpty()
        val places = if (cellDirs.isEmpty()) null else SearchAreas(tmp.newFolder(), opener = JdbcSearchDb.opener).build(AreaPlan(
            cellDirs.mapIndexed { i, d -> AreaSpec("cell-$i", "1", d, null) }))
        places?.warmUp()
        val lat = 51.17; val lon = 6.93 // Hilden / Düsseldorf
        // warm (connections open, pages cached): the phone's second round
        for (t in typed) idx.search(AddressQuery.parse(t))
        val out = StringBuilder()
        val times = ArrayList<String>()
        var sum = 0.0
        for (t in typed) {
            val t0 = System.nanoTime()
            val addr = idx.search(AddressQuery.parse(t))
            val t1 = System.nanoTime()
            val q = SearchQuery(t, null, lat, lon)
            val hits = places?.search(q, addr) ?: addr.map { SearchRanking.addressHit(it, q) }.sortedWith(SearchRanking.ORDER)
            val t2 = System.nanoTime()
            // address time: median of 7 more runs
            val med = (0 until 7).map { val a = System.nanoTime(); idx.search(AddressQuery.parse(t)); System.nanoTime() - a }.sorted()[3] / 1e6
            sum += med
            times += String.format(Locale.ROOT, "%-16s addr %6.1f ms (first %4d ms, %3d hits)  index %4d ms", "'$t'", med,
                (t1 - t0) / 1_000_000, addr.size, (t2 - t1) / 1_000_000)
            out.append("Q '").append(t).append("' → ").append(hits.size).append(" (").append(hits.count { it.place.id.startsWith("a:") }).append(" addresses)\n")
            for (h in hits) out.append("  ").append(h.place.id).append(' ').append(h.tier).append(' ')
                .append(String.format(Locale.ROOT, "%.6f", h.score)).append(' ').append(h.place.name).append('\n')
        }
        println(times.joinToString("\n"))
        println(String.format(Locale.ROOT, "addr total of medians %.1f ms", sum))
        System.getenv("ONPATH_ADDRESS_OUT")?.let { File(it).writeText(out.toString()) }
        idx.close()
        // the bare SQL of a one-letter word over every file (what is left when nothing else is done)
        val dbs = AddressIndex.sources(dir).map { JdbcAddressDb(it) }
        for (w in listOf("s", "k", "ko")) {
            fun med(block: () -> Unit) = (0 until 7).map { val a = System.nanoTime(); block(); System.nanoTime() - a }.sorted()[3] / 1e6
            val up = AddressFile.upper(w)
            val words5000 = med { for (d in dbs) d.rows("SELECT street FROM word WHERE w >= ? AND w < ? LIMIT 5000", w, up) }
            val words500 = med { for (d in dbs) d.rows("SELECT street FROM word WHERE w >= ? AND w < ? LIMIT 500", w, up) }
            val ids = dbs.map { d -> d.rows("SELECT street FROM word WHERE w >= ? AND w < ? LIMIT 500", w, up).map { it[0] as Long }.distinct() }
            val streets500 = med { dbs.forEachIndexed { i, d -> d.rows("SELECT id, name, alt, town, loc, pc, p, lat, lon FROM street WHERE id IN (${ids[i].joinToString(",")})") } }
            val streets40 = med { dbs.forEachIndexed { i, d -> d.rows("SELECT id, name, alt, town, loc, pc, p, lat, lon FROM street WHERE id IN (${ids[i].sorted().take(40).joinToString(",")})") } }
            println(String.format(Locale.ROOT, "addr SQL '%s' over %d files: words ≤5000 %.1f ms, ≤500 %.1f ms; streets 500 %.1f ms, 40 %.1f ms",
                w, dbs.size, words5000, words500, streets500, streets40))
        }
        dbs.forEach { it.close() }
    }
}
