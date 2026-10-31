package com.onpath.app.search

import com.onpath.app.data.Geo

/**
 * Where a shared place is said to be: the postcode and town from its address ("Mittelstraße 25, 40721 Hilden" →
 * 40721 / Hilden). A search hit for a shared place must lie there – a namesake in another town is not it (field bug
 * 0.13.2: "Olive" shared from Hilden led to an "Olive" in Düsseldorf). Pure (unit-tested).
 */
data class SharedArea(val postcode: String?, val town: String?) {
    val isEmpty: Boolean get() = postcode == null && town == null

    /**
     * Does [p] name this postcode or town (in its town, village, address line; an address hit also in its name)?
     * Null when [p] says nothing about its town (an offline POI) – then only its distance to the town can tell.
     * [townNames]: more names of the town (other languages: "Merano" = "Meran").
     */
    fun names(p: Place, townNames: List<String> = emptyList()): Boolean? {
        val lines = listOfNotNull(p.town, p.village, p.address, p.name?.takeIf { p.kind == "address" || p.kind == "street" })
        if (lines.isEmpty()) return null
        val words = lines.flatMap { SearchText.tokens(it) }.toSet()
        if (postcode != null && postcode in words) return true
        val towns = (listOfNotNull(town) + townNames).map { SearchText.tokens(it) }.filter { it.isNotEmpty() }
        return towns.any { t -> words.containsAll(t) }
    }

    companion object {
        private val POSTCODE = Regex("^[0-9]{4,5}$")

        /**
         * From Google's address line ("Via Goethe, 28, 39012 Merano BZ, Italien", "Mittelstraße 25, 40721 Hilden"):
         * the part with a postcode gives postcode + town; without one, the last of two or more parts when it has no
         * digits ("Hotel Aurora, Meran"). [query] (a link's `q=` text) is read the same way when there is no address.
         */
        fun of(address: String?, query: String? = null): SharedArea {
            val from = sequenceOf(address, query).mapNotNull { it?.let { a -> parse(a) } }.firstOrNull { !it.isEmpty }
            return from ?: SharedArea(null, null)
        }

        private fun parse(s: String): SharedArea {
            val parts = SharedPlaceParser.cleanAddress(s).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts.asReversed()) {
                val w = p.split(Regex("\\s+"))
                val pc = w.firstOrNull { POSTCODE.matches(it) } ?: continue
                val town = w.filter { it != pc && it.none(Char::isDigit) }.joinToString(" ").ifEmpty { null }
                return SharedArea(pc, town)
            }
            val last = parts.lastOrNull()?.takeIf { parts.size >= 2 && it.none(Char::isDigit) }
            return SharedArea(null, last)
        }
    }
}

/** A town found for a [SharedArea]: its point and all its names. */
data class SharedTown(val lat: Double, val lon: Double, val names: List<String> = emptyList())

/** The searches [SharedPlaceLookup] needs; blocking (called off the main thread), never throw. */
interface SharedPlaceFinder {
    /** Offline hits for [text] that match well (name / street equal or starting with it), best first. */
    fun offline(text: String): List<Place>
    /** Online hits for [text], ranked, best first; empty when offline. */
    fun online(text: String): List<Place>
    /** Where [town] (with [postcode], when known) is – offline towns first, then online; null when unknown. */
    fun town(town: String, postcode: String?): SharedTown?
}

/**
 * Finds a shared place that came without a coordinate – by its address, else by its name IN the shared town – and
 * never jumps to a namesake elsewhere: a hit counts only when it names the shared postcode / town or lies close to
 * that town. Without any town in the share, a name counts only when all its namesakes are one place. Else [Result.Unsure]:
 * nothing is placed, the user searches ([Result.Unsure.searchText]). Pure (unit-tested, SharedPlaceLookupTest).
 */
class SharedPlaceLookup(private val finder: SharedPlaceFinder) {

    sealed class Result {
        /** The place, how it was found ("offline" / "online" + "address" / "name in town" / "name"). */
        data class Found(val place: Place, val online: Boolean, val how: String) : Result()
        /** Hits, but none surely the shared place: show nothing, offer the search with [searchText]. */
        data class Unsure(val searchText: String) : Result()
        /** Nothing at all (offline and no offline hit). */
        object NotFound : Result()
    }

    fun find(name: String?, address: String?, query: String?): Result {
        val nm = name?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        val addr = address?.let { SharedPlaceParser.cleanAddress(it) }?.takeIf { it.isNotBlank() }
        val q = query?.let { SharedPlaceParser.cleanAddress(it) }?.takeIf { it.isNotBlank() }
        val area = SharedArea.of(addr, q)
        val placeName = nm ?: q?.substringBefore(',')?.trim()?.takeIf { area.town != null && it != area.town }
        // texts that carry the place's location: the address, name + address, a link's q= text
        val located = LinkedHashSet<String>().apply {
            if (addr != null) add(addr)
            if (nm != null && addr != null) add("$nm, $addr")
            if (q != null) add(q)
        }.toList()
        val nameInTown = placeName?.let { n -> area.town?.let { "$n $it" } }
        val searchText = nameInTown ?: placeName ?: addr ?: q ?: return Result.NotFound
        if (located.isEmpty() && placeName == null) return Result.NotFound

        var seen = false
        val town by lazy { area.town?.let { finder.town(it, area.postcode) } }
        fun ok(p: Place): Boolean {
            val said = area.names(p, town?.names.orEmpty())
            if (said == true) return true
            val t = town ?: return false
            val d = Geo.distance(p.lat, p.lon, t.lat, t.lon)
            // a place naming another town counts only right at the border; one that names none, in the town
            return d <= if (said == false) BORDER_M else IN_TOWN_M
        }
        fun pick(hits: List<Place>): Place? {
            if (hits.isEmpty()) return null
            seen = true
            if (!area.isEmpty) return hits.firstOrNull { ok(it) }
            // no town to check: only when every namesake among the hits is the same place
            val first = hits.first()
            // an offline address is named "Via Roma 3 · Meran": the street and number are what must be the same
            fun key(p: Place) = SearchText.normalize(p.name?.substringBefore(" · "))
            return first.takeIf { hits.all { h -> key(h) != key(first) || Geo.distance(h.lat, h.lon, first.lat, first.lon) <= ONE_PLACE_M } }
        }

        for (t in located) pick(finder.offline(t))?.let { return Result.Found(it, false, "offline address") }
        if (placeName != null && !area.isEmpty) pick(finder.offline(placeName))?.let { return Result.Found(it, false, "offline name in town") }
        for (t in located) pick(finder.online(t))?.let { return Result.Found(it, true, "online address") }
        if (nameInTown != null) pick(finder.online(nameInTown))?.let { return Result.Found(it, true, "online name in town") }
        if (area.isEmpty && placeName != null) {
            pick(finder.offline(placeName))?.let { return Result.Found(it, false, "offline name") }
            pick(finder.online(placeName))?.let { return Result.Found(it, true, "online name") }
        }
        return if (seen) Result.Unsure(searchText) else Result.NotFound
    }

    companion object {
        /** A hit that names no town counts when this close to the shared town's point. */
        const val IN_TOWN_M = 5_000.0
        /** A hit that names another town counts only this close (a street on the town border). */
        const val BORDER_M = 1_500.0
        /** Namesakes this close are one place (a POI and its map label, two entrances). */
        const val ONE_PLACE_M = 2_000.0
    }
}
