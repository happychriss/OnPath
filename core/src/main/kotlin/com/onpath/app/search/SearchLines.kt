package com.onpath.app.search

import java.util.Locale

/** The kept places' and "Recent"'s line format and the Recent rule – pure, shared with the planner (WebKept). */
object SearchLines {
    const val MAX_RECENT = 20

    /** [p] first, the same place not twice, at most [MAX_RECENT]. */
    fun addRecent(recent: List<Place>, p: Place): List<Place> =
        (listOf(p) + recent.filter { it.id != p.id && !SearchMerge.samePlace(it, p) }).take(MAX_RECENT)

    fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')

    fun encode(p: Place): String = listOf(p.id, clean(p.name), p.kind, p.sub.orEmpty(),
        String.format(Locale.ROOT, "%.6f", p.lat), String.format(Locale.ROOT, "%.6f", p.lon),
        p.ele?.toString().orEmpty(), clean(p.address), p.source.name,
        clean(p.town), clean(p.village), if (p.shared) "s" else "").joinToString("\t")

    fun decode(l: String): Place? {
        val p = l.split('\t'); if (p.size < 9) return null
        return Place(p[0], p[1].ifEmpty { null }, p[2], p[4].toDoubleOrNull() ?: return null, p[5].toDoubleOrNull() ?: return null,
            p[6].toIntOrNull(), p[3].ifEmpty { null }, p[7].ifEmpty { null },
            // expected: unknown/old source name → POI
            runCatching { Place.Source.valueOf(p[8]) }.getOrDefault(Place.Source.POI),
            // fields 10–11 (town, village) are missing in older entries
            town = p.getOrNull(9)?.ifEmpty { null }, village = p.getOrNull(10)?.ifEmpty { null },
            // field 12: shared into OnPath (missing in older entries)
            shared = p.getOrNull(11) == "s")
    }
}
