package com.onpath.app.recording

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.onpath.app.debug.Problems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Demo location (Settings → Diagnostics → Developer tools → Demo): simulated GPS fixes for screenshots that must not
 * show where the owner really is. While a demo runs, every [LocationTracker] (the app's and the recording service's)
 * ignores real GPS and publishes these fixes instead – the same pipeline as real fixes, so navigation, turn cues,
 * arrival, recording, re-routing and the lock-screen notification behave as on a real tour.
 *
 * - **Ride**: along a line ([DemoPath]) at the activity's modelled speed × [State.factor] (1× / 5× / 20×), one fix a
 *   second. The fix time runs on a simulated clock (factor × faster), so the recorded track has realistic speeds and the
 *   point filter keeps it; moving time and pace shown during the tour are realistic only at 1×. At the end it stands.
 * - **Place**: a fixed position (non-moving shots).
 *
 * Process memory only: an app restart (process ends) always means real GPS again; a demo also stops by itself after
 * [MAX_RUN_MS]. Tours recorded while it runs are named "Demo · …" ([TOUR_PREFIX]). Main thread only.
 */
object DemoLocation {
    enum class Kind { RIDE, PLACE }

    data class State(
        val kind: Kind,
        /** "planned route", "GPX track …", "map centre" – for the Developer tools line and the problem log. */
        val label: String,
        val factor: Int,
        val paused: Boolean,
        val alongM: Double,
        val lengthM: Double,
        val ended: Boolean,
    )

    /** Who takes the simulated fixes ([LocationTracker]). */
    interface Sink {
        /** A simulated fix; [timeMs] = the simulated clock (Location.time). */
        fun onDemo(fix: DemoFix, timeMs: Long)
        /** The demo stopped: back to real GPS. */
        fun onDemoEnd()
    }

    val FACTORS = listOf(1, 5, 20)
    const val TICK_MS = 1000L
    const val MAX_RUN_MS = 6 * 60 * 60 * 1000L
    const val ACCURACY_M = 5f
    /** Name prefix of tours recorded during a demo (easy to find and delete). */
    const val TOUR_PREFIX = "Demo · "
    /** Location extra marking a simulated fix. */
    const val EXTRA_DEMO = "onpath.demo"

    private val _state = MutableStateFlow<State?>(null)
    /** null = off (real GPS). */
    val state: StateFlow<State?> = _state
    val active: Boolean get() = _state.value != null

    /** The switch: real GPS fixes are used only while no demo runs. */
    fun takesRealFix(): Boolean = !active

    private val sinks = CopyOnWriteArraySet<Sink>()
    private var path: DemoPath? = null
    private var place: DemoFix? = null
    private var simS = 0.0
    private var clockMs = 0L
    private var ranMs = 0L
    private var last: Pair<DemoFix, Long>? = null

    /** Adds [s] (idempotent); a running demo gives it the current position at once. */
    fun register(s: Sink) {
        if (sinks.add(s)) last?.let { (f, t) -> s.onDemo(f, t) }
    }

    fun unregister(s: Sink) { sinks.remove(s) }

    /** Rides [p] from its start. [autoTick] false = the caller ticks ([tick], tests). */
    fun startRide(p: DemoPath, label: String, factor: Int = 1, nowMs: Long = System.currentTimeMillis(), autoTick: Boolean = true) {
        begin(nowMs)
        path = p; place = null
        _state.value = State(Kind.RIDE, label, factor.coerceIn(1, FACTORS.last()), paused = false, alongM = 0.0, lengthM = p.lengthM,
            ended = false)
        Problems.info("demo", "Demo location ON: riding the $label (${"%.1f".format(java.util.Locale.ROOT, p.lengthM / 1000)} km, " +
            "${(p.durationS / 60).toInt()} min at 1×) – real GPS ignored")
        emit(p.at(0.0))
        if (autoTick) startTicker()
    }

    /** Stands at [lat] / [lon] (a fixed simulated position). */
    fun jumpTo(lat: Double, lon: Double, ele: Double?, label: String, nowMs: Long = System.currentTimeMillis(), autoTick: Boolean = true) {
        begin(nowMs)
        path = null
        place = DemoFix(lat, lon, ele, null, 0.0, 0.0, ended = false)
        _state.value = State(Kind.PLACE, label, 1, paused = false, alongM = 0.0, lengthM = 0.0, ended = false)
        Problems.info("demo", "Demo location ON: standing at the $label – real GPS ignored")
        emit(place!!)
        if (autoTick) startTicker()
    }

