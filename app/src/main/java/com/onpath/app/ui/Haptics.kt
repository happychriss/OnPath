package com.onpath.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.onpath.app.debug.Problems

/**
 * Plays a map-hold vibration ([MapGestures.Buzz], rules in [PlanHaptics]) – the one place the plan vibrates.
 * Gated only by OnPath's "Vibrate when planning" ([planVibrate]). Always sent with an explicit media usage, never as
 * touch / unknown usage: Android turns a short unlabelled pulse into touch feedback and mutes it while the system
 * "Touch feedback" is off ([PlanHaptics.MAX_UNLABELLED_TOUCH_SEGMENTS]). Never throws.
 */
object Haptics {
    fun play(ctx: Context, b: MapGestures.Buzz, planVibrate: Boolean) {
        if (!PlanHaptics.shouldPlay(b, planVibrate)) return
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            if (v == null || !v.hasVibrator()) return
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(PlanHaptics.pattern(b), PlanHaptics.amplitudes(b), -1)
            else VibrationEffect.createWaveform(PlanHaptics.pattern(b), -1)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
            else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        } catch (e: Exception) {
            Problems.info("ui", "Vibration failed (no vibration for this hold)", e)
        }
    }
}
