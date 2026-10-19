package com.onpath.app.recording

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

enum class GpsStatus(val label: String) {
    NO_PERMISSION("No location permission"),
    OFF("Location is off"),
    SEARCHING("Searching GPS"),
    WEAK("GPS weak"),
    GOOD("GPS good"),
}

/**
 * Thin wrapper over the platform LocationManager (GPS + network), no Play Services.
 * GPS fixes win; network fixes only seed the position until the first GPS fix. After that a stale GPS fix is
 * kept (its age is shown) rather than a cell/Wi-Fi guess that can be kilometres off in the mountains.
 */
class LocationTracker(context: Context, private val gpsIntervalMs: Long = 1000L) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private var running = false
    private var lastGpsElapsed = 0L
    var onFix: ((Location) -> Unit)? = null

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastGpsElapsed = SystemClock.elapsedRealtime()
            publish(loc)
        }
        override fun onProviderEnabled(provider: String) { _enabled.value = isEnabled() }
        override fun onProviderDisabled(provider: String) { _enabled.value = isEnabled() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    private val netListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (lastGpsElapsed == 0L) publish(loc)
        }
        override fun onProviderEnabled(provider: String) { _enabled.value = isEnabled() }
        override fun onProviderDisabled(provider: String) { _enabled.value = isEnabled() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

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
    fun hasGps(): Boolean = try { LocationManager.GPS_PROVIDER in lm.allProviders } catch (e: Exception) {
        Problems.warn("gps", "Location providers could not be listed; treating device as without GPS", e)
        false
    }

    fun isEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) {
        Problems.warn("gps", "Location on/off state could not be read; treating location as off", e)
        false
    }

    @SuppressLint("MissingPermission")
    fun start() {
        _enabled.value = isEnabled()
        if (running || !hasPermission()) return
        try {
            val providers = lm.allProviders
            if (LocationManager.GPS_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsIntervalMs, 0f, gpsListener, Looper.getMainLooper())
            }
            if (LocationManager.NETWORK_PROVIDER in providers) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, netListener, Looper.getMainLooper())
            }
            running = true
            // Seed with the last known position so the map has something immediately.
            if (_location.value == null) {
                val last = listOfNotNull(
                    // expected: a provider may be missing or have no last fix; it is only a seed for the map
                    runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
                    runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
                ).maxByOrNull { it.elapsedRealtimeNanos }
                if (last != null) _location.value = last
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
        if (!running) return
        try { lm.removeUpdates(gpsListener); lm.removeUpdates(netListener) } catch (e: Exception) {
            Problems.warn("gps", "Location updates could not be removed on stop", e)
        }
        running = false
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
        fun ageSeconds(loc: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L
    }
}