    fun setFactor(f: Int) { _state.value = _state.value?.copy(factor = f.coerceIn(1, FACTORS.last())) }

    fun setPaused(p: Boolean) { _state.value = _state.value?.copy(paused = p) }

    /** Back to real GPS; every tracker is told at once. */
    fun stop(why: String = "stopped in Developer tools") {
        if (_state.value == null) return
        stopTicker()
        _state.value = null
        path = null; place = null; last = null
        Problems.info("demo", "Demo location OFF ($why) – real GPS again")
        sinks.forEach { s ->
            try { s.onDemoEnd() } catch (e: Exception) { Problems.error("demo", "Demo end not taken by a location user; it may show the demo position", e) }
        }
    }

    /** One step of [realDtMs] real milliseconds: moves on (×factor unless paused / at the end) and sends the fix. */
    fun tick(realDtMs: Long) {
        val st = _state.value ?: return
        ranMs += realDtMs
        if (ranMs > MAX_RUN_MS) { stop("ran ${MAX_RUN_MS / 3_600_000} h – stopped by itself"); return }
        val p = path
        if (p == null) { clockMs += realDtMs; emit(place ?: return); return }
        val moving = !st.paused && !st.ended
        val simDt = realDtMs / 1000.0 * (if (moving) st.factor else 1)
        clockMs += (simDt * 1000).toLong()
        if (moving) simS += simDt
        val f = p.at(simS)
        val shown = if (st.paused) f.copy(bearingDeg = null, speedMs = 0.0) else f
        if (f.ended && !st.ended) Problems.info("demo", "Demo ride reached the end of the ${st.label}")
        _state.value = st.copy(alongM = f.alongM, ended = f.ended)
        emit(shown)
    }

    private fun begin(nowMs: Long) {
        if (active) stop("a new demo started")
        simS = 0.0; clockMs = nowMs; ranMs = 0L
    }

    private fun emit(f: DemoFix) {
        last = f to clockMs
        sinks.forEach { s ->
            try { s.onDemo(f, clockMs) } catch (e: Exception) { Problems.error("demo", "Demo fix not taken by a location user", e) }
        }
    }

    // ---- the ticker (main looper, one fix a second; the recording service's wake lock keeps it going screen-off) ----
    private var handler: Handler? = null
    private var lastTickEl = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            // a late tick (busy phone) moves on by the real time, at most 5 s at once
            tick((now - lastTickEl).coerceIn(0L, 5 * TICK_MS))
            lastTickEl = now
            if (active) handler?.postDelayed(this, TICK_MS)
        }
    }

    private fun startTicker() {
        val h = handler ?: Handler(Looper.getMainLooper()).also { handler = it }
        h.removeCallbacks(ticker)
        lastTickEl = SystemClock.elapsedRealtime()
        h.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() { handler?.removeCallbacks(ticker) }

    // ---- Android Location ----

    /** A fix that looks like a good GPS fix: ~5 m, bearing and speed while moving, sea-level height from the line. */
    fun toLocation(f: DemoFix, timeMs: Long): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = f.lat; longitude = f.lon
        accuracy = ACCURACY_M
        time = timeMs
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (f.bearingDeg != null && f.speedMs > 0) {
            bearing = f.bearingDeg.toFloat(); speed = f.speedMs.toFloat()
            bearingAccuracyDegrees = 5f; speedAccuracyMetersPerSecond = 0.3f
        } else speed = 0f
        f.ele?.let { e ->
            altitude = e; verticalAccuracyMeters = 3f
            if (Build.VERSION.SDK_INT >= 34) { mslAltitudeMeters = e; mslAltitudeAccuracyMeters = 3f }
        }
        extras = Bundle().apply { putBoolean(EXTRA_DEMO, true) }
    }

    /** True for a fix made by [toLocation]. */
    fun isDemo(loc: Location): Boolean = try { loc.extras?.getBoolean(EXTRA_DEMO, false) == true } catch (e: Exception) {
        Problems.warn("demo", "Location extras unreadable; fix taken as real GPS", e); false
    }
}
