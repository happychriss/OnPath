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
 * Off when the system "touch vibration" setting is off. Never throws.
 */
object Haptics {
    fun play(ctx: Context, f: PlanHaptics.Feel) {
        if (f == PlanHaptics.Feel.NONE) return
        try {
            if (!touchVibrationOn(ctx)) return
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            if (v == null || !v.hasVibrator()) return
            val effect = if (f == PlanHaptics.Feel.TICK && Build.VERSION.SDK_INT >= 29)
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createWaveform(PlanHaptics.pattern(f), -1)
            // as touch feedback: Android 13+ also applies the user's touch vibration strength
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
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
