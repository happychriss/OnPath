package com.onpath.app.suggest

import com.onpath.app.routing.Activity
import com.onpath.app.routing.RouterRules
import java.io.File

/**
 * The routing profile of an activity exactly as the app routes with it: the same .brf ([RouterRules.profileFile]) and
 * the same parameters (SAC limit, e-bike power …), so the network's "allowed" is the router's verdict (a suggestion must
 * never use a way navigation refuses). Kept apart from [NetReader] so the reader compiles without the app's routing code
 * (bench tools).
 *
 * [keyValues] is a verbatim copy of BRouterEngine's private `keyValues` (core routing stays untouched);
 * RouterProfileTest compares the two texts, so a change there fails until it is copied here.
 */
object RouterProfile {
    /** [profileDir] = where the app keeps lookups.dat and the .brf files (BRouterEngine copies them from the assets). */
    fun of(profileDir: File, activity: Activity, sacLimit: Int): NetReader.Profile =
        NetReader.Profile(File(profileDir, RouterRules.profileFile(activity)), keyValues(activity, sacLimit))

    private const val MAX_SNAP_M = 1000.0

    private fun viaCorrectionM(activity: Activity): Double = RouterRules.viaCorrectionM(activity)

    // ---- copy of BRouterEngine.keyValues (RouterProfileTest) ----
    internal fun keyValues(activity: Activity, sacLimit: Int, official: Int = 0): MutableMap<String, String> {
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
        // "Prefer official routes" (Settings → Map): 0 sets nothing – the profile's own value applies, the same routes as
        // before and as the web planner. 1 normal, 2 strong: marked hiking / cycle / MTB route networks weigh more.
        val o = official.coerceIn(0, 2)
        if (o > 0) when (activity) {
            Activity.HIKE -> kv["hiking_routes_preference"] = if (o == 1) "0.8" else "2.0" // profile 0.2
            Activity.MTB, Activity.EMTB -> kv["cycleroutes_pref"] = if (o == 1) "0.4" else "1.0" // profile 0.2
            Activity.BIKE_TOURING, Activity.EBIKE, Activity.BIKE_TREKKING -> kv["stick_to_cycleroutes"] = "1" // profile false
        }
        return kv
    }
    // ---- end of copy ----
}
