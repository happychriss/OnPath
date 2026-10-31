package com.onpath.app.search

import com.onpath.app.debug.Problems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a place shared into OnPath goes (AppViewModel.receiveSharedPlace; unit-tested with a fake network and a fake
 * search, SharedPlaceResolveTest). Owner 2026-10-31 (A-020), in this order:
 *  1. a coordinate in the shared text or link (`?q=lat,lon`, `@lat,lon`, `!3d…!4d…`) – also in the link a short link
 *     (maps.app.goo.gl) redirects to: only the redirects are followed, the Google Maps page is never loaded;
 *  2. the name + address (from the share text or the resolved link): our offline index, then Photon;
 *  3. then the phone's own address service (Android `Geocoder`, [PlaceGeocoder]);
 *  4. every hit of 2 and 3 must lie in the shared town / postcode ([SharedPlaceLookup]);
 *  5. else [SharedOutcome.Unsure]: the "Place not clear" box with the search prefilled.
 * What is kept: only the point and the shared name / address on the orange ring (owner rule, CLAUDE.md).
 */
class SharedPlaceResolve(
    /** One HTTP request (no redirects followed, no body read) – [ShortLinkResolver.http] on the phone. */
    private val fetch: (String) -> HttpHop,
    /** The address / name search ([SharedPlaceLookup] via SearchSession.findShared); never throws. */
    private val find: suspend (name: String?, address: String?, query: String?) -> SharedPlaceLookup.Result,
) {
    suspend fun resolve(shared: SharedPlace): SharedOutcome {
        var p = shared
        if (!p.hasPoint && p.link != null) {
            val link = p.link!!
            val r = withContext(Dispatchers.IO) { ShortLinkResolver(fetch).resolve(link) }
            r.place?.let { rp -> p = p.copy(lat = rp.lat, lon = rp.lon, name = p.name ?: rp.name, address = p.address ?: rp.address, query = p.query ?: rp.query) }
            if (!p.hasPoint) Problems.info("search", "Shared map link gave no position (${r.reason}); searching the address instead")
        }
        val lat = p.lat; val lon = p.lon
        if (lat != null && lon != null) {
            Problems.info("search", "Shared place at %.5f, %.5f (%s)".format(java.util.Locale.ROOT, lat, lon,
                if (shared.hasPoint) "in the shared text / link" else "from the resolved link"))
            // "Gelateria Costantin, Passeggiata …, Italien" (Google's place path) → the name only on the ring
            val shownName = p.name?.substringBefore(',')?.trim()?.ifEmpty { null } ?: p.name
            return SharedOutcome.Show(Place("shared:$lat,$lon", shownName, "other", lat, lon, address = p.address,
                note = SharedPosition.of(point = true).note))
        }
        return when (val found = find(p.name, p.address, p.query)) {
            is SharedPlaceLookup.Result.Found -> {
                Problems.info("search", "Shared place positioned from its address (${found.how})")
                val shownName = p.name?.substringBefore(',')?.trim()?.ifEmpty { null } ?: found.place.name
                SharedOutcome.Show(found.place.copy(name = shownName, note = SharedPosition.of(point = false).note))
            }
            // hits, but none surely the place (a namesake in another town): no ring – the search, prefilled
            is SharedPlaceLookup.Result.Unsure -> {
                Problems.info("search", "Shared place not clear: no sure hit in the shared town – the search is offered")
                SharedOutcome.Unsure(found.searchText)
            }
            SharedPlaceLookup.Result.NotFound -> SharedOutcome.NotFound
        }
    }
}

/** What the map shows for a shared place. */
sealed class SharedOutcome {
    /** The orange ring at [place] (its note says when the position came from the address). */
    data class Show(val place: Place) : SharedOutcome()
    /** No sure place: the "Place not clear" box, its button opens the search with [searchText]. */
    data class Unsure(val searchText: String) : SharedOutcome()
    /** Nothing found: the HINWEIS "This place was not found". */
    object NotFound : SharedOutcome()
}
