package com.onpath.app.map

import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine

/**
 * The map look per activity (+ hiking T level): which of the style's layers show and how the hiking route band is
 * filtered. One rule for the app ([MapController.setOverlay] / [MapController.setSacLimit]) and the browser planner
 * (web engine, compiled with TeaVM – owner: routes and the map look are identical in the browser and the app).
 *
 * Pure Kotlin on purpose: no Android, no MapLibre, no reflection – plain layer ids, booleans and a JSON string. The
 * shipped styles (tools/make_style.py) carry the Hike look (Hike layers visible, bike layers hidden, the band at T3);
 * this table switches them for the activity.
 */
object ActivityLayers {
    /** Twin of a base layer drawn instead of it for bike activities (tools/make_style.py BIKEVIEW). */
    const val BIKEVIEW = "-bikeview"
    /** Layer drawn only for hiking (tools/make_style.py HIKEVIEW). */
    const val HIKEVIEW = "-hikeview"
    /** Town part of a layer, drawn below the streets (tools/make_style.py town_below_streets) – switched with it. */
    const val TOWN = "-town"

    /** Route band layers: the band plus its major-route / overview / regional parts (a style may lack some). */
    val ROUTE_SUFFIXES = listOf("", "-over", "-major-low", "-major", "-major-label", "-low", "-regional")

    /** The hiking band's two layers whose filter follows the T level ([hikeRouteFilters]). */
    val HIKE_BAND_LAYERS = listOf("routes-hike", "routes-hike-over")

    /** Format 2 styles have one forbidden-way layer set per BRouter profile family; format 1 only foot / bike. */
    const val PER_PROFILE_MARKER = "access-no-dash-touring"

    /**
     * Layers switched by name for [activity] (before the style check; each also switched with its [TOWN] part).
     * [perProfile]: the style has the per-profile forbidden-way layers ([PER_PROFILE_MARKER]).
     */
    fun named(activity: Activity, perProfile: Boolean): List<Pair<String, Boolean>> {
        val walking = activity == Activity.HIKE
        val mtb = activity == Activity.MTB || activity == Activity.EMTB
        // touring.brf family: Bike (BIKE_TOURING) and E-bike (BRouterEngine.profileFile)
        val touring = BRouterEngine.profileFile(activity) == "touring.brf"
        val trekking = !walking && !mtb && !touring
        // Blocked-way marking follows the activity: walkers may use "access=no + foot=yes" ways, bikers
        // "access=no + bicycle=yes" ones (tools/make_style.py access_restrictions). Format 2: one set per profile family;
        // format 1 has foot / bike only, where "bike" serves every bike activity.
        val accessOn = listOf("foot" to walking, "bike" to if (perProfile) trekking else !walking,
            "touring" to (perProfile && touring), "mtb" to (perProfile && mtb))
        val access = accessOn.flatMap { (mode, on) ->
            // red dash + no-entry sign (no tint band: never louder than a street)
            listOf("dash", "sign").flatMap { kind -> listOf("", "-service").map { "access-no-$kind-$mode$it" to on } }
        }
        // Way quality (tools/make_style.py): T-labels (SAC) for every activity but MTB, which gets one "T3 S2" label
        // (mtb:scale too), loose-surface edges for every bike activity. Green ways, forbidden ways and blocking barrier
        // nodes per BRouter profile family: hiking-mountain (Hike), trekking.brf (Gravelbike), touring.brf (Bike,
        // E-bike), mtb.brf (MTB, E-MTB).
        return access + listOf(
            // format 2 only (map package with overlays.pmtiles): bike map, barrier nodes
            "bike-lane-left" to !walking, "bike-lane-left-shared" to !walking,
            "bike-lane-right" to !walking, "bike-lane-right-shared" to !walking,
            "bike-oneway" to !walking, "bike-oneway-streets" to !walking, "bike-oneway-paths" to !walking,
            "bike-both-ways" to !walking,
            "barrier-blocked-foot" to walking, "barrier-blocked-bike" to trekking,
            "barrier-blocked-touring" to touring, "barrier-blocked-mtb" to mtb,
            // ways excluded only for their difficulty: a grey line (router class 3)
            "too-hard-foot" to walking, "too-hard-bike" to trekking, "too-hard-touring" to touring, "too-hard-mtb" to mtb,
            "trails-sac-label" to !mtb, "trails-mtb-label" to mtb,
            "surface-loose-track" to !walking, "surface-loose-path" to !walking,
            // thin green "good for walking / riding" ways (tools/make_style.py green_ways)
            "greenway-foot" to walking, "greenway-bike" to trekking,
            "greenway-touring" to touring, "greenway-mtb" to mtb)
    }

