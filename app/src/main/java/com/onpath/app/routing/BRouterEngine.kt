package com.onpath.app.routing

import android.content.Context
import btools.mapaccess.MatchedWaypoint
import btools.router.OsmNodeNamed
import btools.router.OsmTrack
import btools.router.ProfileCache
import btools.router.RoutingContext
import java.io.File
import java.util.Locale
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
 * [route] is blocking and serialized (one route at a time); call it from a background thread.
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
        try {
            synchronized(LOCK) {
                val key = cacheKey(waypoints, activity, sacLimit)
                cache[key] ?: routeLocked(waypoints, activity, sacLimit).also { cache[key] = it }
            }
        } catch (e: RoutingException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw RoutingException("Not enough memory for this route. Add via points or choose closer points.")
        } catch (e: StackOverflowError) {
            throw RoutingException("Route too complex for on-phone routing. Add via points or choose closer points.")
        } catch (e: LinkageError) {
            throw RoutingException("Routing engine could not be loaded.")
        } catch (e: Exception) {
            throw RoutingException("Routing failed: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun cacheKey(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): CacheKey {
        val c = ArrayList<Long>(waypoints.size * 2)
        for (p in waypoints) {
            c.add(Math.round(p.lat * 1_000_000.0)); c.add(Math.round(p.lon * 1_000_000.0))
        }
        return CacheKey(c, activity, if (activity == Activity.HIKE) sacLimit.coerceIn(1, 5) else 0)
    }

    private fun routeLocked(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int): RouteResult {
        if (waypoints.size < 2) throw RoutingException("Set a start point and a destination.")
        checkSegments(waypoints)
        ensureProfiles()
        val timeoutMs = timeoutFor(waypoints)

        val rc = RoutingContext()
        rc.localFunction = File(profileDir, profileFile(activity)).absolutePath
        rc.keyValues = keyValues(activity, sacLimit)

        val nodes = ArrayList<OsmNodeNamed>(waypoints.size)
        waypoints.forEachIndexed { i, p ->
            val n = OsmNodeNamed()
            n.name = when (i) {
                0 -> "from"
                waypoints.size - 1 -> "to"
                else -> "via$i"
            }
            n.ilon = ((p.lon + 180.0) * 1_000_000.0 + 0.5).toInt()
            n.ilat = ((p.lat + 90.0) * 1_000_000.0 + 0.5).toInt()
            nodes.add(n)
        }

        val track: OsmTrack
        try {
            val engine = BRouter(null, null, segmentsDir, nodes, rc)
            engine.quite = true
            engine.doRun(timeoutMs)
            val err = engine.errorMessage
            if (err != null) throw RoutingException(describeError(err, activity, sacLimit, timeoutMs))
            track = engine.foundTrack ?: throw RoutingException(describeError(null, activity, sacLimit, timeoutMs))
        } catch (e: RoutingException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw RoutingException("Not enough memory for this route. Add via points or choose closer points.")
        } catch (e: Exception) {
            throw RoutingException(describeError(e.message ?: e.toString(), activity, sacLimit, timeoutMs))
        }

        return toResult(track, waypoints, activity, matchedPoints(track, nodes))
    }

    /**
     * BRouter's snapped crosspoint per input node (matched by name), or empty when unavailable.
     * `OsmTrack.matchedWaypoints` is protected, hence reflection; any failure just means "unknown".
     */
    private fun matchedPoints(track: OsmTrack, nodes: List<OsmNodeNamed>): List<GeoPoint?> = try {
        val f = OsmTrack::class.java.getDeclaredField("matchedWaypoints").apply { isAccessible = true }
        val list = (f.get(track) as? List<*>).orEmpty().filterIsInstance<MatchedWaypoint>()
        val byName = list.filter { it.crosspoint != null && it.name != null }.associateBy { it.name }
        nodes.map { n ->
            byName[n.name]?.crosspoint?.let { c -> GeoPoint(c.ilat / 1_000_000.0 - 90.0, c.ilon / 1_000_000.0 - 180.0) }
        }
    } catch (e: Throwable) {
        emptyList()
    }

    // ---- result conversion -------------------------------------------------------------

    private fun toResult(track: OsmTrack, waypoints: List<GeoPoint>, activity: Activity, matched: List<GeoPoint?>): RouteResult {
        val els = track.nodes
        if (els.isEmpty()) throw RoutingException("No route found between these points for ${activity.label}.")
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
                throw RoutingException(
                    "${pointName(i, waypoints.size)} is too far (${d.roundToInt()} m) from any path " +
                        "or road usable for ${activity.label}. Move it closer to a path."
                )
            }
        }

        val distanceM = track.distance.toDouble()
        // breakdown is a nice-to-have: never fail a route because of it
        val stats = try {
            BRouterWayStats.fromMessages(track.aggregateMessages(), distanceM)
        } catch (e: Exception) {
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
            throw RoutingException("Cannot create routing profile folder.")
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
                    if (!tmp.renameTo(target)) throw RoutingException("Cannot install routing profile $name.")
                }
            }
        } catch (e: RoutingException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException("Cannot install routing profiles: ${e.message}")
        }
        profilesReady = true
    }

    /**
     * Longest out-and-back spike (one way) BRouter cuts at a via point. Much shorter for hiking, where a
     * via point on a spur is usually a deliberate goal (summit, viewpoint, a spot on a Waalweg): with
     * 250 m the owner's point 3 on the Algunder Waalweg was cut off and left unconnected (100 m away).
     * 50 m still trims small snapping spikes.
     */
    private fun viaCorrectionM(activity: Activity): Double =
        if (activity == Activity.HIKE) VIA_CORRECTION_HIKE_M else VIA_CORRECTION_M

    private fun profileFile(activity: Activity): String = when (activity) {
        Activity.BIKE_TOURING -> "fastbike-verylowtraffic.brf"
        Activity.BIKE_TREKKING, Activity.EBIKE -> "trekking.brf"
        Activity.MTB, Activity.EMTB -> "mtb.brf"
        Activity.HIKE -> "hiking-mountain.brf"
    }

    /** Profile parameter overrides; BRouter injects these as numeric `assign`s that win over the .brf. */
    private fun keyValues(activity: Activity, sacLimit: Int): MutableMap<String, String> {
        val kv = HashMap<String, String>()
        kv["turnInstructionMode"] = "0" // no voice hints needed
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
            Activity.HIKE -> kv["SAC_scale_limit"] = sacLimit.coerceIn(1, 5).toString()
            else -> Unit
        }
        return kv
    }

    // ---- errors -----------------------------------------------------------------------------

    private fun checkSegments(waypoints: List<GeoPoint>) {
        for (p in waypoints) {
            if (p.lat.isNaN() || p.lon.isNaN() || abs(p.lat) > 89.0 || abs(p.lon) > 180.0) {
                throw RoutingException("Invalid coordinates.")
            }
            val name = segmentName(p)
            if (!File(segmentsDir, name).isFile) {
                throw RoutingException(
                    "No routing data for this area (missing $name in ${segmentsDir.absolutePath})."
                )
            }
        }
    }

    /**
     * Search time budget: long routes may need the full [TIMEOUT_MS], short ones that are not found
     * quickly are almost always unreachable (e.g. only via paths above the SAC limit), so give up sooner.
     */
    private fun timeoutFor(waypoints: List<GeoPoint>): Long {
        var km = 0.0
        for (i in 1 until waypoints.size) km += distM(waypoints[i - 1], waypoints[i]) / 1000.0
        return (MIN_TIMEOUT_MS + km * TIMEOUT_MS_PER_KM).toLong().coerceIn(MIN_TIMEOUT_MS, TIMEOUT_MS)
    }

    private fun describeError(msg: String?, activity: Activity, sacLimit: Int, timeoutMs: Long): String {
        val m = msg ?: ""
        return when {
            m.contains("datafile") && m.contains("not found") -> {
                val file = Regex("datafile (\\S+) not found").find(m)?.groupValues?.get(1) ?: ""
                "No routing data for this area${if (file.isNotEmpty()) " (missing $file)" else ""}."
            }
            m.contains("position not mapped") -> {
                val who = m.substringBefore("-position")
                "${pointNameFromBRouter(who)} is more than ${MAX_SNAP_M.toInt()} m from any path or road " +
                    "usable for ${activity.label}. Move it closer to a path."
            }
            m.contains("timeout") || m.contains("killed") ->
                if (activity == Activity.HIKE) {
                    "No route found within ${timeoutMs / 1000} s. The destination may only be reachable via " +
                        "paths harder than SAC T${sacLimit.coerceIn(1, 5)} - try a higher difficulty limit, " +
                        "move the point, or add a via point."
                } else {
                    "Route calculation took too long (over ${timeoutMs / 1000} s). " +
                        "Try points closer together or add a via point."
                }
            m.contains("island") ->
                "No route found: a point lies on an isolated path network not usable for ${activity.label}. Move it."
            m.contains("memory limit") ->
                "Route too long for on-phone routing. Add via points or choose closer points."
            m.contains("no track found") || m.isBlank() ->
                "No route found between these points for ${activity.label}."
            else -> "Routing failed: $m"
        }
    }

    private fun pointName(i: Int, n: Int): String = when (i) {
        0 -> "Start point"
        n - 1 -> "Destination"
        else -> "Via point $i"
    }

    private fun pointNameFromBRouter(who: String): String = when {
        who == "from" -> "Start point"
        who == "to" -> "Destination"
        who.startsWith("via") -> "Via point ${who.removePrefix("via")}"
        else -> "A route point"
    }

    companion object {
        private val LOCK = Any()
        private const val TIMEOUT_MS = 30_000L
        private const val MIN_TIMEOUT_MS = 15_000L
        private const val TIMEOUT_MS_PER_KM = 500.0
        private const val VIA_CORRECTION_M = 400.0
        private const val VIA_CORRECTION_HIKE_M = 50.0
        private const val CACHE_SIZE = 8
        private const val MAX_SNAP_M = 1000.0
        private const val ELE_FILTER_M = 10.0
        private const val ASSET_DIR = "brouter"
        private val PROFILE_ASSETS = listOf(
            "lookups.dat",
            "fastbike-verylowtraffic.brf",
            "trekking.brf",
            "mtb.brf",
            "hiking-mountain.brf",
        )

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
