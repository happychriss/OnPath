package com.onpath.app.data

import android.content.Context
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import org.json.JSONArray
import java.io.File

/** Small persisted settings and the auto-saved current plan (R-08). */
class Prefs internal constructor(private val sp: android.content.SharedPreferences, filesDir: File) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("onpath", Context.MODE_PRIVATE), context.applicationContext.filesDir)

    private val followFile = File(filesDir, "follow.json")

    /**
     * Last chosen activity (owner 2026-10-22: the app always starts with it; fresh install → Hike). Older installs:
     * the activity the old app started with ([LastActivity] – Settings default before the stale key activity).
     */
    var activity: Activity
        get() = LastActivity.activity(activityPref("lastActivity"), activityPref("defaultActivity"), activityPref("activity"))
        set(v) = sp.edit().putString("lastActivity", v.name).apply()

    /** Stored activity [key]; not set → null (expected), unknown value → Hike with a warning. */
    private fun activityPref(key: String): Activity? {
        val v = try { sp.getString(key, null) } catch (e: Exception) {
            Problems.warn("settings", "Setting $key unreadable, using Hike", e); return Activity.HIKE
        } ?: return null
        return try { Activity.valueOf(v) } catch (e: Exception) {
            Problems.warn("settings", "Setting $key has unknown activity '$v', using Hike", e); Activity.HIKE
        }
    }

    /** Last chosen hiking difficulty (SAC T1–T5; fresh install T2, older installs keep the level they started with). */
    var sac: Int
        get() = LastActivity.sac(intPref("lastSac"), intPref("defaultSac"), intPref("sac"))
        set(v) = sp.edit().putInt("lastSac", v.coerceIn(1, 5)).apply()

    private fun intPref(key: String): Int? = try { if (sp.contains(key)) sp.getInt(key, 0) else null } catch (e: Exception) {
        Problems.warn("settings", "Setting $key unreadable, using the default", e); null
    }

    /**
     * Pace level (five levels since 2026-10-22) under [key]; not set yet → the old three-level value under [oldKey]
     * mapped onto the five ([com.onpath.app.time.Pace.fromOld]), so existing users keep their pace. New keys because the
     * old "FIT" (×0.8) is now "Very fit", not the new "Fit".
     */
    private fun pace(key: String, oldKey: String): com.onpath.app.time.Pace = try {
        if (sp.contains(key)) com.onpath.app.time.Pace.of(sp.getString(key, null))
        else com.onpath.app.time.Pace.fromOld(sp.getString(oldKey, null))
    } catch (e: Exception) {
        Problems.warn("settings", "Setting $key unreadable, using Average pace", e); com.onpath.app.time.Pace.DEFAULT
    }

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

    /** Hidden POI kinds; a set stored under older defaults is upgraded once ([com.onpath.app.poi.PoiKinds.upgradeHidden]). */
    private fun hiddenPoiKinds(): Set<String> {
        val kinds = com.onpath.app.poi.PoiKinds
        val stored = sp.getStringSet("poiHidden", null)?.toSet() ?: return kinds.defaultHidden
        if (sp.getInt("poiDefaultsVer", 0) >= kinds.DEFAULTS_VERSION) return stored
        val now = kinds.upgradeHidden(stored)
        sp.edit().putStringSet("poiHidden", HashSet(now)).putInt("poiDefaultsVer", kinds.DEFAULTS_VERSION).apply()
        return now
    }

    var settings: AppSettings
        get() = AppSettings(
            hiddenPoiKinds = hiddenPoiKinds(),
            contours = sp.getBoolean("contours", true),
            hillshade = sp.getBoolean("hillshade", true),
            offRouteWarning = sp.getBoolean("offRouteOn", true),
            offRouteVibrate = sp.getBoolean("offRouteVibrate", true),
            junctionVibrate = sp.getBoolean("junctionVibrate", true),
            offRouteM = sp.getInt("offRouteM", 40).takeIf { it in AppSettings.OFF_ROUTE_CHOICES } ?: 40,
            planVibrate = sp.getBoolean("planVibrate", true),
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            mapTheme = sp.getString("mapTheme", "classic") ?: "classic",
            navHeadingUp = sp.getBoolean("navHeadingUp", true),  // Navigate orientation
            hikePace = pace("hikePaceLevel", "hikePace"),
            bikePace = pace("bikePaceLevel", "bikePace"),
            mapsWifiOnly = sp.getBoolean("mapsWifiOnly", false),
        )
        set(v) = sp.edit()
            .putStringSet("poiHidden", HashSet(v.hiddenPoiKinds))
            .putInt("poiDefaultsVer", com.onpath.app.poi.PoiKinds.DEFAULTS_VERSION)
            .putBoolean("contours", v.contours)
            .putBoolean("hillshade", v.hillshade)
            .putBoolean("offRouteOn", v.offRouteWarning)
            .putBoolean("offRouteVibrate", v.offRouteVibrate)
            .putBoolean("junctionVibrate", v.junctionVibrate)
            .putInt("offRouteM", v.offRouteM)
            .putBoolean("planVibrate", v.planVibrate)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putString("mapTheme", v.mapTheme)
            .putBoolean("navHeadingUp", v.navHeadingUp)  // Navigate orientation
            .putString("hikePaceLevel", v.hikePace.name)
            .putString("bikePaceLevel", v.bikePace.name)
            .remove("hikePace").remove("bikePace") // the old three-level keys, migrated by [pace]
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

    /** The plan's points as the last plan link brought them (snapped by the router); null = none (A-005). */
    var planLinkPoints: List<GeoPoint>?
        get() = sp.getString("planLinkPoints", null)?.let { s -> TourDb.jsonToWaypoints(s).map { GeoPoint(it.lat, it.lon) } }
        set(v) = sp.edit().putString("planLinkPoints", v?.let { l -> TourDb.waypointsToJson(l.map { GpxWaypoint(it.lat, it.lon, null) }) }).apply()

    /** Waypoints placed on a POI: passed exactly, never straightened by the router. */
    var planPinned: Set<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("planPinned", null)).map { GeoPoint(it.lat, it.lon) }.toSet()
        set(v) = sp.edit().putString("planPinned", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /**
     * Pace learned across tours was dropped (owner 2026-10-22: learning per tour only): removes the old stored value
     * ("paceStore") once. Returns true when there was one.
     */
    fun dropOldLearnedPace(): Boolean {
        if (!sp.contains("paceStore")) return false
        sp.edit().remove("paceStore").apply()
        return true
    }


    /** Developer tools unlocked by 7 taps on the version in Settings → About (owner C5 2026-10-24); stays. */
    var devToolsUnlocked: Boolean
        get() = sp.getBoolean("devToolsUnlocked", false)
        set(v) = sp.edit().putBoolean("devToolsUnlocked", v).apply()

    /** Last visit to Settings → Diagnostics (ms): newer serious problems colour its list line red (owner C6). */
    var diagnosticsSeenMs: Long
        get() = sp.getLong("diagnosticsSeenMs", 0L)
        set(v) = sp.edit().putLong("diagnosticsSeenMs", v).apply()

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
