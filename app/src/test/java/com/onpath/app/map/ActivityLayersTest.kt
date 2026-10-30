package com.onpath.app.map

import com.onpath.app.routing.Activity
import com.onpath.app.routing.BRouterEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.style.expressions.Expression
import java.io.File

/**
 * The map look per activity ([ActivityLayers], shared with the browser planner): the table, its layers in the shipped
 * styles, and – for the move out of MapController – the same result as MapController's former code for every activity
 * and T level.
 */
class ActivityLayersTest {
    private val f1 = JSONObject(File("src/main/assets/style/onpath.json").readText())
    private val f2 = JSONObject(File("src/main/assets/style/f2/onpath.json").readText())

    private fun layers(s: JSONObject): List<JSONObject> {
        val a = s.getJSONArray("layers")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    private fun ids(s: JSONObject) = layers(s).map { it.getString("id") }

    /** The style's own visibility (layout "visibility", default visible). */
    private fun shipped(s: JSONObject): Map<String, Boolean> =
        layers(s).associate { it.getString("id") to (it.optJSONObject("layout")?.optString("visibility", "visible") != "none") }

    /** Visibility after [ActivityLayers.visibility] is applied to [s] (what MapController.setOverlay does now). */
    private fun applied(s: JSONObject, a: Activity, routes: Boolean) = shipped(s) + ActivityLayers.visibility(a, ids(s), routes)

    /**
     * MapController.setOverlay as it was before the table moved to [ActivityLayers] (commit b0b6fc5), on a fake style:
     * `getLayer(id)` = id in the style, `setProperties(visibility)` = a write into the map. Kept here only as the
     * reference for [sameAsTheFormerMapController].
     */
    private fun former(s: JSONObject, activity: Activity, enabled: Boolean, @Suppress("UNUSED_PARAMETER") sac: Int): Map<String, Boolean> {
        val vis = shipped(s).toMutableMap()
        val styleIds = ids(s)
        fun getLayer(id: String) = id.takeIf { it in vis }
        val want = if (activity == Activity.HIKE) "routes-hike" else "routes-bike"
        val walking = activity == Activity.HIKE
        val mtb = activity == Activity.MTB || activity == Activity.EMTB
        val touring = BRouterEngine.profileFile(activity) == "touring.brf"
        val trekking = !walking && !mtb && !touring
        val perProfile = getLayer("access-no-dash-touring") != null
        val accessOn = mapOf("foot" to walking, "bike" to if (perProfile) trekking else !walking,
            "touring" to (perProfile && touring), "mtb" to (perProfile && mtb))
        val access = accessOn.flatMap { (mode, on) ->
            listOf("dash", "sign").flatMap { kind -> listOf("", "-service").map { "access-no-$kind-$mode$it" to on } }
        }
        for ((id, on) in access + listOf(
                "bike-lane-left" to !walking, "bike-lane-left-shared" to !walking,
                "bike-lane-right" to !walking, "bike-lane-right-shared" to !walking,
                "bike-oneway" to !walking, "bike-oneway-streets" to !walking, "bike-oneway-paths" to !walking,
                "bike-both-ways" to !walking,
                "barrier-blocked-foot" to walking, "barrier-blocked-bike" to trekking,
                "barrier-blocked-touring" to touring, "barrier-blocked-mtb" to mtb,
                "too-hard-foot" to walking, "too-hard-bike" to trekking, "too-hard-touring" to touring, "too-hard-mtb" to mtb,
                "trails-sac-label" to !mtb, "trails-mtb-label" to mtb,
                "surface-loose-track" to !walking, "surface-loose-path" to !walking,
                "greenway-foot" to walking, "greenway-bike" to trekking,
                "greenway-touring" to touring, "greenway-mtb" to mtb)) {
            for (lid in listOf(id, "$id-town")) getLayer(lid)?.let { vis[it] = on }
        }
        val idSet = styleIds.toSet()
        for (l in styleIds) {
            val on = when {
                l.endsWith("-bikeview") -> !walking
                l.endsWith("-hikeview") -> walking
                "$l-bikeview" in idSet -> walking
                else -> continue
            }
            vis[l] = on
        }
        for (id in listOf("routes-hike", "routes-bike")) {
            val on = enabled && id == want
            for (suffix in listOf("", "-over", "-major-low", "-major", "-major-label", "-low", "-regional")) {
                getLayer("$id$suffix")?.let { vis[it] = on }
            }
        }
        return vis
    }

    /** MapController.setSacLimit as it was before (commit b0b6fc5): the two filters built with MapLibre's Expression. */
    private fun formerFilters(limit: Int): Map<String, Expression> {
        val sac = Expression.coalesce(Expression.get("sac"), Expression.literal(0))
        val hike = Expression.eq(Expression.get("kind"), Expression.literal("hike"))
        val piece = Expression.not(Expression.has("majoronly"))
        val t = Expression.literal(limit.coerceIn(1, 6))
        val within = Expression.all(Expression.lte(sac, t), Expression.not(Expression.has("hx")))
        val over = Expression.any(Expression.gt(sac, t), Expression.has("hx"))
        return linkedMapOf("routes-hike" to Expression.all(hike, piece, within), "routes-hike-over" to Expression.all(hike, piece, over))
    }

    /** Numbers as numbers (the JSON converter reads 3 as 3.0): structure and values compared, not their spelling. */
    private fun norm(json: String): String = json.replace(Regex("""(\d+)\.0\b"""), "$1").replace(" ", "")

    @Test fun sameAsTheFormerMapController() {
        var compared = 0
        for (style in listOf(f1, f2)) for (a in Activity.entries) for (sac in 1..5) for (enabled in listOf(true, false)) {
            assertEquals("$a T$sac routes=$enabled", former(style, a, enabled, sac), applied(style, a, enabled))
            compared++
        }
        assertEquals(2 * 6 * 5 * 2, compared)
    }

    @Test fun sameFiltersAsTheFormerMapController() {
        for (limit in 0..7) {
            val now = ActivityLayers.hikeRouteFilters(limit)
            val before = formerFilters(limit)
            assertEquals(before.keys, now.keys)
            for ((id, json) in now) {
                assertEquals("$id T$limit", norm(before.getValue(id).toString()), norm(Expression.Converter.convert(json).toString()))
                assertEquals("$id T$limit as JSON", norm(JSONArray(before.getValue(id).toString()).toString()), norm(JSONArray(json).toString()))
            }
        }
    }

    /** The table per activity: the layers switched on by name in a format-2 style (every other named layer off). */
    @Test fun tableLocked() {
        val common = listOf("bike-lane-left", "bike-lane-left-shared", "bike-lane-right", "bike-lane-right-shared",
            "bike-oneway", "bike-oneway-streets", "bike-oneway-paths", "bike-both-ways", "surface-loose-track", "surface-loose-path")
        fun access(mode: String) = listOf("dash", "sign").flatMap { k -> listOf("", "-service").map { "access-no-$k-$mode$it" } }
        fun family(mode: String) = access(mode) + listOf("barrier-blocked-$mode", "too-hard-$mode", "greenway-$mode")
        val expected = mapOf(
            Activity.HIKE to family("foot") + "trails-sac-label",
            Activity.BIKE_TOURING to common + family("touring") + "trails-sac-label",
            Activity.EBIKE to common + family("touring") + "trails-sac-label",
            Activity.BIKE_TREKKING to common + family("bike") + "trails-sac-label",
            Activity.MTB to common + family("mtb") + "trails-mtb-label",
            Activity.EMTB to common + family("mtb") + "trails-mtb-label")
        val allNamed = ActivityLayers.named(Activity.HIKE, true).map { it.first }
        assertEquals("every named layer once", allNamed.size, allNamed.toSet().size)
        for ((a, on) in expected) {
            val table = ActivityLayers.named(a, perProfile = true)
            assertEquals("$a: same layers for every activity", allNamed, table.map { it.first })
            assertEquals("$a visible", on.toSet(), table.filter { it.second }.map { it.first }.toSet())
        }
        // format 1 (no per-profile sets): every bike activity uses the "bike" forbidden-way layers
        for (a in Activity.entries) {
            val t = ActivityLayers.named(a, perProfile = false).toMap()
            assertEquals(a.name, a != Activity.HIKE, t.getValue("access-no-dash-bike"))
            assertFalse(a.name, t.getValue("access-no-dash-touring") || t.getValue("access-no-dash-mtb"))
        }
    }

    /** The owner's example: Bike shows the bike map, Hike the hiking one; route bands by activity. */
    @Test fun bikeAndHikeLooks() {
        val bike = applied(f2, Activity.BIKE_TOURING, true)
        for (id in listOf("greenway-touring", "surface-loose-path", "surface-loose-track", "bike-oneway-streets",
                "routes-bike", "access-no-dash-touring", "cycleway-bikeview")) assertTrue(id, bike.getValue(id))
        for (id in listOf("greenway-foot", "access-no-dash-foot", "too-hard-foot", "routes-hike", "routes-hike-over",
                "cycleway", "track-own-single-hikeview")) assertFalse(id, bike.getValue(id))
        val hike = applied(f2, Activity.HIKE, true)
        for (id in listOf("greenway-foot", "too-hard-foot", "routes-hike", "routes-hike-over", "cycleway", "track-own-single-hikeview"))
            assertTrue(id, hike.getValue(id))
        for (id in listOf("greenway-touring", "surface-loose-path", "bike-oneway-streets", "routes-bike", "cycleway-bikeview"))
            assertFalse(id, hike.getValue(id))
        // MTB: the cycle band (mtb.brf rewards cycle routes), its own green ways
        val mtb = applied(f2, Activity.MTB, true)
        assertTrue(mtb.getValue("routes-bike") && mtb.getValue("greenway-mtb") && mtb.getValue("trails-mtb-label"))
        assertFalse(mtb.getValue("trails-sac-label"))
        // route bands off: neither band
        val off = applied(f2, Activity.HIKE, false)
        assertFalse(off.getValue("routes-hike") || off.getValue("routes-bike"))
        // the shipped style is the Hike look: applying Hike changes nothing
        assertEquals(shipped(f2), applied(f2, Activity.HIKE, true))
        assertEquals(shipped(f1), applied(f1, Activity.HIKE, true))
    }

    /** Every layer the table names is in the format-2 style; format 1 lacks exactly the format-2-only ones. */
    @Test fun tableLayersExistInTheStyles() {
        val have2 = ids(f2).toSet()
        val have1 = ids(f1).toSet()
        for (a in Activity.entries) for (id in ActivityLayers.named(a, true).map { it.first }) assertTrue("$id in f2", id in have2)
        for (id in ActivityLayers.HIKE_BAND_LAYERS + listOf("routes-hike", "routes-bike")) {
            assertTrue("$id in f2", id in have2)
            assertTrue("$id in f1", id in have1)
        }
        val named = ActivityLayers.named(Activity.HIKE, true).map { it.first }
        val f2only = named.filter { it !in have1 }.toSet()
        val formatTwoOnly = named.filter {
            it.endsWith("-service") || it.startsWith("bike-") || it.startsWith("barrier-blocked-") || it.startsWith("too-hard-") ||
                (it.startsWith("access-") && (it.contains("-touring") || it.contains("-mtb")))
        }.toSet()
        assertEquals("format 1 lacks only the format-2 layers", formatTwoOnly, f2only)
        assertTrue(ActivityLayers.PER_PROFILE_MARKER in have2 && ActivityLayers.PER_PROFILE_MARKER !in have1)
        // the "-town" parts the table switches with their layer
        val towns = ActivityLayers.visibility(Activity.BIKE_TREKKING, ids(f2)).keys.filter { it.endsWith(ActivityLayers.TOWN) }
        assertTrue(towns.toString(), "greenway-bike-town" in towns)
        // every bike / hike view layer is switched
        val switched = ActivityLayers.visibility(Activity.MTB, ids(f2)).keys
        for (id in ids(f2)) if (id.endsWith(ActivityLayers.BIKEVIEW) || id.endsWith(ActivityLayers.HIKEVIEW)) assertTrue(id, id in switched)
    }

    /** The shipped styles carry the band at T3: the table's T3 filters are the shipped ones (tools/make_style.py). */
    @Test fun t3FiltersAreTheShippedOnes() {
        val t3 = ActivityLayers.hikeRouteFilters(3)
        for (s in listOf(f1, f2)) {
            val byId = layers(s).associateBy { it.getString("id") }
            for ((id, json) in t3) assertEquals(id, byId.getValue(id).getJSONArray("filter").toString(), JSONArray(json).toString())
        }
        assertTrue(ActivityLayers.hikeRouteFilters(4).getValue("routes-hike").contains("""["<=",["coalesce",["get","sac"],0],4]"""))
        // out-of-range levels are clamped as before (1..6)
        assertEquals(ActivityLayers.hikeRouteFilters(1), ActivityLayers.hikeRouteFilters(-3))
        assertEquals(ActivityLayers.hikeRouteFilters(6), ActivityLayers.hikeRouteFilters(9))
    }

    /** The web engine's form: one JSON object with the visibility and the filters. */
    @Test fun jsonForTheWeb() {
        val j = JSONObject(ActivityLayers.toJson(Activity.HIKE, 4, ids(f2)))
        val vis = j.getJSONObject("visibility")
        assertTrue(vis.getBoolean("greenway-foot"))
        assertFalse(vis.getBoolean("greenway-touring"))
        assertEquals(ActivityLayers.visibility(Activity.HIKE, ids(f2)).size, vis.length())
        val f = j.getJSONObject("filters")
        assertEquals(JSONArray(ActivityLayers.hikeRouteFilters(4).getValue("routes-hike")).toString(), f.getJSONArray("routes-hike").toString())
        val bike = JSONObject(ActivityLayers.toJson(Activity.BIKE_TOURING, 2, listOf("a\"b", "greenway-foot"))).getJSONObject("visibility")
        assertFalse(bike.getBoolean("greenway-foot"))
        assertEquals(1, bike.length())
    }
}
