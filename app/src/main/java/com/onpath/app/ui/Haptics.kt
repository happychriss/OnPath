package com.onpath.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.onpath.app.debug.Problems

/**
 * Plays a [PlanHaptics.Feel] – the one place the plan vibrates (map holds, point menu, Points list all end here).
 * Gated by OnPath's own setting "Vibrate when planning" ([planVibrate], owner 2026-10-21), not by the system "Touch
 * feedback" flag – that one only silences the light menu tick ([PlanHaptics.shouldPlay]). Never throws.
 */
object Haptics {
    fun play(ctx: Context, f: PlanHaptics.Feel, planVibrate: Boolean) {
        try {
            val tick = f == PlanHaptics.Feel.TICK
            if (!PlanHaptics.shouldPlay(f, planVibrate, systemTouch = !tick || touchVibrationOn(ctx))) return
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            if (v == null || !v.hasVibrator()) return
            val effect = when {
                tick && Build.VERSION.SDK_INT >= 29 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                v.hasAmplitudeControl() -> VibrationEffect.createWaveform(PlanHaptics.pattern(f), PlanHaptics.amplitudes(f), -1)
                else -> VibrationEffect.createWaveform(PlanHaptics.pattern(f), -1)
            }
            // The tick is touch feedback (Android 13+: the user's touch strength). The pulses are not: as touch usage
            // they would be muted with "Touch feedback" off – exactly what the owner does not want.
            if (tick && Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else v.vibrate(effect)
        } catch (e: Exception) {
            Problems.info("ui", "Vibration failed (no vibration for this plan edit)", e)
        }
    }

    /** System setting "Touch vibration" / "Haptic feedback" (on when unreadable). */
    private fun touchVibrationOn(ctx: Context): Boolean = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    } catch (e: Exception) {
        Problems.info("ui", "Touch vibration setting unreadable; vibrating", e); true
    }
}
