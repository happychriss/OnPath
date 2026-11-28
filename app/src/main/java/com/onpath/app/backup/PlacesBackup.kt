package com.onpath.app.backup

import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.gpx.GpxWriter
import com.onpath.app.search.SavedPlaces
import com.onpath.app.search.SearchStore

/**
 * Zuhause and the favourite places ([SavedPlaces], owner 2026-11-15) in the backup folder: one file [FILE_NAME] beside
 * the tours. Other apps see the places as waypoints; OnPath reads them back from its record
 * `<onpath:places version="1">` (the same lines as on the phone, [SearchStore.encode]) – never mistaken for a tour (a
 * tour's record is `<onpath:backup>`). Pure, never throws on reading.
 */
object PlacesBackup {
    const val FILE_NAME = "OnPath Orte.gpx"
    const val VERSION = 1
    private val recordRe = Regex("<onpath:places\\b[^>]*>(.*?)</onpath:places\\s*>", RegexOption.DOT_MATCHES_ALL)
    private const val HOME = "home\t"
    private const val FAV = "fav\t"

    fun encode(s: SavedPlaces, timeMs: Long = System.currentTimeMillis()): String {
        val lines = listOfNotNull(s.home?.let { HOME + SearchStore.encode(it) }) + s.favourites.map { FAV + SearchStore.encode(it) }
        val wpts = listOfNotNull(s.home?.let { GpxWaypoint(it.lat, it.lon, "⌂ " + it.title(), it.ele?.toDouble(), "home") }) +
            s.favourites.map { GpxWaypoint(it.lat, it.lon, it.title(), it.ele?.toDouble(), "favourite") }
        return GpxWriter.write("OnPath – Orte", emptyList(), wpts, timeMs = timeMs,
            extensions = "<onpath:places version=\"$VERSION\">${GpxWriter.esc(lines.joinToString("\n"))}</onpath:places>",
            extensionNs = "xmlns:onpath=\"${BackupCodec.NS}\"")
    }

    /** True when [text] is OnPath's places file. */
    fun isPlaces(text: String): Boolean = recordRe.containsMatchIn(text)

    /** The places of a places file; null when it is none or its record cannot be read (reported). */
    fun decode(text: String): SavedPlaces? = try {
        val body = recordRe.find(text)?.groupValues?.get(1) ?: return null
        var home: com.onpath.app.search.Place? = null
        val favs = ArrayList<com.onpath.app.search.Place>()
        for (l in unescape(body).lines()) when {
            l.startsWith(HOME) -> home = SearchStore.decode(l.removePrefix(HOME)) ?: home
            l.startsWith(FAV) -> SearchStore.decode(l.removePrefix(FAV))?.let { favs += it }
        }
        com.onpath.app.search.homeAsFavourite(SavedPlaces(home, favs)) // an old backup's Zuhause comes back as a favourite
    } catch (e: Exception) {
        Problems.warn("backup", "Places file ($FILE_NAME) unreadable – no places restored", e); null
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
