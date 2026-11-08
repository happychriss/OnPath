package com.onpath.app.recording

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.onpath.app.debug.Problems

/**
 * The navigation vibrations (turn cues, off-route warning) – one code path for the tour and for the "Test vibration"
 * button (Settings → Phone setup), so the test feels exactly like the trail.
 */
object Cues {
    fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    } catch (e: Exception) { Problems.warn("sensor", "Vibrator not readable", e); null }

    fun hasVibrator(ctx: Context): Boolean = vibrator(ctx)?.hasVibrator() == true

    /** Plays [pattern] (off, on, off, on … ms); false when the phone has no vibrator or it failed (reported). */
    fun vibrate(ctx: Context, pattern: LongArray, what: String): Boolean {
        return try {
            val vib = vibrator(ctx) ?: return false
            if (!vib.hasVibrator()) { Problems.warn("sensor", "This phone reports no vibrator ($what); given without vibration"); return false }
            val effect = VibrationEffect.createWaveform(pattern, -1)
            // As an alarm: Android drops vibrations without a usage while the app is not on screen (phone in the
            // pocket); alarm vibrations also pass Do Not Disturb, silent mode and the touch setting.
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            true
        } catch (e: Exception) {
            Problems.warn("sensor", "Vibration failed ($what); given without vibration", e)
            false
        }
    }

    /** The test: one pulse (owner 2026-11-08). */
    // owner 2026-11-08: one buzz is enough for the test (the old "once, then twice" felt like a second test after Weiter)
    val TEST_PATTERN: LongArray = longArrayOf(0, com.onpath.app.navigation.JunctionCues.PULSE_MS)
}
