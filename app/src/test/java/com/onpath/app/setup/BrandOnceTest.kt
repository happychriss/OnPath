package com.onpath.app.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-08 (father's feedback, Samsung): the brand page popped up at „Los geht's“. After „Erledigt“ or
 * „Später“ – in any guide – it never comes before a tour again; Settings → Phone setup keeps it as a reminder.
 */
class BrandOnceTest {
    private val samsung = PhoneState(sdk = 35, manufacturer = "samsung", location = LocationGrant.PRECISE, vibrationFelt = Felt.YES)

    @Test fun beforeTheFirstAnswerTheBrandPageComesBeforeATour() {
        assertEquals(listOf(CheckId.BRAND), PhoneCheck.steps(Purpose.BEFORE_TOUR, samsung,
            snoozed = PhoneCheck.quietBeforeTour(emptySet(), emptySet())))
    }

    @Test fun answeredOnceItNeverComesBeforeATourAgain() {
        val quiet = PhoneCheck.quietBeforeTour(emptySet(), setOf(CheckId.BRAND))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, samsung, snoozed = quiet))
        // no time limit: not a one-week snooze, a lasting answer (the snooze set is empty here)
        assertTrue(CheckId.BRAND in quiet)
    }

    @Test fun settingsStillShowsItAsOpen() {
        val quiet = PhoneCheck.quietBeforeTour(emptySet(), setOf(CheckId.BRAND))
        assertTrue(CheckId.BRAND in PhoneCheck.steps(Purpose.SETTINGS, samsung, snoozed = quiet))
        val row = PhoneCheck.all(samsung).first { it.id == CheckId.BRAND }
        assertFalse("„Später“ leaves it open in Settings", row.ok)
        // „Erledigt“: done everywhere
        assertTrue(PhoneCheck.all(samsung.copy(brandDone = true)).first { it.id == CheckId.BRAND }.ok)
    }

    @Test fun onlyTheBrandPageIsOnceOnly() {
        // an answered required or other recommended step is not made quiet by this rule (only the 7-day snooze does that)
        val quiet = PhoneCheck.quietBeforeTour(emptySet(), setOf(CheckId.LOCATION, CheckId.NOTIFICATIONS, CheckId.BRAND))
        assertEquals(setOf(CheckId.BRAND), quiet)
        val noNotif = samsung.copy(notificationsOn = false)
        assertEquals(listOf(CheckId.NOTIFICATIONS), PhoneCheck.steps(Purpose.BEFORE_TOUR, noNotif, snoozed = quiet))
        // the snoozed set passes through
        assertEquals(setOf(CheckId.VIBRATION, CheckId.BRAND), PhoneCheck.quietBeforeTour(setOf(CheckId.VIBRATION), setOf(CheckId.BRAND)))
    }

    @Test fun theFirstSetupStillOffersItOnce() {
        assertEquals(listOf(CheckId.BRAND), PhoneCheck.steps(Purpose.FIRST_SETUP, samsung))
    }
}
