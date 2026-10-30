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
 * On a followed route (Navigate, matched to the line): the direction of the route ahead wins ([onRoute]) – the
 * next stretch points straight up, whatever the compass or a wobbly GPS bearing says (owner: "the way should be a
 * straight line in front of me"). Exception: walking clearly against it (GPS bearing > [OPPOSED_DEG]
 * off) – then the GPS bearing, so the map never shows you walking down the screen.
 * Otherwise, moving (GPS speed ≥ [MOVING_MPS] with a bearing): the GPS bearing – it is where you walk, not where
 * the phone points. Standing or slow: the compass (rotation-vector sensor). All are smoothed and a change
 * smaller than [DEADBAND_DEG] is not passed on, so the map does not jitter while you stand still.
 * Route and GPS (1 fix/s) are smoothed only lightly: ~90 % of a turn after 1–2 fixes (owner: no lag when turning).
 */
class HeadingFilter {
    companion object {
        const val MOVING_MPS = 1.0f
        /** Below this the GPS bearing is no longer trusted (hysteresis). */
        const val SLOW_MPS = 0.6f
        const val GPS_VALID_MS = 4_000L
        const val DEADBAND_DEG = 2.0
        private const val SENSOR_ALPHA = 0.12
        private const val GPS_ALPHA = 0.8
        private const val ROUTE_ALPHA = 0.85
        /** A route direction older than this is dropped (no new fix matched to the line). */
        const val ROUTE_VALID_MS = 15_000L
        /** Moving with a GPS bearing this far from the route direction = walking against the route. */
        const val OPPOSED_DEG = 100.0
        /** Walked this far → a movement direction from positions (works also below [MOVING_MPS]). */
        const val MOVE_STEP_M = 15.0
        /** A movement direction is trusted this long. */
        const val MOVE_VALID_MS = 30_000L
        /** Positions worse than this do not count for the movement direction. */
        const val MOVE_MAX_ACCURACY_M = 20.0

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
    private var gpsBearing = 0.0
    private var route: Double? = null
    private var routeAt = -1L
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var moveBearing: Double? = null
    private var moveAt = -1L

    /**
     * A position (any speed): every [MOVE_STEP_M] walked gives the direction you really move in. Slow uphill or a
     * wrong match on an out-and-back route can make the route direction point backwards (map turned by 180°); walking
     * against the route ahead switches to compass / GPS.
     */
    fun onPosition(lat: Double, lon: Double, accuracyM: Double, nowMs: Long) {
        if (accuracyM.isNaN() || accuracyM > MOVE_MAX_ACCURACY_M) return
        if (anchorLat.isNaN()) { anchorLat = lat; anchorLon = lon; return }
        val kx = Math.cos(Math.toRadians(lat)) * 111_320.0
        val dx = (lon - anchorLon) * kx
        val dy = (lat - anchorLat) * 110_540.0
        if (hypot(dx, dy) < MOVE_STEP_M) return
        moveBearing = norm(Math.toDegrees(atan2(dx, dy)))
        moveAt = nowMs
        anchorLat = lat; anchorLon = lon
    }

    private fun movingAgainst(r: Double, nowMs: Long): Boolean {
        if (usingGps(nowMs) && abs(diff(r, gpsBearing)) > OPPOSED_DEG) return true
        val m = moveBearing ?: return false
        return nowMs - moveAt <= MOVE_VALID_MS && abs(diff(r, m)) > OPPOSED_DEG
    }

    /** True while the GPS bearing is used (moving). */
    fun usingGps(nowMs: Long) = gpsMoving && gpsAt >= 0 && nowMs - gpsAt <= GPS_VALID_MS

    /** True while the route direction is used (on the route, not walking against it). */
    fun usingRoute(nowMs: Long): Boolean {
        val r = route ?: return false
        if (nowMs - routeAt > ROUTE_VALID_MS) return false
        return !movingAgainst(r, nowMs)
    }

    /**
     * Direction of the route ahead (null = not on a followed route any more); returns the new heading to show
     * or null when it did not change enough / is not used.
     */
    fun onRoute(bearingDeg: Double?, nowMs: Long): Double? {
        if (bearingDeg == null || bearingDeg.isNaN()) { route = null; return null }
        route = norm(bearingDeg); routeAt = nowMs
        if (!usingRoute(nowMs)) return null
        return step(route!!, ROUTE_ALPHA)
    }

    /** A GPS fix; returns the new heading to show or null when it did not change enough. */
    fun onGps(bearing: Float?, speedMps: Float?, nowMs: Long): Double? {
        val speed = speedMps ?: 0f
        gpsMoving = bearing != null && if (gpsMoving) speed >= SLOW_MPS else speed >= MOVING_MPS
        if (!gpsMoving || bearing == null) return null
        gpsAt = nowMs
        gpsBearing = bearing.toDouble()
        if (usingRoute(nowMs)) return null
        return step(gpsBearing, GPS_ALPHA)
    }

    /** A compass reading (degrees from true north); ignored while moving with GPS or on a followed route. */
    fun onSensor(headingDeg: Double, nowMs: Long): Double? {
        if (headingDeg.isNaN() || usingGps(nowMs) || usingRoute(nowMs)) return null
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
 * Two directions, like Google Maps (owner):
 * - **view** – where you face: compass standing, GPS bearing moving. Shown by the arrow at the position dot.
 * - **map** – how the map is turned in heading-up: on a followed route the route ahead (the way stays straight up),
 *   else the same as view.
 * MapLibre's [CompassEngine] (camera TRACKING_COMPASS and the puck's own arrow) gets the map direction while
 * [mapToCompass] (heading-up following – the app then draws the view arrow itself, see ui/FacingArrow), else the view
 * direction (camera not turned by it; the puck's arrow shows where you face). The app never moves the camera itself:
 * MapLibre ends its tracking on every app camera move.
 * Both are turned smoothly at display rate (ease-out, [TURN_TAU_S]); the sensor runs only while MapLibre listens (map
 * visible). Without a sensor it works from the GPS bearing; nothing here throws.
 */
class HeadingEngine(context: Context) : CompassEngine, SensorEventListener {
    companion object {
        /** Ease-out time constant of a turn: ~90 % in 0.5 s. */
        const val TURN_TAU_S = 0.22
    }
    private val app = context.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = try {
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    } catch (e: Exception) { Problems.warn("sensor", "Rotation sensor lookup failed, heading from GPS only", e); null }
    private val listeners = LinkedHashSet<CompassListener>()

    /** View direction (where you face) at display rate, for the app's own arrow; main thread. */
    var onViewHeading: ((Double) -> Unit)? = null

    /** True: MapLibre gets the map direction (heading-up camera); false: the view direction. */
    var mapToCompass = false
        set(v) { if (field != v) { field = v; reemit() } }

    private val viewFilter = HeadingFilter()
    private val mapFilter = HeadingFilter()

    /** One smoothly animated direction. */
    private inner class Channel(val out: (Double) -> Unit) {
        var shown: Double? = null
        var target: Double? = null
        fun set(h: Double) { target = h; if (quiet) shown = h else if (shown == null || choreographer == null) { shown = h; out(h) } else kick() }
        /** Next frame; true while still moving. */
        fun step(dt: Double): Boolean {
            val s = shown ?: return false; val t = target ?: return false
            val d = HeadingFilter.diff(s, t)
            if (abs(d) < 0.05) { if (s != t) { shown = t; out(t) }; return false }
            val next = HeadingFilter.norm(s + d * (1 - Math.exp(-dt / TURN_TAU_S)))
            shown = next; out(next)
            return true
        }
    }
    private val view = Channel { h ->
        if (!mapToCompass) pushView(h)
        try { onViewHeading?.invoke(h) } catch (e: Exception) { Problems.warn("sensor", "Facing arrow not updated", e) }
    }
    private val mapCh = Channel { h -> if (mapToCompass) pushView(h) }

    /** The direction MapLibre gets now. */
    private fun current(): Double? = if (mapToCompass) mapCh.shown ?: mapFilter.heading else view.shown ?: viewFilter.heading

    private val choreographer: android.view.Choreographer? = try { android.view.Choreographer.getInstance() } catch (e: Exception) {
        Problems.warn("sensor", "No frame clock – the map turns without smoothing", e); null
    }
    private var lastFrameNs = 0L
    private var animating = false
    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (listeners.isEmpty()) { animating = false; return }
            val dt = if (lastFrameNs == 0L) 1.0 / 60 else ((frameTimeNanos - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
            lastFrameNs = frameTimeNanos
            val a = view.step(dt)
            val b = mapCh.step(dt)
            if (a || b) choreographer?.postFrameCallback(this) else animating = false
        }
    }
    private fun kick() {
        if (!animating && listeners.isNotEmpty() && choreographer != null) {
            animating = true; lastFrameNs = 0L
            choreographer.postFrameCallback(frame)
        }
    }

    private var registered = false
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var declination = 0f
    private val r = FloatArray(9)

    private var noSensorReported = false

    override fun addCompassListener(l: CompassListener) {
        listeners += l
        // a (re)added listener gets the current direction at once – it must not wait for the next change
        if (!quiet) current()?.let { h -> try { l.onCompassChanged(h.toFloat()) } catch (e: Exception) { Problems.warn("sensor", "Heading not passed to the map", e) } }
        if (!registered && sensor == null && !noSensorReported) {
            noSensorReported = true
            Problems.info("sensor", "No rotation sensor – map heading follows the GPS bearing only")
        }
        register()
    }

    private fun register() {
        if (!registered && sensor != null && !quiet) {
            registered = try {
                sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
            } catch (e: Exception) {
                Problems.warn("sensor", "Compass sensor could not be registered, heading from GPS only", e); false
            }
        }
    }

    /**
     * The map is covered by the rest view (ui/RestView.kt, cycling): nothing goes to MapLibre (no camera turns, so the
     * map draws no frames) and the sensor is off; the route / GPS direction is still taken in. Back to false: the
     * sensor again and the current direction at once (no glide from the old one).
     */
    var quiet = false
        set(v) {
            if (field == v) return
            field = v
            if (v) {
                release()
                if (animating) { choreographer?.removeFrameCallback(frame); animating = false }
            } else if (listeners.isNotEmpty()) {
                register()
                mapCh.target?.let { mapCh.shown = it }
                view.target?.let { view.shown = it }
                reemit()
            }
        }

    override fun removeCompassListener(l: CompassListener) {
        listeners -= l
        if (listeners.isEmpty()) {
            release()
            if (animating) { choreographer?.removeFrameCallback(frame); animating = false }
        }
    }

    fun release() {
        if (registered) try { sm?.unregisterListener(this) } catch (e: Exception) { Problems.warn("sensor", "Compass sensor could not be released", e) }
        registered = false
    }

    override fun getLastHeading(): Float = (current() ?: 0.0).toFloat()

    /** Where you face now (for the arrow), or null. */
    val viewHeading: Double? get() = view.shown ?: viewFilter.heading
    override fun getLastAccuracySensorStatus(): Int = accuracy

    /** Every fix: GPS bearing while moving, movement direction, and the magnetic declination for the compass. */
    fun onLocation(loc: Location) {
        try {
            declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), loc.time).declination
        } catch (e: Exception) { Problems.warn("sensor", "Magnetic declination not computed, compass keeps the last value", e) }
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN
        try {
            viewFilter.onPosition(loc.latitude, loc.longitude, acc, now())
            mapFilter.onPosition(loc.latitude, loc.longitude, acc, now())
        } catch (e: Exception) { Problems.warn("sensor", "Movement direction not updated", e) }
        if (listeners.isEmpty()) return
        val b = if (loc.hasBearing()) loc.bearing else null
        val sp = if (loc.hasSpeed()) loc.speed else null
        viewFilter.onGps(b, sp, now())?.let { view.set(it) }
        // every fix passes the map direction on, changed or not (after Plan → Navigate the camera must not wait for a
        // change of direction)
        val m = mapFilter.onGps(b, sp, now())
        if (m != null) mapCh.set(m) else reemit()
    }

    /** Passes the current direction on again (camera mode changed: the new tracking starts from it). */
    fun reemit() { current()?.let { if (listeners.isNotEmpty()) pushView(it) } }

    /** Direction of the followed route ahead (Navigate, on the route); null = none (compass / GPS again). Map only. */
    fun setRoute(bearingDeg: Double?) {
        val h = mapFilter.onRoute(bearingDeg, now())
        if (h != null && listeners.isNotEmpty()) mapCh.set(h)
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
            val true_ = HeadingFilter.norm(Math.toDegrees(atan2(ex.toDouble(), ny.toDouble())) + declination)
            viewFilter.onSensor(true_, now())?.let { view.set(it) }
            mapFilter.onSensor(true_, now())?.let { mapCh.set(it) }
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

    private fun pushView(h: Double) = if (quiet) Unit else listeners.toList().forEach { l ->
        try { l.onCompassChanged(h.toFloat()) } catch (e: Exception) { Problems.warn("sensor", "Heading not passed to the map", e) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (e: Exception) { Problems.warn("sensor", "Display rotation unreadable, assuming portrait", e); Surface.ROTATION_0 }

    private fun now() = android.os.SystemClock.elapsedRealtime()
}
