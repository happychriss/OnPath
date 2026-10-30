package com.onpath.app.plansync

import com.onpath.app.plansync.SyncPoll.Shown as S
import com.onpath.app.sync.SyncRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core feature C16, sync while the app stays open (owner 2026-10-30, "like WhatsApp Web"): about every minute while
 * the app is on screen, paired, no tour runs and My routes / Browser sync is shown (not the Plan map); back-off when the server is
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
        assertTrue("My routes", SyncPoll.routesScreen(routesOpen = true, settingsOpen = false, syncPage = false))
        assertTrue("Settings → Browser sync", SyncPoll.routesScreen(routesOpen = false, settingsOpen = true, syncPage = true))
        assertFalse("the Plan map / Navigate: no poll (owner 2026-10-30, the pass on open covers it)",
            SyncPoll.routesScreen(routesOpen = false, settingsOpen = false, syncPage = false))
        assertFalse("another Settings page", SyncPoll.routesScreen(routesOpen = false, settingsOpen = true, syncPage = false))
        assertFalse("another Settings page over My routes", SyncPoll.routesScreen(routesOpen = true, settingsOpen = true, syncPage = false))
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

    @Test fun myRoutesOrBrowserSyncOpenedPullsRightAway() {
        fun shown(visible: Boolean = true, routes: Boolean = false, planned: Boolean = true, settings: Boolean = false, sync: Boolean = false) =
            SyncPoll.shown(visible, routes, planned, settings, sync)
        assertEquals(S.ROUTES_PLANNED, shown(routes = true))
        assertEquals(S.ROUTES_DONE, shown(routes = true, planned = false))
        assertEquals(S.SYNC_PAGE, shown(settings = true, sync = true))
        assertEquals("Settings over My routes", S.OTHER, shown(routes = true, settings = true))
        assertEquals("app not on screen", S.OTHER, shown(visible = false, routes = true))
        assertEquals("map / Navigate", S.OTHER, shown())

        assertEquals("my routes opened", SyncPoll.opened(S.OTHER, S.ROUTES_PLANNED))
        assertEquals("opened on the Done tab", "my routes opened", SyncPoll.opened(S.OTHER, S.ROUTES_DONE))
        assertEquals("planned tab", SyncPoll.opened(S.ROUTES_DONE, S.ROUTES_PLANNED))
        assertEquals("browser sync opened", SyncPoll.opened(S.OTHER, S.SYNC_PAGE))
        assertEquals("Settings → Browser sync over My routes", "browser sync opened", SyncPoll.opened(S.ROUTES_PLANNED, S.SYNC_PAGE))
        assertEquals("back from Settings to My routes", "my routes opened", SyncPoll.opened(S.SYNC_PAGE, S.ROUTES_PLANNED))
        assertNull("Planned → Done", SyncPoll.opened(S.ROUTES_PLANNED, S.ROUTES_DONE))
        assertNull("left", SyncPoll.opened(S.ROUTES_PLANNED, S.OTHER))
        assertNull("left", SyncPoll.opened(S.SYNC_PAGE, S.OTHER))
        assertNull("no change", SyncPoll.opened(S.ROUTES_PLANNED, S.ROUTES_PLANNED))
    }

    @Test fun passOnOpenIsDebouncedAndNeverDuringATour() {
        val p = SyncPoll()
        assertTrue("nothing ran yet", p.openDue(1_000, paired = true, recording = false, busy = false))
        assertFalse("not paired", p.openDue(1_000, paired = false, recording = false, busy = false))
        assertFalse("never during a tour", p.openDue(1_000, paired = true, recording = true, busy = false))
        assertFalse("a pass runs or waits", p.openDue(1_000, paired = true, recording = false, busy = true))
        p.requested(100_000) // e.g. the pass on open itself
        assertFalse("asked for 5 s ago", p.openDue(105_000, paired = true, recording = false, busy = false))
        p.passEnded(102_000, ok = true)
        assertFalse("ended 9 s ago", p.openDue(111_000, paired = true, recording = false, busy = false))
        assertTrue("ended 10 s ago", p.openDue(112_000, paired = true, recording = false, busy = false))
        p.passEnded(200_000, ok = false) // offline: the open still tries again after 10 s
        assertTrue(p.openDue(210_000, paired = true, recording = false, busy = false))
        // the pass on open counts for the poll: the next one a minute later
        p.passEnded(300_000, ok = true)
        p.requested(400_000)
        assertEquals(min, p.waitMs(400_000))
    }
}
