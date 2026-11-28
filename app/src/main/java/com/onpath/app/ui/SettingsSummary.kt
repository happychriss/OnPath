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

/** One line under a topic in the Settings list; [attention] colours it (problem = red, recommended = amber, news = accent). */
data class Summary(val text: String, val attention: Attention = Attention.NONE) {
    /** NONE grey, ACCENT blue (news), WARNING amber (recommended), PROBLEM red. */
    enum class Attention { NONE, ACCENT, WARNING, PROBLEM }
}

/** The groups of the Settings list: an urgent row on top (no heading), "Your settings", "App". */
enum class TopicGroup { URGENT, YOURS, APP }

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
        if (!s.offRouteWarning) Txt.s(Res.string.settings_sum_nav_off, onOff(s.junctionGuide != com.onpath.app.data.JunctionGuide.OFF))
        else Txt.s(Res.string.settings_sum_nav, s.offRouteM,
            onOff(s.offRouteVibrate || s.junctionGuide != com.onpath.app.data.JunctionGuide.OFF)))

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

    /**
     * Phone setup in the list (owner 2026-10-30): something a tour needs → red, "1 item needed for tours"; only
     * recommended items missing → amber, "2 steps open" (owner 2026-11-08; with an amber dot on the row); else
     * "Everything set up". Optional items never count.
     */
    fun phone(checks: List<com.onpath.app.setup.Check>): Summary {
        val needed = checks.count { !it.ok && it.need == com.onpath.app.setup.Need.REQUIRED }
        val open = openSteps(checks)
        return when {
            needed > 0 -> Summary(Txt.p(Res.plurals.settings_sum_phone_needed, needed, needed), Summary.Attention.PROBLEM)
            open > 0 -> Summary(Txt.p(Res.plurals.settings_sum_phone_open, open, open), Summary.Attention.WARNING)
            else -> Summary(Txt.s(Res.string.settings_sum_phone_ok))
        }
    }

    /** The setup steps still open: required or recommended, not done (optional ones never count). */
    fun openSteps(checks: List<com.onpath.app.setup.Check>?): Int =
        checks.orEmpty().count { !it.ok && it.need != com.onpath.app.setup.Need.OPTIONAL }

    /**
     * Phone setup at the top of Settings (owner 2026-11-08, father's feedback): while any step is open – red when a tour
     * needs it ([phoneNeeded]), else amber "N steps open" – and back at its place in "App" when everything is done.
     */
    fun phoneOnTop(checks: List<com.onpath.app.setup.Check>?): Boolean = openSteps(checks) > 0

    /** Something a tour needs is missing on the phone: Phone setup goes to the top of Settings and into the ☰ menu. */
    fun phoneNeeded(checks: List<com.onpath.app.setup.Check>?): Boolean =
        checks.orEmpty().any { !it.ok && it.need == com.onpath.app.setup.Need.REQUIRED }

    /**
     * The Settings list in groups, in order: [TopicGroup.URGENT] (only Navigation – it holds Phone setup – and only while
     * a setup step is open, [phoneOnTop]; owner: "at the top"), then "Your settings", then "App". Only the start-list
     * pages ([SettingsPage.top]) of [topics] are listed; empty groups are left out.
     */
    fun groups(topics: List<SettingsPage>, phoneChecks: List<com.onpath.app.setup.Check>?): List<Pair<TopicGroup, List<SettingsPage>>> {
        val list = topics.filter { it.top }
        val urgent = if (phoneOnTop(phoneChecks) && SettingsPage.NAVIGATION in list) listOf(SettingsPage.NAVIGATION) else emptyList()
        val rest = list - urgent.toSet()
        return listOf(TopicGroup.URGENT to urgent, TopicGroup.YOURS to rest.filter { !it.app }, TopicGroup.APP to rest.filter { it.app })
            .filter { it.second.isNotEmpty() }
    }

    /**
     * Navigate → ⚙ "on the way" (owner 2026-11-07): the Navigation page first, then only the topics that make sense
     * while riding, in this order – the rest behind "Alle Einstellungen". [available] = the topics this phone shows.
     */
    val ON_THE_WAY = listOf(SettingsPage.MAP, SettingsPage.POI) // V1 (Chriss 2026-11-28 10:59): Dein Tempo, Offline-Karten via „Alle Einstellungen“

    fun onTheWay(available: List<SettingsPage>): List<SettingsPage> = ON_THE_WAY.filter { it in available }

    // ---- the six start-list pages (owner 2026-11-27) ----

    /** Plan: "Tempo Mittel · markierte Wege Standard". */
    fun plan(s: AppSettings, activity: Activity): Summary =
        Summary(Txt.s(Res.string.settings_sum_plan, (if (TimeEstimate.isBike(activity)) s.bikePace else s.hikePace).label, markedTrailsLabel(s.markedTrails)))

    /**
     * The setting's own name by activity (owner 2026-11-27): „Wanderrouten folgen“ on foot, „Radrouten folgen“ on any bike
     * – the BRouter profiles follow hiking route relations resp. cycle route relations (routing/MarkedTrails).
     */
    fun markedTrailsTitle(activity: Activity): String =
        Txt.s(if (TimeEstimate.isBike(activity)) Res.string.settings_trails_bike else Res.string.settings_trails_hike)

    /** The word of a "Marked trails" level: Weniger · Standard · Mehr. */
    fun markedTrailsLabel(level: Int): String = Txt.s(when (AppSettings.markedTrailsOf(level)) {
        AppSettings.MARKED_TRAILS_LESS -> Res.string.settings_trails_less
        AppSettings.MARKED_TRAILS_MORE -> Res.string.settings_trails_more
        else -> Res.string.settings_trails_standard
    })

    /** Map: "Höhenlinien an · Relief an · 3 Gebiete" (the offline areas only when some are downloaded). */
    fun mapPage(s: AppSettings, offline: OfflineMapsUi?): Summary = Summary(listOfNotNull(
        Txt.s(Res.string.settings_sum_contours, onOff(s.contours)),
        Txt.s(Res.string.settings_sum_relief, onOff(s.hillshade)),
        offline?.areas?.size?.takeIf { it > 0 }?.let { Txt.p(Res.plurals.settings_sum_offline_areas, it, it) },
    ).joinToString(" · "))

    /** Navigation: Phone setup's red / amber line while a step is open ([phone]), else the navigation values. */
    fun navigationPage(s: AppSettings, phoneChecks: List<com.onpath.app.setup.Check>?): Summary =
        phoneChecks?.let { phone(it) }?.takeIf { it.attention != Summary.Attention.NONE } ?: navigation(s)

    /**
     * About OnPath: a serious problem not looked at yet (red, [diagnostics]) or update news ([updates]) first, else
     * "0.10.0 – aktuell · Quellen, Lizenz".
     */
    fun aboutPage(version: String, u: AppViewModel.UpdateState?, problems: List<com.onpath.app.debug.Problems.Entry>?,
                  seenMs: Long = 0L): Summary {
        problems?.let { diagnostics(it, seenMs) }?.takeIf { it.attention == Summary.Attention.PROBLEM }?.let { return it }
        val upd = u?.let { updates(it, version) }
        if (upd != null && upd.attention != Summary.Attention.NONE) return upd
        return Summary((upd?.text ?: v(version)) + " · " + about.text)
    }

    val privacy: Summary get() = Summary(Txt.s(Res.string.settings_sum_privacy))

    val about: Summary get() = Summary(Txt.s(Res.string.settings_sum_about))

    /** Legal & credits: "Website, Impressum, Quellen, Lizenzen". */
    val legal: Summary get() = Summary(Txt.s(Res.string.settings_sum_legal))

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
