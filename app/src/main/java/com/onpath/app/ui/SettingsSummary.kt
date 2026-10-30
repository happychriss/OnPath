package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.time.TimeEstimate
import com.onpath.strings.*
import org.jetbrains.compose.resources.StringResource
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The topics of Settings (owner: a list of topics, each opens its own page), in list order: "Your settings" first,
 * then the smaller "App" group.
 */
enum class SettingsPage(private val titleRes: StringResource, val app: Boolean = false) {
    PACE(Res.string.settings_page_pace),
    POI(Res.string.settings_page_poi),
    MAP(Res.string.settings_page_map),
    NAVIGATION(Res.string.settings_page_navigation),
    OFFLINE_MAPS(Res.string.settings_page_offline_maps),
    BACKUP(Res.string.settings_page_backup),
    /** Plan sync with the web planner (A-007): shown when the app has the sync (always, not in older screenshots). */
    SYNC(Res.string.settings_page_sync),
    /** Phone setup (location, notifications, vibration, battery …): shown when the app passes the checks. */
    PHONE(Res.string.settings_page_phone, app = true),
    UPDATES(Res.string.settings_page_updates, app = true),
    PRIVACY(Res.string.settings_page_privacy, app = true),
    DIAGNOSTICS(Res.string.settings_page_diagnostics, app = true),
    ABOUT(Res.string.settings_page_about, app = true);

    /** Topic name in the app's language (strings_settings.xml). */
    val title: String get() = Txt.s(titleRes)
}

/**
 * Developer tools in Settings → Diagnostics (owner: one release APK): hidden until the version line in About is tapped
 * [TAPS] times (the Android convention); the unlock is remembered. Debug builds show them always.
 */
object DevToolsUnlock {
    const val TAPS = 7

    fun unlocked(taps: Int): Boolean = taps >= TAPS

    fun shown(debugBuild: Boolean, unlocked: Boolean): Boolean = debugBuild || unlocked
}

/** One line under a topic in the Settings list; [attention] colours it (problem = red, news = accent). */
data class Summary(val text: String, val attention: Attention = Attention.NONE) {
    enum class Attention { NONE, ACCENT, PROBLEM }
}

/**
 * The one-line summaries of the Settings list (Android settings style: the current values at a glance).
 */
object SettingsSummary {
    fun pace(s: AppSettings, activity: Activity): Summary =
        Summary(Txt.s(Res.string.settings_sum_pace, (if (TimeEstimate.isBike(activity)) s.bikePace else s.hikePace).label))

    fun poi(hidden: Set<String>): Summary {
        val groups = PoiGroup.entries.map { PoiKinds.inGroup(it) }.filter { it.isNotEmpty() }
        val shown = groups.count { g -> g.any { it.key !in hidden } }
        return Summary(when (shown) {
            groups.size -> if (groups.flatten().none { it.key in hidden }) Txt.s(Res.string.settings_sum_poi_all_shown)
                else Txt.s(Res.string.settings_sum_poi_groups, groups.size, groups.size)
            0 -> Txt.s(Res.string.settings_sum_poi_all_hidden)
            else -> Txt.s(Res.string.settings_sum_poi_groups, shown, groups.size)
        })
    }

    fun map(s: AppSettings): Summary = Summary(Txt.s(Res.string.settings_sum_map, onOff(s.contours), onOff(s.hillshade),
        Txt.s(if (s.navHeadingUp) Res.string.settings_map_travel else Res.string.settings_map_north)))

    fun navigation(s: AppSettings): Summary = Summary(
        if (!s.offRouteWarning) Txt.s(Res.string.settings_sum_nav_off, onOff(s.junctionVibration != com.onpath.app.data.JunctionVibration.OFF))
        else Txt.s(Res.string.settings_sum_nav, s.offRouteM,
            onOff(s.offRouteVibrate || s.junctionVibration != com.onpath.app.data.JunctionVibration.OFF)))

    fun offlineMaps(ui: OfflineMapsUi): Summary {
        val waiting = ui.waiting?.let { w -> (w.regions + w.routingParts).takeIf { it > 0 } }
        val base = when {
            ui.areas.isEmpty() && !ui.available -> Txt.s(Res.string.settings_sum_maps_unavailable)
            ui.areas.isEmpty() -> Txt.s(Res.string.settings_sum_maps_none)
            else -> Txt.p(Res.plurals.settings_sum_maps_areas, ui.areas.size, sizeText(ui.areas.sumOf { it.bytes }), ui.areas.size)
        }
        return Summary(waiting?.let { Txt.s(Res.string.settings_sum_maps_waiting, base, it) } ?: base)
    }

