package com.onpath.app.ui.search

import com.onpath.app.search.AreaPlan
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.Place
import com.onpath.app.search.SearchProvider
import com.onpath.app.search.SearchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Field bug 0.13.2 (owner): typing "olive hilde…" showed the restaurant for a very short moment, then the list jumped to
 * a different one. The online answer (Photon) finds "Restaurant Olive" in Hilden; offline only loose matches ("Oliver
 * Schilder", "Olivenhain Wilde": a typo / a word inside a word) – the offline POI "Restaurant Olive" has no town words.
 *
 * Rule: for the same text, rows on screen never move or vanish – later answers (online, a re-indexed map) only add
 * rows below. A newer text is ranked afresh, and online rows of the older text that still match it stay in that list.
 */
class SearchStableListTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hilden = 51.17 to 6.94

    private fun pois(dir: File, vararg names: String) {
        File(dir, OfflineIndex.POIS).writeText("""{"type":"FeatureCollection","features":[""" + names.mapIndexed { i, n ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${6.93 + i * 0.002},${51.16 + i * 0.001}]},"properties":{"id":"n$i","kind":"restaurant","name":"$n"}}"""
        }.joinToString(",") + "]}")
        File(dir, OfflineIndex.POIS).setLastModified(System.currentTimeMillis() + names.size * 10_000L)
    }

    /** Photon as seen for "olive h…" near Hilden: the restaurant first (a little slow, like the network). */
    private val photon = object : SearchProvider {
        override val attribution = "test"
        override fun search(query: String, lat: Double, lon: Double, limit: Int): List<Place> {
            Thread.sleep(80)
            return listOf(
                Place("o:N1", "Restaurant Olive", "restaurant", 51.1655, 6.9395, address = "Mittelstraße 1, 40721 Hilden",
                    source = Place.Source.ONLINE, cc = "DE", town = "Hilden"),
                Place("o:N2", "Hopmanns Olive", "restaurant", 51.20, 6.98, address = "Ziegeleiweg 2, 40699 Erkrath",
                    source = Place.Source.ONLINE, cc = "DE", town = "Erkrath"),
            )
        }
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(1) }
    }

    /**
     * Every distinct result list shown for [text] (polled every ms – each state lasts ≥ the 350 ms debounce). The list of
     * the text before, still on screen until the first answer for [text], is left out.
     */
    private class Recorder(private val s: SearchSession, private val text: String) {
        val seen = ArrayList<List<String?>>()
        private val before = s.ui.results.map { it.place.name }
        @Volatile private var on = true
        private val t = Thread {
            while (on) {
                val u = s.ui
                if (u.query == text) {
                    val names = u.results.map { it.place.name }
                    synchronized(seen) { if (seen.lastOrNull() != names && !(seen.isEmpty() && names == before)) seen += names }
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        fun stop(): List<List<String?>> { on = false; t.join(); return synchronized(seen) { seen.toList() } }
    }

    /** Each list shown for one text starts with the rows of the one before (rows only added below). */
    private fun assertNoJump(lists: List<List<String?>>) {
        for ((a, b) in lists.zipWithNext())
            assertEquals("rows on screen moved or vanished: $a → $b (all: $lists)", a, b.take(a.size))
    }

    private fun session(scope: CoroutineScope, data: File, files: File, plan: () -> AreaPlan = { AreaPlan.of(data) }) =
        SearchSession(scope, data, files, SearchStore(object : android.content.ContextWrapper(null) {
            // no Android in a JVM test: recents are not used here
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences? = null
        }), photon, areaPlan = plan, openIndex = com.onpath.app.search.JdbcSearchDb.opener,
            context = { SearchContext(hilden.first, hilden.second, null) })

    private fun onlineDone(s: SearchSession, text: String) {
        waitFor("online answer for '$text'") { s.ui.query == text && s.ui.onlineState == OnlineState.LOADING }
        waitFor("online answer for '$text'") { s.ui.onlineState == OnlineState.DONE }
        Thread.sleep(50)
    }

    @Test fun typingOnKeepsTheRestaurantOnTop() {
        val data = tmp.newFolder("data")
        pois(data, "Restaurant Olive", "Oliver Schilder", "Olivenhain Wilde")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = session(scope, data, tmp.newFolder("files"))
        try {
            s.onQuery("olive hild")
            onlineDone(s, "olive hild")
            assertTrue(s.ui.results.any { it.place.name == "Restaurant Olive" })

            // one more letter: the online rows that still match stay; the fresh answer only adds below
            val rec = Recorder(s, "olive hilde")
            s.onQuery("olive hilde")
            onlineDone(s, "olive hilde")
            val lists = rec.stop()
            assertTrue("something shown: $lists", lists.isNotEmpty())
            assertNoJump(lists)
            // the exact name + town match is on top, from the first list for this text to the last
            assertEquals("Restaurant Olive", lists.first().first())
            assertEquals("Restaurant Olive", lists.last().first())
        } finally { scope.cancel() }
    }

    @Test fun indexSwapMidQueryOnlyAddsBelow() {
        val data = tmp.newFolder("data")
        pois(data, "Zoggler Hütte")
        val gate = AtomicReference(CountDownLatch(0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = session(scope, data, tmp.newFolder("files"), plan = { gate.get().await(30, TimeUnit.SECONDS); AreaPlan.of(data) })
        try {
            s.onQuery("zog")
            waitFor("first index") { s.ui.hits.isNotEmpty() }

            // a map cell joins (the "Search words read" moment): the old index finds nothing for the text, online finds
            // the restaurant; then the new index answers with loose offline matches
            pois(data, "Zoggler Hütte", "Oliver Schilder", "Olivenhain Wilde")
            val slow = CountDownLatch(1); gate.set(slow)
            val rec = Recorder(s, "olive hilde")
            s.onQuery("olive hilde")
            onlineDone(s, "olive hilde")
            assertEquals("Restaurant Olive", s.ui.results.first().place.name)
            slow.countDown()
            waitFor("new index answers") { s.ui.results.any { it.place.name == "Oliver Schilder" } }
            onlineDone(s, "olive hilde")
            val lists = rec.stop()
            assertNoJump(lists)
            assertEquals("Restaurant Olive", lists.last().first())
        } finally { gate.set(CountDownLatch(0)); scope.cancel() }
    }
}
