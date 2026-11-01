package com.onpath.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.onpath.app.data.AppSettings
import com.onpath.app.data.Prefs
import com.onpath.app.debug.Problems

/**
 * Phone setup (docs/design/onboarding-phone-check.md) – out of AppViewModel (review Arch 3, owner 2026-11-01), the same
 * code: the phone as MainActivity reads it, the welcome, the step-by-step guide and its answers. The ViewModel owns it
 * (created where these members were declared) and forwards every member, so the UI and the emulator tests are
 * unchanged. [settingsNow] = the current settings (vibration / voice in use).
 */
class PhoneSetupState(private val prefs: Prefs, private val settingsNow: () -> AppSettings) {
    private val settings: AppSettings get() = settingsNow()

    /** The phone as last read by MainActivity (every return to the app, every system answer). */
    var phone by mutableStateOf(com.onpath.app.setup.PhoneState(sdk = android.os.Build.VERSION.SDK_INT))
        private set
    /** Android's unused-app reset for OnPath (read off the main thread at start). */
    var unusedReset = com.onpath.app.setup.ResetStatus.UNKNOWN
    /** What the phone or the user removed since the last use (the guide and Settings say so calmly). */
    var phoneTaken by mutableStateOf<Map<com.onpath.app.setup.CheckId, com.onpath.app.setup.Taken>>(emptyMap())
    /** The first-start welcome ("Set up now / Later"). */
    var setupWelcome by mutableStateOf(false)
    /** The open step-by-step guide; null = none. */
    var setupGuide by mutableStateOf<com.onpath.app.setup.SetupGuide?>(null)
        private set
    private var setupThen: (() -> Unit)? = null
    val setupOpen: Boolean get() = setupWelcome || setupGuide != null
    /** The tour whose "Notifications off" chip was confirmed with OK (in memory: the next tour shows it again). */
    var notificationsOkTour by mutableStateOf<Long?>(null)

    /** Navigation vibrations in use: the vibration step applies only then. */
    val vibrationUsed: Boolean get() = settings.junctionGuide == com.onpath.app.data.JunctionGuide.VIBRATION ||
        (settings.offRouteWarning && settings.offRouteVibrate)

    fun updatePhone(s: com.onpath.app.setup.PhoneState) {
        phone = s
        setupGuide = setupGuide?.refresh(s, vibrationUsed, voiceUsed)
        finishSetupIfDone()
    }

    /**
     * Opens the guide for [purpose] with what is missing; nothing missing → [then] runs at once. Before a tour and for
     * the locate button [then] runs when the guide ends and what it needs is there.
     */
    fun openSetup(purpose: com.onpath.app.setup.Purpose, then: (() -> Unit)? = null) {
        val g = com.onpath.app.setup.SetupGuide.of(purpose, phone, vibrationUsed, prefs.snoozed(System.currentTimeMillis()), voiceUsed)
            ?.refresh(phone, vibrationUsed, voiceUsed)
        if (g == null || g.finished && then != null && setupReady(purpose)) { then?.invoke(); return }
        Problems.trace("setup", "Phone setup guide ($purpose): ${g.steps.joinToString()}")
        setupThen = then
        setupGuide = g
    }

    /** Settings → Phone setup → a row: that one step. */
    fun openSetupStep(id: com.onpath.app.setup.CheckId) {
        setupThen = null
        setupGuide = com.onpath.app.setup.SetupGuide.single(id)
    }

    fun setupWelcomeNow() {
        setupWelcome = false; prefs.setupWelcomeSeen = true
        openSetup(com.onpath.app.setup.Purpose.FIRST_SETUP)
    }

    /** "Later": before a tour a recommended step stays quiet for a week. */
    fun setupLater() {
        val g = setupGuide ?: return
        val id = g.current
        if (id != null && g.purpose == com.onpath.app.setup.Purpose.BEFORE_TOUR &&
            com.onpath.app.setup.PhoneCheck.need(id) != com.onpath.app.setup.Need.REQUIRED) prefs.snooze(id, System.currentTimeMillis())
        setupGuide = g.putOff().refresh(phone, vibrationUsed, voiceUsed)
        finishSetupIfDone()
    }

    /** The system dialog for [id] came back without it. */
    fun setupDenied(id: com.onpath.app.setup.CheckId) {
        val g = setupGuide ?: return
        if (g.current == id && !com.onpath.app.setup.PhoneCheck.isOk(id, phone)) setupGuide = g.denied(id)
    }

    fun setupVibrationPlayed() { setupGuide = setupGuide?.vibrationPlayed() }

    fun setupVibrationAnswer(f: com.onpath.app.setup.Felt) {
        if (setupGuide?.current == com.onpath.app.setup.CheckId.VOICE) {
            prefs.voiceHeard = f
            Problems.trace("setup", "Voice test: heard = $f")
            setupGuide = setupGuide?.vibrationAnswer(f)
            updatePhone(phone.copy(voiceHeard = f))
            return
        }
        prefs.vibrationFelt = f
        Problems.trace("setup", "Vibration test: felt = $f")
        setupGuide = setupGuide?.vibrationAnswer(f)
        updatePhone(phone.copy(vibrationFelt = f))
    }

    /** Spoken directions switched on for bike or hike: the voice step applies only then. */
    val voiceUsed: Boolean get() = settings.junctionGuide == com.onpath.app.data.JunctionGuide.VOICE

    /** The voice test found the phone's speech output in this [status] (recording/Speaker). */
    fun setupVoiceStatus(status: com.onpath.app.setup.VoiceStatus) {
        prefs.voiceStatus = status
        Problems.trace("setup", "Voice test: speech output $status")
        updatePhone(phone.copy(voice = status))
    }

    fun setupBrandDone() {
        prefs.brandStepsDone = true
        updatePhone(phone.copy(brandDone = true))
    }

    /** Closes the welcome / guide (Back, Close, Later on the welcome). */
    fun closeSetup() {
        if (setupWelcome) { setupWelcome = false; prefs.setupWelcomeSeen = true }
        setupGuide = null
        setupThen = null
    }

    private fun setupReady(purpose: com.onpath.app.setup.Purpose) = when (purpose) {
        com.onpath.app.setup.Purpose.BEFORE_TOUR -> com.onpath.app.setup.PhoneCheck.tourReady(phone)
        com.onpath.app.setup.Purpose.LOCATE -> com.onpath.app.setup.PhoneCheck.isOk(com.onpath.app.setup.CheckId.LOCATION, phone)
        else -> true
    }

    /** At its end a guide for a tour / the locate button closes by itself and goes on; the others show their end page. */
    private fun finishSetupIfDone() {
        val g = setupGuide ?: return
        if (!g.finished) return
        val goOn = g.purpose == com.onpath.app.setup.Purpose.BEFORE_TOUR || g.purpose == com.onpath.app.setup.Purpose.LOCATE
        val single = g.purpose == com.onpath.app.setup.Purpose.SETTINGS && g.steps.size == 1
        if (goOn && setupReady(g.purpose) || single) {
            val then = setupThen
            setupGuide = null; setupThen = null
            then?.invoke()
        }
    }
}
