package com.onpath.app.ui.search

import com.onpath.app.data.Geo
import com.onpath.app.debug.Problems
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
import com.onpath.app.search.SavedPlaces
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SearchProvider
import com.onpath.app.search.SearchQuery
import com.onpath.app.search.SearchRanking
import com.onpath.app.search.SearchStore
import com.onpath.app.search.SharedPlaceFinder
import com.onpath.app.search.SharedPlaceLookup
import com.onpath.app.search.SharedTown
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

/**
 * State and background work of the search view. Offline search runs on every keystroke (off the main
 * thread, newer queries supersede older ones); the online provider is asked after 350 ms without typing,
 * only for 3+ letters, and fails silently (offline, timeout).
 */
class SearchSession(
    private val scope: CoroutineScope,
    private val dataDir: File,
    private val cacheDir: File,
    store: SearchStore,
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
    /** The phone's address service – only for a shared place, after offline + online (A-020); replaced in tests. */
    private val geocoder: com.onpath.app.search.PlaceGeocoder = com.onpath.app.search.PlaceGeocoder.NONE,
    /**
     * The data folder's storage can be read (field 2026-11-08: not for about a minute after a phone restart). Until
     * then the index is not built: the folder looks empty, and a build would drop every area from the index file.
     */
    private val storageReady: () -> Boolean = { true },
    context: () -> SearchContext,
) : SearchState(AppPorts(store), online != null, context) {
    @Volatile private var addresses: AddressIndex? = null

    /** Address files (re)opened when they changed; never throws. */
    @Synchronized private fun ensureAddresses(): AddressIndex {
        val sig = AddressIndex.signature(dataDir)
        addresses?.let { if (it.signature == sig) return it }
        addresses?.close()
        return AddressIndex.open(dataDir, openAddresses).also { addresses = it }
    }

    /** The app's side of [SearchPorts]: the store, and the index the session holds (volatile: read off the main thread). */
    private class AppPorts(private val store: SearchStore) : SearchPorts {
        @Volatile var index: PlaceIndex? = null
        override fun saved(): SavedPlaces = store.saved()
        override fun recent(): List<Place> = store.recent()
        override fun addRecent(p: Place) { store.addRecent(p) }
        override fun clearRecent() { store.clearRecent() }
        override fun indexedPlace(id: String): Place? = index?.place(id)
    }

    private var index: PlaceIndex?
        get() = (ports as AppPorts).index
        set(v) { (ports as AppPorts).index = v }
    private val indexLock = Mutex()
    private var refreshJob: Job? = null
    @Volatile private var lastCheck = 0L
    private val areas: SearchAreas get() = areasFor(cacheDir, openIndex)
    private var offlineJob: Job? = null
    private var onlineJob: Job? = null

    /**
     * Called when the view opens: a fresh, empty search (never the old text) with the recent places; (re)builds the
     * index if the data files changed.
     */
    fun onOpen() {
        val ctx = context()
        allFavourites = false
        ui = startLists(ui.copy(query = "", category = null, hasRoute = ctx.route != null), ctx)
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
        if (!withContext(Dispatchers.IO) { storageReady() }) {
            Problems.info("search", "Offline search index not built: storage not ready yet (built when it is)")
            return cur ?: com.onpath.app.search.OfflineIndex(emptyList(), "")
        }
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

    /**
     * A shared place without a coordinate (no search view): by its address, else by its name in the shared town –
     * never a namesake elsewhere ([SharedPlaceLookup]: the rules, unit-tested). Offline first – good matches only (name
     * or street equal / starting with the text, tier ≥ 2) – then the online provider. Never throws.
     */
    suspend fun findShared(name: String?, address: String?, query: String?): SharedPlaceLookup.Result {
        val ctx = context()
        val idx = try { ensureIndex() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            Problems.warn("search", "Place index unavailable; shared place searched online only", e); null
        }
        val provider = online
        val finder = object : SharedPlaceFinder {
            override fun offline(text: String): List<Place> = try {
                val q = SearchQuery(text, null, ctx.refLat, ctx.refLon, null, limit = 20) // room for the namesake in the shared town behind nearer ones
                val addr = ensureAddresses().search(AddressQuery.parse(text))
                (idx?.search(q, addr) ?: addr.map { SearchRanking.addressHit(it, q) }).filter { it.tier >= 2 }.map { it.place }
            } catch (e: Exception) {
                Problems.warn("search", "Offline search for a shared place failed", e); emptyList()
            }

            override fun online(text: String): List<Place> = if (provider == null) emptyList() else try {
                val addr = ensureAddresses()
                OnlineRanking.rank(provider.search(text, ctx.refLat, ctx.refLon, 10), ctx.refLat, ctx.refLon,
                    addr.countryNear(ctx.refLat, ctx.refLon), OnlineRanking.queryTowns(text, addr.townsNamed(SearchText.tokens(text))))
            } catch (e: Exception) {
                Problems.info("search", "Online search for a shared place failed (offline or server unreachable)", e); emptyList()
            }

            override fun town(town: String, postcode: String?): SharedTown? {
                val named = try { ensureAddresses().townsNamed(SearchText.tokens(town)) } catch (e: Exception) {
                    Problems.warn("search", "Offline towns unreadable for a shared place", e); emptyList()
                }
                // one town of that name (or all its entries close together); namesakes elsewhere → ask online with the postcode
                val first = named.firstOrNull()
                if (first != null && named.all { Geo.distance(it.lat, it.lon, first.lat, first.lon) < 10_000 }) {
                    return SharedTown(first.lat, first.lon, named.flatMap { listOf(it.name) + it.alt }.distinct())
                }
                val o = online(listOfNotNull(postcode, town).joinToString(" ")).firstOrNull() ?: return null
                return SharedTown(o.lat, o.lon, listOfNotNull(o.town, o.name).distinct())
            }

            override fun geocode(text: String): List<Place> = try { geocoder.find(text) } catch (e: Exception) {
                Problems.info("search", "The phone's address service failed for a shared place", e); emptyList()
            }
        }
        return withContext(Dispatchers.IO) { SharedPlaceLookup(finder).find(name, address, query) }
    }

    override fun run() {
        val s = begin()
        offlineJob?.cancel(); onlineJob?.cancel()
        if (s == null) return
        offlineJob = scope.launch {
            val idx = ensureIndex()
            val (hits, carried) = withContext(Dispatchers.IO) {
                // a newer keystroke cancels this job: the address files not searched yet are skipped
                val addr = if (s.wantsAddresses) ensureAddresses().search(AddressQuery.parse(s.query)) { !isActive } else emptyList()
                ensureActive()
                val hits = idx.search(s.q, addr)
                val keep = offlineKeep(s)
                val carried = if (keep.isEmpty()) emptyList() else carried(s, hits, keep, ensureAddresses().townsNamed(SearchText.tokens(s.text)))
                hits to carried
            }
            offlineDone(s, hits, carried)
        }
        if (!s.useOnline) { onlineOff(); return }
        onlineJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (!onlineLoading(s)) return@launch
            val res = withContext(Dispatchers.IO) {
                // A town named in the query ("via Roma 3 Bolzano") centres the search there; the user's country
                // (from the nearest offline town) ranks first.
                val addr = ensureAddresses()
                val named = addr.townsNamed(SearchText.tokens(s.text))
                val (bLat, bLon) = searchCentre(s, named)
                try {
                    val r = online!!.search(s.text, bLat, bLon, 20)
                    onlineAnswer(s, r, bLat, bLon, addr.countryNear(s.ctx.refLat, s.ctx.refLon), named)
                } catch (e: Exception) {
                    Problems.info("search", "Online search failed (offline or server unreachable)", e); null
                }
            }
            if (!current(s)) return@launch
            offlineJob?.join()
            onlineDone(s, res)
        }
    }

    /** Written on the main thread, read on the IO one. */
    @Volatile override var onlineCentre: Pair<Double, Double>? = null

    companion object {
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
