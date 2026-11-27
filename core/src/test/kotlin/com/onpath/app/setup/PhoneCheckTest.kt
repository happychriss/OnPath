package com.onpath.app.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design/onboarding-phone-check.md: what the guide asks for, in which order, and what it says was removed. */
class PhoneCheckTest {
    private val day = 24L * 3600 * 1000
    private val good = PhoneState(sdk = 35, manufacturer = "Google", location = LocationGrant.PRECISE,
        vibrationFelt = Felt.YES, unusedReset = ResetStatus.ON)

    @Test fun aWellSetUpPixelNeedsNothing() {
        for (p in Purpose.entries.filter { it != Purpose.SETTINGS }) assertEquals(p.name, emptyList<CheckId>(), PhoneCheck.steps(p, good))
        assertTrue(PhoneCheck.tourReady(good))
        // Settings lists everything that applies; the unused-app reset is optional and "on" by default
        val all = PhoneCheck.all(good)
        assertEquals(listOf(CheckId.UNUSED_RESET), all.filter { !it.ok }.map { it.id })
        assertEquals(Need.OPTIONAL, all.first { it.id == CheckId.UNUSED_RESET }.need)
    }

    /** Shorter setup (owner 2026-11-26/27): the first start asks only for the location; the rest comes at „Los geht's“. */
    @Test fun freshInstallFirstSetupAsksOnlyTheLocation() {
        val fresh = PhoneState(sdk = 35, manufacturer = "Google", notificationsOn = false, backgroundRestricted = true)
        assertEquals(listOf(CheckId.LOCATION, CheckId.PRECISE), PhoneCheck.steps(Purpose.FIRST_SETUP, fresh))
        assertEquals("notifications and battery before the first recording",
            listOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.NOTIFICATIONS, CheckId.BATTERY), PhoneCheck.steps(Purpose.BEFORE_TOUR, fresh))
        assertFalse(PhoneCheck.tourReady(fresh))
        assertEquals(listOf(CheckId.LOCATION), PhoneCheck.steps(Purpose.LOCATE, fresh))
    }

    @Test fun approximateOnlyAsksForPrecise() {
        val s = good.copy(location = LocationGrant.APPROXIMATE)
        assertEquals(listOf(CheckId.PRECISE), PhoneCheck.steps(Purpose.BEFORE_TOUR, s))
        assertFalse(PhoneCheck.tourReady(s))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.LOCATE, s)) // the dot works with approximate
    }

    @Test fun recommendedItemsBeforeATourCanBeSnoozedRequiredNever() {
        val s = good.copy(notificationsOn = false, vibrationFelt = Felt.UNKNOWN, backgroundRestricted = true, locationOn = false)
        assertEquals(listOf(CheckId.LOCATION_ON, CheckId.NOTIFICATIONS, CheckId.BATTERY),
            PhoneCheck.steps(Purpose.BEFORE_TOUR, s))
        val snoozed = setOf(CheckId.NOTIFICATIONS, CheckId.BATTERY, CheckId.LOCATION_ON)
        assertEquals("location off is required", listOf(CheckId.LOCATION_ON), PhoneCheck.steps(Purpose.BEFORE_TOUR, s, snoozed = snoozed))
        // the settings guide ignores snoozes
        assertTrue(CheckId.NOTIFICATIONS in PhoneCheck.steps(Purpose.SETTINGS, s, snoozed = snoozed))
    }

    /** Owner 2026-11-26/27: the vibration is never asked in a guide – it simply works; the test stays in Settings. */
    @Test fun vibrationIsNeverAskedOnlyOfferedInSettings() {
        val s = good.copy(vibrationFelt = Felt.NO)
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, s))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, s))
        assertTrue(CheckId.VIBRATION in PhoneCheck.steps(Purpose.SETTINGS, s))
        assertFalse(CheckId.VIBRATION in PhoneCheck.steps(Purpose.SETTINGS, s, vibrationOn = false))
        assertTrue("no vibrator: nothing to test", PhoneCheck.isOk(CheckId.VIBRATION, s.copy(hasVibrator = false)))
    }

    @Test fun voiceStepOnlyWithSpokenDirectionsOn_okOnceHeard() {
        // off: no step, no row
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, good))
        // on (owner 2026-11-27): asked at the first start only when the offline voice is missing – never before a tour
        assertEquals("voice not known yet / there: no step", emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, good, voiceOn = true))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, good.copy(voice = VoiceStatus.READY), voiceOn = true))
        for (v in listOf(VoiceStatus.MISSING, VoiceStatus.NO_LANGUAGE, VoiceStatus.ONLINE)) {
            assertEquals("$v: load it at home", listOf(CheckId.VOICE), PhoneCheck.steps(Purpose.FIRST_SETUP, good.copy(voice = v), voiceOn = true))
            assertEquals("$v: never at „Los geht's“", emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, good.copy(voice = v), voiceOn = true))
        }
        // the guide passes the step once an offline voice is there – the test sentence is not required
        val g = SetupGuide.of(Purpose.FIRST_SETUP, good.copy(voice = VoiceStatus.MISSING), voiceOn = true)!!
        assertTrue(g.refresh(good.copy(voice = VoiceStatus.READY), voiceOn = true).finished)
        assertEquals(SetupGuide.End.ALL_SET, g.refresh(good.copy(voice = VoiceStatus.READY), voiceOn = true).end(good.copy(voice = VoiceStatus.READY)))
        // Settings still asks for the test sentence
        assertTrue(CheckId.VOICE in PhoneCheck.steps(Purpose.SETTINGS, good.copy(voice = VoiceStatus.READY), voiceOn = true))
        assertEquals(Need.RECOMMENDED, PhoneCheck.need(CheckId.VOICE))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, good, snoozed = setOf(CheckId.VOICE), voiceOn = true))
        val heard = good.copy(voice = VoiceStatus.READY, voiceHeard = Felt.YES)
        assertTrue(PhoneCheck.isOk(CheckId.VOICE, heard))
        // the voice was removed from the phone since: missing again
        assertFalse(PhoneCheck.isOk(CheckId.VOICE, heard.copy(voice = VoiceStatus.MISSING)))
        // no speech output at all: nothing to set up
        assertFalse(PhoneCheck.applies(CheckId.VOICE, good.copy(voice = VoiceStatus.NO_ENGINE), voiceOn = true))
        val fresh = PhoneState(sdk = 35, manufacturer = "Google")
        assertEquals(listOf(CheckId.LOCATION, CheckId.PRECISE), PhoneCheck.steps(Purpose.FIRST_SETUP, fresh, voiceOn = true))
    }

    @Test fun channelSwitchedOffCountsAsNotificationsOff() {
        assertFalse(PhoneCheck.isOk(CheckId.NOTIFICATIONS, good.copy(channelOff = true)))
    }

    @Test fun batteryRestrictedOrRestrictedBucket() {
        assertFalse(PhoneCheck.isOk(CheckId.BATTERY, good.copy(backgroundRestricted = true)))
        assertFalse(PhoneCheck.isOk(CheckId.BATTERY, good.copy(standbyBucket = PhoneCheck.BUCKET_RESTRICTED)))
        assertTrue(PhoneCheck.isOk(CheckId.BATTERY, good.copy(standbyBucket = 10)))
    }

    @Test fun batterySaverOnlyWhenItStopsGps() {
        val on = good.copy(powerSave = true, locationSaverMode = LocationSaverMode.GPS_OFF_SCREEN_OFF)
        assertEquals(listOf(CheckId.BATTERY_SAVER), PhoneCheck.steps(Purpose.BEFORE_TOUR, on))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, on.copy(locationSaverMode = LocationSaverMode.FOREGROUND_ONLY)))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, on)) // a passing state, not set-up
    }

    @Test fun brandsWithBackgroundKillers() {
        assertEquals(Brand.SAMSUNG, Brand.of("samsung"))
        assertEquals(Brand.XIAOMI, Brand.of(" Redmi "))
        assertEquals(Brand.HUAWEI, Brand.of("HONOR"))
        assertEquals(Brand.ONEPLUS, Brand.of("realme"))
        assertEquals(null, Brand.of("Google"))
        val sam = good.copy(manufacturer = "samsung")
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.FIRST_SETUP, sam)) // not at the first start any more
        assertEquals(listOf(CheckId.BRAND), PhoneCheck.steps(Purpose.BEFORE_TOUR, sam))
        assertEquals(emptyList<CheckId>(), PhoneCheck.steps(Purpose.BEFORE_TOUR, sam.copy(brandDone = true)))
        assertFalse(PhoneCheck.all(good).any { it.id == CheckId.BRAND })
    }

    @Test fun takenAwayByThePhoneAfterMonthsOrSwitchedOff() {
        val before = LastSeen(PhoneCheck.okNow(good), atMs = 0L)
        val reset = good.copy(location = LocationGrant.NONE, notificationsOn = false)
        assertEquals(mapOf(CheckId.LOCATION to Taken.BY_PHONE, CheckId.PRECISE to Taken.BY_PHONE, CheckId.NOTIFICATIONS to Taken.BY_PHONE),
            PhoneCheck.takenAway(before, reset, nowMs = 90 * day))
        // a week later: the user (or something else) switched it off
        assertEquals(Taken.OFF, PhoneCheck.takenAway(before, reset, nowMs = 7 * day)[CheckId.LOCATION])
        // reset not active on this phone: never "by the phone"
        assertEquals(Taken.OFF, PhoneCheck.takenAway(before, reset.copy(unusedReset = ResetStatus.OFF), nowMs = 90 * day)[CheckId.LOCATION])
        // nothing remembered yet, or nothing missing
        assertEquals(emptyMap<CheckId, Taken>(), PhoneCheck.takenAway(null, reset, 90 * day))
        assertEquals(emptyMap<CheckId, Taken>(), PhoneCheck.takenAway(before, good, 90 * day))
    }

    @Test fun describeForDiagnostics() {
        val d = PhoneCheck.describe(good.copy(manufacturer = "samsung", powerSave = true, locationSaverMode = 1))
        assertTrue(d, d.startsWith("Phone check: sdk 35, samsung, location PRECISE"))
        assertTrue(d, "brand SAMSUNG" in d && "saver on (location mode 1)" in d)
    }
}

