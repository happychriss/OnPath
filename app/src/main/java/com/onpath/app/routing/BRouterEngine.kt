package com.onpath.app.routing

import com.onpath.strings.*
import android.content.Context
import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.OsmNodePairSet
import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.ProfileCache
import btools.router.RoutingContext
import com.onpath.app.debug.Problems
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import btools.router.RoutingEngine as BRouter

/**
 * Fully offline routing with BRouter (btools.router), running in-process.
 *
 * @param context any context (application context is used)
 * @param segmentsDir directory containing the BRouter *.rd5 files,
 *   e.g. `getExternalFilesDir(null)/onpath/segments` holding `E10_N45.rd5`.
 *
 * Profiles (lookups.dat + *.brf) are shipped in `assets/brouter/` and copied to
 * `filesDir/brouter/profiles/` on first use, because BRouter reads them as files.
 * [route] is blocking and serialized (one route at a time); call it from a background thread. The variant with
 * [RouteLimits] (re-routing while navigating) bounds the wait + search time and can be cancelled.
 * It only ever throws [RoutingException]. Recent results are cached, so repeating an identical
 * request (same points, activity and SAC limit) returns instantly.
 *
 * Via points are hints: BRouter snaps each to the nearest usable way (within [MAX_SNAP_M]) and,
 * with `correctMisplacedViaPoints`, cuts out the out-and-back spike a via point slightly off the
 * natural line would otherwise cause (up to [VIA_CORRECTION_M] one way, [VIA_CORRECTION_HIKE_M] for hiking).
 */
class BRouterEngine(context: Context, private val segmentsDir: File) : RoutingEngine {

    private val appContext: Context = context.applicationContext ?: context
    private val profileDir = File(appContext.filesDir, "brouter/profiles")
    @Volatile private var profilesReady = false

