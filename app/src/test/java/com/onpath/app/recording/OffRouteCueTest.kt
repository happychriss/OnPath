package com.onpath.app.recording

import com.onpath.app.recording.GuidanceGate.OffRouteCue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Leaving the route is told, never silent (owner 2026-11-07, core feature C5 "leaving the route is two steps, both on
 * screen and said"): with the voice the sentence "Abseits der Route", without one – the phone has no voice (Voice step
 * 2d06b5e6: "a tour whose voice cannot speak vibrates the turns"), the voice off – the off-route vibration. GitHub's
 * emulator has no voice: E2E 2026-11-07 waited for a sentence that a voiceless tour never says
 * (`CoreFieldE2ETest.leavingTheRouteShowsAndSaysBothStepsAndTheCardGoesBackOnTheRoute`).
 */
class OffRouteCueTest {
    @Test fun aTourWithoutAVoiceVibratesStep1() =
        assertEquals(OffRouteCue.VIBRATE, GuidanceGate.offRouteCue(stepVibrates = true, planQuiet = false, voiceOn = false,
            vibrateSetting = true))

    @Test fun withTheVoiceStep1IsSaidNotVibrated() =
        assertEquals(OffRouteCue.SAY, GuidanceGate.offRouteCue(stepVibrates = true, planQuiet = false, voiceOn = true,
            vibrateSetting = true))

    @Test fun planIsSilent() {
        for (voice in listOf(true, false)) assertEquals(OffRouteCue.NONE,
            GuidanceGate.offRouteCue(stepVibrates = true, planQuiet = true, voiceOn = voice, vibrateSetting = true))
    }

    @Test fun aStepThatWantsNoAttentionGetsNone() =
        assertEquals(OffRouteCue.NONE, GuidanceGate.offRouteCue(stepVibrates = false, planQuiet = false, voiceOn = false,
            vibrateSetting = true))

    /** Settings → Navigation "vibrate when off route" off and no voice: the card and the question still show. */
    @Test fun theVibrationSettingOffIsRespected() =
        assertEquals(OffRouteCue.NONE, GuidanceGate.offRouteCue(stepVibrates = true, planQuiet = false, voiceOn = false,
            vibrateSetting = false))

    /** The service asks this one rule and writes the vibration into the event log (the E2E reads it there). */
    @Test fun theServiceUsesTheRuleAndTracesTheVibration() {
        val svc = File("src/main/java/com/onpath/app/recording/RecordingService.kt").readText()
        val handle = svc.substringAfter("private fun handle(step: Navigator.Step").substringBefore("if (step.cancelJob)")
        assertEquals(true, handle.contains("GuidanceGate.offRouteCue("))
        assertEquals(true, handle.contains("Problems.trace(\"navigation\", \"\${GuidanceGate.OFF_ROUTE_VIBRATED}"))
    }
}
