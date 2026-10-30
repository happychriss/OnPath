package com.onpath.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onpath.app.debug.Problems

/** In the pocket this long: let the phone go to sleep normally (screen stays off until the power button). */
const val POCKET_SLEEP_MS = 60_000L

/**
 * "Keep screen on while navigating", pocket-aware (docs/design/battery-audit.md):
 * the screen stays on while you look at it; the proximity sensor turns it off when covered (like during a call)
 * and on again when you take the phone out. Covered for [POCKET_SLEEP_MS]: keep-on is dropped and the phone sleeps
 * as usual (the map stops, GPS recording goes on in the service). Only while the screen is started; without a
 * proximity sensor it is plain keep-screen-on. [pocket] = false (bike on the handlebar, ScreenPolicy): plain
 * keep-screen-on, no proximity sensor. Never throws.
 */
@Composable
fun KeepScreenOn(on: Boolean, pocket: Boolean = true) {
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val active = on && started
    DisposableEffect(active, pocket) {
        if (!active) { view.keepScreenOn = false; return@DisposableEffect onDispose { } }
        if (!pocket) { view.keepScreenOn = true; return@DisposableEffect onDispose { view.keepScreenOn = false } }
        val guard = PocketGuard(context.applicationContext) { keep -> view.keepScreenOn = keep }
        guard.start()
        onDispose { guard.stop(); view.keepScreenOn = false }
    }
}

private class PocketGuard(private val app: Context, private val keepOn: (Boolean) -> Unit) : SensorEventListener {
    private val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var lock: PowerManager.WakeLock? = null
    private var sensor: Sensor? = null
    private var asleep = false
    private val sleep = Runnable { goToSleep() }

    fun start() {
        keepOn(true)
        try {
            if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                if (sensor != null) {
                    lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "OnPath:pocket").apply {
                        setReferenceCounted(false); acquire()
                    }
                    sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        } catch (e: Exception) {
            Problems.warn("sensor", "Pocket detection not available; screen simply stays on while navigating", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(sleep)
        try { sm?.unregisterListener(this) } catch (e: Exception) { Problems.warn("sensor", "Proximity sensor not released", e) }
        try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { Problems.warn("sensor", "Pocket wake lock not released", e) }
        lock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sensor ?: return
        val near = event.values.isNotEmpty() && event.values[0] < minOf(s.maximumRange, 5f)
        if (near) {
            if (!asleep) { handler.removeCallbacks(sleep); handler.postDelayed(sleep, POCKET_SLEEP_MS) }
        } else {
            handler.removeCallbacks(sleep)
            if (asleep) wake()
        }
    }

    /** Long in the pocket: release keep-on; the screen stays off until uncovered, then times out normally. */
    private fun goToSleep() {
        asleep = true
        keepOn(false)
        try { lock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } } catch (e: Exception) {
            Problems.warn("sensor", "Pocket wake lock not released", e)
        }
    }

    /** Taken out again while the app is still showing: back to keep-on + pocket detection. */
    private fun wake() {
        asleep = false
        keepOn(true)
        try { lock?.let { if (!it.isHeld) it.acquire() } } catch (e: Exception) {
            Problems.warn("sensor", "Pocket wake lock not taken again", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