/** The guide's flow: skip what is fine, "Later", a no from Android, how it ends. */
class SetupGuideTest {
    private val fresh = PhoneState(sdk = 35, manufacturer = "Google")

    @Test fun approximateAnswerLeadsToThePreciseStep() {
        val g = SetupGuide.of(Purpose.BEFORE_TOUR, fresh)!!
        assertEquals(CheckId.LOCATION, g.current)
        val after = g.refresh(fresh.copy(location = LocationGrant.APPROXIMATE))
        assertEquals(CheckId.PRECISE, after.current)
        assertEquals(CheckId.LOCATION, after.justDone)
        val precise = after.refresh(fresh.copy(location = LocationGrant.PRECISE))
        assertTrue("nothing else is missing", precise.finished)
    }

    @Test fun noLocationAtAllSkipsThePreciseStep() {
        val restricted = fresh.copy(backgroundRestricted = true)
        val g = SetupGuide.of(Purpose.BEFORE_TOUR, restricted)!!.denied(CheckId.LOCATION).putOff().refresh(restricted)
        assertEquals(CheckId.BATTERY, g.current)
        assertEquals(null, g.justDone)
    }

    @Test fun endsSayWhatIsLeft() {
        val restricted = fresh.copy(backgroundRestricted = true)
        val ready = restricted.copy(location = LocationGrant.PRECISE)
        val g = SetupGuide.of(Purpose.BEFORE_TOUR, restricted)!!
        // everything allowed, battery not restricted any more
        val done = g.refresh(ready.copy(backgroundRestricted = false))
        assertTrue(done.finished)
        assertEquals(SetupGuide.End.ALL_SET, done.end(ready.copy(backgroundRestricted = false)))
        // battery put off
        val later = g.refresh(ready).putOff()
        assertTrue(later.finished)
        assertEquals(SetupGuide.End.SOME_LATER, later.end(ready))
        // location never allowed before a tour
        val no = g.putOff().refresh(fresh).putOff()
        assertEquals(SetupGuide.End.TOUR_NOT_READY, no.end(fresh))
    }

