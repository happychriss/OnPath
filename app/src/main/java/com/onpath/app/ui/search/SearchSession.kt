package com.onpath.app.ui.search

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SearchMerge
import com.onpath.app.search.SearchProvider
import com.onpath.app.search.SearchQuery
import com.onpath.app.search.SearchStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where the user is and what is planned – read when a search runs. */
data class SearchContext(val refLat: Double, val refLon: Double, val route: List<GeoPoint>?)

enum class OnlineState { IDLE, LOADING, DONE, FAILED }

/** Everything the search view shows (plain data, also used by the screenshot tests). */
data class SearchUi(
    val query: String = "",
    val category: SearchKinds.Category? = null,
    val hits: List<SearchHit> = emptyList(),
    val online: List<SearchHit> = emptyList(),
    val onlineState: OnlineState = OnlineState.IDLE,
    val indexing: Boolean = false,
    val recent: List<SearchHit> = emptyList(),
    val hasRoute: Boolean = false,
) {
    val active: Boolean get() = query.isNotBlank() || category != null
}

/**
 * State and background work of the search view. Offline search runs on every keystroke (off the main
 * thread, newer queries supersede older ones); the online provider is asked after 350 ms without typing,
 * only for 3+ letters, and fails silently (offline, timeout).
 */
class SearchSession(
    private val scope: CoroutineScope,
    private val dataDir: File,
    private val cacheDir: File,
    private val store: SearchStore,
    private val online: SearchProvider?,
    private val context: () -> SearchContext,
) {
    var ui by mutableStateOf(SearchUi())
        private set

    private var index: OfflineIndex? = null
    private val indexLock = Mutex()
    private var offlineJob: Job? = null
    private var onlineJob: Job? = null
    private var generation = 0

    /** Called when the view opens: refreshes recents, (re)builds the index if the data files changed. */
    fun onOpen() {
        val ctx = context()
        ui = ui.copy(recent = recentHits(ctx), hasRoute = ctx.route != null)
        scope.launch { ensureIndex(); if (ui.active) run() }
    }

    fun onQuery(q: String) { ui = ui.copy(query = q); run() }

    fun onCategory(c: SearchKinds.Category?) { ui = ui.copy(category = if (ui.category == c) null else c); run() }

    fun clear() { ui = ui.copy(query = "", hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE); run() }

    fun remember(p: Place) { store.addRecent(p) }

    fun clearRecent() { store.clearRecent(); ui = ui.copy(recent = emptyList()) }

    /** A recent entry picked again: the offline copy (full details) when the index still has it. */
    fun resolve(p: Place): Place = index?.entries?.firstOrNull { it.place.id == p.id }?.place ?: p

    private fun recentHits(ctx: SearchContext) = store.recent().map { p ->
        SearchHit(p, 0.0, 0, Geo.distance(ctx.refLat, ctx.refLon, p.lat, p.lon), false)
    }

    /** The offline index, built (off the main thread) when needed; for route names (search/RouteNamer). */
    suspend fun offlineIndex(): OfflineIndex = ensureIndex()

    private suspend fun ensureIndex(): OfflineIndex = indexLock.withLock {
        val cur = index ?: cached
        val sig = withContext(Dispatchers.IO) { OfflineIndex.signature(dataDir) }
        if (cur != null && cur.signature == sig) { index = cur; return cur }
        ui = ui.copy(indexing = true)
        val built = withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            OfflineIndex.load(dataDir, cacheDir).also { Log.i("OnPath", "search index: ${it.entries.size} entries in ${System.currentTimeMillis() - t0} ms") }
        }
        index = built; cached = built
        ui = ui.copy(indexing = false)
        built
    }

    private fun run() {
        val gen = ++generation
        val u = ui
        val ctx = context()
        offlineJob?.cancel(); onlineJob?.cancel()
        if (!u.active) { ui = u.copy(hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE, hasRoute = ctx.route != null); return }
        val q = SearchQuery(u.query, u.category, ctx.refLat, ctx.refLon, ctx.route)
        offlineJob = scope.launch {
            val idx = ensureIndex()
            val hits = withContext(Dispatchers.Default) { idx.search(q) }
            if (gen == generation) ui = ui.copy(hits = hits, hasRoute = ctx.route != null)
        }
        val text = u.query.trim()
        if (online == null || text.length < 3) { ui = ui.copy(online = emptyList(), onlineState = OnlineState.IDLE); return }
        onlineJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (gen != generation) return@launch
            ui = ui.copy(onlineState = OnlineState.LOADING)
            val res = withContext(Dispatchers.IO) {
                try { online.search(text, ctx.refLat, ctx.refLon, 10) } catch (e: Exception) {
                    Problems.info("search", "Online search failed (offline or server unreachable)", e); null
                }
            }
            if (gen != generation) return@launch
            offlineJob?.join()
            if (res == null) { ui = ui.copy(online = emptyList(), onlineState = OnlineState.FAILED); return@launch }
            val kinds = u.category?.kinds
            val extras = SearchMerge.onlineExtras(ui.hits.map { it.place }, res)
                .filter { kinds == null || it.kind in kinds }
                .map { SearchHit(it, 0.0, 0, Geo.distance(ctx.refLat, ctx.refLon, it.lat, it.lon), false) }
            ui = ui.copy(online = extras, onlineState = OnlineState.DONE)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 350L
        /** Kept for the whole process: re-opening the search is instant. */
        @Volatile private var cached: OfflineIndex? = null
    }
}
