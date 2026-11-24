package com.onpath.app.search

/**
 * The offline place index as the app uses it: [DiskIndex] (SQLite file, the app) or [OfflineIndex] (all in memory –
 * the reference the disk index is tested against, and small sets in tests). Pure Kotlin in its own file: the web
 * planner compiles it with [OfflineIndex] and [RouteNamer] (not [DiskIndex]).
 */
interface PlaceIndex {
    /** Same signature = same content ([AreaPlan.signature]). */
    val signature: String

    /** Number of entries (POIs, merged trail names, base-map places). Cheap. */
    val size: Int

    /** True when it can no longer answer (its file broke): the next check builds a new one. */
    val broken: Boolean get() = false

    /** Ranked results; blocking (disk) – call off the main thread. */
    fun search(q: SearchQuery, addresses: List<AddressHit> = emptyList()): List<SearchHit>

    /** The entry with this id (the first in index order), or null. */
    fun place(id: String): Place?

    /**
     * Named POIs and base-map places (no trails) of [kinds] inside the box, in index order – the places
     * [RouteNamer] looks at. Blocking – off the main thread.
     */
    fun placesIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, kinds: Set<String>): List<Place>

    /** Prepares what [search] needs (reads the word index into memory), so the first keystroke answers fast. */
    fun warmUp() {}
}
