package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.BaseMapPlaces
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.PhotonParser
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SavedPlaces
import com.onpath.app.search.SearchRanking
import com.onpath.app.ui.search.Search
import com.onpath.app.ui.search.SearchContext
import com.onpath.app.ui.search.SearchPorts
import com.onpath.app.ui.search.SearchState
import com.onpath.app.ui.search.SearchUi
import java.io.File

/**
 * The web planner's search (docs/design/web-planner.md §10) – the app's search code, run in the browser:
 * per map area the same index entries as the phone ([OfflineIndex.readPois], trail names from overlays.pmtiles,
 * peaks and places from base.pmtiles via [BaseMapPlaces]), ranked by [SearchRanking]; online results parsed by
 * [PhotonParser], re-ranked by [OnlineRanking] and mixed in as the app's SearchSession does. Not yet: offline house
 * numbers (the app's addresses.sqlite needs SQLite). Recents and kept places: [WebKept].
 */
object WebSearch {
    private val areas = LinkedHashMap<String, List<OfflineIndex.Entry>>()
    private var entries: List<OfflineIndex.Entry> = emptyList()

    /** Where the user is and what is planned for the run being started (the page passes them with each search). */
    private var ctx = SearchContext(0.0, 0.0, null)

    /** What the page keeps (keptplaces.ts) for the empty search's lists: Zuhause, the favourites, „Zuletzt gesucht“. */
    private var kept = SavedPlaces()
    private var recents: List<Place> = emptyList()

    /**
     * The app's [SearchState] driven by the page (web-ready, umbau 2026-11-20): the planner has no coroutines, so the
     * page is the driver – its search() is [SearchState.begin] + the offline stage, its Photon answer the online stage
     * ([SearchState.onlineDone]); the 350 ms wait and the fetch stay the page's (SearchSession.DEBOUNCE_MS). Kept places
     * and recent picks are the page's ([WebKept]); [SearchPorts] reads what the page passed with the call.
     */
    private object State : SearchState(Ports, hasOnline = true, context = { ctx }) {
        /** The last run started; the online answer belongs to it only while it is the latest ([current]). */
        var last: Search? = null

        override fun run() {} // expected: the page drives the stages (search / online below), never this hook

        /** The text and chip as typed (the app's onQuery / onCategory without starting a run – the page starts it). */
        fun set(text: String, cat: SearchKinds.Category?) { ui = ui.copy(query = text, category = cat) }

        /** The search closed (or text and chip emptied): the next one starts afresh, as the app's inactive search. */
        fun reset() { ui = SearchUi(); last = null }

        /** The empty search's lists ([SearchState.startLists]); [all] = „Alle Favoriten ›“. */
        fun lists(all: Boolean): SearchUi { allFavourites = all; return startLists(SearchUi(), ctx) }
    }

    private object Ports : SearchPorts {
        override fun saved() = kept
        override fun recent() = recents
        override fun addRecent(p: Place) {} // expected: the page keeps „Zuletzt gesucht“ (WebKept.addRecent, keptplaces.ts)
        override fun clearRecent() {} // expected: the page clears its own list (keptplaces.ts)
        override fun indexedPlace(id: String): Place? = entries.firstOrNull { it.place.id == id }?.place
    }

    /** The empty search's lists ([WebKept.start]): what the app's SearchState shows for these kept places. */
    internal fun startLists(saved: SavedPlaces, recent: List<Place>, all: Boolean, lat: Double, lon: Double): SearchUi {
        kept = saved; recents = recent; ctx = SearchContext(lat, lon, null)
        return State.lists(all)
    }

    private fun categoryOf(name: String): SearchKinds.Category? = SearchKinds.Category.entries.firstOrNull { it.name == name }

    /**
     * The six search chips (the app's SearchKinds.Category, labels in the page's language): JSON [[name, label, [kind, …]], …]
     * – the kinds let the page filter the rows on screen at once on a chip tap, before the engine answers.
     */
    fun categories(): String = SearchKinds.Category.entries.joinToString(",", "[", "]") { c ->
        "[" + s(c.name) + "," + s(c.label) + "," + c.kinds.joinToString(",", "[", "]") { s(it) } + "]"
    }

    /** The search closed (or the text and chip emptied): the next one starts afresh, as the app's inactive search. */
    fun reset() = State.reset()

