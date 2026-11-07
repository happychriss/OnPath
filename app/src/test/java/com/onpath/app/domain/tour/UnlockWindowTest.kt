package com.onpath.app.domain.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.onpath.app.domain.tour.UnlockWindow.BarButton as B

/** One hold unlocks the bar for 20 s (owner 2026-11-07 09:05) – [UnlockWindow]. */
class UnlockWindowTest {
    private val t0 = 1_000_000L

    @Test fun shutAtTheStartAndATapNeverUnlocks() {
        assertTrue(UnlockWindow.locked(lockOn = true, until = UnlockWindow.LOCKED, now = t0))
        assertEquals("a tap on a shut bar keeps it shut", UnlockWindow.LOCKED, UnlockWindow.afterTap(UnlockWindow.LOCKED, t0))
    }

    @Test fun oneHoldOpensTheBarForTwentySeconds() {
        val until = UnlockWindow.afterFire(t0)
        assertEquals(t0 + 20_000, until)
        assertFalse("right after the hold: plain taps", UnlockWindow.locked(true, until, t0 + 1))
        assertFalse("19.9 s later still open", UnlockWindow.locked(true, until, t0 + 19_999))
        assertTrue("20 s idle: locked again", UnlockWindow.locked(true, until, t0 + 20_000))
    }

    @Test fun everyTapStartsTheWindowAgain() {
        var until = UnlockWindow.afterFire(t0)
        until = UnlockWindow.afterTap(until, t0 + 15_000) // Weiter after 15 s
        assertFalse("open 30 s after the hold", UnlockWindow.locked(true, until, t0 + 30_000))
        until = UnlockWindow.afterTap(until, t0 + 34_000) // Beenden 19 s later
        assertFalse(UnlockWindow.locked(true, until, t0 + 53_999))
        assertTrue("then 20 s idle: shut", UnlockWindow.locked(true, until, t0 + 54_000))
        assertEquals("a tap after it shut does not open it", until, UnlockWindow.afterTap(until, t0 + 60_000))
    }

    @Test fun screenOffOrPocketShutsItAtOnce() {
        // owner 2026-11-07 10:45: "Pause drücken, dann in die Tasche – danach soll wieder gesperrt sein"
        val until = UnlockWindow.afterFire(t0)                // held Pause: open for 20 s
        assertFalse(UnlockWindow.locked(true, until, t0 + 1_000))
        val shut = UnlockWindow.afterScreenOff()              // 2 s later into the pocket / power button / background
        assertTrue("locked although 18 s of the window were left", UnlockWindow.locked(true, shut, t0 + 2_000))
        assertTrue("every button held again", UnlockWindow.needsHold(B.END, lockOn = true,
            windowOpen = UnlockWindow.isOpen(shut, t0 + 2_000)))
        assertEquals("a tap after it does not open it again", shut, UnlockWindow.afterTap(shut, t0 + 3_000))
    }

    @Test fun holdTimeAusHasNothingToUnlock() {
        assertFalse("Aus: never held buttons", UnlockWindow.locked(lockOn = false, until = UnlockWindow.LOCKED, now = t0))
    }

    @Test fun weiterAlwaysNeedsAHoldTheOthersOnlyWhileShut() {
        // shut: every button is held
        for (b in B.entries) assertTrue("$b held while shut", UnlockWindow.needsHold(b, lockOn = true, windowOpen = false))
        // open (one hold fired): Pause, Planen, Beenden on a tap – Weiter still held (owner 2026-11-07 10:15)
        assertFalse(UnlockWindow.needsHold(B.PAUSE, lockOn = true, windowOpen = true))
        assertFalse(UnlockWindow.needsHold(B.PLAN, lockOn = true, windowOpen = true))
        assertFalse(UnlockWindow.needsHold(B.END, lockOn = true, windowOpen = true))
        assertTrue("a stray tap never resumes", UnlockWindow.needsHold(B.RESUME, lockOn = true, windowOpen = true))
        // Aus: nothing is held
        for (b in B.entries) for (open in listOf(false, true))
            assertFalse("$b with the lock off", UnlockWindow.needsHold(b, lockOn = false, windowOpen = open))
    }

    @Test fun testsCanShortenTheWindow() {
        try {
            UnlockWindow.openMsForTests = 1_500
            assertEquals(t0 + 1_500, UnlockWindow.afterFire(t0))
        } finally { UnlockWindow.openMsForTests = null }
        assertEquals(t0 + UnlockWindow.OPEN_MS, UnlockWindow.afterFire(t0))
    }
}
