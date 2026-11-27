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

    /**
     * Before a tour (owner 2026-11-27, shorter setup): the vibration and the voice are never asked; every recommended step
     * that is left (notifications, battery, brand, saver – what this phone lacks) offers Later, and Later goes on to the tour.
     */
    @Test fun recommendedStepBeforeATourAndLaterGoesOn() {
        launch { Prefs(InstrumentationRegistry.getInstrumentation().targetContext).vibrationFelt = Felt.UNKNOWN }
        var started = false
        scenario.onActivity { a -> vm(a).setup.openSetup(Purpose.BEFORE_TOUR) { started = true } }
        val seen = ArrayList<CheckId?>()
        repeat(6) {
            var step: CheckId? = null
            scenario.onActivity { step = vm(it).setup.setupGuide?.current }
            if (step == null) return@repeat
            seen += step
            // "Continue" where nothing can be done
            val later = device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Later|Continue"))), 5_000)
            assertNotNull("the step $step offers Later", later)
            later.click()
            device.waitForIdle()
        }
        assertTrue("never the vibration or the voice before a tour: $seen", seen.none { it == CheckId.VIBRATION || it == CheckId.VOICE })
        var open = true
        scenario.onActivity { open = vm(it).setup.setupGuide != null }
        assertTrue("the tour starts after Later ($seen)", started && !open)
        // a week of quiet for those steps: the next start goes straight on
        var again = false
        scenario.onActivity { vm(it).setup.openSetup(Purpose.BEFORE_TOUR) { again = true } }
        assertTrue(again)
    }
}
