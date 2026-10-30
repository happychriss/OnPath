package com.onpath.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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

/**
 * "Keep screen on while navigating", pocket-aware (docs/design/battery-audit.md, docs/design/navigation-cues.md):
 * the screen stays on while you look at it; the proximity sensor turns it off when covered (like during a call)
 * and on again when you take the phone out. Covered for [POCKET_SLEEP_MS]: keep-on is dropped and the phone sleeps
 * as usual (the map stops, GPS recording goes on in the service). Only while the screen is started; without a
 * proximity sensor it is plain keep-screen-on. [pocketAfterMs] (ScreenPolicy): how long the sensor must be covered
 * before it counts – 0 on foot (at once), 5 s on the bike (a hand reaching for the handlebar phone is ignored,
 * [PocketLogic]); null = plain keep-screen-on, no proximity sensor. Never throws.
 */
@Composable
fun KeepScreenOn(on: Boolean, pocketAfterMs: Long? = 0L) {
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
    DisposableEffect(active, pocketAfterMs) {
        if (!active) { view.keepScreenOn = false; return@DisposableEffect onDispose { } }
        if (pocketAfterMs == null) { view.keepScreenOn = true; return@DisposableEffect onDispose { view.keepScreenOn = false } }
        val guard = PocketGuard(context.applicationContext, pocketAfterMs) { keep -> view.keepScreenOn = keep }
        guard.start()
        onDispose { guard.stop(); view.keepScreenOn = false }
    }
}

/** Drives [PocketLogic] with the proximity sensor and the main looper's clock; applies its answer. */
private class PocketGuard(app: Context, private val armMs: Long, private val keepOn: (Boolean) -> Unit) : SensorEventListener {
    private val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val logic = PocketLogic(armMs)
    private var lock: PowerManager.WakeLock? = null
    private var sensor: Sensor? = null
    private var kept = true
    private val tick = Runnable { apply(logic.at(SystemClock.uptimeMillis())) }

    fun start() {
        keepOn(true)
        try {
            if (pm?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true) {
                sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                if (sensor != null) {
                    lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "OnPath:pocket").apply {
                        setReferenceCounted(false)
                    }
                    apply(logic.at(SystemClock.uptimeMillis())) // on foot: the lock at once
                    sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        } catch (e: Exception) {
            Problems.warn("sensor", "Pocket detection not available; screen simply stays on while navigating", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        try { sm?.unregisterListener(this) } catch (e: Exception) { Problems.warn("sensor", "Proximity sensor not released", e) }
        try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { Problems.warn("sensor", "Pocket wake lock not released", e) }
        lock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sensor ?: return
        val near = event.values.isNotEmpty() && event.values[0] < minOf(s.maximumRange, 5f)
        apply(logic.sensor(near, SystemClock.uptimeMillis()))
    }

    /**
     * Keep-on and the proximity wake lock as [PocketLogic] says. Long in the pocket: the lock is let go "when uncovered"
     * (the screen stays off until then, then times out normally); uncovered on the bike: let go at once (screen on).
     */
    private fun apply(o: PocketLogic.Out) {
        if (o.keepOn != kept) { kept = o.keepOn; keepOn(o.keepOn) }
        val l = lock
        if (l != null && o.lock != l.isHeld) {
            try {
                if (o.lock) l.acquire() else l.release(if (o.keepOn) 0 else PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
                if (armMs > 0) Problems.trace("sensor", if (o.lock) "Covered ${armMs / 1000} s: pocket – screen off" else "Pocket ended")
            } catch (e: Exception) {
                Problems.warn("sensor", if (o.lock) "Pocket wake lock not taken" else "Pocket wake lock not released", e)
            }
        }
        handler.removeCallbacks(tick)
        logic.nextChangeInMs(SystemClock.uptimeMillis())?.let { handler.postDelayed(tick, it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
