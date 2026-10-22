package com.onpath.app.ui

import com.onpath.app.data.AppSettings
import com.onpath.app.debug.Problems
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.time.Pace
import com.onpath.app.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/** One-line summaries of the Settings topics (owner 2026-10-22). */
class SettingsSummaryTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_761_150_000_000L // 2025-10-22 16:20 UTC
    private val none = Summary.Attention.NONE

    @Test fun pace() {
        val s = AppSettings(hikePace = Pace.AVERAGE, bikePace = Pace.FIT)
        assertEquals(Summary("Average · learns during each tour"), SettingsSummary.pace(s, Activity.HIKE))
        assertEquals(Summary("Fit · learns during each tour"), SettingsSummary.pace(s, Activity.MTB))
    }

    @Test fun poi() {
        assertEquals("All shown", SettingsSummary.poi(emptySet()).text)
        assertEquals("All hidden", SettingsSummary.poi(PoiKinds.all.map { it.key }.toSet()).text)
        val groups = com.onpath.app.poi.PoiGroup.entries.count { PoiKinds.inGroup(it).isNotEmpty() }
        val firstGroup = PoiKinds.inGroup(com.onpath.app.poi.PoiGroup.FOOD).map { it.key }.toSet()
        assertEquals("${groups - 1} of $groups groups shown", SettingsSummary.poi(firstGroup).text)
    }

    @Test fun map() {
        assertEquals("Contours on · Hill shading on · Walking direction", SettingsSummary.map(AppSettings()).text)
        assertEquals("Contours off · Hill shading on · North up", SettingsSummary.map(AppSettings(contours = false, navHeadingUp = false)).text)
    }

    @Test fun navigation() {
        assertEquals("Warn after 40 m · vibration on", SettingsSummary.navigation(AppSettings()).text)
        assertEquals("Warn after 75 m · vibration off",
            SettingsSummary.navigation(AppSettings(offRouteM = 75, offRouteVibrate = false, junctionVibrate = false)).text)
        assertEquals("Off-route warning off · junction vibration on", SettingsSummary.navigation(AppSettings(offRouteWarning = false)).text)
    }

    @Test fun offlineMaps() {
        val areas = List(34) { OfflineArea("10-$it-361", "A$it", 38_000_000, "19 Oct 2026") }
        assertEquals(Summary("1.2 GB · 34 areas", none), SettingsSummary.offlineMaps(OfflineMapsUi(true, areas, false)))
        assertEquals("41 MB · 1 area · 3 waiting",
            SettingsSummary.offlineMaps(OfflineMapsUi(true, areas.take(1).map { it.copy(bytes = 43_000_000) }, false,
                waiting = WaitingDownloads(2, 1, 0))).text)
        assertEquals("No areas downloaded", SettingsSummary.offlineMaps(OfflineMapsUi(true, emptyList(), false)).text)
        assertEquals("Downloads not available in this build", SettingsSummary.offlineMaps(OfflineMapsUi(false, emptyList(), false)).text)
    }

    @Test fun backup() {
        fun b(last: Long, err: String? = null) = BackupUi("Documents/OnPath", last, 3, err, false)
        assertEquals(Summary("Last backup today 15:40"), SettingsSummary.backup(b(now - 40 * 60_000), now, utc))
        assertEquals(Summary("Last backup yesterday 16:20"), SettingsSummary.backup(b(now - 24 * 3_600_000), now, utc))
        assertEquals(Summary("Last backup 12 Oct 16:20"), SettingsSummary.backup(b(now - 10 * 24 * 3_600_000), now, utc))
        assertEquals(Summary("No backup yet"), SettingsSummary.backup(b(0), now, utc))
        assertEquals(Summary("Last backup failed", Summary.Attention.PROBLEM), SettingsSummary.backup(b(now, "storage full"), now, utc))
    }

    @Test fun updates() {
        val rel = AppUpdater.Release("0.10.1", "", "", 0, "")
        assertEquals(Summary("0.10.0 – up to date"), SettingsSummary.updates(AppViewModel.UpdateState.UpToDate, "0.10.0 (build 1000)"))
        assertEquals(Summary("Update available: 0.10.1", Summary.Attention.ACCENT),
            SettingsSummary.updates(AppViewModel.UpdateState.Available(rel), "0.10.0"))
        assertEquals(Summary.Attention.PROBLEM, SettingsSummary.updates(AppViewModel.UpdateState.Failed("x"), "0.10.0").attention)
        assertEquals(Summary("0.10.0"), SettingsSummary.updates(AppViewModel.UpdateState.Idle, "0.10.0"))
    }

    @Test fun diagnostics() {
        fun e(l: Problems.Level) = Problems.Entry(l, "a", "m${l.name}", null, 0, 0, 1)
        assertEquals(Summary("No problems"), SettingsSummary.diagnostics(emptyList()))
        assertEquals(Summary("2 problems"), SettingsSummary.diagnostics(listOf(e(Problems.Level.WARN), e(Problems.Level.INFO))))
        assertEquals(Summary("2 problems · 1 serious", Summary.Attention.PROBLEM),
            SettingsSummary.diagnostics(listOf(e(Problems.Level.WARN), e(Problems.Level.ERROR))))
    }
}
