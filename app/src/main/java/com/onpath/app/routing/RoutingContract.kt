package com.onpath.app.routing

import com.onpath.app.debug.Problems
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource

/*
 * Shared contract between the app UI and the on-device routing engine.
 * Do not change without updating docs/architecture-contract.md.
 */

/**
 * The six activities. [label] is the one name the user sees (picker, chip, button, messages) and [line] the one short
 * explaining line under it in the activity picker; [chip] = the name on the plan sheet's activity chip, shorter only
 * where the full name would cut the route's distance ("Gravel"; owner rule: time and distance in full). Stored values
 * (prefs, DB, nav session, backups) use the enum name, never [label].
 */
enum class Activity(private val labelRes: StringResource, private val lineRes: StringResource, private val chipRes: StringResource) {
    BIKE_TOURING(Res.string.activity_bike_touring, Res.string.activity_bike_touring_line, Res.string.activity_bike_touring_chip),
    BIKE_TREKKING(Res.string.activity_bike_trekking, Res.string.activity_bike_trekking_line, Res.string.activity_bike_trekking_chip),
    EBIKE(Res.string.activity_ebike, Res.string.activity_ebike_line, Res.string.activity_ebike_chip),
    MTB(Res.string.activity_mtb, Res.string.activity_mtb_line, Res.string.activity_mtb_chip),
    EMTB(Res.string.activity_emtb, Res.string.activity_emtb_line, Res.string.activity_emtb_chip),
    HIKE(Res.string.activity_hike, Res.string.activity_hike_line, Res.string.activity_hike_chip);

    /** Texts in the app's language (strings_activity.xml; German: Fahrrad, Gravel-Rad, …). */
    val label: String get() = Txt.s(labelRes)
    val line: String get() = Txt.s(lineRes)
    val chip: String get() = Txt.s(chipRes)
}

/**
 * A point. [way]: what the way from this point to the next one is like (routed lines only, see [WayAttrs]; time model);
 * [turn]: a junction instruction at this point (navigation); [exit]: with a roundabout [turn], the exit to take (1, 2, …;
 * 0 = unknown); [turnAngle]: BRouter's angle of that turn in degrees (> 0 right, < 0 left; the roundabout sign draws its
 * exit there); [shape]: the ways at that junction (null = unknown) – the bike vibrates only where one could go wrong
 * ([JunctionShape]). Equality uses only lat, lon and ele (two points at the same place are the same point), so lines with
 * and without way attributes compare equal.
 */
data class GeoPoint(val lat: Double, val lon: Double, val ele: Double? = null, val way: WayAttrs? = null,
                    val turn: JunctionCue? = null, val exit: Int = 0, val turnAngle: Float = 0f,
                    val shape: JunctionShape? = null) {
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
     * lookups fold synonyms, e.g. allowed → yes), null = not tagged / unknown. Used for route warnings.
     */
    val bicycle: String? = null,
    /**
     * Part of a signed cycle route (BRouter's route_bicycle_icn / ncn / rcn / lcn). A footway without a bicycle tag on a
     * route is ridden, not pushed (owner; touring.brf / trekking.brf cost it so).
     */
    val cycleRoute: Boolean = false,
    /**
     * highway=pedestrian (a pedestrian zone; [wayType] says "footway" for it too). No cycling there unless signed
     * (bicycle=yes / designated), also on a cycle route (German law) – stays "pushing".
     */
    val pedestrian: Boolean = false,
    /**
     * Water to cross: the way is a ford (ford=yes / stepping_stones) or the span ends at a ford node. Route hints
     * (owner: "a hint on a route if it contains anything that requires validation").
     */
    val ford: Boolean = false,
    /**
     * A barrier a bike does not simply pass (stile, kissing_gate, turnstile, full-height_turnstile – OSM value) at the
     * node ending this span; null = none of those (bollards, gates, lift gates are passable and not kept).
     */
    val barrier: String? = null,
    /** highway=construction: the way is being built or rebuilt and may be closed. */
    val construction: Boolean = false,
) {
    /**
     * Compact text form for files ("path|natural_trail|3|-1||bad||yes|r|stile"; field 9 = flags: r cycle route,
     * p pedestrian zone, f ford, c construction; field 10 = [barrier]); [parse] reads it back (older codes: no bike
     * access, not on a cycle route, no pedestrian zone, no ford / barrier / construction).
     */
    fun code(): String = listOf(wayType, surface, sac.toString(), mtb.toString(), smoothness.orEmpty(), visibility.orEmpty(),
        rough.orEmpty(), bicycle.orEmpty(), (if (cycleRoute) "r" else "") + (if (pedestrian) "p" else "") +
            (if (ford) "f" else "") + (if (construction) "c" else ""), barrier.orEmpty()).joinToString("|")

    companion object {
        fun parse(code: String?): WayAttrs? = try {
            val c = code?.split('|')
            if (c == null || c.size < 4 || c[0].isEmpty()) null
            else WayAttrs(c[0], c[1], c[2].toIntOrNull() ?: 0, c[3].toIntOrNull() ?: -1,
                c.getOrNull(4)?.ifEmpty { null }, c.getOrNull(5)?.ifEmpty { null }, c.getOrNull(6)?.ifEmpty { null },
                c.getOrNull(7)?.ifEmpty { null }, c.getOrNull(8)?.contains('r') == true, c.getOrNull(8)?.contains('p') == true,
                ford = c.getOrNull(8)?.contains('f') == true, barrier = c.getOrNull(9)?.ifEmpty { null },
                construction = c.getOrNull(8)?.contains('c') == true)
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
 * same or shorter budget (leg by leg) would time out too, so the planner does not retry.
 */
class RoutingException(message: String, val timedOut: Boolean = false, kind: Kind? = null) : Exception(message) {
    /**
     * What failed, for code that must not read the message (it is in the user's language).
     * Without [kind] it is found from the message (the app's own texts for these cases); a timed-out
     * request that is nothing else is [Kind.TIMEOUT].
     */
    val kind: Kind = kind ?: Kind.of(message).let { if (it == Kind.OTHER && timedOut) Kind.TIMEOUT else it }

    enum class Kind {
        OTHER,
        /** Routing data for the area is missing (the map loads it). */
        NO_DATA,
        /** The request was cancelled (a newer one, or the user). */
        CANCELLED,
        /** Another route is being calculated. */
        BUSY,
        /** Out of memory / too complex: fewer or closer points help. */
        TOO_COMPLEX,
        /** The routing engine could not be loaded. */
        UNAVAILABLE,
        /** The request took too long (also: no route within the time limit). */
        TIMEOUT,
        /** The start point is too far from any usable way (re-routing: nothing to retry from here). */
        START_POINT;

        companion object {
            fun of(message: String): Kind = when (message) {
                Txt.s(Res.string.plan_err_no_data) -> NO_DATA
                Txt.s(Res.string.plan_err_cancelled) -> CANCELLED
                Txt.s(Res.string.plan_err_busy) -> BUSY
                Txt.s(Res.string.plan_err_memory), Txt.s(Res.string.plan_err_complex) -> TOO_COMPLEX
                Txt.s(Res.string.plan_err_engine) -> UNAVAILABLE
                else -> if (message.startsWith(Txt.s(Res.string.plan_err_start_point))) START_POINT else OTHER
            }
        }
    }
}

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
