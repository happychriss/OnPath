package com.onpath.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.onpath.app.data.Prefs
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone setup before a tour (docs/design/onboarding-phone-check.md): with location allowed nothing blocks; a step that
 * is only recommended (the untested vibration, the voice not heard yet) shows once, and "Later" starts the tour anyway.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupE2ETest : E2EBase() {

    @Test fun recommendedStepBeforeATourAndLaterGoesOn() {
        launch { Prefs(InstrumentationRegistry.getInstrumentation().targetContext).vibrationFelt = Felt.UNKNOWN }
        var started = false
        var hasVibrator = true
        scenario.onActivity { a ->
            val vm = vm(a)
            hasVibrator = vm.phone.hasVibrator
            vm.openSetup(Purpose.BEFORE_TOUR) { started = true }
        }
        if (!hasVibrator) { assertTrue("no vibrator: nothing to ask, the tour starts", started); return }
        var current: CheckId? = null
        scenario.onActivity { current = vm(it).setupGuide?.current }
        assertEquals(CheckId.VIBRATION, current)
        // every recommended step offers Later: the vibration, then (voice on, its test sentence not heard yet) the voice
        val seen = ArrayList<CheckId?>()
        repeat(3) {
            var step: CheckId? = null
            scenario.onActivity { step = vm(it).setupGuide?.current }
            if (step == null) return@repeat
            seen += step
            // "Continue" where nothing can be done (no speech engine on the phone)
            val later = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Later|Continue"))), 5_000)
            assertNotNull("the step $step offers Later", later)
            later.click()
            device.waitForIdle()
        }
        assertTrue("$seen", seen.all { it == CheckId.VIBRATION || it == CheckId.VOICE })
        var open = true
        scenario.onActivity { open = vm(it).setupGuide != null }
        assertTrue("the tour starts after Later", started && !open)
        // a week of quiet for that step: the next start goes straight on
        var again = false
        scenario.onActivity { vm(it).openSetup(Purpose.BEFORE_TOUR) { again = true } }
        assertTrue(again)
    }
}
