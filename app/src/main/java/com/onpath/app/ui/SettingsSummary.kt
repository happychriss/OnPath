package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.poi.PoiGroup
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.time.TimeEstimate
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The topics of Settings (owner 2026-10-22: a list of topics, each opens its own page), in list order: "Your settings"
 * first, then the smaller "App" group (owner C7 2026-10-24).
 */
enum class SettingsPage(val title: String, val app: Boolean = false) {
    PACE("Your pace"),
    POI("Points of interest"),
    MAP("Map"),
    NAVIGATION("Navigation"),
    OFFLINE_MAPS("Offline maps"),
    BACKUP("Backup"),
    UPDATES("Updates", app = true),
    PRIVACY("Privacy", app = true),
    DIAGNOSTICS("Diagnostics", app = true),
    ABOUT("About", app = true),
}

/**
 * Developer tools in Settings → Diagnostics (owner C5 2026-10-24, one release APK): hidden until the version line in
 * About is tapped [TAPS] times (the Android convention); the unlock is remembered. Debug builds show them always. Pure.
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
        "Contour lines ${onOff(s.contours)} · Hill shading ${onOff(s.hillshade)} · ${if (s.navHeadingUp) "Direction of travel" else "North up"}")

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

    /**
     * Owner C6 2026-10-24: grey and neutral ("For bug reports"); red only while a serious problem newer than the last
     * visit to Diagnostics ([seenMs]) has not been looked at.
     */
    fun diagnostics(problems: List<com.onpath.app.debug.Problems.Entry>, seenMs: Long = 0L): Summary {
        val unseen = problems.count { it.level >= com.onpath.app.debug.Problems.Level.ERROR && it.last > seenMs }
        return when {
            unseen > 0 -> Summary("$unseen new ${plural(unseen, "problem")}", Summary.Attention.PROBLEM)
            problems.isEmpty() -> Summary(FOR_BUG_REPORTS)
            else -> Summary("$FOR_BUG_REPORTS · ${problems.size} ${plural(problems.size, "note")}")
        }
    }

    const val FOR_BUG_REPORTS = "For bug reports"

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
