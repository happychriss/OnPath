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
 * is only recommended (the untested vibration) shows once, and "Later" starts the tour anyway.
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
        val later = device.wait(Until.findObject(By.text("Later")), 5_000)
        assertNotNull("the step offers Later", later)
        later.click()
        device.waitForIdle()
        var open = true
        scenario.onActivity { open = vm(it).setupGuide != null }
        assertTrue("the tour starts after Later", started && !open)
        // a week of quiet for that step: the next start goes straight on
        var again = false
        scenario.onActivity { vm(it).openSetup(Purpose.BEFORE_TOUR) { again = true } }
        assertTrue(again)
    }
}
