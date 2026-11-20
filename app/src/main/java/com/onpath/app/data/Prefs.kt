package com.onpath.app.data

import android.content.Context
import android.content.SharedPreferences
import com.onpath.app.debug.Problems
import com.onpath.app.gpx.GpxWaypoint
import com.onpath.app.map.ReliefAuto
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.routing.GeoPoint
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.LastSeen
import com.onpath.app.setup.PhoneCheck
import com.onpath.app.setup.VoiceSetup
import com.onpath.app.setup.VoiceStatus
import com.onpath.app.time.Pace
import java.io.File

/** Small persisted settings and the auto-saved current plan (R-08). */
class Prefs internal constructor(private val sp: SharedPreferences, filesDir: File) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("onpath", Context.MODE_PRIVATE), context.applicationContext.filesDir)

    // ---- small app state, all in this one place (review Arch 11: written around Prefs before) ----

    /** When the automatic update check last ran (epoch ms; 0 = never). */
    var updateCheckMs: Long
        get() = sp.getLong("updateCheck", 0)
        set(v) = sp.edit().putLong("updateCheck", v).apply()

    /** The newest crash already announced (Problems' time; the next one is said once). */
    var crashShownUntil: Long
        get() = sp.getLong("crashShownUntil", 0)
        set(v) = sp.edit().putLong("crashShownUntil", v).apply()

    /** Live sharing: the name on the viewer page ("Tour von …"); null = never asked, "" = asked and left empty. */
    var liveName: String?
        get() = try { sp.getString("liveName", null) } catch (e: Exception) {
            Problems.warn("settings", "Setting liveName unreadable, asked again", e); null
        }
        set(v) = sp.edit().putString("liveName", v).apply()

    /** The app language override (LanguageChoice name; null = the phone's). */
    var appLanguage: String?
        get() = sp.getString("appLanguage", null)
        set(v) = sp.edit().putString("appLanguage", v).apply()

    /** Fingerprint of the last share / open intent handled (IncomingIntent: not handled twice after a restart). */
    /** My routes' order as `RouteSort.encode` wrote it (owner 2026-11-12); null = never chosen (newest first). */
    var routesSort: String?
        get() = try { sp.getString("routesSort", null) } catch (e: Exception) {
            Problems.warn("settings", "Setting routesSort unreadable, newest first", e); null
        }
        set(v) = sp.edit().putString("routesSort", v).apply()

    /**
     * The tour under My routes → Zuletzt while the map is empty (owner 2026-11-12: exactly one plan there – the one
     * worked on last; RoutesState.recentId); -1 = none known.
     */
    var zuletztId: Long
        get() = sp.getLong("zuletztId", -1L)
        set(v) = sp.edit().putLong("zuletztId", v).apply()

    /** The one-time clean-up of older Zuletzt plans ran (RoutesState.trimDrafts): never again. */
    var zuletztTrimmed: Boolean
        get() = sp.getBoolean("zuletztTrimmed", false)
        set(v) = sp.edit().putBoolean("zuletztTrimmed", v).apply()

    var lastIncomingIntent: String?
        get() = sp.getString("lastIncomingIntent", null)
        set(v) = sp.edit().putString("lastIncomingIntent", v).apply()

    /**
     * Last chosen activity (owner: the app always starts with it; fresh install → Hike). Older installs:
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
     * Pace level (five levels) under [key]; not set yet → the old three-level value under [oldKey] mapped onto the
     * five ([com.onpath.app.time.Pace.fromOld]), so existing users keep their pace. Separate keys because the old
     * "FIT" (×0.8) maps to "Very fit", not to "Fit".
     */
    private fun pace(key: String, oldKey: String): Pace = try {
        if (sp.contains(key)) Pace.of(sp.getString(key, null))
        else Pace.fromOld(sp.getString(oldKey, null))
    } catch (e: Exception) {
        Problems.warn("settings", "Setting $key unreadable, using Average pace", e); Pace.DEFAULT
    }

    /** Writes that may lag (camera, last seen): background, coalesced, never waited for in onStop ([LazyPrefs]). */
    private val later = LazyPrefs.of(sp)

    /** Writes the lagging values now, on the background thread (the screen stops; the caller does not wait). */
    fun flushLazy() = later.flush()

    /** Last camera: lat, lon, zoom. Saved at every camera idle – through [later], not apply() (stalls at onStop). */
    var camera: Triple<Double, Double, Double>?
        get() {
            @Suppress("UNCHECKED_CAST")
            (later.peek("camera") as? Triple<Double, Double, Double>)?.let { return it }
            return if (!sp.contains("camLat")) null else Triple(
                sp.getFloat("camLat", 0f).toDouble(), sp.getFloat("camLon", 0f).toDouble(), sp.getFloat("camZoom", 12f).toDouble()
            )
        }
        set(v) {
            if (v == null) return
            later.put("camera", v) { e ->
                e.putFloat("camLat", v.first.toFloat()).putFloat("camLon", v.second.toFloat()).putFloat("camZoom", v.third.toFloat())
            }
        }

    /** Hidden POI kinds; a set stored under older defaults is upgraded once ([com.onpath.app.poi.PoiKinds.upgradeHidden]). */
    private fun hiddenPoiKinds(): Set<String> {
        val kinds = PoiKinds
        val stored = sp.getStringSet("poiHidden", null)?.toSet() ?: return kinds.defaultHidden
        if (sp.getInt("poiDefaultsVer", 0) >= kinds.DEFAULTS_VERSION) return stored
        val now = kinds.upgradeHidden(stored)
        sp.edit().putStringSet("poiHidden", HashSet(now)).putInt("poiDefaultsVer", kinds.DEFAULTS_VERSION).apply()
        return now
    }

    /**
     * The hold time of the tour's bar ([HoldSetting]); without the new key the old lock switch decides (off → 0, on → 1 s).
     * A value of the wrong type never crashes: the default, reported.
     */
    private fun navHoldMs(): Int = try {
        HoldSetting.fromPrefs(
            storedMs = if (sp.contains("navHoldMs")) sp.getInt("navHoldMs", HoldSetting.DEFAULT_MS) else null,
            oldLock = if (sp.contains("navLock")) sp.getBoolean("navLock", true) else null)
    } catch (e: ClassCastException) {
        Problems.warn("settings", "Setting navHoldMs / navLock unreadable, using ${HoldSetting.DEFAULT_MS} ms", e)
        HoldSetting.DEFAULT_MS
    }

    var settings: AppSettings
        get() = AppSettings(
            hiddenPoiKinds = hiddenPoiKinds(),
            contours = sp.getBoolean("contours", true),
            hillshade = sp.getBoolean("hillshade", true),
            offRouteWarning = sp.getBoolean("offRouteOn", true),
            offRouteVibrate = sp.getBoolean("offRouteVibrate", true),
            junctionVibration = JunctionVibration.of(sp.getString("junctionVibration", null), sp.getBoolean("junctionVibrate", true)),
            offRouteM = sp.getInt("offRouteM", 40).takeIf { it in AppSettings.OFF_ROUTE_CHOICES } ?: 40,
            planVibrate = sp.getBoolean("planVibrate", true),
            keepScreenOn = sp.getBoolean("keepScreenOn", true),
            bikeScreen = BikeScreen.of(sp.getString("bikeScreen", null), sp.getBoolean("keepScreenOn", true)),
            junctionGuide = JunctionGuide.of(sp.getString("junctionGuide", null),
                voiceOn = sp.getBoolean("voiceBike", true) || sp.getBoolean("voiceHike", true),
                vibrationOn = JunctionVibration.of(sp.getString("junctionVibration", null), sp.getBoolean("junctionVibrate", true)) != JunctionVibration.OFF),
            positionOnRoute = sp.getBoolean("positionOnRoute", true),
            navHoldMs = navHoldMs(),
            mapTheme = sp.getString("mapTheme", "classic") ?: "classic",
            navHeadingUp = sp.getBoolean("navHeadingUp", true),
            hikePace = pace("hikePaceLevel", "hikePace"),
            bikePace = pace("bikePaceLevel", "bikePace"),
            mapsWifiOnly = sp.getBoolean("mapsWifiOnly", false),
        )
        set(v) = sp.edit()
            .putStringSet("poiHidden", HashSet(v.hiddenPoiKinds))
            .putInt("poiDefaultsVer", PoiKinds.DEFAULTS_VERSION)
            .putBoolean("contours", v.contours)
            .putBoolean("hillshade", v.hillshade)
            .putBoolean("offRouteOn", v.offRouteWarning)
            .putBoolean("offRouteVibrate", v.offRouteVibrate)
            .putString("junctionVibration", v.junctionVibration.name).remove("junctionVibrate")
            .putInt("offRouteM", v.offRouteM)
            .putBoolean("planVibrate", v.planVibrate)
            .putBoolean("keepScreenOn", v.keepScreenOn)
            .putString("bikeScreen", v.bikeScreen.name)
            .putString("junctionGuide", v.junctionGuide.name)
            .putBoolean("positionOnRoute", v.positionOnRoute)
            .putInt("navHoldMs", v.navHoldMs).remove("navLock") // the old on/off switch, migrated by [navHoldMs]
            .putString("mapTheme", v.mapTheme)
            .putBoolean("navHeadingUp", v.navHeadingUp)
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

    /**
     * Multi-day tours (stage 4, ui/MultiDayFlow): the open tour's days and night marks (JSON); null = a normal plan. Kept
     * like the plan's points, so a restart keeps them.
     */
    var planMultiDay: String?
        get() = sp.getString("planMultiDay", null)
        set(v) = sp.edit().putString("planMultiDay", v).apply()

    /** The day of a multi-day tour being followed as the plan (JSON, ui/MultiDayFlow); null = none. */
    var planDayFollow: String?
        get() = sp.getString("planDayFollow", null)
        set(v) = sp.edit().putString("planDayFollow", v).apply()

    /** Multi-day tours whose map question was answered (uuids, §7: asked once). */
    var multiDayMapsAsked: Set<String>
        get() = sp.getStringSet("multiDayMapsAsked", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("multiDayMapsAsked", v).apply()

    /** Map areas of multi-day tours the user said "load" to (cell ids), kept beside every plan's corridor. */
    var multiDayMapCells: Set<String>
        get() = sp.getStringSet("multiDayMapCells", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("multiDayMapCells", v).apply()

    /** Of [multiDayMapCells]: those asked for on mobile data – they wait for Wi-Fi. */
    var multiDayWifiCells: Set<String>
        get() = sp.getStringSet("multiDayWifiCells", null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("multiDayWifiCells", v).apply()

    /** The plan's points as the last plan link brought them (snapped by the router); null = none (core feature C15). */
    var planLinkPoints: List<GeoPoint>?
        get() = sp.getString("planLinkPoints", null)?.let { s -> TourDb.jsonToWaypoints(s).map { GeoPoint(it.lat, it.lon) } }
        set(v) = sp.edit().putString("planLinkPoints", v?.let { l -> TourDb.waypointsToJson(l.map { GpxWaypoint(it.lat, it.lon, null) }) }).apply()

    /** Waypoints placed on a POI: passed exactly, never straightened by the router. */
    var planPinned: Set<GeoPoint>
        get() = TourDb.jsonToWaypoints(sp.getString("planPinned", null)).map { GeoPoint(it.lat, it.lon) }.toSet()
        set(v) = sp.edit().putString("planPinned", TourDb.waypointsToJson(v.map { GpxWaypoint(it.lat, it.lon, null) })).apply()

    /**
     * Removes the stored pace learned across tours ("paceStore") once – pace is learned per tour only (owner).
     * Returns true when there was one.
     */
    fun dropOldLearnedPace(): Boolean {
        if (!sp.contains("paceStore")) return false
        sp.edit().remove("paceStore").apply()
        return true
    }

    /**
     * Relief switched off by the app on a slow phone (map/ReliefAuto) – kept apart from the setting itself: "reliefAutoOff"
     * = the app did it once (never twice), "reliefByUser" = the user set Relief by hand in Settings → Map (always wins).
     */
    var reliefAuto: ReliefAuto.State
        get() = ReliefAuto.State(autoOff = sp.getBoolean("reliefAutoOff", false),
            byUser = sp.getBoolean("reliefByUser", false))
        set(v) = sp.edit().putBoolean("reliefAutoOff", v.autoOff).putBoolean("reliefByUser", v.byUser).apply()

    // ---- Phone setup (docs/design/onboarding-phone-check.md) ----
    /** Phone setup: the voice's test sentence was heard (spoken directions start only then). */
    var voiceHeard: Felt
        get() = com.onpath.app.debug.enumOrNull<Felt>(sp.getString("voiceHeard", null), "settings")
            ?: Felt.UNKNOWN
        set(v) = sp.edit().putString("voiceHeard", v.name).apply()

    /** The speech output for the app language as last found (recording/Speaker). */
    var voiceStatus: VoiceStatus
        get() = com.onpath.app.debug.enumOrNull<VoiceStatus>(sp.getString("voiceStatus", null), "settings")
            ?: VoiceStatus.UNKNOWN
        set(v) = sp.edit().putString("voiceStatus", v.name).apply()

    /** Which engine lacks the voice and whether Google's is there (the voice step's text), as last found. */
    var voiceFix: VoiceSetup.Fix?
        get() = VoiceSetup.decode(sp.getString("voiceFix", null))
        set(v) = sp.edit().putString("voiceFix", VoiceSetup.encode(v)).apply()

    /** The last check of the speech engines as one line (diagnostics export: engine, version, voices). */
    var voiceDetail: String
        get() = sp.getString("voiceDetail", null) ?: "voice not checked yet"
        set(v) = sp.edit().putString("voiceDetail", v).apply()

    /** The user's answer to the vibration test ("Did you feel it?"). */
    var vibrationFelt: Felt
        get() = com.onpath.app.debug.enumOrNull<Felt>(sp.getString("vibrationFelt", null), "settings")
            ?: Felt.UNKNOWN
        set(v) = sp.edit().putString("vibrationFelt", v.name).apply()

    /** The user went through the steps for their phone brand ("Done"). */
    var brandStepsDone: Boolean
        get() = sp.getBoolean("brandStepsDone", false)
        set(v) = sp.edit().putBoolean("brandStepsDone", v).apply()

    /**
     * The brand page was answered once („Erledigt“ or „Später“, owner 2026-11-08): it never comes before a tour again
     * (PhoneCheck.ONCE_BEFORE_TOUR) – only Settings → Phone setup keeps it.
     */
    var brandAnswered: Boolean
        get() = sp.getBoolean("brandAnswered", false)
        set(v) = sp.edit().putBoolean("brandAnswered", v).apply()

    /** The welcome ("Set up now / Later") was answered – it is shown once. */
    var setupWelcomeSeen: Boolean
        get() = sp.getBoolean("setupWelcomeSeen", false)
        set(v) = sp.edit().putBoolean("setupWelcomeSeen", v).apply()

    /** How often each system dialog was shown (location, notifications) – after a denial Android may stop showing it. */
    fun asks(what: String): Int = sp.getInt("asks_$what", 0)
    fun countAsk(what: String) = sp.edit().putInt("asks_$what", asks(what) + 1).apply()

    /** Recommended checks put off with "Later" before a tour: id → when (ms). */
    fun snoozed(now: Long): Set<CheckId> = CheckId.entries.filter {
        now - sp.getLong("snooze_${it.name}", 0L) < PhoneCheck.SNOOZE_MS
    }.toSet()
    fun snooze(id: CheckId, now: Long) = sp.edit().putLong("snooze_${id.name}", now).apply()

    /** What was fine when OnPath was last open, and when (for "your phone removed …"). */
    var lastSeen: LastSeen?
        get() {
            (later.peek("lastSeen") as? LastSeen)?.let { return it }
            val at = sp.getLong("lastSeenAt", 0L).takeIf { it > 0 } ?: return null
            val ok = sp.getString("lastSeenOk", "").orEmpty().split(',').filter { it.isNotEmpty() }.mapNotNull { n ->
                com.onpath.app.debug.enumOrNull<CheckId>(n, "settings") }.toSet()
            return LastSeen(ok, at)
        }
        // written in Activity.onStop – through [later], so onStop never waits for the disk
        set(v) { if (v != null) later.put("lastSeen", v) { e -> e.putLong("lastSeenAt", v.atMs).putString("lastSeenOk", v.ok.joinToString(",") { it.name }) } }

    /** ☰ → Pair with browser opened the camera once: the step line before it is not shown again (PairEntry). */
    var pairHintSeen: Boolean
        get() = sp.getBoolean("pairHintSeen", false)
        set(v) = sp.edit().putBoolean("pairHintSeen", v).apply()


    /** Developer tools unlocked by 7 taps on the version in Settings → About (owner); stays. */
    var devToolsUnlocked: Boolean
        get() = sp.getBoolean("devToolsUnlocked", false)
        set(v) = sp.edit().putBoolean("devToolsUnlocked", v).apply()

    /**
     * Developer tools → „Tourvorschläge“ (docs/design/tour-suggestions.md §7 stage 4): on by default since 2026-11-18
     * (owner: on for everyone with the card row); the switch stays as an off-switch.
     */
    var suggestTest: Boolean
        get() = sp.getBoolean("suggestTest", true)
        set(v) = sp.edit().putBoolean("suggestTest", v).apply()

    /** Last visit to Settings → Diagnostics (ms): newer serious problems colour its list line red (owner). */
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
}
