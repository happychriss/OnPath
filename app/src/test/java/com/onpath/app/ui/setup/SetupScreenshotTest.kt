package com.onpath.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.LocationSaverMode
import com.onpath.app.setup.PhoneCheck
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.Purpose
import com.onpath.app.setup.ResetStatus
import com.onpath.app.setup.SetupGuide
import com.onpath.app.setup.Taken
import com.onpath.app.ui.OnPathTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Phone setup (docs/design/onboarding-phone-check.md): the welcome, guide steps and Settings → Phone setup. */
open class SetupScreenshotTest {
    @get:Rule open val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val fresh = PhoneState(sdk = 35, manufacturer = "Google")
    private val samsung = PhoneState(sdk = 34, manufacturer = "samsung", location = LocationGrant.PRECISE, vibrationFelt = Felt.YES)
    private val located = fresh.copy(location = LocationGrant.PRECISE)

    private fun shot(guide: SetupGuide?, welcome: Boolean = false, phone: PhoneState = fresh, taken: Map<CheckId, Taken> = emptyMap()) =
        paparazzi.snapshot { OnPathTheme { SetupGuideScreen(guide, welcome, phone, taken, SetupCallbacks()) } }

    @Test fun welcome() = shot(null, welcome = true)
    @Test fun locationStep() = shot(SetupGuide.of(Purpose.FIRST_SETUP, fresh))
    @Test fun locationRemovedByThePhone() =
        shot(SetupGuide.of(Purpose.BEFORE_TOUR, fresh), taken = mapOf(CheckId.LOCATION to Taken.BY_PHONE))
    @Test fun locationBlocked() =
        shot(SetupGuide.of(Purpose.LOCATE, fresh)!!.denied(CheckId.LOCATION), phone = fresh.copy(locationAskBlocked = true))
    @Test fun preciseStep() {
        val approx = fresh.copy(location = LocationGrant.APPROXIMATE)
        shot(SetupGuide.of(Purpose.BEFORE_TOUR, fresh)!!.refresh(approx), phone = approx)
    }
    @Test fun vibrationQuestion() = shot(SetupGuide.of(Purpose.FIRST_SETUP, located)!!.vibrationPlayed(), phone = located)
    @Test fun vibrationNotFelt() =
        shot(SetupGuide.of(Purpose.FIRST_SETUP, located)!!.vibrationPlayed().vibrationAnswer(Felt.NO), phone = located)
    @Test fun samsungSteps() = shot(SetupGuide.of(Purpose.FIRST_SETUP, samsung), phone = samsung)
    @Test fun endSomeLater() = shot(SetupGuide.of(Purpose.FIRST_SETUP, located)!!.putOff(), phone = located)

    private val settingsPhone = samsung.copy(notificationsOn = false, vibrationFelt = Felt.UNKNOWN, unusedReset = ResetStatus.ON, brandDone = true)
    @Test fun settingsPage() = settings(settingsPhone)
    /** The same page further down (it scrolls): vibration, battery, the brand and the unused-app reset. */
    @Test fun settingsPageLower() = settings(settingsPhone,
        only = setOf(CheckId.VIBRATION, CheckId.BATTERY, CheckId.BRAND, CheckId.UNUSED_RESET))

    /** No vibrator (neutral "Not on this phone") and a battery saver that keeps GPS (its own ✓ text) – those rows only. */
    @Test fun settingsPageNoVibratorSaverOn() =
        settings(located.copy(hasVibrator = false, powerSave = true, locationSaverMode = LocationSaverMode.THROTTLE_SCREEN_OFF),
            only = setOf(CheckId.VIBRATION, CheckId.BATTERY, CheckId.BATTERY_SAVER))

    private fun settings(p: PhoneState, only: Set<CheckId>? = null) = paparazzi.snapshot {
        val checks = PhoneCheck.all(p).filter { only == null || it.id in only }
        OnPathTheme { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(top = 24.dp)) { PhoneSetupSection(checks, p, onFix = {}, onTestVibration = {}) } } }
    }
}

/** The same pages in German. */
class SetupScreenshotGermanTest : SetupScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** Older users: the phone's font size at its largest (2.0) – the pages scroll, nothing is cut. */
class SetupScreenshotLargeFontTest : SetupScreenshotTest() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f))
}
