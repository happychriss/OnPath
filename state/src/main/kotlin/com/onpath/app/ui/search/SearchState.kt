package com.onpath.app.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.Geo
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.AddressFile
import com.onpath.app.search.OnlineRanking
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SearchMerge
import com.onpath.app.search.SearchQuery
import com.onpath.app.search.SearchRanking
import com.onpath.app.search.SearchText

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
    /** „Zuletzt gesucht“ without the places shown above it ([SavedPlaces.start]). */
    val recent: List<SearchHit> = emptyList(),
    /** „⌂ Zuhause“ on top of the empty search (owner 2026-11-15); null = none set (the row is absent). */
    val home: SearchHit? = null,
    /** „★ Favoriten“: at most [SavedPlaces.SHOWN_FAVOURITES] until „Alle Favoriten ›“ ([moreFavourites] > 0). */
    val favourites: List<SearchHit> = emptyList(),
    val moreFavourites: Int = 0,
    val hasRoute: Boolean = false,
    /**
     * The list on screen, kept by [SearchState]: ranked once per text ([shownFor]); later answers for the same text
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

/** What [SearchState] asks of the app (the kept places and recent picks, the offline index): the app implements it, the planner too. */
interface SearchPorts {
    /** Zuhause and the favourites (never throws; an unreadable file reads as none). */
    fun saved(): SavedPlaces
    /** „Zuletzt gesucht“, newest first. */
    fun recent(): List<Place>
    /** A place picked: it goes to the top of „Zuletzt gesucht“. */
    fun addRecent(p: Place)
    fun clearRecent()
    /** The offline index's copy of the place with this id (full details), or null (no index yet / not in it). */
    fun indexedPlace(id: String): Place?
}

/** One search run, as plain data: what [SearchState.begin] decided, handed to the stages after it (it may cross a message border). */
class Search(
    /** The run's number; a newer run makes the answers of this one stale. */
    val gen: Int,
    /** What the ranked list is for ([SearchState.keyOf]). */
    val key: String,
    val query: String,
    val q: SearchQuery,
    /** The trimmed text. */
    val text: String,
    /** The online provider is asked for this one (there is one, and 3+ letters). */
    val useOnline: Boolean,
    val kinds: Set<String>?,
    /** Online rows on screen (an earlier text), to carry over when they still match. */
    val before: List<Place>,
    val ctx: SearchContext,
) {
    /** The address files are searched (a street / address may be meant). */
    val wantsAddresses: Boolean get() = kinds == null || "street" in kinds || "address" in kinds
}

/** The online provider's answer, ranked: [places] around ([lat], [lon]) with the [towns] named in the query. */
class OnlineAnswer(val places: List<Place>, val towns: Set<String>, val lat: Double, val lon: Double)

/**
 * State and decisions of the search view: what is on screen ([ui]), the empty search's lists, and a run as stages –
 * [begin] (plain data), the offline answer ([offlineKeep], [carried], [offlineDone]), the online answer
 * ([onlineLoading], [searchCentre], [onlineAnswer], [onlineDone]). The work between the stages (the index, the address
 * files, the provider, threads, the 350 ms wait, cancelling) is the driver's: [SearchSession] in the app (coroutines),
 * the planner's worker. Android-free and without coroutines. Index, saved places and recent ones come through
 * [SearchPorts]. The web-ready cut of `SearchSession` (docs/design/web-ready-status.md §7.1).
 */
