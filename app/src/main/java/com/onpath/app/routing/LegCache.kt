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
 *
 * Behind the memory an optional [store] (owner 2026-11-07, "saved routes load without routing again"; the app:
 * data/LegFiles on the phone): every finished leg is written there with the routing code and the routing data it was
 * computed with, so a saved route loaded after an app restart takes its legs from there – no router run. A stored leg
 * is used only while it is [LegStore.Stored.fresh] (same routing fingerprint, the routing data under it unchanged);
 * a stale one is only a stand-in line while the leg is routed again ([stale]).
 */
class LegCache(private val capacity: Int = CAPACITY, private val store: LegStore? = null) {
    data class Key(val fromLat: Long, val fromLon: Long, val toLat: Long, val toLon: Long, val activity: Activity, val sac: Int)

    /** A leg found: [stored] = read from the [store] (not routed in this process). */
    class Hit(val leg: RouteResult, val stored: Boolean)

    private val lock = Any()
    private val map = object : LinkedHashMap<Key, RouteResult>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RouteResult>?) = size > capacity
    }

    fun get(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): RouteResult? = lookup(from, to, activity, sac)?.leg

    /** The leg from memory, else a fresh one from the [store] (then kept in memory too); null = route it. */
    fun lookup(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Hit? {
        val k = key(from, to, activity, sac)
        synchronized(lock) { map[k] }?.let { return Hit(it, stored = false) }
        val s = store?.get(k)?.takeIf { it.fresh } ?: return null
        synchronized(lock) { map[k] = s.leg }
        return Hit(s.leg, stored = true)
    }

    /**
     * A line for the leg while it is routed (a loaded route on newer routing data, docs/design/long-bike-routes.md
     * "Saved routes"): memory or the [store], fresh or not; null = none known.
     */
    fun stale(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): RouteResult? {
        val k = key(from, to, activity, sac)
        return synchronized(lock) { map[k] } ?: store?.get(k)?.leg
    }

    /** Is the leg known fresh (memory or [store], then kept in memory) – a plan whose legs all are needs no router run. */
    fun has(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Boolean = lookup(from, to, activity, sac) != null

    fun put(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int, leg: RouteResult) {
        val k = key(from, to, activity, sac)
        synchronized(lock) { map[k] = leg }
        store?.put(k, from, to, leg)
    }

    /**
     * The plan's points were moved onto the line after the run (WaypointSnap – a tap beside the way goes onto it): the
     * leg [from] → [to] also under the moved points [newFrom] → [newTo], so the plan as it is now – and as it is saved –
     * takes the legs it shows instead of routing them again. The same line: the join keeps its cut there (a moved point
     * lies on the line, where the run's join put it). False = the leg is not in memory (a straight leg).
     */
    fun alias(from: GeoPoint, to: GeoPoint, newFrom: GeoPoint, newTo: GeoPoint, activity: Activity, sac: Int): Boolean {
        val leg = synchronized(lock) { map[key(from, to, activity, sac)] } ?: return false
        put(newFrom, newTo, activity, sac, leg)
        return true
    }

    /** Legs held now (in memory). */
    val size: Int get() = synchronized(lock) { map.size }

    /**
     * Newer routing data (the same points may route differently now): every leg in memory goes. The [store] keeps its
     * legs – each says by itself whether the data under it changed ([LegStore.Stored.fresh]).
     */
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

/**
 * Legs kept beyond the process ([LegCache]'s store; the app: data/LegFiles). [get] says whether the stored leg is still
 * [Stored.fresh] for this device: routed with the same routing fingerprint (BRouter, profiles, PLANNER_VERSION) and on
 * the same routing data under the leg. Implementations never throw: a leg that cannot be read or written is no leg.
 */
interface LegStore {
    class Stored(val leg: RouteResult, val fresh: Boolean)

    fun get(key: LegCache.Key): Stored?

    fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult)
}