    @Test fun stepNumbersAndSingleStep() {
        val g = SetupGuide.of(Purpose.FIRST_SETUP, fresh)!!
        assertEquals(1, g.stepNumber); assertEquals(2, g.steps.size) // location + precise only (shorter setup)
        assertEquals("location + precise are one ask of Android: no counter", 1, g.shownCount)
        val tour = SetupGuide.of(Purpose.BEFORE_TOUR, fresh.copy(notificationsOn = false, backgroundRestricted = true))!!
        assertEquals(listOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.NOTIFICATIONS, CheckId.BATTERY), tour.steps)
        assertEquals(3, tour.shownCount)
        val atPrecise = tour.refresh(fresh.copy(location = LocationGrant.APPROXIMATE, notificationsOn = false, backgroundRestricted = true))
        assertEquals(CheckId.PRECISE, atPrecise.current); assertEquals(1, atPrecise.shownNumber)
        val atNotif = atPrecise.refresh(fresh.copy(location = LocationGrant.PRECISE, notificationsOn = false, backgroundRestricted = true))
        assertEquals(CheckId.NOTIFICATIONS, atNotif.current); assertEquals(2, atNotif.shownNumber)
        assertEquals(listOf(CheckId.BATTERY), SetupGuide.single(CheckId.BATTERY).steps)
        assertEquals(null, SetupGuide.of(Purpose.LOCATE, fresh.copy(location = LocationGrant.APPROXIMATE)))
    }
}
