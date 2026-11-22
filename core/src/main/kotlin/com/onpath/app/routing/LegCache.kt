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
class LegCache(private val capacity: Int = CAPACITY, private val store: LegStore? = null,
               private val maxLinePoints: Int = MAX_LINE_POINTS) {
    data class Key(val fromLat: Long, val fromLon: Long, val toLat: Long, val toLon: Long, val activity: Activity, val sac: Int,
                   /** A stitch's cut leg (docs/design/detour-stitch.md §8.9): its own entry, read only by [lookupAnchored]. */
                   val cut: Boolean = false)

    /** A leg found: [stored] = read from the [store] (not routed in this process). */
    class Hit(val leg: RouteResult, val stored: Boolean)

    private val lock = Any()
    private val map = LinkedHashMap<Key, RouteResult>(minOf(capacity, 64) + 1, 0.75f, true)
    /** Plain legs in [map] that are the router's own answer for their two exact points (§8.15); always a subset of the keys of [map]. */
    private val exactKeys = HashSet<Key>()
    /** Line points of the legs held (the memory bound beside [capacity], [maxLinePoints]). */
    private var linePoints = 0L

    /** Keeps [leg] under [k] (under [lock]); the oldest legs go while more than [capacity] legs or [maxLinePoints] are held. */
    private fun keep(k: Key, leg: RouteResult, exact: Boolean = false) {
        map.put(k, leg)?.let { linePoints -= it.points.size }
        if (exact) exactKeys.add(k) else exactKeys.remove(k)
        linePoints += leg.points.size
        val it = map.entries.iterator()
        while ((map.size > capacity || linePoints > maxLinePoints) && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == k) continue // the leg just kept stays
            linePoints -= e.value.points.size
            exactKeys.remove(e.key)
            it.remove()
        }
    }

    fun get(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): RouteResult? = lookup(from, to, activity, sac)?.leg

    /** The leg from memory, else a fresh one from the [store] (then kept in memory too); null = route it. Never a cut leg. */
    fun lookup(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Hit? = lookup(key(from, to, activity, sac))

    /**
     * For a leg of an expanded plan that touches an anchor (§8.9): an exact plain leg first (§8.15, the router's own answer
     * for the two points), then the stitch's cut leg (memory, then fresh in the [store]), else the plain leg. Null = route it.
     */
    fun lookupAnchored(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Hit? =
        lookupExact(key(from, to, activity, sac)) ?: lookup(key(from, to, activity, sac, cut = true)) ?: lookup(key(from, to, activity, sac))

    /** The plain leg only when it is marked exact: in memory, or fresh and exact in the [store] (then kept in memory, marked). */
    private fun lookupExact(k: Key): Hit? {
        synchronized(lock) {
            val m = map[k]
            if (m != null) return if (k in exactKeys) Hit(m, stored = false) else null // a plain leg in memory is the newest word
        }
        val s = store?.get(k)?.takeIf { it.fresh && it.exact } ?: return null
        synchronized(lock) { keep(k, s.leg, exact = true) }
        return Hit(s.leg, stored = true)
    }

    /** The plain leg [from] → [to] is the router's own answer for exactly these points (§8.15): marked in memory, or fresh exact in the [store]. */
    fun isExact(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Boolean = lookupExact(key(from, to, activity, sac)) != null

    /** Leg [i] of [plan]: [lookupAnchored] where it touches an anchor, else [lookup] – the one rule for the key (§8.9). */
    fun lookupLeg(plan: ExpandedPlan, i: Int, activity: Activity, sac: Int): Hit? {
        val pts = plan.points
        return if (plan.touchesAnchor(i)) lookupAnchored(pts[i], pts[i + 1], activity, sac) else lookup(pts[i], pts[i + 1], activity, sac)
    }

    /**
     * The leg from MEMORY only (no [store] read), under the plain key or the [cut] key: for [LegSplit.seed] and
     * [aliasExpanded], which look at many candidate legs – a cold load would otherwise do thousands of file lookups.
     */
    fun inMemory(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int, cut: Boolean = false): RouteResult? =
        synchronized(lock) { map[key(from, to, activity, sac, cut)] }

    private fun lookup(k: Key): Hit? {
        synchronized(lock) { map[k] }?.let { return Hit(it, stored = false) }
        val s = store?.get(k)?.takeIf { it.fresh } ?: return null
        synchronized(lock) { keep(k, s.leg) }
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

    /**
     * [stale] for leg [i] of [plan]: a FRESH leg first ([lookupLeg]), only then a stale cut leg (where the leg touches an
     * anchor, §8.9), then a stale plain one – a stand-in never takes an old line where a current one exists.
     */
    fun staleLeg(plan: ExpandedPlan, i: Int, activity: Activity, sac: Int): RouteResult? {
        lookupLeg(plan, i, activity, sac)?.let { return it.leg }
        val pts = plan.points
        if (plan.touchesAnchor(i)) {
            val k = key(pts[i], pts[i + 1], activity, sac, cut = true)
            (synchronized(lock) { map[k] } ?: store?.get(k)?.leg)?.let { return it }
        }
        return stale(pts[i], pts[i + 1], activity, sac)
    }

    /** Is the leg known fresh (memory or [store], then kept in memory) – a plan whose legs all are needs no router run. */
    fun has(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Boolean = lookup(from, to, activity, sac) != null

    /**
     * [cut] = a stitch's cut leg (own key, §8.9). [exact] = a plain leg that is the router's own answer for exactly these
     * points (§8.15: both points already 1e-6°); never together with [cut]. Without [exact] an older mark under the key goes.
     */
    fun put(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int, leg: RouteResult, cut: Boolean = false, exact: Boolean = false) {
        require(!(exact && cut)) { "a cut leg is never exact" }
        val k = key(from, to, activity, sac, cut)
        synchronized(lock) { keep(k, leg, exact) }
        if (exact) store?.putExact(k, from, to, leg) else store?.put(k, from, to, leg)
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

    /**
     * [alias] over expanded lists (§8.2): the plan [old] was snapped onto its line and is [new] now. Same layout only
     * (same size, same [ExpandedPlan.visibleIndex]), else 0. Every leg with a changed end is copied under the new ends
     * keeping its kind (a cut leg stays cut, a plain one plain; a leg that touches an anchor: cut first, then plain), and
     * each visible leg with anchors also as its full plain leg (the §8.5 fallback's leg, the old base). Memory only.
     * Returns the number of legs copied.
     */
    fun aliasExpanded(old: ExpandedPlan, new: ExpandedPlan, activity: Activity, sac: Int): Int {
        if (old.points.size != new.points.size || old.visibleIndex != new.visibleIndex) return 0
        fun moved(i: Int, j: Int) = key(old.points[i], old.points[j], activity, sac) != key(new.points[i], new.points[j], activity, sac)
        var copied = 0
        for (i in 0 until old.points.size - 1) {
            if (!moved(i, i + 1)) continue
            val cutLeg = if (old.touchesAnchor(i)) inMemory(old.points[i], old.points[i + 1], activity, sac, cut = true) else null
            val leg = cutLeg ?: inMemory(old.points[i], old.points[i + 1], activity, sac) ?: continue
            if (cutLeg == null && isExactInMemory(new.points[i], new.points[i + 1], activity, sac)) continue
            put(new.points[i], new.points[i + 1], activity, sac, leg, cut = cutLeg != null)
            copied++
        }
        for (v in 0 until old.visibleCount - 1) {
            val from = old.expandedIndex(v); val to = old.expandedIndex(v + 1)
            if (to - from < 2 || !moved(from, to)) continue
            val leg = inMemory(old.points[from], old.points[to], activity, sac) ?: continue
            if (isExactInMemory(new.points[from], new.points[to], activity, sac)) continue
            put(new.points[from], new.points[to], activity, sac, leg)
            copied++
        }
        return copied
    }

    /** The mark in memory only (no [store] read). */
    fun isExactInMemory(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int): Boolean =
        synchronized(lock) { key(from, to, activity, sac) in exactKeys }

    /** Legs held now (in memory). */
    val size: Int get() = synchronized(lock) { map.size }

    /**
     * Newer routing data (the same points may route differently now): every leg in memory goes. The [store] keeps its
     * legs – each says by itself whether the data under it changed ([LegStore.Stored.fresh]).
     */
    fun clear() { synchronized(lock) { map.clear(); exactKeys.clear(); linePoints = 0 } }

    companion object {
        /**
         * Legs kept: one per plan point of the largest plan ([PlanCode.MAX_POINTS]) plus room for its anchors (at most 500,
         * detour-stitch.md §8.8) and the cut legs – a multi-day tour of 189 points (München–Venedig, stage 0 2026-11-17)
         * routed ~120 of its legs again at every edit with 64.
         */
        const val CAPACITY = 1_000
        /**
         * The memory bound: line points held in all legs (a point ~50 bytes → ~12 MB). München–Venedig, 594 km, is ~60 000
         * line points – several such tours fit; a 35 km bike leg is ~1 000.
         */
        const val MAX_LINE_POINTS = 250_000

        /** The T level counts only for Hike (the bike profiles ignore it – same as BRouterEngine's own cache key). */
        fun key(from: GeoPoint, to: GeoPoint, activity: Activity, sac: Int, cut: Boolean = false) = Key(
            Math.round(from.lat * 1_000_000.0), Math.round(from.lon * 1_000_000.0),
            Math.round(to.lat * 1_000_000.0), Math.round(to.lon * 1_000_000.0),
            activity, if (activity == Activity.HIKE) sac.coerceIn(1, 5) else 0, cut)
    }
}

/**
 * Legs kept beyond the process ([LegCache]'s store; the app: data/LegFiles). [get] says whether the stored leg is still
 * [Stored.fresh] for this device: routed with the same routing fingerprint (BRouter, profiles, PLANNER_VERSION) and on
 * the same routing data under the leg. Implementations never throw: a leg that cannot be read or written is no leg.
 */
interface LegStore {
    /** [exact] = the leg is marked as the router's own answer for its two exact points (§8.15). */
    class Stored(val leg: RouteResult, val fresh: Boolean, val exact: Boolean = false)

    fun get(key: LegCache.Key): Stored?

    fun put(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult)

    /** [put] of an exact leg (§8.15); a store that cannot mark it just keeps the leg. */
    fun putExact(key: LegCache.Key, from: GeoPoint, to: GeoPoint, leg: RouteResult) = put(key, from, to, leg)
}
