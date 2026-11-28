package com.onpath.app.ui

import com.onpath.app.poi.PoiGroup
import com.onpath.app.setup.ResetStatus
import com.onpath.app.ui.setup.checkWhy
import com.onpath.app.ui.setup.notOnThisPhone
import com.onpath.app.ui.setup.rowState
import java.io.File
import java.util.Locale
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.Felt
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.PhoneCheck
import com.onpath.app.data.AppSettings
import com.onpath.app.data.JunctionGuide
import com.onpath.app.debug.Problems
import com.onpath.app.plansync.BrowserLink
import com.onpath.app.plansync.PlanSync
import com.onpath.app.plansync.SyncProblem
import com.onpath.app.plansync.SyncState
import com.onpath.app.poi.PoiKinds
import com.onpath.app.routing.Activity
import com.onpath.app.setup.Check
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.LocationSaverMode
import com.onpath.app.setup.Need
import com.onpath.app.time.Pace
import com.onpath.app.update.AppUpdater
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.setup_bat_do
import com.onpath.strings.setup_loc_do
import com.onpath.strings.setup_loc_why
import com.onpath.strings.setup_notif_why
import com.onpath.strings.setup_saver_ok_why
import com.onpath.strings.setup_saver_why
import com.onpath.strings.setup_state_missing
import com.onpath.strings.setup_state_none
import com.onpath.strings.setup_state_ok
import com.onpath.strings.setup_state_untested
import com.onpath.strings.setup_vib_none
import com.onpath.strings.setup_vib_not_felt
import com.onpath.strings.setup_vib_sound_settings
import com.onpath.strings.setup_vib_why
import com.onpath.strings.setup_welcome_text
import com.onpath.strings.sync_card_off
import com.onpath.strings.sync_card_on
import com.onpath.strings.sync_paired_plan
import com.onpath.strings.sync_unpaired_plan
import com.onpath.strings.sync_unpaired_plan_offline
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings pages (converted from ProblemFoldTest, SettingRowsTest, SettingsOnTheWayTest, SettingsSummaryTest,
 * SyncSettingsTest, SetupTextsTest – test-audit G12): row order, one-line summaries per topic, the sync status line and
 * landing, the phone-setup "why" texts that never contradict their state, the diagnostics fold. Every check of the old
 * files is kept; the source pins no JVM test can replace stay inside developerTools.
 */
class SettingsPagesTest {
    // ---- from ProblemFoldTest.kt ----
    // Owner 2026-11-02: Settings → Diagnostics starts with the problem list folded to one line ("127 entries, 3 errors ▸"),
    // so the developer tools under it are reached without scrolling; a tap unfolds it. English like the whole page.
    @Test fun oneLineWithEntriesAndErrors() {
        assertEquals("127 entries, 3 errors ▸", ProblemFold.summary(127, 3, open = false))
        assertEquals("127 entries, 3 errors ▾", ProblemFold.summary(127, 3, open = true))
        assertEquals("1 entry, 1 error ▸", ProblemFold.summary(1, 1, open = false))
        assertEquals("5 entries ▸", ProblemFold.summary(5, 0, open = false))
        assertEquals("No problems noticed.", ProblemFold.summary(0, 0, open = false))
    }

    // ---- settings rows (owner 2026-11-06 23:05) and the topics on the way (owner 2026-11-07) ----
    // Settings in value rows (owner 2026-11-06 23:05): where "Handy einrichten" sits on the Navigation page, what it says
    // is missing, which test the one "Testen" runs, and the value a row shows.
    private fun c(id: CheckId, ok: Boolean, need: Need) = Check(id, ok, need)
    private val L = SettingRows.PhoneRow.LAST

    /** Where "Handy einrichten" sits on the Navigation page and what it says is missing: a table of check sets -> (row, missing). */
    @Test fun phoneRowPlaceAndMissingList() {
        val none = emptyList<CheckId>()
        val cases = listOf(
            "all set up: last plain row" to Triple(listOf(c(CheckId.LOCATION, true, Need.REQUIRED), c(CheckId.BATTERY, true, Need.RECOMMENDED)), L, none),
            "a missing required check: top in red" to Triple(listOf(c(CheckId.LOCATION, false, Need.REQUIRED), c(CheckId.BATTERY, false, Need.RECOMMENDED)),
                SettingRows.PhoneRow.TOP_PROBLEM, listOf(CheckId.LOCATION, CheckId.BATTERY)),
            "only a recommended one missing: top in amber" to Triple(listOf(c(CheckId.LOCATION, true, Need.REQUIRED), c(CheckId.VIBRATION, false, Need.RECOMMENDED)),
                SettingRows.PhoneRow.TOP_WARNING, listOf(CheckId.VIBRATION)),
            "an optional check never moves it nor is listed" to Triple(listOf(c(CheckId.BRAND, false, Need.OPTIONAL)), L, none),
        )
        for ((why, t) in cases) { assertEquals(why, t.second, SettingRows.phoneRow(t.first)); assertEquals(why, t.third, SettingRows.missing(t.first)) }
        assertEquals("no checks known keeps it last", L, SettingRows.phoneRow(null))
        assertEquals(none, SettingRows.missing(null))
        val mixed = listOf(c(CheckId.BATTERY, false, Need.RECOMMENDED), c(CheckId.LOCATION, false, Need.REQUIRED),
            c(CheckId.VIBRATION, true, Need.RECOMMENDED), c(CheckId.LOCATION, false, Need.REQUIRED))
        assertEquals("required first, each once", listOf(CheckId.LOCATION, CheckId.BATTERY), SettingRows.missing(mixed))
    }

