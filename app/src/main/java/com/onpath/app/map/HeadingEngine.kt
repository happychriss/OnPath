package com.onpath.app.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.onpath.app.debug.Problems
import android.view.Surface
import android.view.WindowManager
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Walking direction for the Navigate camera (heading-up). Pure, unit-tested.
 *
 * Moving (GPS speed ≥ [MOVING_MPS] with a bearing): the GPS bearing wins – it is where you walk, not where
 * the phone points. Standing or slow: the compass (rotation-vector sensor). Both are smoothed and a change
 * smaller than [DEADBAND_DEG] is not passed on, so the map does not jitter while you stand still.
 */
class HeadingFilter {
    companion object {
        const val MOVING_MPS = 1.0f
        /** Below this the GPS bearing is no longer trusted (hysteresis). */
        const val SLOW_MPS = 0.6f
        const val GPS_VALID_MS = 4_000L
        const val DEADBAND_DEG = 3.0
        private const val SENSOR_ALPHA = 0.12
        private const val GPS_ALPHA = 0.5

        fun diff(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180) d -= 360.0
            if (d <= -180) d += 360.0
            return d
        }
        fun norm(d: Double) = ((d % 360.0) + 360.0) % 360.0
    }

    private var smooth: Double? = null
    /** Last value passed on (null = none yet). */
    var heading: Double? = null; private set
    private var gpsAt = -1L
    private var gpsMoving = false

    /** True while the GPS bearing is used (moving). */
    fun usingGps(nowMs: Long) = gpsMoving && gpsAt >= 0 && nowMs - gpsAt <= GPS_VALID_MS

    /** A GPS fix; returns the new heading to show or null when it did not change enough. */
    fun onGps(bearing: Float?, speedMps: Float?, nowMs: Long): Double? {
        val speed = speedMps ?: 0f
        gpsMoving = bearing != null && if (gpsMoving) speed >= SLOW_MPS else speed >= MOVING_MPS
        if (!gpsMoving || bearing == null) return null
        gpsAt = nowMs
        return step(bearing.toDouble(), GPS_ALPHA)
    }

    /** A compass reading (degrees from true north); ignored while moving with GPS. */
    fun onSensor(headingDeg: Double, nowMs: Long): Double? {
        if (headingDeg.isNaN() || usingGps(nowMs)) return null
        return step(headingDeg, SENSOR_ALPHA)
    }

    private fun step(target: Double, alpha: Double): Double? {
        val s = smooth?.let { norm(it + alpha * diff(it, target)) } ?: norm(target)
        smooth = s
        val last = heading
        if (last != null && abs(diff(last, s)) < DEADBAND_DEG) return null
        heading = s
        return s
    }
}

/**
 * [CompassEngine] for MapLibre's location component: rotation-vector sensor plus GPS bearing (see
 * [HeadingFilter]). MapLibre adds a listener only while the render or camera mode uses the compass
 * (Navigate), so the sensor is registered only then and released as soon as the last listener goes.
 * Without a sensor it still works from the GPS bearing; nothing here throws.
 */
class HeadingEngine(context: Context) : CompassEngine, SensorEventListener {
    private val app = context.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = try {
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    } catch (e: Exception) { Problems.warn("sensor", "Rotation sensor lookup failed, heading from GPS only", e); null }
    private val listeners = LinkedHashSet<CompassListener>()
    private val filter = HeadingFilter()
    private var registered = false
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var declination = 0f
    private val r = FloatArray(9)

    val isListening: Boolean get() = registered
    private var noSensorReported = false

    override fun addCompassListener(l: CompassListener) {
        listeners += l
        if (!registered && sensor == null && !noSensorReported) {
            noSensorReported = true
            Problems.info("sensor", "No rotation sensor – map heading follows the GPS bearing only")
        }
        if (!registered && sensor != null) {
            registered = try {
                sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
            } catch (e: Exception) {
                Problems.warn("sensor", "Compass sensor could not be registered, heading from GPS only", e); false
            }
        }
    }

    override fun removeCompassListener(l: CompassListener) {
        listeners -= l
        if (listeners.isEmpty()) release()
    }

    fun release() {
        if (registered) try { sm?.unregisterListener(this) } catch (e: Exception) { Problems.warn("sensor", "Compass sensor could not be released", e) }
        registered = false
    }

    override fun getLastHeading(): Float = (filter.heading ?: 0.0).toFloat()
    override fun getLastAccuracySensorStatus(): Int = accuracy

    /** Every fix: GPS bearing while moving, and the magnetic declination for the compass. */
    fun onLocation(loc: Location) {
        try {
            declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time).declination
        } catch (e: Exception) { Problems.warn("sensor", "Magnetic declination not computed, compass keeps the last value", e) }
        if (listeners.isEmpty()) return
        val h = filter.onGps(if (loc.hasBearing()) loc.bearing else null, if (loc.hasSpeed()) loc.speed else null, now())
        if (h != null) emit(h)
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            SensorManager.getRotationMatrixFromVector(r, event.values)
            // Direction the screen's top edge points to, plus where the back of the phone looks: flat → top edge,
            // held upright → camera direction; in between both point forward. No gimbal flip at 45°.
            val (ux, uy) = when (displayRotation()) {
                Surface.ROTATION_90 -> r[0] to r[3]      // screen top = device +X
                Surface.ROTATION_180 -> -r[1] to -r[4]   // device −Y
                Surface.ROTATION_270 -> -r[0] to -r[3]   // device −X
                else -> r[1] to r[4]                     // device +Y
            }
            val bx = -r[2]; val by = -r[5]               // device −Z (back) in world east/north
            val ex = ux + bx; val ny = uy + by
            if (hypot(ex, ny) < 0.2f) return
            val magnetic = Math.toDegrees(atan2(ex.toDouble(), ny.toDouble()))
            val h = filter.onSensor(HeadingFilter.norm(magnetic + declination), now()) ?: return
            emit(h)
        } catch (e: Exception) {
            Problems.warn("sensor", "Compass reading failed, reading skipped", e)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) {
        accuracy = acc
        listeners.toList().forEach { l ->
            try { l.onCompassAccuracyChange(acc) } catch (e: Exception) { Problems.warn("sensor", "Compass accuracy not passed to the map", e) }
        }
    }

    private fun emit(h: Double) = listeners.toList().forEach { l ->
        try { l.onCompassChanged(h.toFloat()) } catch (e: Exception) { Problems.warn("sensor", "Heading not passed to the map", e) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (e: Exception) { Problems.warn("sensor", "Display rotation unreadable, assuming portrait", e); Surface.ROTATION_0 }

    private fun now() = android.os.SystemClock.elapsedRealtime()
}
