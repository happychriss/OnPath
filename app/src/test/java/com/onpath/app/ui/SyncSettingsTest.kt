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
}
