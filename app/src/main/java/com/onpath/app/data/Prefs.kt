package com.onpath.app.data

import android.content.Context
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
        get() = runCatching { Activity.valueOf(sp.getString("activity", null)!!) }.getOrDefault(Activity.HIKE)
        set(v) = sp.edit().putString("activity", v.name).apply()

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
            offRouteM = sp.getInt("offRouteM", 40).takeIf { it in AppSettings.OFF_ROUTE_CHOICES } ?: 40,
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            mapTheme = sp.getString("mapTheme", "classic") ?: "classic",
            navHeadingUp = sp.getBoolean("navHeadingUp", true),  // Navigate orientation
        )
        set(v) = sp.edit()
            .putStringSet("poiHidden", HashSet(v.hiddenPoiKinds))
            .putBoolean("overlay", v.routeOverlay)
            .putBoolean("contours", v.contours)
            .putBoolean("hillshade", v.hillshade)
            .putBoolean("offRouteOn", v.offRouteWarning)
            .putInt("offRouteM", v.offRouteM)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putString("mapTheme", v.mapTheme)
            .putBoolean("navHeadingUp", v.navHeadingUp)  // Navigate orientation
            .apply()

    var planWaypoints: List<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("plan", null)).map { GeoPoint(it.lat, it.lon) }
        set(v) = sp.edit().putString("plan", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /** Waypoints placed on a POI: passed exactly, never straightened by the router. */
    var planPinned: Set<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("planPinned", null)).map { GeoPoint(it.lat, it.lon) }.toSet()
        set(v) = sp.edit().putString("planPinned", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

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
            tmp.renameTo(followFile)
        } catch (_: Exception) { }
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
    } catch (_: Exception) { null }
}
