package com.onpath.app.recording

import com.onpath.strings.*

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.onpath.app.debug.Problems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GPS silent this long → an accurate network fix may stand in (indoors). */
const val GPS_STALE_MS = 30_000L
/** Network fixes worse than this are never shown once GPS was seen (cell towers: km off). */
const val NET_MAX_ACCURACY_M = 150f

/** A GPS fix worse than this is weak (station roof, indoors at a window): a clearly better Wi-Fi fix may win. */
const val GPS_WEAK_M = 30f
/** A network fix counts as current this long (network updates come every 5 s). */
const val NET_FRESH_MS = 12_000L

/**
 * Whether a network fix is shown: before the first GPS fix; when GPS is stale; or (owner, e.g. under a station roof)
 * when the last GPS fix is weak and this one is at least twice as accurate – in the last two cases only when it is
 * Wi-Fi accurate. Pure.
 */
fun useNetworkFix(lastGpsElapsed: Long, nowElapsed: Long, hasAccuracy: Boolean, accuracyM: Float,
                  lastGpsAccuracyM: Float = 0f): Boolean =
    lastGpsElapsed == 0L || (hasAccuracy && accuracyM <= NET_MAX_ACCURACY_M &&
        (nowElapsed - lastGpsElapsed > GPS_STALE_MS || (lastGpsAccuracyM > GPS_WEAK_M && accuracyM * 2 <= lastGpsAccuracyM)))

/**
 * Whether a GPS fix is shown: always, unless it is weak and a current network fix is at least twice as accurate
 * (otherwise the dot would jump between the two every second). [gpsAccuracyM] null = unknown → shown. Pure.
 */
fun showGpsFix(gpsAccuracyM: Float?, lastNetElapsed: Long, lastNetAccuracyM: Float, nowElapsed: Long): Boolean =
    gpsAccuracyM == null || gpsAccuracyM <= GPS_WEAK_M || lastNetElapsed == 0L ||
        nowElapsed - lastNetElapsed > NET_FRESH_MS || lastNetAccuracyM * 2 > gpsAccuracyM

enum class GpsStatus(private val res: org.jetbrains.compose.resources.StringResource) {
    NO_PERMISSION(Res.string.nav_gps_no_permission),
    OFF(Res.string.nav_gps_off),
    SEARCHING(Res.string.nav_gps_searching),
    WEAK(Res.string.nav_gps_weak),
    GOOD(Res.string.nav_gps_good);

    val label: String get() = Txt.s(res)
}

/**
 * Thin wrapper over the platform LocationManager (GPS + network), no Play Services.
 * GPS fixes win. A network (Wi-Fi / cell) fix is used before the first GPS fix, and again when GPS has been silent
 * for [GPS_STALE_MS] (indoors, a restaurant – owner: "when I plan it must know where I am") or is weak
 * and the Wi-Fi fix twice as accurate (train station roof) – but only when it is at least
 * [NET_MAX_ACCURACY_M] accurate (Wi-Fi), never a cell guess kilometres off in the mountains.
 * [useNetwork] = false never asks for it (recording service: the track is GPS only). A tour started indoors adds the
 * fused provider until its first GPS fix ([setFusedWanted]; the app's tracker only).
 * While a [DemoLocation] demo runs, real fixes are ignored and the simulated ones are published instead.
 */
