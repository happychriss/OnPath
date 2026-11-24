package com.onpath.app.search

import android.content.Context
import com.onpath.app.debug.Problems
import com.onpath.app.routing.GeoPoint

/**
 * Small persisted search state: the last 20 picked places ("Recent", owner 2026-11-15: 8 was too few) and the names of route points
 * that were added from a place (so stops can be listed as "Leiter Alm" instead of "Point 3").
 * Only OSM / Photon data is stored – never anything from Google, except a place the user shared into OnPath
 * themselves (owner 2026-11-02: "zuletzt habe ich es ja gesucht"): it goes to "Recent" with its share sign.
 */
class SearchStore(context: Context) {
    private val sp = context.getSharedPreferences("onpath-search", 0)
    private val places = context.getSharedPreferences(PLACES_PREFS, 0)

    fun recent(): List<Place> = sp.getString("recent", null).orEmpty().lines().mapNotNull { decode(it) }

    fun addRecent(p: Place) {
        val list = SearchLines.addRecent(recent(), p)
        sp.edit().putString("recent", list.joinToString("\n") { encode(it) }).apply()
    }

    fun clearRecent() { sp.edit().remove("recent").apply() }

    /**
     * Zuhause and the favourite places ([SavedPlaces], owner 2026-11-15) – their own file `onpath-places`, which Android's
     * backup takes along (res/xml backup rules); the search prefs (recent, names) stay out of it. Never throws: an
     * unreadable entry is skipped (reported), an unreadable file = nothing kept.
     */
    fun saved(): SavedPlaces = try { savedOrThrow() } catch (e: Exception) {
        Problems.warn("search", "Favourite places / Zuhause unreadable – none shown", e); SavedPlaces()
    }

    /**
     * As [saved], but an unreadable file throws instead of reading as „nothing kept“ – the browser sync
     * (plansync/SearchPlaceStore) must never take an unreadable file for places the user removed.
     */
    fun savedOrThrow(): SavedPlaces = SavedPlaces(home = places.getString("home", null)?.let { decode(it) },
        favourites = places.getString("favourites", null).orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { l ->
            decode(l) ?: null.also { Problems.warn("search", "A favourite place could not be read – left out") }
        })

    fun save(s: SavedPlaces) {
        try { synchronized(LOCK) { write(s, durable = false) } } catch (e: Exception) {
            Problems.error("search", "Favourite places / Zuhause could not be saved", e)
        }
    }

    /**
     * Changes the kept places in one step – read, [change], write – alone among every writer in the process (the place
     * menu, a backup restore, the browser sync's import), so none of them writes over another's change. [durable] = on
     * disk before it returns (the sync: its ledger already counts the change as done). Returns the new places; throws
     * when they could not be read or written (nothing changed then).
     */
    fun update(durable: Boolean = false, change: (SavedPlaces) -> SavedPlaces): SavedPlaces = synchronized(LOCK) {
        val cur = savedOrThrow()
        val next = change(cur)
        if (next != cur) write(next, durable)
        next
    }

    private fun write(s: SavedPlaces, durable: Boolean) {
        val e = places.edit().putString("home", s.home?.let { encode(it) })
            .putString("favourites", s.favourites.joinToString("\n") { encode(it) })
        if (durable) { if (!e.commit()) throw java.io.IOException("kept places not written") } else e.apply()
    }

    fun names(): Map<GeoPoint, String> = sp.getString("names", null).orEmpty().lines().mapNotNull { l ->
        val p = l.split('\t'); if (p.size < 3) return@mapNotNull null
        val lat = p[0].toDoubleOrNull() ?: return@mapNotNull null; val lon = p[1].toDoubleOrNull() ?: return@mapNotNull null
        GeoPoint(lat, lon) to p[2]
    }.toMap()

    fun saveNames(names: Map<GeoPoint, String>) {
        sp.edit().putString("names", names.entries.toList().takeLast(200)
            .joinToString("\n") { (g, n) -> "${g.lat}\t${g.lon}\t${SearchLines.clean(n)}" }).apply()
    }

    companion object {
        const val MAX_RECENT = SearchLines.MAX_RECENT
        /** Zuhause + favourites: in Android's backup (backup_rules.xml / data_extraction_rules.xml). */
        const val PLACES_PREFS = "onpath-places"
        /** One writer of the kept places at a time ([update]). */
        private val LOCK = Any()
        fun encode(p: Place): String = SearchLines.encode(p)
        fun decode(l: String): Place? = SearchLines.decode(l)
    }
}
