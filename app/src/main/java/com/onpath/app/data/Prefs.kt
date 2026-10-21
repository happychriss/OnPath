package com.onpath.app.data

import android.content.Context
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.json.JSONArray
import java.io.File

/** Small persisted settings and the auto-saved current plan (R-08). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("onpath", Context.MODE_PRIVATE)
    private val followFile = File(context.applicationContext.filesDir, "follow.json")

    var activity: Activity
        get() = activityPref("activity")
        set(v) = sp.edit().putString("activity", v.name).apply()

    /** Stored activity [key]; not set yet → Hike (expected), unknown value → Hike with a warning. */
    private fun activityPref(key: String): Activity {
        val v = try { sp.getString(key, null) } catch (e: Exception) {
            Problems.warn("settings", "Setting $key unreadable, using Hike", e); null
        } ?: return Activity.HIKE
        return try { Activity.valueOf(v) } catch (e: Exception) {
            Problems.warn("settings", "Setting $key has unknown activity '$v', using Hike", e); Activity.HIKE
        }
    }

    var sac: Int
        get() = sp.getInt("sac", 3)
        set(v) = sp.edit().putInt("sac", v).apply()

    /** Last camera: lat, lon, zoom. */
    var camera: Triple<Double, Double, Double>?
        get() = if (!sp.contains("camLat")) null else Triple(
            sp.getFloat("camLat", 0f).toDouble(), sp.getFloat("camLon", 0f).toDouble(), sp.getFloat("camZoom", 12f).toDouble()
        )
        set(v) {
            if (v == null) return
            sp.edit().putFloat("camLat", v.first.toFloat()).putFloat("camLon", v.second.toFloat())
                .putFloat("camZoom", v.third.toFloat()).apply()
        }

    var settings: AppSettings
        get() = AppSettings(
            hiddenPoiKinds = sp.getStringSet("poiHidden", null)?.toSet() ?: com.onpath.app.poi.PoiKinds.defaultHidden,
            routeOverlay = sp.getBoolean("overlay", true),
            contours = sp.getBoolean("contours", true),
            hillshade = sp.getBoolean("hillshade", true),
            offRouteWarning = sp.getBoolean("offRouteOn", true),
            offRouteVibrate = sp.getBoolean("offRouteVibrate", true),
            offRouteM = sp.getInt("offRouteM", 40).takeIf { it in AppSettings.OFF_ROUTE_CHOICES } ?: 40,
            planVibrate = sp.getBoolean("planVibrate", true),
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            mapTheme = sp.getString("mapTheme", "classic") ?: "classic",
            navHeadingUp = sp.getBoolean("navHeadingUp", true),  // Navigate orientation
            defaultActivity = activityPref("defaultActivity"),
            defaultSac = sp.getInt("defaultSac", 3).coerceIn(1, 5),
            hikePace = com.onpath.app.time.Pace.of(sp.getString("hikePace", null)),
            bikePace = com.onpath.app.time.Pace.of(sp.getString("bikePace", null)),
            mapsWifiOnly = sp.getBoolean("mapsWifiOnly", false),
        )
        set(v) = sp.edit()
            .putStringSet("poiHidden", HashSet(v.hiddenPoiKinds))
            .putBoolean("overlay", v.routeOverlay)
            .putBoolean("contours", v.contours)
            .putBoolean("hillshade", v.hillshade)
            .putBoolean("offRouteOn", v.offRouteWarning)
            .putBoolean("offRouteVibrate", v.offRouteVibrate)
            .putInt("offRouteM", v.offRouteM)
            .putBoolean("planVibrate", v.planVibrate)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putString("mapTheme", v.mapTheme)
            .putBoolean("navHeadingUp", v.navHeadingUp)  // Navigate orientation
            .putString("defaultActivity", v.defaultActivity.name)
            .putInt("defaultSac", v.defaultSac)
            .putString("hikePace", v.hikePace.name)
            .putString("bikePace", v.bikePace.name)
            .putBoolean("mapsWifiOnly", v.mapsWifiOnly)
            .apply()

    var planWaypoints: List<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("plan", null)).map { GeoPoint(it.lat, it.lon) }
        set(v) = sp.edit().putString("plan", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /**
     * What the plan was last saved or loaded as (name, tour id, points): the plan counts as unsaved when its
     * points differ (RouteRules.isUnsaved). Null name = never saved / loaded.
     */
    var planSavedName: String?
        get() = sp.getString("planSavedName", null)
        set(v) = sp.edit().putString("planSavedName", v).apply()
    var planSavedId: Long
        get() = sp.getLong("planSavedId", -1)
        set(v) = sp.edit().putLong("planSavedId", v).apply()
    var planSavedPoints: List<GeoPoint>?
        get() = sp.getString("planSavedPoints", null)?.let { s -> TourDb.jsonToWaypoints(s).map { GeoPoint(it.lat, it.lon) } }
        set(v) = sp.edit().putString("planSavedPoints", v?.let { l -> TourDb.waypointsToJson(l.map { GpxWaypoint(it.lat, it.lon, null) }) }).apply()

    /** Waypoints placed on a POI: passed exactly, never straightened by the router. */
    var planPinned: Set<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("planPinned", null)).map { GeoPoint(it.lat, it.lon) }.toSet()
        set(v) = sp.edit().putString("planPinned", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /** Learned pace per activity (time/PaceStore JSON); null = nothing learned yet. */
    var paceStore: String?
        get() = sp.getString("paceStore", null)
        set(v) = sp.edit().putString("paceStore", v).apply()

    /** "Allow all the time" was offered once (first tour); never nag again – Settings has the way back. */
    var backgroundAsked: Boolean
        get() = sp.getBoolean("backgroundAsked", false)
        set(v) = sp.edit().putBoolean("backgroundAsked", v).apply()

    /** Last check for newer routing data after a route (ms since epoch; AppViewModel.checkRoutingData). */
    var routingDataCheckedAt: Long
        get() = sp.getLong("routingDataCheckedAt", 0L)
        set(v) = sp.edit().putLong("routingDataCheckedAt", v).apply()

    /** The followed line is a shown/imported track, not the plan ("Continue navigation" then does not re-plan). */
    var followIsTrack: Boolean
        get() = sp.getBoolean("followIsTrack", false)
        set(v) = sp.edit().putBoolean("followIsTrack", v).apply()

    /** The line being followed (off-route warning); stored in a file so the service can reload it. */
    fun saveFollow(name: String, points: List<GeoPoint>?) {
        try {
            if (points == null) { followFile.delete(); return }
            val arr = JSONArray()
            val ele = JSONArray()
            points.forEach { arr.put(it.lat); arr.put(it.lon); ele.put(it.ele ?: org.json.JSONObject.NULL) }
            val tmp = File(followFile.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("name", name).put("pts", arr).put("ele", ele).toString())
            if (!tmp.renameTo(followFile)) Problems.error("data", "Followed line not saved (rename failed) – lost after a restart")
        } catch (e: Exception) { Problems.error("data", "Followed line not saved – lost after a restart", e) }
    }

    fun loadFollow(): Pair<String, List<GeoPoint>>? = try {
        if (!followFile.exists()) null else {
            val o = org.json.JSONObject(followFile.readText())
            val a = o.getJSONArray("pts")
            val e = o.optJSONArray("ele") // optional (build 2+): elevation per point for "remaining ascent"
            o.getString("name") to (0 until a.length() / 2).map {
                val h = e?.takeIf { arr -> it < arr.length() && !arr.isNull(it) }?.optDouble(it)
                GeoPoint(a.getDouble(it * 2), a.getDouble(it * 2 + 1), h?.takeIf { v -> !v.isNaN() })
            }
        }
    } catch (e: Exception) { Problems.warn("data", "Saved followed line unreadable, not restored", e); null }
}