    /** Small LRU of recent results; guarded by [LOCK]. */
    private val cache = object : LinkedHashMap<CacheKey, RouteResult>(CACHE_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, RouteResult>?) = size > CACHE_SIZE
    }

    private data class CacheKey(val coords: List<Long>, val activity: Activity, val sac: Int)

    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult =
        routeWith(waypoints, activity, sacLimit, null)

    /** With a time limit (incl. waiting for a running request) and a cancel signal (BRouter `terminate()`). */
    override fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult =
        routeWith(waypoints, activity, sacLimit, limits)

    private fun routeWith(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits?): RouteResult =
        try {
            val start = System.currentTimeMillis()
            if (limits == null) LOCK.lock()
            else if (!LOCK.tryLock(limits.timeoutMs, TimeUnit.MILLISECONDS)) throw RoutingException(BUSY, timedOut = true)
            lastLockWait.set(System.currentTimeMillis() - start)
            try {
                val key = cacheKey(waypoints, activity, sacLimit)
                cache[key] ?: run {
                    val budget = limits?.let { (it.timeoutMs - (System.currentTimeMillis() - start)).coerceAtLeast(MIN_BUDGET_MS) }
                    routeLocked(waypoints, activity, sacLimit, budget, limits?.cancel)
                }.also { cache[key] = it }
            } finally {
                LOCK.unlock()
            }
        } catch (e: RoutingException) {
            throw e
        } catch (e: InterruptedException) {
            // expected: a newer request or the user cancelled
            Thread.currentThread().interrupt()
            throw RoutingException(CANCELLED)
        } catch (e: OutOfMemoryError) {
            Problems.error("routing", "BRouter ran out of memory", e)
            throw RoutingException(Txt.s(Res.string.plan_err_memory))
        } catch (e: StackOverflowError) {
            Problems.error("routing", "BRouter stack overflow (route too complex)", e)
            throw RoutingException(Txt.s(Res.string.plan_err_complex))
        } catch (e: LinkageError) {
            Problems.error("routing", "BRouter classes could not be loaded (LinkageError)", e)
            throw RoutingException(Txt.s(Res.string.plan_err_engine))
        } catch (e: Exception) {
            Problems.error("routing", "Routing threw unexpectedly", e)
            throw RoutingException(UNEXPECTED)
        }

    private fun cacheKey(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): CacheKey {
        val c = ArrayList<Long>(waypoints.size * 2)
        for (p in waypoints) {
            c.add(Math.round(p.lat * 1_000_000.0)); c.add(Math.round(p.lon * 1_000_000.0))
        }
        return CacheKey(c, activity, if (activity == Activity.HIKE) sacLimit.coerceIn(1, 5) else 0)
    }

    private fun routeLocked(
        waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, budgetMs: Long? = null, cancel: RouteCancel? = null,
    ): RouteResult {
        if (waypoints.size < 2) throw RoutingException(Txt.s(Res.string.plan_err_set_start_dest))
        checkSegments(waypoints)
        ensureProfiles()
        val limit = RouteTimeLimit.forRoute(waypoints, activity)
        val timeoutMs = budgetMs?.coerceAtMost(limit) ?: limit
        if (cancel?.isCancelled == true) throw RoutingException(CANCELLED)

        val (track, nodes) = try {
            search(waypoints, activity, sacLimit, timeoutMs, cancel)
        } catch (e: RoutingException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Problems.error("routing", "BRouter ran out of memory", e)
            throw RoutingException(Txt.s(Res.string.plan_err_memory))
        } catch (e: Exception) {
            Problems.warn("routing", "BRouter threw an exception; shown to the user as a routing error", e)
            throw RoutingException(describeError(e.toString(), activity, sacLimit, timeoutMs), timedOut = isTimeout(e.toString()))
        }

        return toResult(track, waypoints, activity, matchedPoints(track, nodes))
    }

    /**
     * Runs BRouter and returns the track with the nodes it was given. A point that BRouter snapped onto an "island" (a
     * way without a connection to the rest of the network, e.g. a footway inside a cathedral) is snapped again to the
     * next way: BRouter's own island retry snaps to the same way again, so the app hands it the ways to skip (owner rule:
     * a point that cannot be routed takes the closest usable way).
     */
    private fun search(
        waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, timeoutMs: Long, cancel: RouteCancel?,
    ): Pair<OsmTrack, List<OsmNodeNamed>> {
        val t0 = System.currentTimeMillis()
        val skipWays = ArrayList<LongArray>()
        while (true) {
            val rc = RoutingContext()
            rc.localFunction = File(profileDir, profileFile(activity)).absolutePath
            rc.keyValues = keyValues(activity, sacLimit)
            val nodes = bRouterNodes(waypoints)
            val engine = BRouter(null, null, segmentsDir, nodes, rc)
            engine.quite = true
            if (skipWays.isNotEmpty()) skipOnSnap(engine, skipWays)
            val left = timeoutMs - (System.currentTimeMillis() - t0)
            cancel?.onCancel { engine.terminate() }
            try { engine.doRun(left.coerceAtLeast(MIN_BUDGET_MS)) } finally { cancel?.onCancel(null) }
            if (cancel?.isCancelled == true) throw RoutingException(CANCELLED)
            val err = engine.errorMessage
                ?: return (engine.foundTrack ?: throw RoutingException(describeError(null, activity, sacLimit, timeoutMs))) to nodes
            val island = if (isTimeout(err)) null else islandPoint(err, islandRetries(engine), waypoints.size)
            if (island == null) throw RoutingException(describeError(err, activity, sacLimit, timeoutMs), timedOut = isTimeout(err))
            val way = snappedWay(engine, nodes[island].name)
            if (way == null || skipWays.size >= MAX_ISLAND_SKIPS || timeoutMs - (System.currentTimeMillis() - t0) < MIN_BUDGET_MS) {
                throw RoutingException(describeError("island", activity, sacLimit, timeoutMs))
            }
            Problems.info("routing", "Point ${island + 1} of ${waypoints.size} snapped to a way without a connection " +
                "(BRouter: $err); snapping it to the next way")
            skipWays.add(way)
        }
    }

    private fun bRouterNodes(waypoints: List<GeoPoint>): List<OsmNodeNamed> = waypoints.mapIndexed { i, p ->
        OsmNodeNamed().apply {
            name = when (i) {
                0 -> "from"
                waypoints.size - 1 -> "to"
                else -> "via$i"
            }
            ilon = ((p.lon + 180.0) * 1_000_000.0 + 0.5).toInt()
            ilat = ((p.lat + 90.0) * 1_000_000.0 + 0.5).toInt()
        }
    }

    /**
     * The way (its two end nodes as BRouter ids) that BRouter snapped the point [name] to in a failed run, or null.
     * BRouter keeps the snapped ways in the protected `RoutingEngine.matchedWaypoints`, hence reflection.
     */
    private fun snappedWay(engine: BRouter, name: String): LongArray? = try {
        val f = BRouter::class.java.getDeclaredField("matchedWaypoints").apply { isAccessible = true }
        (f.get(engine) as? List<*>).orEmpty().filterIsInstance<MatchedWaypoint>()
            .firstOrNull { it.name == name && it.node1 != null && it.node2 != null }
            ?.let { longArrayOf(it.node1.idFromPos, it.node2.idFromPos) }
    } catch (e: Throwable) {
        Problems.warn("routing", "BRouter snapped ways not readable (reflection); no second snap for an isolated way", e)
        null
    }

    /** How often BRouter itself retried after an island (its `islandNodePairs` freeze count); -1 = unknown. */
    private fun islandRetries(engine: BRouter): Int = try {
        val f = BRouter::class.java.getDeclaredField("islandNodePairs").apply { isAccessible = true }
        (f.get(engine) as OsmNodePairSet).freezeCount
    } catch (e: Throwable) {
        Problems.warn("routing", "BRouter island retries not readable (reflection)", e)
        -1
    }

    /** Makes BRouter's waypoint matching skip [ways] (its island list – used only when snapping points). */
    private fun skipOnSnap(engine: BRouter, ways: List<LongArray>) {
        try {
            val f = BRouter::class.java.getDeclaredField("islandNodePairs").apply { isAccessible = true }
            val pairs = f.get(engine) as OsmNodePairSet
            ways.forEach { pairs.addTempPair(it[0], it[1]) }
            pairs.freezeTempPairs()
        } catch (e: Throwable) {
            Problems.warn("routing", "BRouter island list not writable (reflection); point stays on the isolated way", e)
        }
    }

    /**
     * BRouter's snapped crosspoint per input node (matched by name), null where unknown. Read in BRouter's own package
     * ([btools.router.OnPathHints.matched], `OsmTrack.matchedWaypoints` is protected) – no reflection, so the web
     * planner (TeaVM) gets them too and re-routes to a stop on a way that the via correction cut out ([Planner.plan]).
     */
    private fun matchedPoints(track: OsmTrack, nodes: List<OsmNodeNamed>): List<GeoPoint?> {
        val rows = btools.router.OnPathHints.matched(track, nodes.map { it.name }.toTypedArray())
        val out = nodes.indices.map { i -> rows.getOrNull(i)?.takeIf { it.size == 2 }?.let { GeoPoint(it[0], it[1]) } }
        if (out.all { it == null }) Problems.info("routing", "BRouter reported no matched waypoints; using route geometry instead")
        return out
    }

    // ---- result conversion -------------------------------------------------------------

    private fun toResult(track: OsmTrack, waypoints: List<GeoPoint>, activity: Activity, matched: List<GeoPoint?>): RouteResult {
        val els = track.nodes
        if (els.isEmpty()) throw RoutingException(Txt.s(Res.string.plan_err_no_route_for, activity.label))
        val points = ArrayList<GeoPoint>(els.size)
        var lastSElev: Short? = null
        var buffer = 0.0
        var descent = 0.0
        for (e in els) {
            val lat = e.getILat() / 1_000_000.0 - 90.0
            val lon = e.getILon() / 1_000_000.0 - 180.0
            val se = e.getSElev()
            if (se == Short.MIN_VALUE) {
                points.add(GeoPoint(lat, lon, null))
                continue
            }
            points.add(GeoPoint(lat, lon, se / 4.0))
            // filtered descent, mirroring BRouter's hysteresis filter for ascent
            val prev = lastSElev
            if (prev != null) {
                buffer += (prev - se) / 4.0
                if (buffer > ELE_FILTER_M) {
                    descent += buffer - ELE_FILTER_M
                    buffer = ELE_FILTER_M
                } else if (buffer < 0.0) {
                    buffer = 0.0
                }
            }
            lastSElev = se
        }

        // BRouter widens its search radius when a point is far from any usable way;
        // treat a large snap distance as "point too far from any path".
        waypoints.forEachIndexed { i, wp ->
            val d = when (i) {
                0 -> distM(wp, points.first())
                waypoints.size - 1 -> distM(wp, points.last())
                else -> points.minOf { distM(wp, it) }
            }
            // a via point at the end of a removed out-and-back spike may lie further from the route
            val limit = if (i == 0 || i == waypoints.size - 1) MAX_SNAP_M else MAX_SNAP_M + viaCorrectionM(activity)
            if (d > limit) {
                throw RoutingException(Txt.s(Res.string.plan_err_too_far, pointName(i, waypoints.size), d.roundToInt(), activity.label))
            }
        }

        // way attributes per point for the time model (nice-to-have: never fail a route because of it)
        val ways = try {
            BRouterWayStats.perNode(track.aggregateMessages(),
                IntArray(els.size) { els[it].getILon() - 180_000_000 }, IntArray(els.size) { els[it].getILat() - 90_000_000 })
        } catch (e: Exception) {
            Problems.warn("routing", "Way attributes per point could not be read; times computed without them", e)
            emptyList()
        }
        if (ways.size == points.size) for (i in points.indices) points[i] = points[i].copy(way = ways[i])
        try { JunctionCue.apply(points, btools.router.OnPathHints.of(track)) } catch (e: Exception) {
            Problems.warn("routing", "Junction instructions not read; navigation gives no junction vibration on this route", e)
        }

        val distanceM = track.distance.toDouble()
        // breakdown is a nice-to-have: never fail a route because of it
        val stats = try {
            BRouterWayStats.fromMessages(track.aggregateMessages(), distanceM)
        } catch (e: Exception) {
            Problems.warn("routing", "Way type / surface breakdown could not be computed; shown empty", e)
            BRouterWayStats.Breakdown(emptyList(), emptyList())
        }
        return RouteResult(
            points = points,
            distanceM = distanceM,
            ascentM = track.ascend.toDouble(),
            descentM = descent,
            durationS = track.getTotalSeconds().toDouble(),
            wayTypes = stats.wayTypes,
            surfaces = stats.surfaces,
            matched = matched,
        )
    }

    // ---- profiles -------------------------------------------------------------------------

    private fun ensureProfiles() {
        if (profilesReady) return
        if (!profileDir.isDirectory && !profileDir.mkdirs()) {
            Problems.error("routing", "Routing profile folder could not be created")
            throw RoutingException(Txt.s(Res.string.plan_err_profile_folder))
        }
        try {
            for (name in PROFILE_ASSETS) {
                val bytes = appContext.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val target = File(profileDir, name)
                // only rewrite when changed: BRouter caches parsed profiles by file timestamp
                if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) continue
                val tmp = File(profileDir, "$name.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        Problems.error("routing", "Routing profile could not be installed: $name")
                        throw RoutingException(Txt.s(Res.string.plan_err_profile_install, name))
                    }
                }
            }
            for (name in removeObsoleteProfiles(profileDir)) Problems.warn("routing", "Old routing profile could not be deleted: $name")
        } catch (e: RoutingException) {
            throw e
        } catch (e: Exception) {
            Problems.error("routing", "Routing profiles could not be installed", e)
            throw RoutingException(Txt.s(Res.string.plan_err_profiles))
        }
        profilesReady = true
    }

    /**
     * Longest out-and-back spike (one way) BRouter cuts at a via point. Much shorter for hiking, where a
     * via point on a spur is usually a deliberate goal (summit, viewpoint): 250 m cut off a point 100 m
     * up a spur. 50 m still trims small snapping spikes.
     */
    private fun viaCorrectionM(activity: Activity): Double =
        if (activity == Activity.HIKE) VIA_CORRECTION_HIKE_M else VIA_CORRECTION_M

    /** Profile parameter overrides; BRouter injects these as numeric `assign`s that win over the .brf. */
    private fun keyValues(activity: Activity, sacLimit: Int): MutableMap<String, String> {
        val kv = HashMap<String, String>()
        // BRouter's turn instructions at real junctions → GeoPoint.turn (junction vibration + lock screen, owner).
        // A mode > 1 makes BRouter compute them (1 = "auto-choose" leaves the field 0 = none in RoutingContext); the mode
        // only changes output formats we do not use. 4 = comment style.
        kv["turnInstructionMode"] = "4"
        // remove out-and-back spikes at via points (BRouter reads these as global profile variables)
        kv["correctMisplacedViaPoints"] = "1"
        kv["correctMisplacedViaPointsDistance"] = viaCorrectionM(activity).toInt().toString()
        // keep all way tags in the track messages (surface, tracktype, sac_scale ... for the breakdown)
        kv["processUnusedTags"] = "1"
        // snap points within MAX_SNAP_M in one pass and fail fast (before any search) when a point
        // is further away, instead of BRouter widening the search up to 60 km
        kv["use_dynamic_range"] = "0"
        kv["waypointCatchingRange"] = MAX_SNAP_M.toInt().toString()
        when (activity) {
            Activity.EBIKE -> {
                kv["bikerPower"] = "180"; kv["maxSpeed"] = "25"; kv["totalMass"] = "110"
            }
            Activity.EMTB -> {
                kv["bikerPower"] = "180"; kv["maxSpeed"] = "25"; kv["totalMass"] = "115"
            }
            Activity.HIKE -> {
                // above the limit = forbidden (profile). Preferred = the limit but at most T3: a marked T2/T3 trail beats the
                // untagged path next to it ("unknown is not easy"), yet a T5 setting means "can do", not "seek T5".
                val sac = sacLimit.coerceIn(1, 5)
                kv["SAC_scale_limit"] = sac.toString()
                kv["SAC_scale_preferred"] = minOf(sac, 3).toString()
            }
            else -> Unit
        }
        return kv
    }

    // ---- errors -----------------------------------------------------------------------------

    private fun checkSegments(waypoints: List<GeoPoint>) {
        for (p in waypoints) {
            if (p.lat.isNaN() || p.lon.isNaN() || abs(p.lat) > 89.0 || abs(p.lon) > 180.0) {
                throw RoutingException(Txt.s(Res.string.plan_err_coordinates))
            }
            val name = segmentName(p)
            if (!File(segmentsDir, name).isFile) {
                // file name and folder only for diagnostics – the user sees a plain notice (RoutingDataNotice)
                Problems.info("routing", "Routing data missing: $name in ${segmentsDir.absolutePath}")
                throw RoutingException(NO_DATA)
            }
            // the phone loads 1° parts (Rd5Parts): an empty part would make BRouter say "no way within 1000 m" –
            // misleading; it is missing data (the loader fetches it)
            if (Rd5Parts.hasData(segmentsDir, p.lat, p.lon) == false) {
                Problems.info("routing", "Routing data part missing: ${Rd5Parts.partOf(p.lat, p.lon).key} in ${segmentsDir.absolutePath}")
                throw RoutingException(NO_DATA)
            }
        }
    }

    private fun isTimeout(m: String) = m.contains("timeout") || m.contains("killed")

    private fun describeError(msg: String?, activity: Activity, sacLimit: Int, timeoutMs: Long): String {
        val m = msg ?: ""
        return when {
            m.contains("datafile") && m.contains("not found") -> {
                Problems.info("routing", "BRouter: $m")
                NO_DATA
            }
            m.contains("position not mapped") -> {
                val who = m.substringBefore("-position")
                Txt.s(Res.string.plan_err_not_mapped, pointNameFromBRouter(who), MAX_SNAP_M.toInt(), activity.label)
            }
            isTimeout(m) ->
                if (activity == Activity.HIKE) {
                    Txt.s(Res.string.plan_err_timeout_hike, (timeoutMs / 1000).toInt(), sacLimit.coerceIn(1, 5))
                } else {
                    Txt.s(Res.string.plan_err_timeout, (timeoutMs / 1000).toInt())
                }
            m.contains("island") ->
                Txt.s(Res.string.plan_err_island, activity.label)
            m.contains("memory limit") ->
                Txt.s(Res.string.plan_err_too_long)
            m.contains("no track found") || m.isBlank() ->
                Txt.s(Res.string.plan_err_no_route_for, activity.label)
            else -> {
                // BRouter's own wording is technical: the details go to the problem log, the user gets a plain sentence
                Problems.warn("routing", "BRouter error not recognised: $m")
                Txt.s(Res.string.plan_err_no_route_for_try, activity.label)
            }
        }
    }

    private fun pointName(i: Int, n: Int): String = when (i) {
        0 -> Txt.s(Res.string.plan_err_start_point)
        n - 1 -> Txt.s(Res.string.plan_point_destination)
        else -> Txt.s(Res.string.plan_point_stop, i + 1)
    }

    private fun pointNameFromBRouter(who: String): String = when {
        who == "from" -> Txt.s(Res.string.plan_err_start_point)
        who == "to" -> Txt.s(Res.string.plan_point_destination)
        who.startsWith("via") -> who.removePrefix("via").toIntOrNull()?.let { Txt.s(Res.string.plan_point_stop, it + 1) }
            ?: Txt.s(Res.string.plan_err_a_stop)
        else -> Txt.s(Res.string.plan_err_a_point)
    }

    companion object {
        /** One BRouter search at a time in this process (planning in the app, re-routing in the service). */
        private val LOCK = ReentrantLock()
        /** How long the last request on this thread waited for [LOCK] (ms) – for the per-route log line. */
        private val lastLockWait = ThreadLocal<Long>()

        /** Ms the last limited request on the calling thread waited for another route to finish (0 = none / unknown). */
        fun lastLockWaitMs(): Long = lastLockWait.get() ?: 0L
        val CANCELLED: String get() = Txt.s(Res.string.plan_err_cancelled)
        /** Plain text (no file name, no path); the plan shows RoutingDataNotice instead. */
        val NO_DATA: String get() = Txt.s(Res.string.plan_err_no_data)
        val BUSY: String get() = Txt.s(Res.string.plan_err_busy)
        /** An engine bug (details in the problem log): plain text, no exception message. */
        val UNEXPECTED: String get() = Txt.s(Res.string.plan_err_unexpected)
        private const val MIN_BUDGET_MS = 500L
        /** Isolated ways skipped per request before giving up (each failed try is fast, ~0.1 s). */
        private const val MAX_ISLAND_SKIPS = 3
        /** BRouter retries a start on an island this often (its `freezeCount` limit) before "no track found". */
        private const val BROUTER_ISLAND_RETRIES = 5
        private val ISLAND_ERROR = Regex("(start|target) island detected for section (\\d+)")

        /**
         * The point (index) BRouter snapped onto an island (a way without a connection to the rest of the network) in a
         * failed run, or null when the failure is something else. "target island detected for section i" = point i + 1
         * (BRouter gives up at once); "start island … section i" = point i; "no track found" after BRouter's own island
         * retries ([islandRetries], -1 = unknown) = the start – only known for two points (a failed multi-point route is
         * routed leg by leg by the Planner).
         */
        internal fun islandPoint(err: String, islandRetries: Int, points: Int): Int? {
            ISLAND_ERROR.find(err)?.let { m ->
                val section = m.groupValues[2].toInt()
                return (if (m.groupValues[1] == "target") section + 1 else section).takeIf { it in 0 until points }
            }
            return if (err.startsWith("no track found") && islandRetries >= BROUTER_ISLAND_RETRIES && points == 2) 0 else null
        }
        private const val VIA_CORRECTION_M = 400.0
        private const val VIA_CORRECTION_HIKE_M = 50.0
        private const val CACHE_SIZE = 8
        private const val MAX_SNAP_M = 1000.0
        private const val ELE_FILTER_M = 10.0
        private const val ASSET_DIR = "brouter"
        internal val PROFILE_ASSETS = listOf(
            "lookups.dat",
            "touring.brf",
            "trekking.brf",
            "mtb.brf",
            "hiking-mountain.brf",
        )

        /**
         * Profile per activity. Bike (BIKE_TOURING) and E-bike = touring.brf, OnPath's copy of trekking.brf for a normal bike
         * (owner: paved bike ways and quiet ways, traffic and rough gravel avoided). E-bike changes only power, speed and
         * mass (keyValues).
         */
        internal fun profileFile(activity: Activity): String = when (activity) {
            Activity.BIKE_TOURING, Activity.EBIKE -> "touring.brf"
            Activity.BIKE_TREKKING -> "trekking.brf"
            Activity.MTB, Activity.EMTB -> "mtb.brf"
            Activity.HIKE -> "hiking-mountain.brf"
        }

        /**
         * Deletes the profiles in [dir] that the app no longer ships (*.brf not in [PROFILE_ASSETS]).
         * Returns the names that could not be deleted.
         */
        internal fun removeObsoleteProfiles(dir: File): List<String> =
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.endsWith(".brf") && it.name !in PROFILE_ASSETS }
                .filter { !it.delete() }
                .map { it.name }

        init {
            // keep parsed profiles of several activities in memory
            ProfileCache.setSize(4)
        }

        /** BRouter 5x5 degree tile name, e.g. E10_N45.rd5 for lon 10..15, lat 45..50. */
        fun segmentName(p: GeoPoint): String {
            val lon = (floor(p.lon / 5.0) * 5.0).toInt()
            val lat = (floor(p.lat / 5.0) * 5.0).toInt()
            val ew = if (lon < 0) "W${-lon}" else "E$lon"
            val ns = if (lat < 0) "S${-lat}" else "N$lat"
            return String.format(Locale.ROOT, "%s_%s.rd5", ew, ns)
        }

        private fun distM(a: GeoPoint, b: GeoPoint): Double {
            val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2.0))
            val y = Math.toRadians(b.lat - a.lat)
            return sqrt(x * x + y * y) * 6_371_000.0
        }
    }
}
