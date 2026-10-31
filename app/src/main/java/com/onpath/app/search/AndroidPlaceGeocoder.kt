package com.onpath.app.search

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.onpath.app.debug.Problems
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [PlaceGeocoder] by Android's platform `android.location.Geocoder` (no Play Services library: the phone brings its
 * own service, or none – `Geocoder.isPresent()`). API 33+: the listener variant, waited for here (this runs on a
 * background thread); older phones: the blocking call. Missing or failing service → empty list, INFO in the problem log.
 */
class AndroidPlaceGeocoder(
    private val context: Context,
    private val max: Int = 5,
    private val timeoutMs: Long = 8_000,
) : PlaceGeocoder {

    override fun find(text: String): List<Place> {
        if (text.isBlank()) return emptyList()
        if (!Geocoder.isPresent()) {
            Problems.info("search", "This phone has no address service (Geocoder) – a shared place is searched without it")
            return emptyList()
        }
        val addresses: List<Address> = try {
            val g = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= 33) {
                val done = CountDownLatch(1)
                val out = AtomicReference<List<Address>>(emptyList())
                g.getFromLocationName(text, max, object : Geocoder.GeocodeListener {
                    override fun onGeocode(found: MutableList<Address>) { out.set(found.toList()); done.countDown() }
                    override fun onError(errorMessage: String?) {
                        Problems.info("search", "The phone's address service failed: ${errorMessage ?: "no reason given"}")
                        done.countDown()
                    }
                })
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS))
                    Problems.info("search", "The phone's address service did not answer within ${timeoutMs / 1000} s")
                out.get()
            } else {
                @Suppress("DEPRECATION")
                g.getFromLocationName(text, max).orEmpty()
            }
        } catch (e: Exception) {
            Problems.info("search", "The phone's address service failed (offline?)", e)
            emptyList()
        }
        return addresses.mapIndexedNotNull { i, a ->
            if (!a.hasLatitude() || !a.hasLongitude()) null
            else PlaceGeocoder.place(GeocodedAddress(a.latitude, a.longitude, a.featureName, a.thoroughfare, a.subThoroughfare,
                a.postalCode, a.locality, a.subLocality, a.countryCode, if (a.maxAddressLineIndex >= 0) a.getAddressLine(0) else null), i)
        }
    }
}
