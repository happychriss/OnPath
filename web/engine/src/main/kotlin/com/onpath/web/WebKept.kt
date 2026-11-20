package com.onpath.web

import com.onpath.app.data.Format
import com.onpath.app.debug.Problems
import com.onpath.app.search.Place
import com.onpath.app.search.SavedPlaces
import com.onpath.app.poi.Poi
import com.onpath.app.search.SearchHit
import com.onpath.app.search.SearchMerge
import com.onpath.app.ui.plan.KeptPlacesHost
import com.onpath.app.ui.plan.KeptPlacesSlice
import com.onpath.app.ui.plan.PlacesStore

/**
 * Kept places in the planner (owner 2026-11-15, „Orte merken“, F-PLAN-14; docs/design/web-planner.md §32): the app's
 * own rules ([SavedPlaces], shared code) over what the page keeps in this browser (web/app/src/keptplaces.ts).
 *
 * A place travels between page and engine as ONE line – the app's SearchStore line (id, name, kind, sub, lat, lon,
 * ele, address, source, town, village, shared; tab-separated, 6 decimals), mirrored here because SearchStore is
 * Android code. The page stores these lines untouched, so a later phone ⇄ browser sync can carry them as the phone
 * stores them. Lists of places = lines joined with "\n"; home = one line or "".
 */
object WebKept {
    /** The app's SearchStore.MAX_RECENT (owner 2026-11-15: 20). */
    const val MAX_RECENT = 20

    private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')

    /** Six decimals without a locale (SearchStore: String.format(Locale.ROOT, "%.6f")). */
    internal fun fixed6(v: Double): String {
        val neg = v < 0
        val micro = Math.round(kotlin.math.abs(v) * 1_000_000.0)
        val whole = micro / 1_000_000; val frac = (micro % 1_000_000).toString().padStart(6, '0')
        return (if (neg && micro != 0L) "-" else "") + whole + "." + frac
    }

    /** The app's SearchStore.encode. */
    fun encode(p: Place): String = listOf(p.id, clean(p.name), p.kind, p.sub.orEmpty(), fixed6(p.lat), fixed6(p.lon),
        p.ele?.toString().orEmpty(), clean(p.address), p.source.name, clean(p.town), clean(p.village),
        if (p.shared) "s" else "").joinToString("\t")

    /** The app's SearchStore.decode: null for a line it cannot read. */
    fun decode(l: String): Place? {
        val p = l.split('\t'); if (p.size < 9) return null
        return Place(p[0], p[1].ifEmpty { null }, p[2], p[4].toDoubleOrNull() ?: return null, p[5].toDoubleOrNull() ?: return null,
            p[6].toIntOrNull(), p[3].ifEmpty { null }, p[7].ifEmpty { null },
            Place.Source.entries.firstOrNull { it.name == p[8] } ?: Place.Source.POI, // expected: unknown / old name → POI
            town = p.getOrNull(9)?.ifEmpty { null }, village = p.getOrNull(10)?.ifEmpty { null },
            shared = p.getOrNull(11) == "s")
    }

    private fun places(lines: String): List<Place> = lines.split('\n').filter { it.isNotBlank() }.mapNotNull { l ->
        decode(l) ?: null.also { Problems.warn("search", "A kept place could not be read – left out") }
    }

    private fun saved(home: String, favourites: String) = SavedPlaces(home = home.takeIf { it.isNotBlank() }?.let(::decode), favourites = places(favourites))

    /** Is [code] a favourite / the home place (the menu's „★ Gemerkt“ / „Zuhause entfernen“)? JSON {favourite, home}. */
    fun state(home: String, favourites: String, code: String): String {
        val p = decode(code) ?: return "{\"favourite\":false,\"home\":false}"
        val s = saved(home, favourites)
        return "{\"favourite\":" + s.isFavourite(p) + ",\"home\":" + s.isHome(p) + "}"
    }

