package com.onpath.app.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.onpath.app.debug.Problems
import kotlin.math.sqrt

/**
 * What the Navigate card says without GPS (owner 2026-11-02, phone on the table): the phone lies still → nothing at all;
 * it moves and GPS is missing → the amber warning (after the short gap, which stays a quiet grey line). "Still" comes from
 * the phone's own motion sensor ([StillDetector], [MotionSense]) – no GPS fixes needed, no Google. Without a sensor or
 * while it is not sure yet ([sensorStill] null) the rule is the one before: [MoveWatch] (moving = a point within 2 min,
 * the first 2 min after a start count as moving). Pure.
 */
object NoGpsShow {
    enum class Line { NONE, QUIET, WARN }

    /** [shortGap] = lost for less than [com.onpath.app.navigation.VoiceGuide.GPS_LOST_MS]. */
    fun line(sensorStill: Boolean?, moveWatchMoving: Boolean, shortGap: Boolean): Line = when {
        sensorStill == true -> Line.NONE
        shortGap -> Line.QUIET
        sensorStill == false -> Line.WARN
        !moveWatchMoving -> Line.QUIET
        else -> Line.WARN
    }

    /**
     * The notification's status word says "Searching GPS": GPS lost and worth a line – but never while the app is in Plan
     * during the tour ([planQuiet]; owner 2026-11-06 23:35: "beim Planen keine GPS-Warnung" – it says "Recording").
     */
    fun notifSearching(gpsLost: Boolean, line: Line, planQuiet: Boolean): Boolean = gpsLost && line != Line.NONE && !planQuiet

    /** Standing still for the voice and the off-route checks: the sensor's word when it has one, else [MoveWatch]. */
    fun standingStill(sensorStill: Boolean?, moveWatchMoving: Boolean): Boolean = sensorStill ?: !moveWatchMoving
}

/**
 * Still or moving from accelerometer samples (m/s², with gravity): the spread of the acceleration vector over the last
 * [WINDOW_MS] – independent of how the phone lies. A table ≈ 0.02, a pocket while sitting ≈ 0.2, walking / riding ≥ 1.
 * Unsure (null) with less than [MIN_SPAN_MS] of samples or none for [STALE_MS]. Pure.
 */
class StillDetector {
    companion object {
        const val WINDOW_MS = 10_000L
        const val MIN_SPAN_MS = 6_000L
        const val STALE_MS = 3_000L
        /** Spread (m/s²) below which the phone counts as still. */
        const val STILL_SPREAD = 0.5
    }

    private val t = ArrayDeque<Long>()
    private val v = ArrayDeque<FloatArray>()

    fun add(tMs: Long, x: Float, y: Float, z: Float) {
        t.addLast(tMs); v.addLast(floatArrayOf(x, y, z))
        while (t.isNotEmpty() && tMs - t.first() > WINDOW_MS) { t.removeFirst(); v.removeFirst() }
    }

    fun reset() { t.clear(); v.clear() }

    /** True still, false moving, null not sure (too few or too old samples). */
    fun still(nowMs: Long): Boolean? {
        if (t.isEmpty() || nowMs - t.last() > STALE_MS || t.last() - t.first() < MIN_SPAN_MS) return null
        var spread = 0.0
        for (axis in 0..2) {
            val mean = v.sumOf { it[axis].toDouble() } / v.size
            spread += v.sumOf { (it[axis] - mean) * (it[axis] - mean) } / v.size
        }
        return sqrt(spread) < STILL_SPREAD
    }
}

/**
 * The accelerometer behind [StillDetector] – listens only while asked ([listen]: a recording without a GPS fix, battery).
 * No sensor (or it refuses): [still] stays null and the old rule holds; said once in Problems. Never throws.
 */
class MotionSense(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = StillDetector()
    private var on = false
    private var missingSaid = false

    @Synchronized fun listen(wanted: Boolean) {
        if (wanted == on) return
        if (wanted) {
            if (sm == null || acc == null) {
                if (!missingSaid) { missingSaid = true; Problems.info("gps", "No motion sensor: 'no GPS' uses the old rule (moving = a point within 2 min)") }
                return
            }
            on = try {
                sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (e: Exception) {
                Problems.warn("gps", "Motion sensor not started: 'no GPS' uses the old rule", e); false
            }
        } else {
            try { sm?.unregisterListener(this) } catch (e: Exception) { Problems.warn("gps", "Motion sensor not stopped", e) }
            on = false
            detector.reset()
        }
    }

    /** True still, false moving, null not listening / not sure. [nowMs] = elapsedRealtime. */
    @Synchronized fun still(nowMs: Long): Boolean? = if (on) detector.still(nowMs) else null

    @Synchronized override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 3) return
        detector.add(e.timestamp / 1_000_000L, e.values[0], e.values[1], e.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit // expected: accuracy does not change the rule
}
