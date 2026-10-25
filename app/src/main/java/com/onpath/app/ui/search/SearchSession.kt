package com.onpath.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.AddressDb
import com.onpath.app.search.AreaPlan
import com.onpath.app.search.SearchAreas
import com.onpath.app.search.AddressIndex
import com.onpath.app.search.AddressQuery
import com.onpath.app.search.AndroidAddressDb
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.OnlineRanking
import com.onpath.app.search.SearchText
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SearchMerge
import com.onpath.app.search.SearchProvider
import com.onpath.app.search.SearchQuery
import com.onpath.app.search.SearchRanking
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
    /** No offline index yet and the first one is being built ("Preparing offline search…"); never during a rebuild. */
    val indexing: Boolean = false,
    val recent: List<SearchHit> = emptyList(),
    val hasRoute: Boolean = false,
    /** Offline rows were on screen when [online] arrived: those stay in place, online rows go below (no jumping). */
    val onlineBelow: Boolean = false,
) {
    val active: Boolean get() = query.isNotBlank() || category != null

    /** Offline and online results as ONE list, best first (owner 2026-10-22). */
    val results: List<SearchHit> get() = SearchRanking.mix(hits, online, keepShown = onlineBelow)
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
    /** Opens an offline address file (addresses.sqlite); replaced in tests. */
    private val openAddresses: (File) -> AddressDb = { AndroidAddressDb(it) },
    /**
     * The map areas the index covers (installed cells of the current store generation, or the data folder). Asked on
     * open (and now and then while typing): a changed area is re-indexed in the background, the rest is reused.
     */
    private val areaPlan: () -> AreaPlan = { AreaPlan.of(dataDir) },
    private val context: () -> SearchContext,
) {
    @Volatile private var addresses: AddressIndex? = null

    /** Address files (re)opened when they changed; never throws. */
    @Synchronized private fun ensureAddresses(): AddressIndex {
        val sig = AddressIndex.signature(dataDir)
        addresses?.let { if (it.signature == sig) return it }
        addresses?.close()
        return AddressIndex.open(dataDir, openAddresses).also { addresses = it }
    }

    var ui by mutableStateOf(SearchUi())
        private set

    @Volatile private var index: OfflineIndex? = null
    private val indexLock = Mutex()
    private var refreshJob: Job? = null
    @Volatile private var lastCheck = 0L
    private val areas: SearchAreas get() = areasFor(cacheDir)
    private var offlineJob: Job? = null
    private var onlineJob: Job? = null
    private var generation = 0

    /** Called when the view opens: refreshes recents, (re)builds the index if the data files changed. */
    fun onOpen() {
        val ctx = context()
        ui = ui.copy(recent = recentHits(ctx), hasRoute = ctx.route != null)
        scope.launch { ensureIndex(force = true); if (ui.active) run() }
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

    /**
     * The index to answer with now. When there is one it answers at once, and a newer one is built in the background
     * if the map areas changed – no empty moment while a new map store generation is indexed (field 2026-10-23: "zons"
     * found nothing for a minute). Only the very first time (no index yet) this waits; the view then shows
     * "Preparing offline search…" ([SearchUi.indexing]).
     */
    private suspend fun ensureIndex(force: Boolean = false): OfflineIndex {
        val cur = index ?: areas.latest
        if (cur != null) { index = cur; refresh(force); return cur }
        return rebuild()
    }

    /** Checks the map areas (at most every few seconds while typing) and rebuilds in the background when they changed. */
    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (refreshJob?.isActive == true || (!force && now - lastCheck < RECHECK_MS)) return
        lastCheck = now
        refreshJob = scope.launch {
            val before = index
            val built = rebuild()
            // the old index found nothing for the text on screen: answer again with the new one (no rows move)
            if (built !== before && ui.active && ui.hits.isEmpty()) run()
        }
    }

    /** Builds the index for the current areas when it changed (only changed areas are read); swaps it in when ready. */
    private suspend fun rebuild(): OfflineIndex = indexLock.withLock {
        val cur = index ?: areas.latest
        if (cur == null) ui = ui.copy(indexing = true)
        try {
            val plan = withContext(Dispatchers.IO) { areaPlan() }
            if (cur != null && cur.signature == plan.signature) { index = cur; return cur }
            // SearchAreas logs "Search index: +n areas −m areas in N ms" (perf report 2026-10-22)
            val built = withContext(Dispatchers.IO) { areas.build(plan) }
            index = built
            built
        } finally {
            if (cur == null) ui = ui.copy(indexing = false)
        }
    }

    /** Where a [findOne] result came from. */
    enum class Found { OFFLINE, ONLINE }

    /**
     * The one best place for a shared address / name (no search view): offline first – a good match only (name or
     * street equal / starting with the text, tier ≥ 2) – then the online provider. Null when nothing is found
     * (offline and no offline match, or no match at all). Never throws.
     */
    suspend fun findOne(texts: List<String>): Pair<Place, Found>? {
        if (texts.isEmpty()) return null
        val ctx = context()
        val idx = try { ensureIndex() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Problems.warn("search", "Place index unavailable; shared place searched online only", e); null
        }
        val offline = withContext(Dispatchers.IO) {
            texts.firstNotNullOfOrNull { t ->
                try {
                    val q = SearchQuery(t, null, ctx.refLat, ctx.refLon, null, limit = 5)
                    val addr = ensureAddresses().search(AddressQuery.parse(t))
                    (idx?.search(q, addr) ?: addr.map { com.onpath.app.search.SearchRanking.addressHit(it, q) })
                        .firstOrNull { it.tier >= 2 }?.place
                } catch (e: Exception) {
                    Problems.warn("search", "Offline search for a shared place failed", e); null
                }
            }
        }
        if (offline != null) return offline to Found.OFFLINE
        val provider = online ?: return null
        return withContext(Dispatchers.IO) {
            texts.firstNotNullOfOrNull { t ->
                try {
                    val addr = ensureAddresses()
                    OnlineRanking.rank(provider.search(t, ctx.refLat, ctx.refLon, 10), ctx.refLat, ctx.refLon,
                        addr.countryNear(ctx.refLat, ctx.refLon), OnlineRanking.queryTowns(t, addr.townsNamed(SearchText.tokens(t))))
                        .firstOrNull()
                } catch (e: Exception) {
                    Problems.info("search", "Online search for a shared place failed (offline or server unreachable)", e); null
                }
            }?.let { it to Found.ONLINE }
        }
    }

    private fun run() {
        val gen = ++generation
        // the user typed (or changed the category): the whole list may be re-ranked
        ui = ui.copy(onlineBelow = false)
        val u = ui
        val ctx = context()
        offlineJob?.cancel(); onlineJob?.cancel()
        if (!u.active) { ui = u.copy(hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE, hasRoute = ctx.route != null); return }
        val q = SearchQuery(u.query, u.category, ctx.refLat, ctx.refLon, ctx.route)
        offlineJob = scope.launch {
            val idx = ensureIndex()
            val hits = withContext(Dispatchers.IO) {
                val addr = if (u.category == null) ensureAddresses().search(AddressQuery.parse(u.query)) else emptyList()
                idx.search(q, addr)
            }
            if (gen == generation) ui = ui.copy(hits = hits, hasRoute = ctx.route != null)
        }
        val text = u.query.trim()
        if (online == null || text.length < 3) { ui = ui.copy(online = emptyList(), onlineState = OnlineState.IDLE); return }
        onlineJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (gen != generation) return@launch
            ui = ui.copy(onlineState = OnlineState.LOADING)
            val res = withContext(Dispatchers.IO) {
                // A town named in the query ("via Roma 3 Bolzano") centres the search there; the user's country
                // (from the nearest offline town) ranks first.
                val addr = ensureAddresses()
                val named = addr.townsNamed(SearchText.tokens(text))
                val centre = named.minByOrNull { Geo.distance(ctx.refLat, ctx.refLon, it.lat, it.lon) }
                val (bLat, bLon) = if (centre != null) centre.lat to centre.lon else ctx.refLat to ctx.refLon
                try {
                    val r = online.search(text, bLat, bLon, 20)
                    val towns = OnlineRanking.queryTowns(text, named)
                    OnlineAnswer(OnlineRanking.rank(r, bLat, bLon, addr.countryNear(ctx.refLat, ctx.refLon), towns).take(10), towns, bLat, bLon)
                } catch (e: Exception) {
                    Problems.info("search", "Online search failed (offline or server unreachable)", e); null
                }
            }
            if (gen != generation) return@launch
            offlineJob?.join()
            if (res == null) { ui = ui.copy(online = emptyList(), onlineState = OnlineState.FAILED); return@launch }
            val kinds = u.category?.kinds
            val extras = SearchMerge.onlineExtras(ui.hits.map { it.place }, res.places)
                .filter { kinds == null || it.kind in kinds }
            // scored like the offline hits: SearchUi.results mixes both into one list
            val scored = SearchRanking.onlineHits(extras, q, res.towns, res.lat, res.lon)
            ui = ui.copy(online = scored, onlineState = OnlineState.DONE, onlineBelow = ui.hits.isNotEmpty())
        }
    }

    private class OnlineAnswer(val places: List<Place>, val towns: Set<String>, val lat: Double, val lon: Double)

    companion object {
        const val DEBOUNCE_MS = 350L
        /** While typing, the map areas are checked for changes at most this often (on open: always). */
        const val RECHECK_MS = 5_000L
        private val areaStores = HashMap<String, SearchAreas>()

        /** One [SearchAreas] per app folder for the whole process (its areas stay in memory between builds). */
        @Synchronized fun areasFor(filesDir: File): SearchAreas = areaStores.getOrPut(filesDir.path) {
            SearchAreas(File(filesDir, "search-areas"), legacyCache = File(filesDir, "search-places-v1.tsv"))
        }
    }
}