    /**
     * One change ([op]: keep, unkeep, setHome, clearHome) made by the app's own [KeptPlacesSlice] on what is kept now –
     * the target state (setFavourite / setHome): keep / unkeep (every copy of it goes), setHome replaces the old home,
     * clearHome removes it. A row drawn before another tab or the sync made the same change changes nothing (the
     * slice's own rule). JSON {home, favourites}.
     */
    fun apply(home: String, favourites: String, op: String, code: String): String {
        val now = saved(home, favourites)
        val p = decode(code)
        val s = if (p == null && op != "clearHome") now else {
            val store = PageStore(now)
            val slice = KeptPlacesSlice(store, object : KeptPlacesHost {
                // the page hands the place itself (address, kind) – the slice's Poi path is the app's, not asked here
                override fun searchMark(): Place? = p
                override fun isCandidate(poi: Poi) = false
            })
            when (op) {
                "keep" -> p?.let { slice.setFavourite(it, true) }
                "unkeep" -> p?.let { slice.setFavourite(it, false) }
                "setHome" -> p?.let { slice.setHome(it) }
                "clearHome" -> slice.setHome(null)
            }
            store.places
        }
        return "{\"home\":" + (s.home?.let { str(encode(it)) } ?: "null") + ",\"favourites\":" +
            s.favourites.joinToString(",", "[", "]") { str(encode(it)) } + "}"
    }

    /**
     * [PlacesStore] for one call of the app's [KeptPlacesSlice]: what the page keeps now, changed in memory; the page
     * stores the result itself (keptplaces.ts: localStorage, one change after the other) and its store's change event is
     * what backs it up and sends it to the paired phone (sync.ts) – the two notices are the page's, nothing here.
     */
    private class PageStore(var places: SavedPlaces) : PlacesStore {
        override fun saved() = places
        override fun update(change: (SavedPlaces) -> SavedPlaces): SavedPlaces = change(places).also { places = it }
        override fun backupChanged() {} // expected: the page's store event (keptplaces.ts emit)
        override fun syncChanged() {} // expected: the page's store event → the next sync round (sync.ts keptPlaces)
    }

    /** The app's SearchStore.addRecent: [code] first, the same place not twice, at most [MAX_RECENT]. Lines. */
    fun addRecent(recent: String, code: String): String {
        val p = decode(code) ?: return recent
        return (listOf(p) + places(recent).filter { it.id != p.id && !SearchMerge.samePlace(it, p) }).take(MAX_RECENT)
            .joinToString("\n") { encode(it) }
    }

    /**
     * The empty search (the app's SearchState.startLists + SearchScreen.recentSection): Zuhause, the favourites (five
     * unless [all] – „Alle Favoriten ›“), „Zuletzt gesucht“ without the places shown above. Each row: the place's
     * search-row fields (WebSearch's hit), its [code] and the grey part of the one-line row (SearchScreen.compactLine:
     * kind · height · distance; Zuhause: the place's name · distance), distance from ([lat], [lon]).
     */
    fun start(home: String, favourites: String, recent: String, all: Boolean, lat: Double, lon: Double): String {
        // the lists as the app's SearchState.startLists makes them (SavedPlaces.start, each row with its distance)
        val u = WebSearch.startLists(saved(home, favourites), places(recent), all, lat, lon)
        fun row(h: SearchHit, withTitle: Boolean = false): String {
            val p = h.place
            val line = buildList {
                add(if (withTitle) p.title() else p.kindInfo.label)
                if (!withTitle) p.ele?.let { add("$it m") }
                add(Format.distance(h.distanceM))
            }.joinToString(" · ")
            return WebSearch.hitJson(p, line).dropLast(1) + ",\"shared\":" + p.shared + "}"
        }
        return "{\"home\":" + (u.home?.let { row(it, true) } ?: "null") +
            ",\"favourites\":" + u.favourites.joinToString(",", "[", "]") { row(it) } +
            ",\"more\":" + u.moreFavourites + ",\"recent\":" + u.recent.joinToString(",", "[", "]") { row(it) } + "}"
    }

    private fun str(v: String): String = buildString {
        append('"')
        for (c in v) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\t' -> append("\\t")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }
}