    fun backup(b: BackupUi, now: Long, zone: TimeZone = TimeZone.getDefault()): Summary = when {
        b.lastError != null -> Summary(Txt.s(Res.string.settings_sum_backup_failed), Summary.Attention.PROBLEM)
        b.needsStorageAccess -> Summary(Txt.s(Res.string.settings_sum_backup_storage), Summary.Attention.PROBLEM)
        b.lastMs <= 0 -> Summary(Txt.s(Res.string.settings_sum_backup_none))
        else -> Summary(Txt.s(Res.string.settings_sum_backup_last, whenText(b.lastMs, now, zone)))
    }

    fun updates(u: AppViewModel.UpdateState, version: String): Summary = when (u) {
        is AppViewModel.UpdateState.Available -> Summary(Txt.s(Res.string.settings_sum_upd_available, u.release.version), Summary.Attention.ACCENT)
        is AppViewModel.UpdateState.Downloading -> Summary(Txt.s(Res.string.settings_sum_upd_downloading, u.percent), Summary.Attention.ACCENT)
        AppViewModel.UpdateState.Checking -> Summary(Txt.s(Res.string.settings_sum_upd_checking, v(version)))
        AppViewModel.UpdateState.UpToDate -> Summary(Txt.s(Res.string.settings_sum_upd_uptodate, v(version)))
        is AppViewModel.UpdateState.Failed -> Summary(Txt.s(Res.string.settings_sum_upd_failed, v(version)), Summary.Attention.PROBLEM)
        AppViewModel.UpdateState.Idle -> Summary(v(version))
    }

    /**
     * Grey and neutral ("For bug reports", owner); red only while a serious problem newer than the last
     * visit to Diagnostics ([seenMs]) has not been looked at.
     */
    fun diagnostics(problems: List<com.onpath.app.debug.Problems.Entry>, seenMs: Long = 0L): Summary {
        val unseen = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR && it.last > seenMs }
        return when {
            unseen > 0 -> Summary(Txt.p(Res.plurals.settings_sum_diag_new, unseen, unseen), Summary.Attention.PROBLEM)
            problems.isEmpty() -> Summary(Txt.s(Res.string.settings_sum_diag))
            else -> Summary(Txt.p(Res.plurals.settings_sum_diag_notes, problems.size, problems.size))
        }
    }

    /** "Everything set up", or "2 things to check" (red when something a tour needs is missing). */
    fun phone(checks: List<com.onpath.app.setup.Check>): Summary {
        val missing = checks.filter { !it.ok && it.need != com.onpath.app.setup.Need.OPTIONAL }
        return when {
            missing.isEmpty() -> Summary(Txt.s(Res.string.settings_sum_phone_ok))
            else -> Summary(Txt.p(Res.plurals.settings_sum_phone_missing, missing.size, missing.size),
                if (missing.any { it.need == com.onpath.app.setup.Need.REQUIRED }) Summary.Attention.PROBLEM else Summary.Attention.ACCENT)
        }
    }

    val privacy: Summary get() = Summary(Txt.s(Res.string.settings_sum_privacy))

    val about: Summary get() = Summary(Txt.s(Res.string.settings_sum_about))

    /** "today 18:40", "yesterday 09:12", "21 Oct 18:40". */
    fun whenText(ms: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val n = Calendar.getInstance(zone).apply { timeInMillis = now }
        val time = "%02d:%02d".format(Locale.ROOT, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
        val dayDiff = n.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> Txt.s(Res.string.settings_when_today, time)
            sameYear && dayDiff == 1 -> Txt.s(Res.string.settings_when_yesterday, time)
            else -> java.text.SimpleDateFormat(Txt.s(if (sameYear) Res.string.settings_date_day else Res.string.settings_date_day_year),
                if (Txt.language() == "de") Locale.GERMAN else Locale.ENGLISH)
                .apply { timeZone = zone }.format(java.util.Date(ms)) + " $time"
        }
    }

    /** "410 MB", "1.2 GB", "3.4 MB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> com.onpath.app.data.Format.number(bytes / 1_073_741_824.0, 1) + " GB"
        else -> mbText(bytes)
    }

    /** "0.10.0 (build 1000)" → "0.10.0". */
    private fun v(version: String) = version.substringBefore(" (")
    private fun onOff(b: Boolean) = Txt.s(if (b) Res.string.settings_on else Res.string.settings_off)
}