class LocationTracker(context: Context, private var gpsIntervalMs: Long = 1000L, private val useNetwork: Boolean = true) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var running = false
    private var netRunning = false
    private var lastGpsElapsed = 0L
    private var lastGpsAccuracy = 0f
    /** Last network fix that was shown (time + accuracy), for [showGpsFix]. */
    private var lastNetElapsed = 0L
    private var lastNetAccuracy = 0f
    var onFix: ((Location) -> Unit)? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!DemoLocation.takesRealFix()) return
            val now = SystemClock.elapsedRealtime()
            lastGpsElapsed = now
            lastGpsAccuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
            if (showGpsFix(if (loc.hasAccuracy()) loc.accuracy else null, lastNetElapsed, lastNetAccuracy, now)) publish(loc)
        }
        override fun onProviderEnabled(provider: String) { _enabled.value = isEnabled() }
        override fun onProviderDisabled(provider: String) { _enabled.value = isEnabled() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    /** A network or fused fix: the same rules ([useNetworkFix]) – it stands in for GPS, never beside it. */
    private fun onNetworkLikeFix(loc: Location) {
        if (!DemoLocation.takesRealFix()) return
        val now = SystemClock.elapsedRealtime()
        if (useNetworkFix(lastGpsElapsed, now, loc.hasAccuracy(), loc.accuracy, lastGpsAccuracy)) {
            lastNetElapsed = now; lastNetAccuracy = loc.accuracy
            publish(loc)
        }
    }
    private val netListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onNetworkLikeFix(loc)
        override fun onProviderEnabled(provider: String) { _enabled.value = isEnabled() }
        override fun onProviderDisabled(provider: String) { _enabled.value = isEnabled() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    /** Separate object: removeUpdates(netListener) must not end the fused request, nor the other way round. */
    private val fusedListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = onNetworkLikeFix(loc)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    /** [setFusedWanted]: a tour waits for its first GPS fix (indoors); [fusedRunning]: the request is active. */
    private var fusedWanted = false
    private var fusedRunning = false

    /** Simulated fixes (Developer tools → Demo) take the place of GPS; at its end the phone's own last fix. */
    private val demoSink = object : DemoLocation.Sink {
        override fun onDemo(fix: DemoFix, timeMs: Long) {
            val loc = DemoLocation.toLocation(fix, timeMs)
            lastGpsElapsed = SystemClock.elapsedRealtime(); lastGpsAccuracy = loc.accuracy
            _enabled.value = true
            publish(loc)
        }
        override fun onDemoEnd() {
            lastGpsElapsed = 0L; lastGpsAccuracy = 0f; lastNetElapsed = 0L
            _location.value = if (hasPermission()) lastKnownReal() else null
            _enabled.value = isEnabled()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownReal(): Location? = listOfNotNull(
        // expected: a provider may be missing or have no last fix; the next real fix follows from the listener
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
        if (useNetwork) runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull() else null,
    ).maxByOrNull { it.elapsedRealtimeNanos }

    private fun publish(loc: Location) {
        _location.value = loc
        onFix?.invoke(loc)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Precise location: what recording needs (an "approximate" grant delivers nothing usable from GPS). */
    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The device has a GPS provider at all (emulators and some tablets do not). */
    fun hasGps(): Boolean = DemoLocation.active || try { LocationManager.GPS_PROVIDER in lm.allProviders } catch (e: Exception) {
        Problems.warn("gps", "Location providers could not be listed; treating device as without GPS", e)
        false
    }

    fun isEnabled(): Boolean = DemoLocation.active || try {
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        Problems.warn("gps", "Location on/off state could not be read; treating location as off", e)
        false
    }

    fun start() {
        _enabled.value = isEnabled()
        if (!running && hasPermission()) startReal()
        DemoLocation.register(demoSink) // a running demo gives its position at once
    }

    @SuppressLint("MissingPermission")
    private fun startReal() {
        try {
            val providers = lm.allProviders
            if (LocationManager.GPS_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsIntervalMs, 0f, gpsListener, Looper.getMainLooper())
            }
            if (useNetwork && LocationManager.NETWORK_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, netListener, Looper.getMainLooper())
                netRunning = true
            }
            running = true
            if (fusedWanted) startFused()
            // Seed with the last known position so the map has something immediately (not during a demo: never real).
            if (!DemoLocation.takesRealFix()) Unit
            else if (_location.value == null) {
                val last = lastKnownReal()
                if (last != null) _location.value = last
            } else {
                // Back from the lock screen / background: the position we hold is from before. A tour keeps GPS on in
                // the recording service, so the phone's last GPS fix is seconds old – show it at once.
                // expected: no last GPS fix (GPS off / never used) – the next fix comes from the listener
                val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
                if (gps != null && gps.elapsedRealtimeNanos > _location.value!!.elapsedRealtimeNanos) {
                    lastGpsElapsed = gps.elapsedRealtimeNanos / 1_000_000
                    lastGpsAccuracy = if (gps.hasAccuracy()) gps.accuracy else 0f
                    _location.value = gps
                }
            }
        } catch (e: SecurityException) {
            Problems.error("gps", "Location updates refused (permission withdrawn?); no GPS fixes", e)
            running = false
        } catch (e: Exception) {
            Problems.error("gps", "Location updates could not be started; no GPS fixes", e)
            running = false
        }
    }

    fun stop() {
        DemoLocation.unregister(demoSink)
        if (!running) return
        try { lm.removeUpdates(gpsListener) } catch (e: Exception) {
            Problems.warn("gps", "Location updates could not be removed on stop", e)
        }
        stopNetwork()
        stopFused()
        running = false
    }

    /**
     * Provisional position for a tour started indoors ([com.onpath.app.navigation.Provisional], owner): while [on], the
     * platform fused provider (Android 12+, no Play Services library; on a Pixel Google's location service – Wi-Fi, a
     * faster and better first fix indoors) is asked every [FUSED_MS] beside GPS. Below Android 12 or without it the
     * network request stands in. Only while the app is visible (it stops with [stop]) and only until the tour's first
     * GPS fix, a pause or the end of the tour – the caller switches it off.
     */
    fun setFusedWanted(on: Boolean) {
        if (on == fusedWanted) return
        fusedWanted = on
        if (!running) return
        if (on) startFused() else stopFused()
    }

    @SuppressLint("MissingPermission")
    private fun startFused() {
        if (fusedRunning || !useNetwork) return
        if (Build.VERSION.SDK_INT < 31) {
            Problems.info("gps", "Tour without a GPS fix yet: Android ${Build.VERSION.SDK_INT} has no fused provider, " +
                "the network position stands in")
            return
        }
        try {
            if (!lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                Problems.info("gps", "Tour without a GPS fix yet: no fused provider on this phone, the network position stands in")
                return
            }
            val request = android.location.LocationRequest.Builder(FUSED_MS)
                .setQuality(android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).build()
            lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, ContextCompat.getMainExecutor(app), fusedListener)
            fusedRunning = true
            Problems.trace("gps", "Tour without a GPS fix yet: fused position asked every ${FUSED_MS / 1000} s until the first GPS fix")
            // a recent fused fix (Wi-Fi) at once – the screen is useful before the first update
            // (never an old one: the dot must not jump to where the phone was an hour ago)
            lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.let { last ->
                val held = _location.value
                val fresh = SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos <=
                    com.onpath.app.navigation.Provisional.MAX_AGE_MS * 1_000_000L
                if (fresh && (held == null || last.elapsedRealtimeNanos > held.elapsedRealtimeNanos)) onNetworkLikeFix(last)
            }
        } catch (e: Exception) {
            Problems.warn("gps", "Fused position could not be asked; the network position stands in until the first GPS fix", e)
        }
    }

    private fun stopFused() {
        if (!fusedRunning) return
        fusedRunning = false
        try {
            lm.removeUpdates(fusedListener)
            Problems.trace("gps", "Fused position no longer asked (GPS fix, pause or tour end)")
        } catch (e: Exception) {
            Problems.warn("gps", "Fused location updates could not be removed", e)
        }
    }

    /** Change the GPS rate while running (pause = slower); GPS is never switched off by this. */
    @SuppressLint("MissingPermission")
    fun setInterval(ms: Long) {
        if (ms == gpsIntervalMs) return
        gpsIntervalMs = ms
        if (!running) return
        try {
            lm.removeUpdates(gpsListener)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ms, 0f, gpsListener, Looper.getMainLooper())
        } catch (e: Exception) {
            Problems.error("gps", "GPS rate could not be changed; trying to keep GPS running", e)
            running = false
            start()
        }
    }

    private fun stopNetwork() {
        if (!netRunning) return
        netRunning = false
        try { lm.removeUpdates(netListener) } catch (e: Exception) {
            Problems.warn("gps", "Network location updates could not be removed", e)
        }
    }

    /** Status shown in the GPS chip. */
    fun status(): GpsStatus {
        if (!hasPermission()) return GpsStatus.NO_PERMISSION
        if (!isEnabled()) return GpsStatus.OFF
        val loc = _location.value ?: return GpsStatus.SEARCHING
        val age = ageSeconds(loc)
        return when {
            age > 20 -> GpsStatus.SEARCHING
            !loc.hasAccuracy() || loc.accuracy > 25f -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }
    }

    companion object {
        /** Fused request rate while a tour waits for its first GPS fix. */
        const val FUSED_MS = 2_000L

        fun ageSeconds(loc: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L
    }
}