    /**
     * Visibility of every layer the activity switches, for a style with the layers [styleLayerIds] (in style order):
     * layer id → visible. Layers the style lacks are left out; layers not listed keep their style visibility.
     * [routesShown]: the route bands (hiking band for Hike, cycle band for the bikes – MTB / E-MTB too: mtb.brf ignores
     * route=mtb and rewards cycle routes, docs/map-style-notes.md); false hides both.
     */
    fun visibility(activity: Activity, styleLayerIds: List<String>, routesShown: Boolean = true): Map<String, Boolean> {
        val ids = styleLayerIds.toHashSet()
        val walking = activity == Activity.HIKE
        val out = LinkedHashMap<String, Boolean>()
        for ((id, on) in named(activity, PER_PROFILE_MARKER in ids)) {
            for (lid in listOf(id, "$id$TOWN")) if (lid in ids) out[lid] = on
        }
        // a look per activity group: "-bikeview" layers only for bike activities (quiet tracks), "-hikeview" only for
        // hiking; a "<id>-bikeview" twin replaces its base layer "<id>" for bike activities (make_style.py low_zoom_view)
        for (id in styleLayerIds) {
            out[id] = when {
                id.endsWith(BIKEVIEW) -> !walking
                id.endsWith(HIKEVIEW) -> walking
                "$id$BIKEVIEW" in ids -> walking
                else -> continue
            }
        }
        val want = if (walking) "routes-hike" else "routes-bike"
        for (band in listOf("routes-hike", "routes-bike")) for (suffix in ROUTE_SUFFIXES) {
            val lid = "$band$suffix"
            if (lid in ids) out[lid] = routesShown && band == want
        }
        return out
    }

    /**
     * Filters of the hiking band for the T level [limit] (SAC T1–T5): [HIKE_BAND_LAYERS] id → MapLibre style
     * expression as JSON. Pieces above the level (the router does not use them) or forbidden at any level ("hx") go to
     * the plain grey "-over" band (owner). The same filters as tools/make_style.py hike_band_filter, which ships T3.
     */
    fun hikeRouteFilters(limit: Int): Map<String, String> {
        val t = limit.coerceIn(1, 6)
        val sac = """["coalesce",["get","sac"],0]"""
        val head = """["all",["==",["get","kind"],"hike"],["!",["has","majoronly"]],"""
        return linkedMapOf(
            "routes-hike" to """$head["all",["<=",$sac,$t],["!",["has","hx"]]]]""",
            "routes-hike-over" to """$head["any",[">",$sac,$t],["has","hx"]]]""")
    }

    /**
     * The whole look as one JSON object (web engine call): `{"visibility":{"<layer id>":true|false,…},
     * "filters":{"routes-hike":[…],"routes-hike-over":[…]}}` – set the filters whatever the activity, as the app does.
     */
    fun toJson(activity: Activity, sacLimit: Int, styleLayerIds: List<String>, routesShown: Boolean = true): String {
        val b = StringBuilder("{\"visibility\":{")
        visibility(activity, styleLayerIds, routesShown).entries.forEachIndexed { i, (id, on) ->
            if (i > 0) b.append(',')
            b.append(quote(id)).append(':').append(on)
        }
        b.append("},\"filters\":{")
        hikeRouteFilters(sacLimit).entries.forEachIndexed { i, (id, f) ->
            if (i > 0) b.append(',')
            b.append(quote(id)).append(':').append(f)
        }
        return b.append("}}").toString()
    }

    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> b.append("\\\"")
            '\\' -> b.append("\\\\")
            else -> if (c < ' ') b.append("\\u").append(c.code.toString(16).padStart(4, '0')) else b.append(c)
        }
        return b.append('"').toString()
    }
}
