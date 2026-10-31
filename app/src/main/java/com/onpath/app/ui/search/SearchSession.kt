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
import com.onpath.app.search.AndroidSearchDb
import com.onpath.app.search.PlaceIndex
import com.onpath.app.search.SearchDb
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    /**
     * The list on screen, kept by [SearchSession]: ranked once per text ([shownFor]); later answers for the same text
     * (online, a re-indexed map) only add rows below ([SearchRanking.keepShown]). Null: ranked from [hits] + [online].
     */
    val shown: List<SearchHit>? = null,
    /** The text (normalised) and category [shown] was ranked for. */
    val shownFor: String? = null,
) {
    val active: Boolean get() = query.isNotBlank() || category != null

    /** Offline and online results as ONE list, best first. */
    val results: List<SearchHit> get() = shown ?: SearchRanking.mix(hits, online)
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
    /** Opens the offline index file (SQLite); replaced in tests. */
    private val openIndex: (File) -> SearchDb = { AndroidSearchDb(it) },
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

    @Volatile private var index: PlaceIndex? = null
    private val indexLock = Mutex()
    private var refreshJob: Job? = null
    @Volatile private var lastCheck = 0L
    private val areas: SearchAreas get() = areasFor(cacheDir, openIndex)
    private var offlineJob: Job? = null
    private var onlineJob: Job? = null
    private var generation = 0

    /**
     * Called when the view opens: a fresh, empty search (never the old text) with the recent places; (re)builds the
     * index if the data files changed.
     */
    fun onOpen() {
        val ctx = context()
        ui = ui.copy(query = "", category = null, recent = recentHits(ctx), hasRoute = ctx.route != null)
        run() // clears the old results and stops a search still on its way
        // the address files opened now (one per map cell, each reads its towns), not on the first letter
        scope.launch(Dispatchers.IO) {
            try { ensureAddresses() } catch (e: Exception) {
                Problems.warn("search", "Offline address files not opened in advance; opened when searching", e)
            }
        }
        scope.launch {
            val idx = ensureIndex(force = true)
            // the word index into memory now (once per process), so the first keystroke answers at once
            withContext(Dispatchers.IO) { idx.warmUp() }
            if (ui.active) run()
        }
    }

    /**
     * The map areas changed (a cell joined or left): bring the offline index up to date in the background, so the next
     * search (or route name) does not wait for it. Only the changed areas are read.
     */
    fun prepare() {
        scope.launch {
            try { rebuild() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Problems.warn("search", "Offline search index not updated after a map change; updated when searching", e)
            }
        }
    }

    fun onQuery(q: String) { ui = ui.copy(query = q); run() }

    fun onCategory(c: SearchKinds.Category?) { ui = ui.copy(category = if (ui.category == c) null else c); run() }

    fun clear() { ui = ui.copy(query = "", hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE); run() }

    fun remember(p: Place) { store.addRecent(p) }

    fun clearRecent() { store.clearRecent(); ui = ui.copy(recent = emptyList()) }

    /**
     * A result or recent entry picked: the offline copy (full details) when the index has it. A shown result is that
     * copy already; a recent one is one indexed lookup.
     */
    fun resolve(p: Place): Place = ui.hits.firstOrNull { it.place.id == p.id }?.place ?: index?.place(p.id) ?: p

    private fun recentHits(ctx: SearchContext) = store.recent().map { p ->
        SearchHit(p, 0.0, 0, Geo.distance(ctx.refLat, ctx.refLon, p.lat, p.lon), false)
    }

    /** The offline index, built (off the main thread) when needed; for route names (search/RouteNamer). */
    suspend fun offlineIndex(): PlaceIndex = ensureIndex()

    /**
     * The index to answer with now. When there is one it answers at once, and a newer one is built in the background
     * if the map areas changed – no empty moment while a new map store generation is indexed. Only the very first time
     * (no index yet) this waits; the view then shows
     * "Preparing offline search…" ([SearchUi.indexing]).
     */
    private suspend fun ensureIndex(force: Boolean = false): PlaceIndex {
        val cur = (index ?: areas.latest)?.takeIf { !it.broken }
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
    private suspend fun rebuild(): PlaceIndex = indexLock.withLock {
        val cur = (index ?: areas.latest)?.takeIf { !it.broken }
        if (cur == null) ui = ui.copy(indexing = true)
        try {
            val plan = withContext(Dispatchers.IO) { areaPlan() }
            if (cur != null && cur.signature == plan.signature) { index = cur; return cur }
            // SearchAreas logs "Search index: +n areas −m areas in N ms"
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

    /** What one ranked list is for: the normalised text and the category (a space or case change is the same text). */
    private fun keyOf(u: SearchUi) = SearchText.normalize(u.query) + "|" + (u.category?.name ?: "")

    /** [next] as the list on screen: for the text already shown, rows on screen stay and new ones go below. */
    private fun shownList(cur: SearchUi, key: String, next: List<SearchHit>): List<SearchHit> {
        val shown = cur.shown
        return if (shown != null && cur.shownFor == key) SearchRanking.keepShown(shown, next) else next
    }

    /** Where the last online search was centred (scores online rows kept for the next text). */
    @Volatile private var onlineCentre: Pair<Double, Double>? = null

    private fun run() {
        val gen = ++generation
        // the list on screen stays until the answer for the new text is there (old rows are not re-ranked meanwhile)
        val u = ui
        val ctx = context()
        offlineJob?.cancel(); onlineJob?.cancel()
        if (!u.active) {
            ui = u.copy(hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE, hasRoute = ctx.route != null,
                shown = null, shownFor = null)
            return
        }
        val key = keyOf(u)
        val q = SearchQuery(u.query, u.category, ctx.refLat, ctx.refLon, ctx.route)
        val text = u.query.trim()
        val useOnline = online != null && text.length >= 3
        val kinds = u.category?.kinds
        // online rows on screen (an earlier text) that still match this one stay in its list: typing on never loses them
        val before = u.online.map { it.place }
        offlineJob = scope.launch {
            val idx = ensureIndex()
            val (hits, carried) = withContext(Dispatchers.IO) {
                // a newer keystroke cancels this job: the address files not searched yet are skipped
                val addr = if (kinds == null || "street" in kinds || "address" in kinds) ensureAddresses().search(AddressQuery.parse(u.query)) { !isActive } else emptyList()
                ensureActive()
                val hits = idx.search(q, addr)
                val keep = if (!useOnline) emptyList() else SearchRanking.stillMatching(before, text).filter { kinds == null || it.kind in kinds }
                val carried = if (keep.isEmpty()) emptyList() else {
                    val towns = OnlineRanking.queryTowns(text, ensureAddresses().townsNamed(SearchText.tokens(text)))
                    val (cLat, cLon) = onlineCentre ?: (ctx.refLat to ctx.refLon)
                    SearchRanking.onlineHits(SearchMerge.onlineExtras(hits.map { it.place }, keep), q, towns, cLat, cLon)
                }
                hits to carried
            }
            if (gen != generation) return@launch
            val cur = ui
            ui = cur.copy(hits = hits, online = carried, hasRoute = ctx.route != null,
                shown = shownList(cur, key, SearchRanking.mix(hits, carried)), shownFor = key)
        }
        if (!useOnline) { ui = ui.copy(onlineState = OnlineState.IDLE); return }
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
                    OnlineAnswer(OnlineRanking.rank(r, bLat, bLon, addr.countryNear(ctx.refLat, ctx.refLon), towns, text).take(10), towns, bLat, bLon)
                } catch (e: Exception) {
                    Problems.info("search", "Online search failed (offline or server unreachable)", e); null
                }
            }
            if (gen != generation) return@launch
            offlineJob?.join()
            if (gen != generation) return@launch
            // no answer: the rows on screen stay (also online ones kept from the text before)
            if (res == null) { ui = ui.copy(onlineState = OnlineState.FAILED); return@launch }
            onlineCentre = res.lat to res.lon
            val cur = ui
            val offline = if (cur.shownFor == key) cur.hits else emptyList()
            val extras = SearchMerge.onlineExtras(offline.map { it.place } + cur.online.map { it.place }, res.places)
                .filter { kinds == null || it.kind in kinds }
            // scored like the offline hits, so both rank in one list
            val all = cur.online + SearchRanking.onlineHits(extras, q, res.towns, res.lat, res.lon)
            ui = cur.copy(online = all, onlineState = OnlineState.DONE,
                shown = shownList(cur, key, SearchRanking.mix(offline, all)), shownFor = key)
        }
    }

    private class OnlineAnswer(val places: List<Place>, val towns: Set<String>, val lat: Double, val lon: Double)

    companion object {
        const val DEBOUNCE_MS = 350L
        /** While typing, the map areas are checked for changes at most this often (on open: always). */
        const val RECHECK_MS = 5_000L
        private val areaStores = HashMap<String, SearchAreas>()

        /** One [SearchAreas] per app folder for the whole process (one open index file). */
        @Synchronized fun areasFor(filesDir: File, opener: (File) -> SearchDb = { AndroidSearchDb(it) }): SearchAreas =
            areaStores.getOrPut(filesDir.path) {
                SearchAreas(File(filesDir, "search-areas"), legacyCache = File(filesDir, "search-places-v1.tsv"), opener = opener)
            }

        /** The [SearchAreas] of this app folder if a search created it already, else null (health line: never creates one). */
        @Synchronized fun peekAreas(filesDir: File): SearchAreas? = areaStores[filesDir.path]
    }
}
