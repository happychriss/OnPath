package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint
import com.onpath.app.search.BaseMapPlaces
import com.onpath.app.search.OfflineIndex
import com.onpath.app.search.OnlineRanking
import com.onpath.app.search.PhotonParser
import com.onpath.app.search.Place
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchKinds
import com.onpath.app.search.SearchMerge
import com.onpath.app.search.SearchQuery
import com.onpath.app.search.SearchRanking
import java.io.File

/**
 * The web planner's search (docs/design/web-planner.md §10) – the app's search code, run in the browser:
 * per map area the same index entries as the phone ([OfflineIndex.readPois], trail names from overlays.pmtiles,
 * peaks and places from base.pmtiles via [BaseMapPlaces]), ranked by [SearchRanking]; online results parsed by
 * [PhotonParser], re-ranked by [OnlineRanking] and mixed in as the app's SearchSession does. Not yet: offline house
 * numbers (the app's addresses.sqlite needs SQLite), recents.
 */
object WebSearch {
    private val areas = LinkedHashMap<String, List<OfflineIndex.Entry>>()
    private var entries: List<OfflineIndex.Entry> = emptyList()
    private var lastQuery: SearchQuery? = null
    private var lastOffline: List<SearchHit> = emptyList()
    /** Online rows on screen (also kept from an earlier text that still matches), as the app's SearchUi.online. */
    private var lastOnline: List<SearchHit> = emptyList()
    /** The list on screen and what it was ranked for (normalised text + category) – the app's SearchUi.shown / shownFor. */
    private var shown: List<SearchHit>? = null
    private var shownFor: String? = null
    /** Where the last online search was centred (scores online rows kept for the next text). */
    private var onlineCentre: Pair<Double, Double>? = null

    private fun keyOf(text: String, cat: SearchKinds.Category?) = com.onpath.app.search.SearchText.normalize(text) + "|" + (cat?.name ?: "")

    /** [next] as the list on screen: for the text already shown, rows on screen stay and new ones go below (keepShown). */
    private fun shownList(key: String, next: List<SearchHit>): List<SearchHit> {
        val s = shown
        return (if (s != null && shownFor == key) SearchRanking.keepShown(s, next) else next).also { shown = it; shownFor = key }
    }

    private fun categoryOf(name: String): SearchKinds.Category? = SearchKinds.Category.entries.firstOrNull { it.name == name }

    /** The six search chips (the app's SearchKinds.Category, labels in the page's language): JSON [[name, label], …]. */
    fun categories(): String = SearchKinds.Category.entries.joinToString(",", "[", "]") { "[" + s(it.name) + "," + s(it.label) + "]" }

    /** The search closed (or the text and chip emptied): the next one starts afresh, as the app's inactive search. */
    fun reset() { shown = null; shownFor = null; lastOnline = emptyList(); lastOffline = emptyList(); lastQuery = null }

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
        val cat = categoryOf(category)
        val q = SearchQuery(text, cat, lat, lon, line)
        val key = keyOf(text, cat)
        lastQuery = q
        lastOffline = SearchRanking.rank(entries, q)
        val t = text.trim()
        val kinds = cat?.kinds
        val keep = if (t.length < 3) emptyList()
            else SearchRanking.stillMatching(lastOnline.map { it.place }, t).filter { kinds == null || it.kind in kinds }
        lastOnline = if (keep.isEmpty()) emptyList() else {
            val towns = OnlineRanking.queryTowns(t, emptyList())
            val (cLat, cLon) = onlineCentre ?: (lat to lon)
            SearchRanking.onlineHits(SearchMerge.onlineExtras(lastOffline.map { it.place }, keep), q, towns, cLat, cLon)
        }
        return json(shownList(key, SearchRanking.mix(lastOffline, lastOnline)))
    }

    /**
     * Online answer for the last query: Photon's GeoJSON → places → the app's re-ranking, minus places already listed,
     * scored on the offline scale and added – the rows on screen stay where they are (keepShown). Returns the list.
     */
    fun online(text: String, photonJson: String, category: String = ""): String {
        val q = lastQuery?.takeIf { it.text == text } ?: return json(shown ?: lastOffline)
        val cat = categoryOf(category)
        val key = keyOf(text, cat)
        val kinds = cat?.kinds
        val towns = OnlineRanking.queryTowns(text, emptyList())
        val ranked = OnlineRanking.rank(PhotonParser.parse(photonJson), q.refLat, q.refLon, null, towns, text).take(10)
        val offline = if (shownFor == key) lastOffline else emptyList()
        val extras = SearchMerge.onlineExtras(offline.map { it.place } + lastOnline.map { it.place }, ranked)
            .filter { kinds == null || it.kind in kinds }
        onlineCentre = q.refLat to q.refLon
        lastOnline = lastOnline + SearchRanking.onlineHits(extras, q, towns, q.refLat, q.refLon)
        return json(shownList(key, SearchRanking.mix(offline, lastOnline)))
    }

    private fun json(hits: List<SearchHit>): String = hits.joinToString(",", "[", "]") { h ->
        val p = h.place
        "{\"id\":" + s(p.id) + ",\"title\":" + s(p.title(false)) + ",\"name\":" + (p.name?.let { s(it) } ?: "null") +
            ",\"kind\":" + s(p.kind) + ",\"lat\":" + p.lat + ",\"lon\":" + p.lon +
            ",\"subtitle\":" + s(subtitle(h)) + ",\"address\":" + (p.address?.let { s(it) } ?: "null") +
            ",\"glyph\":" + s(p.kindInfo.glyph) + ",\"color\":" + s(color(p.kind)) +
            ",\"online\":" + (p.source == Place.Source.ONLINE) + "}"
    }

    /** The app's result line (ui/search/SearchScreen.resultSubtitle – a Compose file, so the same pieces here). */
    private fun subtitle(h: SearchHit): String = buildString {
        append(h.place.kindInfo.label(false))
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
            '"' -> append("\\\""); '\\' -> append("\\\\")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}
