package com.onpath.app.ui.setup

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.onpath.app.setup.CheckId
import com.onpath.app.setup.Felt
import com.onpath.app.setup.LocationGrant
import com.onpath.app.setup.PhoneState
import com.onpath.app.setup.Purpose
import com.onpath.app.setup.SetupGuide
import com.onpath.app.setup.VoiceSetup
import com.onpath.app.setup.VoiceStatus
import com.onpath.app.ui.OnPathTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The voice step when the phone's engine has no voice (field report 2026-11-07, Samsung Galaxy S10): which engine,
 * what helps (load the voice, Google's speech services, the speech settings) and what the last tap on Test did.
 */
abstract class VoiceStepScreenshotTest {
    @get:Rule open val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    private val samsungTts = "Samsung TTS"
    private val s10 = PhoneState(sdk = 31, manufacturer = "samsung", location = LocationGrant.PRECISE, vibrationFelt = Felt.YES)
    private val step = SetupGuide(Purpose.FIRST_SETUP, listOf(CheckId.LOCATION, CheckId.PRECISE, CheckId.VIBRATION, CheckId.VOICE), index = 3)

    private fun shot(phone: PhoneState, test: VoiceSetup.VoiceTest? = null, guide: SetupGuide = step) =
        paparazzi.snapshot { OnPathTheme { SetupGuideScreen(guide, false, phone, emptyMap(), SetupCallbacks(), test) } }

    /** The owner's S10 after "Load voice" and back: Samsung's engine still without the voice, the tap answered. */
    @Test fun samsungMissingVoiceTestedAgain() = shot(
        s10.copy(voice = VoiceStatus.MISSING, voiceFix = VoiceSetup.Fix("com.samsung.SMT", samsungTts, false, samsungTts, false, false)),
        VoiceSetup.VoiceTest(VoiceSetup.Outcome.NO_VOICE))

    /** Samsung's engine does not speak the language, Google's is not installed. */
    @Test fun samsungWithoutTheLanguage() = shot(
        s10.copy(voice = VoiceStatus.NO_LANGUAGE, voiceFix = VoiceSetup.Fix("com.samsung.SMT", samsungTts, false, samsungTts, false, false)))

    /** Samsung's engine without the voice, Google's installed: OnPath loads it into Google's. */
    @Test fun loadIntoGoogle() = shot(
        s10.copy(voice = VoiceStatus.MISSING,
            voiceFix = VoiceSetup.Fix("com.google.android.tts", "Speech Services by Google", true, samsungTts, false, true)))

    /** Only a voice that needs the internet: it speaks, the step asks for an offline one. */
    @Test fun onlineVoiceOnly() = shot(
        s10.copy(voice = VoiceStatus.ONLINE, voiceFix = VoiceSetup.Fix("com.samsung.SMT", samsungTts, false, samsungTts, false, false)))

    /** A voice is there but the engine never started speaking: the note and the speech settings. */
    @Test fun testStayedSilent() = shot(s10.copy(voice = VoiceStatus.READY), VoiceSetup.VoiceTest(VoiceSetup.Outcome.SILENT))
}

class VoiceStepScreenshotEnglishTest : VoiceStepScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun english() = Locale.setDefault(Locale.US)
    @After fun back() = Locale.setDefault(before)
}

class VoiceStepScreenshotGermanTest : VoiceStepScreenshotTest() {
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}

/** Older users: font size 2.0 – the page scrolls, nothing is cut. */
class VoiceStepScreenshotLargeFontTest : VoiceStepScreenshotTest() {
    @get:Rule override val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 2.0f))
    private val before = Locale.getDefault()
    @Before fun german() = Locale.setDefault(Locale.GERMANY)
    @After fun back() = Locale.setDefault(before)
}
