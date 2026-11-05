package com.onpath.app.routing

/**
 * The legs of earlier plan runs (owner 2026-11-05, "long bike routes faster", docs/design/long-bike-routes.md): the
 * [Planner] routes a plan leg by leg – point → point, each leg its own request – and keeps every finished leg here, so an
 * edit routes only the legs it changed (a point set in between: its two new legs; a point deleted: one; the rest come
 * from here at once). Same points = same legs = same route: the cache only saves time, a fresh start gives the identical
 * line. Key: both points (to 1e-6°), the activity and – for Hike – the T level; a leg is stored after the Planner's
 * short-trip retry, as the plan uses it.
 *
 * Kept in memory for the process, one per routing engine (the app: AppViewModel; the web planner: its page) – a router or
 * profile change comes with a new app / page, newer routing data clears it ([clear]). Thread-safe (the app plans on one
 * thread, synced routes and the tour's follow line on others). Pure Kotlin – shared with the web planner
 * (app/web-shared-files.txt).
 */
class LegCache(private val capacity: Int = CAPACITY) {
    data class Key(val fromLat: Long, val fromLon: Long, val toLat: Long, val toLon: Long, val activity: Activity, val sac: Int)

    private val lock = Any()
    private val map = object : LinkedHashMap<Key, RouteResult>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RouteResult>?) = size > capacity
    }

    fun get(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): RouteResult? =
        synchronized(lock) { map[key(from, to, activity, sac)] }

    fun put(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int, leg: RouteResult) {
        synchronized(lock) { map[key(from, to, activity, sac)] = leg }
    }

    /** Legs held now. */
    val size: Int get() = synchronized(lock) { map.size }

    /** Newer routing data (the same points may route differently now): every leg is routed again. */
    fun clear() { synchronized(lock) { map.clear() } }

    companion object {
        /** Legs kept: a 35 km bike leg is ~1 000 line points – 64 legs are a few MB, and several long plans fit. */
        const val CAPACITY = 64

        /** The T level counts only for Hike (the bike profiles ignore it – same as BRouterEngine's own cache key). */
        fun key(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int) = Key(
            Math.round(from.lat * 1_000_000.0), Math.round(from.lon * 1_000_000.0),
            Math.round(to.lat * 1_000_000.0), Math.round(to.lon * 1_000_000.0),
            activity, if (activity == Activity.HIKE) sac.coerceIn(1, 5) else 0)
    }
}
