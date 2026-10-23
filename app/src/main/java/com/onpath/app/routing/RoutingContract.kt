package com.onpath.app.routing

import com.onpath.app.debug.Problems

/**
 * Shared contract between the app UI and the on-device routing engine.
 * Do not change without updating docs/architecture-contract.md.
 */
enum class Activity(val label: String) {
    BIKE_TOURING("Touring bike"),
    BIKE_TREKKING("Trekking bike"),
    EBIKE("E-bike"),
    MTB("MTB"),
    EMTB("E-MTB"),
    HIKE("Hike"),
}

/**
 * A point; [way] (optional) = what the way from this point to the next one is like (routed lines only, see
 * [WayAttrs]). Equality ignores [way] (two points at the same place are the same point), so lines with and without way
 * attributes compare as before.
 */
/** [way]: way attributes from here on (time model); [turn]: a junction instruction at this point (navigation). */
data class GeoPoint(val lat: Double, val lon: Double, val ele: Double? = null, val way: WayAttrs? = null,
                    val turn: JunctionCue? = null) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is GeoPoint && lat == other.lat && lon == other.lon && ele == other.ele)

    override fun hashCode(): Int = (lat.hashCode() * 31 + lon.hashCode()) * 31 + (ele?.hashCode() ?: 0)
}

/**
 * Way attributes of one span of a routed line (from BRouter's way tags, see BRouterWayStats.attrs). Used by the time
 * model (time/PaceModel). null on a point = unknown (straight legs, imported tracks, old saved lines) → the activity's
 * usual way is assumed, i.e. the time is as without attributes.
 */
data class WayAttrs(
    /** Way type key as in [Share] (path, track, steps, ...). */
    val wayType: String,
    /** Surface key as in [Share] (asphalt, gravel, natural_trail, alpine, unknown, ...). */
    val surface: String,
    /** SAC hiking scale 1..6 (T1..T6), 0 = not tagged. */
    val sac: Int = 0,
    /** mtb:scale 0..6 (S0..S6), -1 = not tagged. */
    val mtb: Int = -1,
    /** OSM smoothness value (excellent, good, intermediate, bad, very_bad, horrible, ...), null = not tagged. */
    val smoothness: String? = null,
    /** OSM trail_visibility (excellent, good, intermediate, bad, horrible, no), null = not tagged. */
    val visibility: String? = null,
    /** Raw OSM surface for rough ground (rock, stone, scree), null otherwise. */
    val rough: String? = null,
    /**
     * Bike access as BRouter reports the OSM `bicycle` tag (yes, designated, permissive, no, dismount, ...; BRouter's
     * lookups fold synonyms, e.g. allowed → yes), null = not tagged / unknown. Added 2026-10-22 (route warnings).
     */
    val bicycle: String? = null,
) {
    /** Compact text form for files ("path|natural_trail|3|-1||bad||yes"); [parse] reads it back (older codes: no bike access). */
    fun code(): String = listOf(wayType, surface, sac.toString(), mtb.toString(), smoothness.orEmpty(), visibility.orEmpty(),
        rough.orEmpty(), bicycle.orEmpty()).joinToString("|")

    companion object {
        fun parse(code: String?): WayAttrs? = try {
            val c = code?.split('|')
            if (c == null || c.size < 4 || c[0].isEmpty()) null
            else WayAttrs(c[0], c[1], c[2].toIntOrNull() ?: 0, c[3].toIntOrNull() ?: -1,
                c.getOrNull(4)?.ifEmpty { null }, c.getOrNull(5)?.ifEmpty { null }, c.getOrNull(6)?.ifEmpty { null },
                c.getOrNull(7)?.ifEmpty { null })
        } catch (_: Exception) { null } // expected: an unknown / old code = no attributes
    }
}

data class RouteResult(
    val points: List<GeoPoint>,   // full geometry, with elevation where known
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Double,
    /** Komoot-style breakdown, metres per category, largest first (may be empty). */
    val wayTypes: List<Share> = emptyList(),
    val surfaces: List<Share> = emptyList(),
    /**
     * Where the engine matched each input waypoint onto a way (same order and size as the request's
     * waypoints; null entries = unknown). Empty when the engine does not report it (callers then fall
     * back to the route geometry, see [WaypointSnap]).
     */
    val matched: List<GeoPoint?> = emptyList(),
)

/**
 * Metres of the route in one category.
 * Way type keys: cycleway, path, footway, track, quiet_road, road, busy_road, steps, ferry, other.
 * Surface keys: asphalt, paved, gravel, unpaved, ground, natural_trail, alpine, unknown.
 */
data class Share(val key: String, val meters: Double)

/**
 * [timedOut] = the router gave up for time (search budget used up, or the engine stayed busy): another try with the
 * same or shorter budget (leg by leg) would time out too, so the planner does not retry (perf report 2026-10-22).
 */
class RoutingException(message: String, val timedOut: Boolean = false) : Exception(message)

/**
 * Cancel signal for one routing request (re-routing while navigating: a newer request supersedes an older one).
 * Thread-safe; [cancel] may be called from any thread, the engine stops as soon as it can.
 */
class RouteCancel {
    @Volatile var isCancelled = false
        private set
    private var hook: (() -> Unit)? = null

    fun cancel() {
        val h = synchronized(this) { isCancelled = true; hook }
        try { h?.invoke() } catch (e: Exception) {
            Problems.warn("routing", "Route cancel hook failed; the request runs until its timeout", e)
        }
    }

    /** Engine side: what to run on [cancel] (null = nothing running). Runs at once when already cancelled. */
    fun onCancel(h: (() -> Unit)?) {
        val now = synchronized(this) { hook = h; isCancelled }
        if (now && h != null) try { h() } catch (e: Exception) {
            Problems.warn("routing", "Route cancel hook failed; the request runs until its timeout", e)
        }
    }
}

/** Optional limits for one request; see [RoutingEngine.route] with limits. */
data class RouteLimits(
    /** Upper bound for this request incl. waiting for another request to finish (ms). */
    val timeoutMs: Long,
    val cancel: RouteCancel? = null,
)

interface RoutingEngine {
    /**
     * Calculates a route through all waypoints (start, vias, end), fully offline.
     * Blocking; call from a background thread. Throws RoutingException with a
     * plain-language message the UI can show (e.g. "No route found near this point").
     * @param sacLimit hiking difficulty limit 1..5 (SAC T1..T5), only used for HIKE.
     */
    fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int = 3): RouteResult

    /**
     * Same as [route], but gives up after [RouteLimits.timeoutMs] or when [RouteLimits.cancel] fires (throws
     * RoutingException). Engines without support ignore the limits (default), so existing engines stay valid.
     */
    fun route(waypoints: List<GeoPoint>, activity: Activity, sacLimit: Int, limits: RouteLimits): RouteResult =
        route(waypoints, activity, sacLimit)
}
