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

    var planWaypoints: List<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("plan", null)).map { GeoPoint(it.lat, it.lon) }
        set(v) = sp.edit().putString("plan", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /** The line being followed (off-route warning); stored in a file so the service can reload it. */
    fun saveFollow(name: String, points: List<GeoPoint>?) {
        try {
            if (points == null) { followFile.delete(); return }
            val arr = JSONArray()
            points.forEach { arr.put(it.lat); arr.put(it.lon) }
            val tmp = File(followFile.path + ".tmp")
            tmp.writeText(org.json.JSONObject().put("name", name).put("pts", arr).toString())
            tmp.renameTo(followFile)
        } catch (_: Exception) { }
    }

    fun loadFollow(): Pair<String, List<GeoPoint>>? = try {
        if (!followFile.exists()) null else {
            val o = org.json.JSONObject(followFile.readText())
            val a = o.getJSONArray("pts")
            o.getString("name") to (0 until a.length() / 2).map { GeoPoint(a.getDouble(it * 2), a.getDouble(it * 2 + 1)) }
        }
    } catch (_: Exception) { null }
}