    /** Indexes one map area from the files in [dir] (pois.geojson, base.pmtiles, overlays.pmtiles). Returns its entries. */
    fun addArea(key: String, dir: String): Int {
        val d = File(dir)
        val places = ArrayList<Place>()
        safe("pois") { places += OfflineIndex.readPois(File(d, OfflineIndex.POIS)) }
        safe("trails") { places += OfflineIndex.readTrailsFromTiles(File(d, OfflineIndex.OVERLAYS)) }
        safe("base") {
            val base = File(d, OfflineIndex.BASE)
            if (base.isFile) places += BaseMapPlaces.read(base).map { OfflineIndex.placeOf(it) }
        }
        areas[key] = places.map { OfflineIndex.Entry(it) }
        entries = areas.values.flatten()
        return places.size
    }

    fun hasArea(key: String) = key in areas

    /** All places of the indexed map areas (for the route name proposal, WebUi.routeName). */
    fun places(): List<Place> = entries.map { it.place }

    private inline fun safe(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { Problems.warn("search", "Search index: $what skipped (file unreadable)", e) }
    }

    /**
     * Offline hits for [text] (and chip [category], "" = none) near ([lat], [lon]); [route] = "lat,lon;…" of the planned
     * line or "". As the app's SearchSession: online rows already on screen that still match the new text stay in its
     * list; for the same text the rows on screen keep their places, new ones go below (never a jump under the pointer,
     * field bug 0.13.2). JSON list = the list to show.
     */
    fun search(text: String, lat: Double, lon: Double, route: String, category: String = ""): String {
        val line = route.split(';').filter { it.isNotBlank() }.map { p ->
            val (a, b) = p.split(','); GeoPoint(a.toDouble(), b.toDouble())
        }.takeIf { it.size >= 2 }
        ctx = SearchContext(lat, lon, line)
        State.set(text, categoryOf(category))
        val run = State.begin()
        State.last = run
        if (run == null) return "[]"
        // the offline stage (the app's SearchSession on its thread): the index ranked, online rows of the text before that
        // still match carried over; offline house numbers are not in the planner yet (no address files: no towns named)
        val hits = SearchRanking.rank(entries, run.q)
        State.offlineDone(run, hits, State.carried(run, hits, State.offlineKeep(run), emptyList()))
        return json(State.ui.results)
    }

    /**
     * Online answer for the last query: Photon's GeoJSON → places → the app's re-ranking ([SearchState.onlineAnswer]),
     * added below the rows on screen ([SearchState.onlineDone], keepShown). Returns the list.
     */
    fun online(text: String, photonJson: String, category: String = ""): String {
        // a late answer for another text or chip (asked before a chip click, back after it – owner's bug 2026-11-19:
        // „München“ + „Orte“ still showed everything) changes nothing; the app cancels it (SearchSession.run)
        val run = State.last?.takeIf { it.query == text && it.q.category == categoryOf(category) && State.current(it) }
            ?: return json(State.ui.results)
        if (!State.onlineLoading(run)) return json(State.ui.results)
        val (lat, lon) = State.searchCentre(run, emptyList())
        State.onlineDone(run, State.onlineAnswer(run, PhotonParser.parse(photonJson), lat, lon, null, emptyList()))
        return json(State.ui.results)
    }

    private fun json(hits: List<SearchHit>): String = hits.joinToString(",", "[", "]") { h -> hitJson(h.place, subtitle(h)) }

    /** One row of the list: the place's fields, the row's [subtitle] and its [WebKept] line (`code`, to keep it). */
    internal fun hitJson(p: Place, subtitle: String): String =
        "{\"id\":" + s(p.id) + ",\"title\":" + s(p.title()) + ",\"name\":" + (p.name?.let { s(it) } ?: "null") +
            ",\"kind\":" + s(p.kind) + ",\"lat\":" + p.lat + ",\"lon\":" + p.lon +
            ",\"subtitle\":" + s(subtitle) + ",\"address\":" + (p.address?.let { s(it) } ?: "null") +
            ",\"glyph\":" + s(p.kindInfo.glyph) + ",\"color\":" + s(color(p.kind)) +
            ",\"online\":" + (p.source == Place.Source.ONLINE) + ",\"code\":" + s(WebKept.encode(p)) + "}"

    /** The app's result line (ui/search/SearchScreen.resultSubtitle – a Compose file, so the same pieces here). */
    private fun subtitle(h: SearchHit): String = buildString {
        append(h.place.kindInfo.label)
        h.place.ele?.let { append(" · $it m") }
        h.place.municipality?.let { append(" · ").append(it) }
        append(" · ").append(Format.distance(h.distanceM))
        if (h.nearRoute) append(" · near your route")
        if (h.place.source == Place.Source.ONLINE) append(" · online")
    }

    private fun color(kind: String): String {
        val v = SearchKinds.argb(kind) and 0xFFFFFF
        return "#" + v.toString(16).padStart(6, '0')
    }

    private fun s(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\t' -> append("\\t") // a kept place's line keeps its tabs
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}
