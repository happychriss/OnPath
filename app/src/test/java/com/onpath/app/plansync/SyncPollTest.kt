package com.onpath.app.plansync

import com.onpath.app.sync.SyncRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core feature C16, sync while the app stays open (owner 2026-10-30, "like WhatsApp Web"): about every minute while
 * the app is on screen, paired, no tour runs and Plan / My routes / Browser sync is shown; back-off when the server is
 * not reached, reset on success.
 */
class SyncPollTest {
    private val min = 60_000L

    @Test fun pollsOnlyWhenVisiblePairedNoTourAndOnARoutesScreen() {
        assertTrue(SyncPoll.wanted(visible = true, paired = true, recording = false, routesScreen = true))
        assertFalse("app in the background: nothing", SyncPoll.wanted(visible = false, paired = true, recording = false, routesScreen = true))
        assertFalse("not paired: never contacts the server", SyncPoll.wanted(visible = true, paired = false, recording = false, routesScreen = true))
        assertFalse("never during a tour", SyncPoll.wanted(visible = true, paired = true, recording = true, routesScreen = true))
        assertFalse("another screen", SyncPoll.wanted(visible = true, paired = true, recording = false, routesScreen = false))
    }

    @Test fun routesScreens() {
        assertTrue("Plan mode", SyncPoll.routesScreen(planMode = true, routesOpen = false, settingsOpen = false, syncPage = false))
        assertTrue("My routes", SyncPoll.routesScreen(planMode = false, routesOpen = true, settingsOpen = false, syncPage = false))
        assertTrue("Settings → Browser sync", SyncPoll.routesScreen(planMode = true, routesOpen = false, settingsOpen = true, syncPage = true))
        assertFalse("Navigate", SyncPoll.routesScreen(planMode = false, routesOpen = false, settingsOpen = false, syncPage = false))
        assertFalse("another Settings page", SyncPoll.routesScreen(planMode = true, routesOpen = false, settingsOpen = true, syncPage = false))
    }

    @Test fun everyMinuteAfterTheLastPass() {
        val p = SyncPoll()
        assertEquals("nothing ran yet: due at once", 0L, p.waitMs(1_000_000))
        p.requested(1_000_000)
        assertEquals(min, p.waitMs(1_000_000))
        p.passEnded(1_002_000, ok = true) // the pass took 2 s
        assertEquals(min, p.waitMs(1_002_000))
        assertEquals(30_000L, p.waitMs(1_032_000))
        assertEquals(0L, p.waitMs(1_062_000))
        assertEquals(0L, p.waitMs(9_000_000))
        // another trigger (a saved route, back in the app) counts too: the next poll a minute after it
        p.passEnded(9_000_000, ok = true)
        assertEquals(min, p.waitMs(9_000_000))
    }

    @Test fun backOffDoublesUpToTenMinutesAndSuccessResets() {
        val p = SyncPoll()
        var t = 0L
        val seen = mutableListOf<Long>()
        repeat(6) { t += 1_000; p.passEnded(t, ok = false); seen += p.intervalMs }
        assertEquals(listOf(2 * min, 4 * min, 8 * min, 10 * min, 10 * min, 10 * min), seen)
        assertEquals(10 * min, p.waitMs(t))
        p.passEnded(t, ok = true)
        assertEquals(min, p.intervalMs)
        assertEquals(min, p.waitMs(t))
    }

    @Test fun aPassThatDidNotRunChangesNothing() {
        val p = SyncPoll()
        p.passEnded(100_000, ok = false)
        p.passEnded(500_000, ok = null) // not paired / during a tour: no answer from the server either way
        assertEquals(2 * min, p.intervalMs)
        assertEquals(2 * min - 50_000, p.waitMs(150_000))
    }

    @Test fun whatAReportMeans() {
        fun rep(o: SyncRound.Outcome?, error: Boolean = false) =
            PlanSync.Report(manual = false, result = if (o == null && !error) null else PlanSyncPass.Result(o), error = error)
        assertEquals(true, SyncPoll.ok(rep(SyncRound.Outcome.Done(0, 0, 0))))
        assertEquals(true, SyncPoll.ok(rep(SyncRound.Outcome.Ended)))
        assertEquals(false, SyncPoll.ok(rep(SyncRound.Outcome.Offline)))
        assertEquals(false, SyncPoll.ok(rep(SyncRound.Outcome.Refused(503, null))))
        assertEquals(false, SyncPoll.ok(PlanSync.Report(manual = false, result = null, error = true)))
        assertNull(SyncPoll.ok(rep(null)))
    }

    @Test fun anIdlePassStaysOutOfTheEventLog() {
        assertTrue(PlanSync.idle(PlanSyncPass.Result(SyncRound.Outcome.Done(0, 0, 0))))
        assertFalse(PlanSync.idle(PlanSyncPass.Result(SyncRound.Outcome.Done(1, 0, 0))))
        assertFalse(PlanSync.idle(PlanSyncPass.Result(SyncRound.Outcome.Done(0, 0, 0), added = 1, toursChanged = true)))
        assertFalse(PlanSync.idle(PlanSyncPass.Result(SyncRound.Outcome.Offline)))
        assertFalse(PlanSync.idle(null))
    }
}
