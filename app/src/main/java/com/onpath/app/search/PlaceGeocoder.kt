package com.onpath.app.search

/**
 * The phone's own address service (Android's `Geocoder`; on most phones Google's, on others the maker's, on some none)
 * – the last search for a shared place without a coordinate, after our offline index and Photon (owner 2026-10-31,
 * A-020). Behind this interface so the rules are tested without a phone ([AndroidPlaceGeocoder] on the phone).
 * Only the found point and the address line are used for the orange ring, nothing else is kept.
 * Blocking (called off the main thread), never throws: empty = missing, offline or nothing found.
 */
fun interface PlaceGeocoder {
    fun find(text: String): List<Place>

    companion object {
        /** No address service (tests, phones without one). */
        val NONE = PlaceGeocoder { emptyList() }

        /** One answer of the address service as a [Place] (pure); null without a usable position. */
        fun place(a: GeocodedAddress, index: Int): Place? {
            if (!SharedPlaceParser.valid(a.lat, a.lon)) return null
            val street = listOfNotNull(a.street, a.number).joinToString(" ").ifEmpty { null }
            val townLine = listOfNotNull(a.postcode, a.town).joinToString(" ").ifEmpty { null }
            val address = a.line ?: listOfNotNull(street, townLine).joinToString(", ").ifEmpty { null }
            // a feature name that only repeats the number or the street is no name
            val name = a.feature?.takeIf { it != a.number && it != street && it != a.street } ?: street ?: a.town
            return Place("g:$index:%.5f,%.5f".format(java.util.Locale.ROOT, a.lat, a.lon), name,
                if (street != null) "address" else "place", a.lat, a.lon, address = address, source = Place.Source.ONLINE,
                cc = a.countryCode, town = a.town, village = a.district)
        }
    }
}

/** The parts of an `android.location.Address` the shared-place search reads (pure, for tests). */
data class GeocodedAddress(
    val lat: Double,
    val lon: Double,
    /** `featureName`: a POI's name, or just the house number. */
    val feature: String? = null,
    /** `thoroughfare` + `subThoroughfare`. */
    val street: String? = null,
    val number: String? = null,
    val postcode: String? = null,
    /** `locality`. */
    val town: String? = null,
    /** `subLocality`. */
    val district: String? = null,
    val countryCode: String? = null,
    /** `getAddressLine(0)`: the whole address as the service writes it. */
    val line: String? = null,
)
