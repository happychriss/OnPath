package com.onpath.app.ui

import com.onpath.app.plansync.PlanSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings → Browser sync: the list line and the status line of the last pass follow the Messages rules. */
class SyncSettingsTest {
    @Test fun listLine() {
        assertEquals(Summary("Not paired · your routes in the web planner too"), SettingsSummary.sync(SyncUi()))
        assertEquals(Summary("Paired with Chrome on Linux"), SettingsSummary.sync(SyncUi(paired = true, peer = "Chrome on Linux")))
        assertEquals(Summary("Paired with a browser"), SettingsSummary.sync(SyncUi(paired = true)))
        // offline is no problem worth red; a refused / unreadable / not saved pass is
        assertEquals(Summary.Attention.NONE, SettingsSummary.sync(SyncUi(paired = true, problem = PlanSync.Problem.Offline)).attention)
        assertEquals(Summary.Attention.PROBLEM, SettingsSummary.sync(SyncUi(paired = true, problem = PlanSync.Problem.NewerApp(1))).attention)
        // a scanned code waits for an answer
        assertEquals(Summary("Pair with this browser?", Summary.Attention.ACCENT), SettingsSummary.sync(SyncUi(askCheck = "395 514")))
    }

    @Test fun statusLines() {
        // INFO grey for offline, WARNING amber (null) for the server or a newer app, ERROR red for the phone's storage
        assertEquals(NoticeKind.INFO, syncProblemText(PlanSync.Problem.Offline).second)
        assertNull(syncProblemText(PlanSync.Problem.Refused(503, "sync is not set up on this server")).second)
        assertTrue(syncProblemText(PlanSync.Problem.Refused(503, "x")).first.contains("HTTP 503"))
        assertEquals("The browser sync is full – delete some routes or tracks, then try again.",
            syncProblemText(PlanSync.Problem.Refused(507, "too many saved plans")).first)
        assertEquals("1 synced route comes from a newer app – update OnPath to see it.", syncProblemText(PlanSync.Problem.NewerApp(1)).first)
        assertEquals(NoticeKind.ERROR, syncProblemText(PlanSync.Problem.Storage).second)
    }

    /** "Last synced: …" (owner 2026-10-30, no Sync now): relative while recent, then the time. */
    @Test fun lastSyncedAgo() {
        val now = 1_761_150_000_000L // 22 Oct 2025, 16:20 UTC
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("just now", syncAgo(now - 20_000, now))
            assertEquals("just now", syncAgo(now + 5_000, now)) // a clock step back is no "-1 min"
            assertEquals("1 min ago", syncAgo(now - 60_000, now))
            assertEquals("59 min ago", syncAgo(now - 59 * 60_000 - 59_000, now))
            assertEquals(SettingsSummary.whenText(now - 60 * 60_000, now), syncAgo(now - 60 * 60_000, now))
            val before = java.util.Locale.getDefault()
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            try {
                assertEquals("vor 1 min", syncAgo(now - 90_000, now))
                assertEquals("gerade eben", syncAgo(now, now))
            } finally { java.util.Locale.setDefault(before) }
        } finally { java.util.TimeZone.setDefault(zone) }
    }

    /** The ☰ item and its amber dot (owner 2026-10-30): only for a problem worth a look – never for plain offline. */
    @Test fun menuDotRule() {
        val now = 1_761_150_000_000L
        val h = 3_600_000L
        val ok = PlanSync.State(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 48 * h, checkedMs = now - 59_990)
        assertNull(SyncMenuUi.attention(ok))
        assertNull("not paired", SyncMenuUi.attention(PlanSync.State()))
        assertNull("offline is never a dot", SyncMenuUi.attention(ok.copy(problem = PlanSync.Problem.Offline, lastOkMs = now - 30 * h, checkedMs = now)))
        assertEquals(SyncAttention.FULL, SyncMenuUi.attention(ok.copy(problem = PlanSync.Problem.Refused(507, "full"))))
        assertEquals(SyncAttention.REFUSED, SyncMenuUi.attention(ok.copy(problem = PlanSync.Problem.Refused(503, null))))
        assertEquals(SyncAttention.STORAGE, SyncMenuUi.attention(ok.copy(problem = PlanSync.Problem.Storage)))
        assertEquals(SyncAttention.ENDED, SyncMenuUi.attention(PlanSync.State(ended = true)))
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
        assertNull(SyncMenuUi.attention(ok.copy(problem = PlanSync.Problem.NewerApp(1))))
    }

    @Test fun menuItemLines() {
        val now = 1_761_150_000_000L
        val ok = PlanSync.State(paired = true, peer = "Chrome on Linux", lastOkMs = now - 60_000, sinceMs = now - 86_400_000, checkedMs = now - 60_000)
        assertEquals(SyncMenuUi(true, "Pixel 8 ↔ Chrome on Linux · 1 min ago", null), SyncMenuUi.of(ok, "Pixel 8", now))
        assertEquals("Pixel 8 ↔ browser", SyncMenuUi.of(ok.copy(peer = null, lastOkMs = 0, checkedMs = 0), "Pixel 8", now).line)
        assertEquals(SyncMenuUi(true, "Browser sync is full", SyncAttention.FULL),
            SyncMenuUi.of(ok.copy(problem = PlanSync.Problem.Refused(507, null)), "Pixel 8", now))
        assertEquals(SyncMenuUi(false, "Pairing ended in the browser", SyncAttention.ENDED), SyncMenuUi.of(PlanSync.State(ended = true), "Pixel 8", now))
        assertEquals(SyncMenuUi(), SyncMenuUi.of(PlanSync.State(), "Pixel 8", now))
    }
}
