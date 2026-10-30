package com.onpath.app.ui.setup

import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.LocationSaverMode
import com.onpath.app.setup.PhoneCheck
import com.onpath.app.setup.PhoneState
import com.onpath.strings.Res
import com.onpath.strings.Txt
import com.onpath.strings.setup_saver_ok_why
import com.onpath.strings.setup_saver_why
import com.onpath.strings.setup_state_missing
import com.onpath.strings.setup_state_none
import com.onpath.strings.setup_state_ok
import com.onpath.strings.setup_state_untested
import com.onpath.strings.setup_vib_none
import com.onpath.strings.setup_vib_why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings → Phone setup shows the same "why" text next to ✓, ⚠ and ✗, so a text must never contradict its state
 * (owner, 2026-10-30): battery saver has its own ✓ text, a phone without a vibrator gets "Not on this phone", not "Set up".
 */
class SetupTextsTest {
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
}