    @Test fun theOneTestRunsTheChosenCueKind() {
        assertEquals(CheckId.VIBRATION, SettingRows.testFor(JunctionGuide.VIBRATION))
        assertEquals(CheckId.VOICE, SettingRows.testFor(JunctionGuide.VOICE))
        assertNull(SettingRows.testFor(JunctionGuide.OFF))
    }

    @Test fun theRowShowsTheChosenWordOrTheFirstForAnUnknownValue() {
        val options = listOf(25 to "25 m", 40 to "40 m", 75 to "75 m")
        assertEquals("40 m", SettingRows.valueOf(options, 40))
        assertEquals("25 m", SettingRows.valueOf(options, 33))
        assertEquals("", SettingRows.valueOf(emptyList<Pair<Int, String>>(), 1))
    }

    @Test fun aLongValueGoesUnderTheTitleInsteadOfBreakingAWord() {
        fun stacked(valueW: Float, wordW: Float) = SettingRows.valueStacked(contentW = 320f, valueW = valueW, titleLongestWordW = wordW, gap = 12f)
        assertEquals("normal font: \"Kartenausrichtung\" + \"In Bewegungsrichtung\" just fit side by side (~320 dp)", false, stacked(140f, 130f))
        assertEquals("large font: the value takes more than 45 % -> under the title", true, stacked(190f, 170f))
        assertEquals("a short value but a title word that would not fit beside it -> stacked", true, stacked(120f, 200f))
        assertEquals("short title, short value (\"Warnen nach · 40 m\") -> side by side", false, stacked(40f, 70f))
    }

    @Test fun `a settings group is one tile per row with a small gap between them (variant B)`() {
        assertEquals("three rows: each its own tile, 2 px apart", listOf(0 to 56, 58 to 114, 116 to 172),
            SettingRows.tileSpans(listOf(56, 56, 56), gap = 2))
        assertEquals("one row: one tile, no gap", listOf(0 to 48), SettingRows.tileSpans(listOf(48), gap = 2))
        assertEquals("no rows: no tiles", emptyList<Pair<Int, Int>>(), SettingRows.tileSpans(emptyList(), gap = 2))
    }

    /**
     * Navigate → ⚙ "on the way" (owner 2026-11-07): the Navigation page first, then only the topics that make sense while
     * riding, in a fixed order; a topic this phone does not show (e.g. Offline maps without the cell store) is left out;
     * nothing else of the full list (Backup, Sync, Updates, Diagnostics, Privacy, About, the Navigation page twice) appears.
     */
    @Test fun theWayTopicsInTheirOrderAndNothingElse() {
        val all = SettingsPage.entries.toList()
        // V1 (Chriss 2026-11-28 10:59): Karte · Interessante Orte; Dein Tempo and Offline-Karten via „Alle Einstellungen“
        assertEquals(listOf(SettingsPage.MAP, SettingsPage.POI), SettingsSummary.onTheWay(all))
        assertEquals(listOf(SettingsPage.MAP), SettingsSummary.onTheWay(all.filter { it != SettingsPage.POI }))
        val way = SettingsSummary.onTheWay(all)
        assertFalse(SettingsPage.NAVIGATION in way) // it is the list itself
        listOf(SettingsPage.BACKUP, SettingsPage.SYNC, SettingsPage.UPDATES, SettingsPage.DIAGNOSTICS, SettingsPage.PRIVACY,
            SettingsPage.ABOUT, SettingsPage.PHONE, SettingsPage.PACE, SettingsPage.OFFLINE_MAPS).forEach { assertFalse("$it", it in way) }
    }