abstract class SearchState(
    protected val ports: SearchPorts,
    /** An online provider exists (online rows are asked for with 3+ letters). */
    private val hasOnline: Boolean,
    protected val context: () -> SearchContext,
) {
    companion object {
        /** How long the online provider waits after the last keystroke before it is asked (ms). */
        const val DEBOUNCE_MS = 350L
    }

    var ui by mutableStateOf(SearchUi())
        protected set

    /** The number of the latest run; an answer for an older one is dropped. */
    protected var generation = 0

    protected var allFavourites = false

    /** Where the last online search was centred (scores online rows kept for the next text); written / read on different threads in the app. */
    protected open var onlineCentre: Pair<Double, Double>? = null

    /** Starts the work for the current text (the driver's `run`): [begin], then the stages. */
    protected abstract fun run()

    fun onQuery(q: String) { ui = ui.copy(query = q); run() }

    fun onCategory(c: SearchKinds.Category?) { ui = ui.copy(category = if (ui.category == c) null else c); run() }

    fun clear() { ui = ui.copy(query = "", hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE); run() }

    fun remember(p: Place) { ports.addRecent(p) }

    fun clearRecent() { ports.clearRecent(); ui = ui.copy(recent = emptyList()) }

    /** „Alle Favoriten ›“: every favourite place, until the search is opened again. */
    fun showAllFavourites() { allFavourites = true; ui = startLists(ui, context()) }

    /** Zuhause, the favourites and „Zuletzt gesucht“ of the empty search ([SavedPlaces.start]). */
    protected fun startLists(u: SearchUi, ctx: SearchContext): SearchUi {
        val s = ports.saved().start(ports.recent(), allFavourites)
        fun hit(p: Place) = SearchHit(p, 0.0, 0, Geo.distance(ctx.refLat, ctx.refLon, p.lat, p.lon), false)
        return u.copy(home = s.home?.let(::hit), favourites = s.favourites.map(::hit), moreFavourites = s.moreFavourites,
            recent = s.recent.map(::hit))
    }

    /**
     * A result or recent entry picked: the offline copy (full details) when the index has it. A shown result is that
     * copy already; a recent one is one indexed lookup.
     */
    fun resolve(p: Place): Place = ui.hits.firstOrNull { it.place.id == p.id }?.place ?: ports.indexedPlace(p.id) ?: p

    /** What one ranked list is for: the normalised text and the category (a space or case change is the same text). */
    protected fun keyOf(u: SearchUi) = SearchText.normalize(u.query) + "|" + (u.category?.name ?: "")

    /** [next] as the list on screen: for the text already shown, rows on screen stay and new ones go below. */
    protected fun shownList(cur: SearchUi, key: String, next: List<SearchHit>): List<SearchHit> {
        val shown = cur.shown
        return if (shown != null && cur.shownFor == key) SearchRanking.keepShown(shown, next) else next
    }

    // ---------------- a run, as stages ----------------

    /**
     * The synchronous head of a run: a new generation; the list on screen stays until the answer for the new text is there
     * (old rows are not re-ranked meanwhile). Null = nothing to search (an empty search: the old results are cleared,
     * the driver stops the work still on its way); else the run as plain data.
     */
    fun begin(): Search? {
        val gen = ++generation
        val u = ui
        val ctx = context()
        if (!u.active) {
            ui = u.copy(hits = emptyList(), online = emptyList(), onlineState = OnlineState.IDLE, hasRoute = ctx.route != null,
                shown = null, shownFor = null)
            return null
        }
        val text = u.query.trim()
        return Search(gen, keyOf(u), u.query, SearchQuery(u.query, u.category, ctx.refLat, ctx.refLon, ctx.route), text,
            hasOnline && text.length >= 3, u.category?.kinds,
            // online rows on screen (an earlier text) that still match this one stay in its list: typing on never loses them
            u.online.map { it.place }, ctx)
    }

    /** No online search for this run: the online state is idle (the driver calls it right after starting the offline work). */
    fun onlineOff() { ui = ui.copy(onlineState = OnlineState.IDLE) }

    /** The run is still the latest one. */
    fun current(s: Search): Boolean = s.gen == generation

    /** Offline stage: the online rows of the text before that still match this one (empty when no online search runs). */
    fun offlineKeep(s: Search): List<Place> =
        if (!s.useOnline) emptyList() else SearchRanking.stillMatching(s.before, s.text).filter { s.kinds == null || it.kind in s.kinds }

    /** Offline stage: [keep] as online hits, scored like the others ([towns]: the offline towns named in the text). */
    fun carried(s: Search, hits: List<SearchHit>, keep: List<Place>, towns: List<AddressFile.Town>): List<SearchHit> {
        val queryTowns = OnlineRanking.queryTowns(s.text, towns)
        val (cLat, cLon) = onlineCentre ?: (s.ctx.refLat to s.ctx.refLon)
        return SearchRanking.onlineHits(SearchMerge.onlineExtras(hits.map { it.place }, keep), s.q, queryTowns, cLat, cLon)
    }

    /** The offline answer is there. False = a newer run came meanwhile (dropped). */
    fun offlineDone(s: Search, hits: List<SearchHit>, carried: List<SearchHit>): Boolean {
        if (s.gen != generation) return false
        val cur = ui
        ui = cur.copy(hits = hits, online = carried, hasRoute = s.ctx.route != null,
            shown = shownList(cur, s.key, SearchRanking.mix(hits, carried)), shownFor = s.key)
        return true
    }

    /** The online wait is over: shows "loading". False = a newer run came meanwhile (stop). */
    fun onlineLoading(s: Search): Boolean {
        if (s.gen != generation) return false
        ui = ui.copy(onlineState = OnlineState.LOADING)
        return true
    }

    /**
     * Online stage: where to search – a town named in the query ("via Roma 3 Bolzano") centres the search there, else the
     * user's position; [named] = the offline towns named in the text.
     */
    fun searchCentre(s: Search, named: List<AddressFile.Town>): Pair<Double, Double> {
        val centre = named.minByOrNull { Geo.distance(s.ctx.refLat, s.ctx.refLon, it.lat, it.lon) }
        return if (centre != null) centre.lat to centre.lon else s.ctx.refLat to s.ctx.refLon
    }

    /** Online stage: the provider's [results] ranked (the user's country [userCc] first), the best 10. */
    fun onlineAnswer(s: Search, results: List<Place>, lat: Double, lon: Double, userCc: String?, named: List<AddressFile.Town>): OnlineAnswer {
        val towns = OnlineRanking.queryTowns(s.text, named)
        return OnlineAnswer(OnlineRanking.rank(results, lat, lon, userCc, towns, s.text).take(10), towns, lat, lon)
    }

    /** The online answer (null = none: offline or the server unreachable) is there; the driver checked [current] and waited for the offline part. */
    fun onlineDone(s: Search, answer: OnlineAnswer?) {
        if (s.gen != generation) return
        // no answer: the rows on screen stay (also online ones kept from the text before)
        if (answer == null) { ui = ui.copy(onlineState = OnlineState.FAILED); return }
        onlineCentre = answer.lat to answer.lon
        val cur = ui
        val offline = if (cur.shownFor == s.key) cur.hits else emptyList()
        val extras = SearchMerge.onlineExtras(offline.map { it.place } + cur.online.map { it.place }, answer.places)
            .filter { s.kinds == null || it.kind in s.kinds }
        // scored like the offline hits, so both rank in one list
        val all = cur.online + SearchRanking.onlineHits(extras, s.q, answer.towns, answer.lat, answer.lon)
        ui = cur.copy(online = all, onlineState = OnlineState.DONE,
            shown = shownList(cur, s.key, SearchRanking.mix(offline, all)), shownFor = s.key)
    }
}
