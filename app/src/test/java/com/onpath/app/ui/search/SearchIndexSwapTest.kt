package com.onpath.app.ui.search

import com.onpath.app.search.AreaPlan
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.SearchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Typing right after a new map store generation must find offline results: while the index is rebuilt (it can take a
 * minute) the previous index answers until the new one is ready; only the very first build shows "Preparing offline
 * search…".
 */
class SearchIndexSwapTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pois(dir: File, vararg names: String) {
        File(dir, OfflineIndex.POIS).writeText("""{"type":"FeatureCollection","features":[""" + names.mapIndexed { i, n ->
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${10.9 + i * 0.01},46.5]},"properties":{"id":"n$i","kind":"hut","name":"$n"}}"""
        }.joinToString(",") + "]}")
        File(dir, OfflineIndex.POIS).setLastModified(System.currentTimeMillis() + names.size * 10_000L)
    }

    private fun waitFor(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout: $what"); Thread.sleep(10) }
    }

    @Test fun oldIndexAnswersWhileTheNewOneIsBuilt() {
        val data = tmp.newFolder("data")
        pois(data, "Zoggler Hütte", "Zonser Alm")
        val gate = CountDownLatch(0)
        val block = java.util.concurrent.atomic.AtomicReference(gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s = SearchSession(scope, data, tmp.newFolder("files"), SearchStore(object : android.content.ContextWrapper(null) {
            // no Android in a JVM test: recents are not used here
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences? = null
        }), null,
            areaPlan = { block.get().await(30, TimeUnit.SECONDS); AreaPlan.of(data) },
            context = { SearchContext(46.5, 10.9, null) })
        try {
            // first build: nothing to answer with yet → "Preparing offline search…" until it is ready
            val first = CountDownLatch(1); block.set(first)
            s.onQuery("zons")
            waitFor("preparing shown") { s.ui.indexing }
            assertTrue(s.ui.hits.isEmpty())
            first.countDown()
            waitFor("first results") { s.ui.hits.isNotEmpty() }
            assertFalse(s.ui.indexing)
            assertEquals("Zonser Alm", s.ui.hits.first().place.name)

            // the map changes (new generation); the rebuild is slow – the old index keeps answering at once
            pois(data, "Zoggler Hütte", "Zonser Alm", "Zonsalm Neu")
            val slow = CountDownLatch(1); block.set(slow)
            s.onQuery("")
            Thread.sleep(50)
            s.onQuery("zoggler")
            waitFor("old index answers during the rebuild") { s.ui.hits.isNotEmpty() }
            assertEquals("Zoggler Hütte", s.ui.hits.first().place.name)
            assertFalse("no 'Preparing' while an index exists", s.ui.indexing)
            assertTrue(slow.count == 1L) // the rebuild is still waiting

            slow.countDown()
            waitFor("new index swapped in") { runBlocking { s.offlineIndex() }.entries.any { it.place.name == "Zonsalm Neu" } }
            s.onQuery("zonsalm")
            waitFor("new results") { s.ui.hits.any { it.place.name == "Zonsalm Neu" } }
        } finally {
            block.set(gate)
            scope.cancel()
        }
    }
}