    // ---- from SettingsSummaryTest.kt ----
    // One-line summaries of the Settings topics.
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
        val groups = PoiGroup.entries.count { PoiKinds.inGroup(it).isNotEmpty() }
        val firstGroup = PoiKinds.inGroup(PoiGroup.FOOD).map { it.key }.toSet()
        assertEquals("${groups - 1} of $groups groups shown", SettingsSummary.poi(firstGroup).text)
    }

    @Test fun map() {
        assertEquals("Contour lines on · Hill shading on · Direction of travel", SettingsSummary.map(AppSettings()).text)
        assertEquals("Contour lines off · Hill shading on · North up", SettingsSummary.map(AppSettings(contours = false, navHeadingUp = false)).text)
    }

    @Test fun navigation() {
        assertEquals("Warn after 40 m · vibration on", SettingsSummary.navigation(AppSettings()).text)
        assertEquals("Warn after 75 m · vibration off",
            SettingsSummary.navigation(AppSettings(offRouteM = 75, offRouteVibrate = false, junctionGuide = JunctionGuide.OFF)).text)
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
        fun e(last: Long) = Problems.Entry(Problems.Level.ERROR, "a", "m", null, 0, last, 1)
        assertEquals(Summary("0.10.0 – up to date · Credits, license"),
            SettingsSummary.aboutPage("0.10.0 (build 1000)", AppViewModel.UpdateState.UpToDate, listOf(e(100)), seenMs = 200))
        assertEquals(Summary("0.10.0 · Credits, license"), SettingsSummary.aboutPage("0.10.0 (build 1000)", null, null))
        assertEquals(Summary.Attention.PROBLEM, SettingsSummary.aboutPage("0.10.0", AppViewModel.UpdateState.UpToDate, listOf(e(300)), seenMs = 200).attention)
        val rel = AppUpdater.Release("0.10.1", "", "", 0, "")
        assertEquals(Summary("Update available: 0.10.1", Summary.Attention.ACCENT),
            SettingsSummary.aboutPage("0.10.0", AppViewModel.UpdateState.Available(rel), emptyList()))
    }

    /**
     * Phone setup (owner 2026-10-30): a tour need → red "n items needed for tours"; only recommended → amber "n recommended";
     * optional items never count; else "Everything set up".
     */
    @Test fun phoneSetupSummary() {
        val good = PhoneState(sdk = 35, location = LocationGrant.PRECISE,
            vibrationFelt = Felt.YES, unusedReset = ResetStatus.ON)
        assertEquals(Summary("Everything set up"), SettingsSummary.phone(PhoneCheck.all(good)))
        val two = SettingsSummary.phone(PhoneCheck.all(good.copy(notificationsOn = false, backgroundRestricted = true)))
        assertEquals(Summary("2 steps open", Summary.Attention.WARNING), two)
        val one = SettingsSummary.phone(PhoneCheck.all(good.copy(notificationsOn = false)))
        assertEquals(Summary("1 step open", Summary.Attention.WARNING), one)
        // location off + a recommended item: only the tour need is counted, red
        val off = SettingsSummary.phone(PhoneCheck.all(good.copy(locationOn = false, notificationsOn = false)))
        assertEquals(Summary("1 item needed for tours", Summary.Attention.PROBLEM), off)
        val approx = SettingsSummary.phone(PhoneCheck.all(
            good.copy(location = LocationGrant.APPROXIMATE, locationOn = false)))
        assertEquals(Summary("2 items needed for tours", Summary.Attention.PROBLEM), approx)
    }

    /**
     * Settings list order (owner 2026-11-08): Phone setup on top while any step is open – red for a tour need, amber
     * "N steps open" for recommended ones; back at its place in "App" when everything is done.
     */
    @Test fun phoneSetupPlaceInList() {
        val all = SettingsPage.entries.toList()
        val top = all.filter { it.top }
        val good = PhoneState(sdk = 35, location = LocationGrant.PRECISE,
            vibrationFelt = Felt.YES)
        fun groups(s: PhoneState?) = SettingsSummary.groups(all, s?.let { PhoneCheck.all(it) })
        val normal = listOf(TopicGroup.YOURS to top.filter { !it.app }, TopicGroup.APP to top.filter { it.app })

        // all fine / no phone checks at all: the usual two groups of the six
        assertEquals(normal, groups(good))
        assertEquals(normal, groups(null))
        assertEquals(false, SettingsSummary.phoneNeeded(PhoneCheck.all(good.copy(notificationsOn = false))))
        // a step open: Navigation (it holds Phone setup) on top, not in "Your settings"
        val onTop = listOf(TopicGroup.URGENT to listOf(SettingsPage.NAVIGATION),
            TopicGroup.YOURS to top.filter { !it.app && it != SettingsPage.NAVIGATION }, TopicGroup.APP to top.filter { it.app })
        assertEquals(onTop, groups(good.copy(notificationsOn = false, backgroundRestricted = true)))
        // a Samsung after „Später“ on the brand page: the one open step keeps it on top – the reminder lives here
        assertEquals(onTop, groups(good.copy(manufacturer = "samsung")))
        assertEquals(normal, groups(good.copy(manufacturer = "samsung", brandDone = true)))

        // a tour need (no location access): Navigation alone on top
        val noLoc = good.copy(location = LocationGrant.NONE)
        assertEquals(true, SettingsSummary.phoneNeeded(PhoneCheck.all(noLoc)))
        assertEquals(onTop, groups(noLoc))
    }

    /** The open steps (owner 2026-11-08): required + recommended not done; optional ones never count. */
    @Test fun openStepsCount() {
        val good = PhoneState(sdk = 35, location = LocationGrant.PRECISE,
            vibrationFelt = Felt.YES, unusedReset = ResetStatus.ON)
        fun open(s: PhoneState) = SettingsSummary.openSteps(PhoneCheck.all(s))
        assertEquals("only the optional unused-app reset is open", 0, open(good))
        assertEquals(1, open(good.copy(notificationsOn = false)))
        assertEquals(2, open(good.copy(notificationsOn = false, backgroundRestricted = true)))
        assertEquals("required counts too", 3, open(good.copy(locationOn = false, notificationsOn = false, backgroundRestricted = true)))
        assertEquals(1, open(good.copy(manufacturer = "samsung")))
        assertEquals(0, SettingsSummary.openSteps(null))
        assertEquals(false, SettingsSummary.phoneOnTop(PhoneCheck.all(good)))
        assertEquals(true, SettingsSummary.phoneOnTop(PhoneCheck.all(good.copy(notificationsOn = false))))
    }

    // ---- from SyncSettingsTest.kt ----
    // Settings → Browser sync: the list line and the status line of the last pass follow the Messages rules.
    @Test fun listLine() {
        assertEquals(Summary("Not connected · your plans in the web planner too"), SettingsSummary.sync(SyncUi()))
        // the browser card on the Settings list (owner 2026-11-28 11:14): one line – Not connected / Connected (amber with a problem)
        assertEquals(PairCardState.OFF, PairCardState.of(SyncUi()))
        assertEquals(PairCardState.OFF, PairCardState.of(SyncUi(paired = true, browser = com.onpath.app.plansync.BrowserLink.UNPAIRED)))
        assertEquals(PairCardState.ON, PairCardState.of(SyncUi(paired = true, peer = "Chrome on Linux")))
        assertEquals(PairCardState.ON, PairCardState.of(SyncUi(paired = true, problem = SyncProblem.Offline)))
        assertEquals(PairCardState.TROUBLE, PairCardState.of(SyncUi(paired = true, problem = SyncProblem.NewerApp(1))))
        assertEquals("Not connected", Txt.s(Res.string.sync_card_off))
        assertEquals("Connected", Txt.s(Res.string.sync_card_on))
        assertEquals(Summary("Connected to Chrome on Linux"), SettingsSummary.sync(SyncUi(paired = true, peer = "Chrome on Linux")))
        assertEquals(Summary("Connected to a browser"), SettingsSummary.sync(SyncUi(paired = true)))
        // offline is no problem worth red; a refused / unreadable / not saved pass is
        assertEquals(Summary.Attention.NONE, SettingsSummary.sync(SyncUi(paired = true, problem = SyncProblem.Offline)).attention)
        assertEquals(Summary.Attention.PROBLEM, SettingsSummary.sync(SyncUi(paired = true, problem = SyncProblem.NewerApp(1))).attention)
        // a scanned code waits for an answer
        assertEquals(Summary("Connect to this browser?", Summary.Attention.ACCENT), SettingsSummary.sync(SyncUi(askCheck = "395 514")))
    }

    /**
     * After Pair (owner 2026-10-30): a pairing made – the first one or another browser joining (A-017) – goes straight
     * back to the Plan screen with "Connected. …" + OK; already paired or a failure stays on the pairing step, no note.
     */
    @Test fun pairLanding() {
        val onSync = PairLanding.Screens(settingsOpen = true, settingsPage = SettingsPage.SYNC, routesOpen = false, note = null)
        val plan = PairLanding.Screens(settingsOpen = false, settingsPage = null, routesOpen = false, note = SyncNote.PAIRED)
        assertEquals(plan, PairLanding.after(PairResult.PAIRED, onSync))
        assertEquals(plan, PairLanding.after(PairResult.JOINED, onSync))
        // Settings opened over My routes: both close, the Plan map shows
        assertEquals(plan, PairLanding.after(PairResult.JOINED, onSync.copy(routesOpen = true)))
        // nothing made: stay where the user is, no note
        assertEquals(onSync, PairLanding.after(PairResult.ALREADY, onSync))
        assertEquals(onSync, PairLanding.after(PairResult.NOT_STORED, onSync))
        assertEquals(onSync, PairLanding.after(PairResult.JOIN_FAILED, onSync))
        // a failure never leaves an older note behind
        assertEquals(onSync, PairLanding.after(PairResult.JOIN_FAILED, onSync.copy(note = SyncNote.PAIRED)))
        assertEquals("Connected. What you save in the browser comes to your phone – everything is kept there.",
            Txt.s(Res.string.sync_paired_plan))
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("Verbunden. Was du im Browser speicherst, kommt auf dein Handy – dort bleibt alles gespeichert.",
                Txt.s(Res.string.sync_paired_plan))
        } finally { Locale.setDefault(before) }
    }

    /**
     * After Unpair (owner 2026-10-30: as simple as pairing): the one question, then straight back to the Plan screen
     * with "Disconnected. …" + OK; the server not reached → the same strip asks to unpair the browser too; the phone could
     * not forget the pairing → stay on Browser sync (its red line), no strip.
     */
    @Test fun unpairLanding() {
        val onSync = PairLanding.Screens(settingsOpen = true, settingsPage = SettingsPage.SYNC, routesOpen = false, note = null)
        fun plan(n: SyncNote) = PairLanding.Screens(settingsOpen = false, settingsPage = null, routesOpen = false, note = n)
        // the mailbox ended (or was gone already): Plan + "Disconnected. …"
        assertEquals(plan(SyncNote.UNPAIRED), PairLanding.afterUnpair(200, onSync))
        assertEquals(plan(SyncNote.UNPAIRED), PairLanding.afterUnpair(404, onSync))
        assertEquals(plan(SyncNote.UNPAIRED), PairLanding.afterUnpair(410, onSync.copy(routesOpen = true)))
        // offline (-1) or another answer: unpaired on the phone anyway – the strip with the extra sentence
        assertEquals(plan(SyncNote.UNPAIRED_OFFLINE), PairLanding.afterUnpair(-1, onSync))
        assertEquals(plan(SyncNote.UNPAIRED_OFFLINE), PairLanding.afterUnpair(503, onSync))
        // an older "Connected. …" never stays behind
        assertEquals(plan(SyncNote.UNPAIRED), PairLanding.afterUnpair(200, onSync.copy(note = SyncNote.PAIRED)))
        // not forgotten here: stay, no strip
        assertEquals(onSync, PairLanding.afterUnpair(PairLanding.UNPAIR_NOT_STORED, onSync.copy(note = SyncNote.PAIRED)))
        assertEquals("Disconnected. Your plans stay on your phone.", Txt.s(Res.string.sync_unpaired_plan))
        assertEquals("Disconnected. Your plans stay on your phone – the browser couldn't be reached, disconnect it there too.",
            Txt.s(Res.string.sync_unpaired_plan_offline))
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("Getrennt. Deine Pläne bleiben auf dem Handy.", Txt.s(Res.string.sync_unpaired_plan))
            assertEquals("Getrennt. Deine Pläne bleiben auf dem Handy – der Browser war nicht erreichbar, trenne ihn dort auch.",
                Txt.s(Res.string.sync_unpaired_plan_offline))
        } finally { Locale.setDefault(before) }
    }

    /** The joining spinner shows only while a code waits (its Pair was tapped). */
    @Test fun joiningOnlyWithACode() {
        val paired = SyncState(paired = true, peer = "Chrome on Linux")
        assertEquals(SyncUi(paired = true, peer = "Chrome on Linux"), SyncUi.of(paired, askCheck = null, joining = true))
        assertTrue(SyncUi.of(paired, askCheck = "395 514", joining = true).joining)
    }

    @Test fun statusLines() {
        // INFO grey for offline, WARNING amber (null) for the server or a newer app, ERROR red for the phone's storage
        assertEquals(NoticeKind.INFO, syncProblemText(SyncProblem.Offline).second)
        assertNull(syncProblemText(SyncProblem.Refused(503, "sync is not set up on this server")).second)
        assertTrue(syncProblemText(SyncProblem.Refused(503, "x")).first.contains("HTTP 503"))
        assertEquals("The browser sync is full – delete some plans or tracks, then try again.",
            syncProblemText(SyncProblem.Refused(507, "too many saved plans")).first)
        assertEquals("1 synced plan comes from a newer app – update OnPath to see it.", syncProblemText(SyncProblem.NewerApp(1)).first)
        assertEquals(NoticeKind.ERROR, syncProblemText(SyncProblem.Storage).second)
    }

    /** "Last synced: …" (owner 2026-10-30, no Sync now): relative while recent, then the time. */
    @Test fun lastSyncedAgo() {
        val now = 1_761_150_000_000L // 22 Oct 2025, 16:20 UTC
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("just now", syncAgo(now - 20_000, now))
            assertEquals("just now", syncAgo(now + 5_000, now)) // a clock step back is no "-1 min"
            assertEquals("1 min ago", syncAgo(now - 60_000, now))
            assertEquals("59 min ago", syncAgo(now - 59 * 60_000 - 59_000, now))
            assertEquals(SettingsSummary.whenText(now - 60 * 60_000, now), syncAgo(now - 60 * 60_000, now))
            val before = Locale.getDefault()
            Locale.setDefault(Locale.GERMANY)
            try {
                assertEquals("vor 1 min", syncAgo(now - 90_000, now))
                assertEquals("gerade eben", syncAgo(now, now))
            } finally { Locale.setDefault(before) }
        } finally { TimeZone.setDefault(zone) }
    }

    /** The ☰ item and its amber dot (owner 2026-10-30): only for a problem worth a look – never for plain offline. */
    @Test fun menuDotRule() {
        val now = 1_761_150_000_000L
        val h = 3_600_000L
        val ok = SyncState(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 48 * h, checkedMs = now - 59_990)
        assertNull(SyncMenuUi.attention(ok))
        assertNull("not paired", SyncMenuUi.attention(SyncState()))
        assertNull("offline is never a dot", SyncMenuUi.attention(ok.copy(problem = SyncProblem.Offline, lastOkMs = now - 30 * h, checkedMs = now)))
        assertEquals(SyncAttention.FULL, SyncMenuUi.attention(ok.copy(problem = SyncProblem.Refused(507, "full"))))
        assertEquals(SyncAttention.REFUSED, SyncMenuUi.attention(ok.copy(problem = SyncProblem.Refused(503, null))))
        assertEquals(SyncAttention.STORAGE, SyncMenuUi.attention(ok.copy(problem = SyncProblem.Storage)))
        assertEquals(SyncAttention.ENDED, SyncMenuUi.attention(SyncState(ended = true)))
        // the last pass (online) ended > 6 h after the last good one: a look
        val stale = ok.copy(lastOkMs = now - 7 * h, checkedMs = now)
        assertEquals(SyncAttention.STALE, SyncMenuUi.attention(stale))
        assertNull("6 h are not yet over", SyncMenuUi.attention(stale.copy(lastOkMs = now - 5 * h)))
        assertNull("no pass since the good one 7 h ago (background, a tour, another screen)",
            SyncMenuUi.attention(ok.copy(lastOkMs = now - 7 * h, checkedMs = now - 7 * h + 10)))
        assertNull("no pass yet in this run of the app", SyncMenuUi.attention(ok.copy(lastOkMs = now - 7 * h, checkedMs = 0)))
        assertNull("paired 1 h ago, nothing good yet: wait", SyncMenuUi.attention(ok.copy(lastOkMs = 0, sinceMs = now - h, checkedMs = now)))
        assertEquals(SyncAttention.STALE, SyncMenuUi.attention(ok.copy(lastOkMs = 0, sinceMs = now - 7 * h, checkedMs = now)))
        // newer app / not saved: the Settings page says it, no dot (owner's list)
        assertNull(SyncMenuUi.attention(ok.copy(problem = SyncProblem.NewerApp(1))))
    }

    @Test fun menuItemLines() {
        val now = 1_761_150_000_000L
        val ok = SyncState(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 86_400_000, checkedMs = now - 60_000)
        assertEquals(SyncMenuUi(true, "Pixel 8 ↔ Chrome on Linux · synced 1 min ago", null), SyncMenuUi.of(ok, "Pixel 8", now))
        assertEquals("Pixel 8 ↔ browser", SyncMenuUi.of(ok.copy(peer = null, lastOkMs = 0, checkedMs = 0), "Pixel 8", now).line)
        assertEquals(SyncMenuUi(true, "Browser sync is full", SyncAttention.FULL),
            SyncMenuUi.of(ok.copy(problem = SyncProblem.Refused(507, null)), "Pixel 8", now))
        assertEquals(SyncMenuUi(false, "Connection ended in the browser", SyncAttention.ENDED), SyncMenuUi.of(SyncState(ended = true), "Pixel 8", now))
        assertEquals(SyncMenuUi(), SyncMenuUi.of(SyncState(), "Pixel 8", now))
    }

    /**
     * Field bug 2026-10-31: the browser unpaired itself, the phone still has its pairing. ☰ reads "Connect to browser"
     * again with the grey "Disconnected in the browser" – no amber, no dot (the user's own action), and no "1 min ago" of
     * the phone's own pass as if the browser was seen. Just paired, no browser hello yet: [waitingForTheBrowser].
     */
    @Test fun unpairedInTheBrowser() {
        val now = 1_761_150_000_000L
        val ok = SyncState(paired = true, peer = null, lastOkMs = now - 60_000, sinceMs = now - 86_400_000, checkedMs = now - 60_000)
        val gone = ok.copy(browser = BrowserLink.UNPAIRED)
        assertEquals(SyncMenuUi(false, "Disconnected in the browser", null), SyncMenuUi.of(gone, "Pixel 8", now))
        assertNull(SyncMenuUi.attention(gone))
        // a real problem still wins (amber, the page)
        assertEquals(SyncAttention.FULL, SyncMenuUi.of(gone.copy(problem = SyncProblem.Refused(507, null)), "Pixel 8", now).warning)
        // Settings list line + the page's state
        val ui = SyncUi.of(gone, askCheck = null)
        assertTrue(ui.unpairedInBrowser)
        assertEquals(Summary("Disconnected in the browser"), SettingsSummary.sync(ui))
        assertEquals(Summary("Disconnected in the browser"), SettingsSummary.sync(ui.copy(problem = SyncProblem.Offline)))
        assertEquals(false, SyncUi.of(ok.copy(browser = BrowserLink.WAITING), null).unpairedInBrowser)
        assertEquals(false, SyncUi.of(SyncState(browser = BrowserLink.UNPAIRED), null).unpairedInBrowser)
        // German
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            assertEquals("Im Browser getrennt", SyncMenuUi.of(gone, "Pixel 8", now).line)
            assertEquals("Pixel 8 ↔ Chrome · abgeglichen vor 1 min", SyncMenuUi.of(ok.copy(peer = "Chrome"), "Pixel 8", now).line)
        } finally { Locale.setDefault(before) }
    }

    /**
     * Field bug 2026-10-31 (owner: "the phone says paired and the planner says nothing"): until the scanned browser's
     * hello is seen, ☰ and Browser sync say "Waiting for the browser …" – no ✓, never "connected"; while a tour records
     * the routes wait for its end: grey "Routes sync after the tour". Grey (no dot), EN + DE.
     */
    @Test fun waitingForTheBrowser() {
        val now = 1_761_150_000_000L
        val ok = SyncState(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 86_400_000, checkedMs = now - 60_000)
        val waiting = ok.copy(browser = BrowserLink.WAITING)
        assertEquals(SyncMenuUi(true, "Waiting for the browser …", null, waiting = true), SyncMenuUi.of(waiting, "Pixel 8", now))
        assertEquals(SyncMenuUi(true, "Waiting for the browser …", null, waiting = true),
            SyncMenuUi.of(waiting.copy(afterTour = true), "Pixel 8", now))
        assertNull(SyncMenuUi.attention(waiting))
        // a real problem still wins (amber)
        assertEquals(SyncAttention.FULL, SyncMenuUi.of(waiting.copy(problem = SyncProblem.Refused(507, null)), "Pixel 8", now).warning)
        // connected during a tour: the routes come after it
        assertEquals(SyncMenuUi(true, "Plans sync after the tour", null), SyncMenuUi.of(ok.copy(afterTour = true), "Pixel 8", now))
        // Settings: list line + the page's state
        val ui = SyncUi.of(waiting.copy(afterTour = true), askCheck = null)
        assertTrue(ui.waiting); assertTrue(ui.afterTour)
        assertEquals(Summary("Waiting for the browser …"), SettingsSummary.sync(ui))
        assertEquals(Summary("Waiting for the browser …"), SettingsSummary.sync(ui.copy(problem = SyncProblem.Offline)))
        assertEquals(false, SyncUi.of(ok, null).waiting)
        assertEquals(false, SyncUi.of(SyncState(afterTour = true), null).afterTour)
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMAN)
            assertEquals("Wartet auf den Browser …", SyncMenuUi.of(waiting, "Pixel 8", now).line)
            assertEquals("Pläne gleichen sich nach der Tour ab", SyncMenuUi.of(ok.copy(afterTour = true), "Pixel 8", now).line)
        } finally { Locale.setDefault(before) }
    }

    /**
     * The ☰ item is one plain line (owner 2026-11-07): "Browser-Verbindung" while paired here (also waiting for the browser
     * or with a problem), "Mit Browser verbinden" when not paired or the browser unpaired itself; the state only in the icon.
     */
    @Test fun menuLabelIsOnePlainLine() {
        val now = 1_000_000_000_000L
        val ok = SyncState(paired = true, peer = "Chrome", lastOkMs = now - 60_000, checkedMs = now, sinceMs = now - 3_600_000)
        assertTrue(SyncMenuUi.of(ok, "Pixel 8", now).labelPaired)
        assertTrue(SyncMenuUi.of(ok.copy(browser = BrowserLink.WAITING), "Pixel 8", now).labelPaired)
        assertTrue(SyncMenuUi.of(ok.copy(problem = SyncProblem.Storage), "Pixel 8", now).labelPaired)
        assertEquals(false, SyncMenuUi.of(SyncState(), "Pixel 8", now).labelPaired)
        assertEquals(false, SyncMenuUi.of(ok.copy(browser = BrowserLink.UNPAIRED), "Pixel 8", now).labelPaired)
        // the pairing ended in the browser: "pair" again, but amber (the reason is read out, not shown)
        val ended = SyncMenuUi.of(SyncState(ended = true), "Pixel 8", now)
        assertEquals(false, ended.labelPaired)
        assertEquals(SyncAttention.ENDED, ended.warning)
    }

    /** "Verbunden seit …" (owner 2026-11-07): today with its time, yesterday with its time, else whole calendar days. */
    @Test fun pairedSince() {
        val zone = TimeZone.getTimeZone("Europe/Berlin")
        fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone.toZoneId()).toInstant().toEpochMilli()
        val now = at(7, 10, 12)
        assertNull(SyncStatusText.since(0, now, zone))
        assertEquals(SyncStatusText.Since.Today("09:12"), SyncStatusText.since(at(7, 9, 12), now, zone))
        assertEquals(SyncStatusText.Since.Today("00:05"), SyncStatusText.since(at(7, 0, 5), now, zone))
        assertEquals(SyncStatusText.Since.Yesterday("23:50"), SyncStatusText.since(at(6, 23, 50), now, zone))
        assertEquals(SyncStatusText.Since.Days(3), SyncStatusText.since(at(4, 18, 0), now, zone))
        // a clock that went back: never "in the future" – today
        assertEquals(SyncStatusText.Since.Today("11:00"), SyncStatusText.since(at(7, 11, 0), now, zone))
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("Verbunden seit heute 09:12", SyncStatusText.sinceText(SyncStatusText.Since.Today("09:12")))
            assertEquals("Verbunden seit gestern 23:50", SyncStatusText.sinceText(SyncStatusText.Since.Yesterday("23:50")))
            assertEquals("Verbunden seit 1 Tag", SyncStatusText.sinceText(SyncStatusText.Since.Days(1)))
            assertEquals("Verbunden seit 3 Tagen", SyncStatusText.sinceText(SyncStatusText.Since.Days(3)))
            // the last pass: a clock time today, "noch nie" before the first one
            assertEquals("10:02", SyncStatusText.last(at(7, 10, 2), now, zone))
            assertEquals("noch nie", SyncStatusText.last(0, now, zone))
            assertTrue(SyncStatusText.last(at(6, 9, 12), now, zone).startsWith("gestern"))
        } finally { Locale.setDefault(before) }
        assertEquals("plan.onpath.earth", SyncStatusText.plannerHost)
    }

    // ---- from SetupTextsTest.kt ----
    // Settings → Phone setup shows the same "why" text next to ✓, ⚠ and ✗, so a text must never contradict its state
    // (owner, 2026-10-30): battery saver has its own ✓ text, a phone without a vibrator gets "Not on this phone", not "Set up".
    private val phone = PhoneState(sdk = 35, manufacturer = "Google", location = LocationGrant.PRECISE, vibrationFelt = Felt.YES)

    private fun check(id: CheckId, p: PhoneState) = PhoneCheck.all(p).first { it.id == id }

    @Test fun batterySaverThatKeepsGpsSaysSoInsteadOfTheWarning() {
        val keeps = phone.copy(powerSave = true, locationSaverMode = LocationSaverMode.THROTTLE_SCREEN_OFF)
        assertTrue(check(CheckId.BATTERY_SAVER, keeps).ok)
        assertEquals(Txt.s(Res.string.setup_saver_ok_why), checkWhy(CheckId.BATTERY_SAVER, keeps))

        val stops = phone.copy(powerSave = true, locationSaverMode = LocationSaverMode.GPS_OFF_SCREEN_OFF)
        assertFalse(check(CheckId.BATTERY_SAVER, stops).ok)
        assertEquals(Txt.s(Res.string.setup_saver_why), checkWhy(CheckId.BATTERY_SAVER, stops))
    }

    @Test fun phoneWithoutVibratorIsNeutralNotSetUp() {
        val none = phone.copy(hasVibrator = false, vibrationFelt = Felt.UNKNOWN)
        val c = check(CheckId.VIBRATION, none)
        assertTrue("nothing to do – never a step", c.ok)
        assertTrue(notOnThisPhone(c, none))
        assertEquals(Res.string.setup_state_none, rowState(c, none))
        assertEquals(Txt.s(Res.string.setup_vib_none), checkWhy(CheckId.VIBRATION, none))
    }

    @Test fun vibrationRowStatesWithAVibrator() {
        fun state(felt: Felt) = phone.copy(vibrationFelt = felt).let { p -> check(CheckId.VIBRATION, p).let { assertFalse(notOnThisPhone(it, p)); rowState(it, p) } }
        assertEquals(Res.string.setup_state_ok, state(Felt.YES))
        assertEquals(Res.string.setup_state_untested, state(Felt.UNKNOWN))
        assertEquals(Res.string.setup_state_missing, state(Felt.NO))
        assertEquals(Txt.s(Res.string.setup_vib_why), checkWhy(CheckId.VIBRATION, phone))
    }

    /**
     * The short onboarding texts the owner approved on 2026-11-08: battery names only "Unrestricted", notifications no
     * longer mention a full storage, "vibration not felt" points to the button under it, location starts with the dot.
     */
    @Test fun approvedShortTextsInBothLanguages() {
        fun texts() {
            val bat = Txt.s(Res.string.setup_bat_do)
            assertFalse(bat, bat.contains("Optimi"))
            val notif = Txt.s(Res.string.setup_notif_why)
            assertFalse(notif, notif.contains("Speicher") || notif.contains("storage"))
            assertTrue(Txt.s(Res.string.setup_vib_not_felt).contains(Txt.s(Res.string.setup_vib_sound_settings)))
        }
        texts()
        assertTrue(Txt.s(Res.string.setup_bat_do).contains("Unrestricted"))
        // owner-chosen words (2026-11-08 evening): the location step names Android's choice; the welcome has no "or later"
        assertEquals("For the dot on the map and your track. Your location and tours stay on your phone.", Txt.s(Res.string.setup_loc_why))
        assertEquals("On the next screen: choose “Precise” and “While using the app”.", Txt.s(Res.string.setup_loc_do))
        assertFalse(Txt.s(Res.string.setup_welcome_text).contains("later"))
        val old = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            texts()
            assertTrue(Txt.s(Res.string.setup_bat_do).contains("„Uneingeschränkt“"))
            assertEquals("Für den Punkt auf der Karte und deine Spur. Standort und Touren bleiben auf deinem Handy.", Txt.s(Res.string.setup_loc_why))
            assertEquals("Auf der nächsten Seite: „Genau“ und „Bei Nutzung der App“ wählen.", Txt.s(Res.string.setup_loc_do))
            assertEquals("OnPath zeigt dir die Karte auch ohne Netz und führt dich unterwegs. Dafür braucht OnPath ein paar " +
                "Einstellungen am Handy – etwa eine Minute.", Txt.s(Res.string.setup_welcome_text))
        } finally { Locale.setDefault(old) }
    }
}
