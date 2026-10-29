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

    /** Offline hits for [text] near ([lat], [lon]); [route] = "lat,lon;…" of the planned line or "". JSON list. */
    fun search(text: String, lat: Double, lon: Double, route: String): String {
        val line = route.split(';').filter { it.isNotBlank() }.map { p ->
            val (a, b) = p.split(','); GeoPoint(a.toDouble(), b.toDouble())
        }.takeIf { it.size >= 2 }
        val q = SearchQuery(text, null, lat, lon, line)
        lastQuery = q
        lastOffline = SearchRanking.rank(entries, q)
        return json(lastOffline)
    }

    /**
     * Online answer for the last query: Photon's GeoJSON → places → the app's re-ranking, minus places the offline
     * list already has, scored on the offline scale and put below the rows already shown (rows never move under the
     * pointer, as in the app). Returns the whole list (offline + online) as JSON.
     */
    fun online(text: String, photonJson: String): String {
        val q = lastQuery?.takeIf { it.text == text } ?: return json(lastOffline)
        val towns = OnlineRanking.queryTowns(text, emptyList())
        val ranked = OnlineRanking.rank(PhotonParser.parse(photonJson), q.refLat, q.refLon, null, towns).take(10)
        val extras = SearchMerge.onlineExtras(lastOffline.map { it.place }, ranked)
        val scored = SearchRanking.onlineHits(extras, q, towns)
        return json(SearchRanking.mix(lastOffline, scored, keepShown = lastOffline.isNotEmpty()))
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
