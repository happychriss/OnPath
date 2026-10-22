package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.time.TimeEstimate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The topics of Settings (owner 2026-10-22: a list of topics, each opens its own page), in list order. */
enum class SettingsPage(val title: String) {
    PACE("Pace & time"),
    POI("Points of interest"),
    MAP("Map"),
    NAVIGATION("Navigation"),
    OFFLINE_MAPS("Offline maps"),
    BACKUP("Backup"),
    PRIVACY("Privacy"),
    UPDATES("Updates"),
    DIAGNOSTICS("Diagnostics"),
    ABOUT("About"),
}

/** One line under a topic in the Settings list; [attention] colours it (problem = red, news = accent). */
data class Summary(val text: String, val attention: Attention = Attention.NONE) {
    enum class Attention { NONE, ACCENT, PROBLEM }
}

/**
 * The one-line summaries of the Settings list (owner 2026-10-22: Android settings style, the current values at a
 * glance). Pure, unit-tested (`SettingsSummaryTest`).
 */
object SettingsSummary {
    fun pace(s: AppSettings, activity: Activity): Summary =
        Summary("${(if (TimeEstimate.isBike(activity)) s.bikePace else s.hikePace).label} · learns during each tour")

    fun poi(hidden: Set<String>): Summary {
        val groups = PoiGroup.entries.map { PoiKinds.inGroup(it) }.filter { it.isNotEmpty() }
        val shown = groups.count { g -> g.any { it.key !in hidden } }
        return Summary(when (shown) {
            groups.size -> if (groups.flatten().none { it.key in hidden }) "All shown" else "${groups.size} of ${groups.size} groups shown"
            0 -> "All hidden"
            else -> "$shown of ${groups.size} groups shown"
        })
    }

    fun map(s: AppSettings): Summary = Summary(
        "Contours ${onOff(s.contours)} · Hill shading ${onOff(s.hillshade)} · ${if (s.navHeadingUp) "Walking direction" else "North up"}")

    fun navigation(s: AppSettings): Summary = Summary(
        if (!s.offRouteWarning) "Off-route warning off · junction vibration ${onOff(s.junctionVibrate)}"
        else "Warn after ${s.offRouteM} m · vibration ${onOff(s.offRouteVibrate || s.junctionVibrate)}")

    fun offlineMaps(ui: OfflineMapsUi): Summary {
        val waiting = ui.waiting?.let { w -> (w.regions + w.routingParts).takeIf { it > 0 } }
        val base = when {
            ui.areas.isEmpty() && !ui.available -> "Downloads not available in this build"
            ui.areas.isEmpty() -> "No areas downloaded"
            else -> "${sizeText(ui.areas.sumOf { it.bytes })} · ${ui.areas.size} ${if (ui.areas.size == 1) "area" else "areas"}"
        }
        return Summary(base + (waiting?.let { " · $it waiting" } ?: ""))
    }

    fun backup(b: BackupUi, now: Long, zone: TimeZone = TimeZone.getDefault()): Summary = when {
        b.lastError != null -> Summary("Last backup failed", Summary.Attention.PROBLEM)
        b.needsStorageAccess -> Summary("Needs storage access", Summary.Attention.PROBLEM)
        b.lastMs <= 0 -> Summary("No backup yet")
        else -> Summary("Last backup ${whenText(b.lastMs, now, zone)}")
    }

    fun updates(u: AppViewModel.UpdateState, version: String): Summary = when (u) {
        is AppViewModel.UpdateState.Available -> Summary("Update available: ${u.release.version}", Summary.Attention.ACCENT)
        is AppViewModel.UpdateState.Downloading -> Summary("Downloading update – ${u.percent} %", Summary.Attention.ACCENT)
        AppViewModel.UpdateState.Checking -> Summary("${v(version)} – checking…")
        AppViewModel.UpdateState.UpToDate -> Summary("${v(version)} – up to date")
        is AppViewModel.UpdateState.Failed -> Summary("${v(version)} – check failed", Summary.Attention.PROBLEM)
        AppViewModel.UpdateState.Idle -> Summary(v(version))
    }

    fun diagnostics(problems: List<com.onpath.app.debug.Problems.Entry>): Summary {
        val serious = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR }
        return when {
            problems.isEmpty() -> Summary("No problems")
            serious > 0 -> Summary("${problems.size} ${plural(problems.size, "problem")} · $serious serious", Summary.Attention.PROBLEM)
            else -> Summary("${problems.size} ${plural(problems.size, "problem")}")
        }
    }

    val privacy = Summary("Nothing is tracked · what leaves your phone")

    val about = Summary("Credits, licence")

    /** "today 18:40", "yesterday 09:12", "21 Oct 18:40". */
    fun whenText(ms: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = ms }
        val n = Calendar.getInstance(zone).apply { timeInMillis = now }
        val time = "%02d:%02d".format(Locale.ROOT, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
        val dayDiff = n.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "today $time"
            sameYear && dayDiff == 1 -> "yesterday $time"
            else -> java.text.SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale.ENGLISH)
                .apply { timeZone = zone }.format(java.util.Date(ms)) + " $time"
        }
    }

    /** "410 MB", "1.2 GB", "3.4 MB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes / 1_073_741_824.0)
        else -> mbText(bytes)
    }

    /** "0.10.0 (build 1000)" → "0.10.0". */
    private fun v(version: String) = version.substringBefore(" (")
    private fun onOff(b: Boolean) = if (b) "on" else "off"
    private fun plural(n: Int, w: String) = if (n == 1) w else "${w}s"
}
